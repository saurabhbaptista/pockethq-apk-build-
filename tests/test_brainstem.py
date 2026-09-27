"""Unit tests with an entirely fake GitHub API; no network or credentials."""
import os
import sys
import unittest
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from ofa.brainstem import Chief, STATUS_MARKER

REPO = "saurabhbaptista/pockethq-apk-build-"


def example(conclusion="success", attempt=1, run_id=444):
    return dict(id=run_id, name="Build Pocket HQ offline Android shell",
                head_repository={"full_name": REPO}, head_branch="main",
                status="completed", conclusion=conclusion,
                run_attempt=attempt, head_sha="ab12" * 10)


class FakeGitHub:
    def __init__(self):
        self.repo = REPO
        self.base = "/repos/" + REPO
        self.entries = []
        self.comments = []
        self.retries = []
        self.mock_jobs = []
        self.mock_log = ""
        self.mock_runs = []

    def issues(self):
        return list(self.entries)

    def create_issue(self, title, body):
        i = dict(number=len(self.entries)+1, title=title, body=body, state="open")
        self.entries.append(i)
        return i

    def edit_issue(self, number, **fields):
        issue = next(i for i in self.entries if i["number"] == number)
        issue.update(fields)
        return issue

    def comment(self, number, body):
        self.comments.append((number, body))

    def jobs(self, run_id):
        return self.mock_jobs

    def log(self, job_id):
        return self.mock_log

    def retry_failed(self, run_id):
        self.retries.append(run_id)

    def call(self, method, path, data=None):
        if method == "GET" and "/actions/workflows/" in path:
            return {"workflow_runs": self.mock_runs}
        raise AssertionError((method, path))


class BrainstemTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeGitHub()
        self.chief = Chief(self.api)
        os.environ.pop("OFA_ALLOW_ONE_TRANSIENT_RETRY", None)

    def test_success_creates_one_status_issue(self):
        self.chief.process(example())
        self.assertEqual(len(self.api.entries), 1)
        self.assertIn(STATUS_MARKER, self.api.entries[0]["body"])
        self.assertIn("success", self.api.entries[0]["body"])

    def test_duplicate_success_only_refreshes_heartbeat(self):
        self.chief.process(example())
        self.chief.process(example())
        self.assertEqual(len(self.api.entries), 1)

    def test_out_of_scope_or_forged_event_has_no_side_effects(self):
        for wrong in (dict(name="Not our workflow"),
                      dict(head_repository={"full_name": "attacker/fork"}),
                      dict(head_branch="feature"), dict(status="in_progress")):
            self.chief.process({**example(), **wrong})
        self.assertEqual(self.api.entries, [])

    def test_failed_compile_creates_work_order_but_never_retries(self):
        self.api.mock_jobs = [dict(id=900, name="compile", conclusion="failure",
                              steps=[dict(name="Compile with D8", conclusion="failure")])]
        self.chief.process(example("failure"))
        self.assertEqual(len(self.api.entries), 2)
        self.assertIn("Engineering work order", self.api.entries[0]["body"])
        self.assertEqual(self.api.retries, [])
        self.chief.process(example("failure"))
        self.assertEqual(len(self.api.entries), 2)

    def test_one_transient_rerun_requires_explicit_policy(self):
        self.api.mock_jobs = [dict(id=900, name="SDK setup", conclusion="failure",
                              steps=[dict(name="Run actions/setup-java@v4", conclusion="failure")])]
        self.api.mock_log = "HTTP 503 Service Unavailable while downloading JDK"
        self.chief.process(example("failure"))
        self.assertEqual(self.api.retries, [])
        self.api = FakeGitHub()
        self.api.mock_jobs = [dict(id=900, name="SDK setup", conclusion="failure",
                              steps=[dict(name="Run actions/setup-java@v4", conclusion="failure")])]
        self.api.mock_log = "HTTP 503 Service Unavailable while downloading JDK"
        os.environ["OFA_ALLOW_ONE_TRANSIENT_RETRY"] = "1"
        try:
            Chief(self.api).process(example("failure"))
            self.assertEqual(self.api.retries, [444])
            Chief(self.api).process(example("failure", attempt=2))
            self.assertEqual(self.api.retries, [444])
        finally:
            os.environ.pop("OFA_ALLOW_ONE_TRANSIENT_RETRY", None)

    def test_successful_rerun_closes_matching_incident(self):
        self.api.mock_jobs = [dict(id=900, name="compile", conclusion="failure",
                              steps=[dict(name="Compile with D8", conclusion="failure")])]
        self.chief.process(example("failure"))
        self.chief.process(example("success", attempt=2))
        self.assertEqual(self.api.entries[0]["state"], "closed")
        self.assertTrue(any("QA confirmation" in c[1] for c in self.api.comments))
        self.assertEqual(len(self.api.entries), 2)

    def test_sweep_uses_existing_completed_build(self):
        self.api.mock_runs = [dict(example(), status="in_progress"), example()]
        self.chief.sweep()
        self.assertEqual(len(self.api.entries), 1)

    def test_no_perpetual_worker_when_no_builds(self):
        self.chief.sweep()
        self.assertEqual(len(self.api.entries), 1)
        self.assertIn("No completed", self.api.entries[0]["body"])


if __name__ == "__main__":
    unittest.main()
