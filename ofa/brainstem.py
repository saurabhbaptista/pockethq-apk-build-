"""OFA-01: bounded deterministic Chief/Watchtower pilot for ONE PUBLIC GitHub repo.

No model inference, no private-data intake, no code deployment. Runs on GitHub events
and scheduled wakeups; persists heartbeat and incident work orders in GitHub Issues.
"""
from __future__ import annotations
import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

WORKFLOW_NAME = "Build Pocket HQ offline Android shell"
STATUS_MARKER = "<!-- OFA-STATUS-V1 -->"
INCIDENT_PREFIX = "<!-- OFA-INCIDENT-RUN:"
TRANSIENT = (
    "502 Bad Gateway", "503 Service Unavailable", "ECONNRESET",
    "Temporary failure in name resolution", "TLS handshake timeout",
)
TRANSIENT_STEPS = (
    "actions/checkout", "actions/setup-java", "setup-android",
    "setup-gradle", "actions/upload-artifact",
)


def utcnow() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def sanitize(value: Any, limit: int = 130) -> str:
    return re.sub(r"[\x00-\x1f\x7f]", " ", str(value or ""))[:limit]


class Github:
    def __init__(self, repo: str, token: str):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
            raise ValueError("Invalid repo")
        if not token:
            raise ValueError("Missing GITHUB_TOKEN")
        self.repo = repo
        self.token = token
        self.base = f"/repos/{repo}"

    def call(self, method: str, path: str, data: dict | None = None) -> Any:
        if not path.startswith(self.base + "/"):
            raise ValueError("API path outside the approved repository")
        body = json.dumps(data).encode() if data is not None else None
        req = urllib.request.Request(
            "https://api.github.com" + path, method=method, data=body,
            headers={"Authorization": "Bearer " + self.token,
                     "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28",
                     "User-Agent": "OFA-Brainstem-v0.1",
                     **({"Content-Type": "application/json"} if body is not None else {})})
        with urllib.request.urlopen(req, timeout=25) as res:
            raw = res.read(3_000_000)
            return json.loads(raw) if raw else {}

    def issues(self) -> list[dict]:
        result: list[dict] = []
        for page in range(1, 6):
            batch = self.call("GET", f"{self.base}/issues?state=all&per_page=100&page={page}")
            result.extend(i for i in batch if "pull_request" not in i)
            if len(batch) < 100:
                break
        return result

    def create_issue(self, title: str, body: str) -> dict:
        return self.call("POST", f"{self.base}/issues", {"title": title, "body": body})

    def edit_issue(self, number: int, **fields: Any) -> dict:
        return self.call("PATCH", f"{self.base}/issues/{number}", fields)

    def comment(self, number: int, body: str) -> dict:
        return self.call("POST", f"{self.base}/issues/{number}/comments", {"body": body})

    def jobs(self, run_id: int) -> list[dict]:
        return self.call("GET", f"{self.base}/actions/runs/{run_id}/jobs?per_page=100").get("jobs", [])

    def log(self, job_id: int) -> str:
        # A provider-signed redirected log URL; never publish its full contents.
        path = f"{self.base}/actions/jobs/{job_id}/logs"
        req = urllib.request.Request("https://api.github.com" + path,
            headers={"Authorization": "Bearer " + self.token,
                     "Accept": "application/vnd.github+json",
                     "User-Agent": "OFA-Brainstem-v0.1"})
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, request, fp, code, msg, headers, url):
                return None
        try:
            urllib.request.build_opener(NoRedirect()).open(req, timeout=25)
        except urllib.error.HTTPError as error:
            if error.code != 302:
                raise
            signed = error.headers.get("Location", "")
            parsed = urllib.parse.urlsplit(signed)
            if parsed.scheme != "https" or not parsed.hostname:
                raise ValueError("Invalid signed log redirect")
            # Signed redirect receives NO GitHub token or auth headers.
            with urllib.request.urlopen(
                    urllib.request.Request(signed, headers={"User-Agent": "OFA-Brainstem-v0.1"}),
                    timeout=25) as res:
                return res.read(220_000).decode("utf-8", "replace")
        raise ValueError("Expected a signed log redirect")

    def retry_failed(self, run_id: int) -> Any:
        return self.call("POST", f"{self.base}/actions/runs/{run_id}/rerun-failed-jobs")


class Chief:
    def __init__(self, api: Github):
        self.api = api
        self.actions: list[str] = []

    def status(self, issues: list[dict], run: dict | None, *, note: str) -> dict:
        marker = STATUS_MARKER
        old = next((i for i in issues if marker in (i.get("body") or "")), None)
        if run:
            run_id = int(run["id"])
            result = sanitize(run.get("conclusion", "unknown"), 24)
            url = f"https://github.com/{self.api.repo}/actions/runs/{run_id}"
            observed = f"Latest observed build: **{result}** (run [{run_id}]({url}), attempt {int(run.get('run_attempt',1))})."
        else:
            observed = "No completed build observed in the recent results."
        body = (f"{marker}\n# OFA Watchtower — public build status\n\n"
                f"Updated: {utcnow()} UTC.\n\n{observed}\n\n"
                f"Chief decision: {sanitize(note,230)}\n\n"
                "Worker: GitHub Watchtower | Manager: Rule-based Chief of Staff | "
                "Auditor: deterministic policy.\n\n"
                "This pilot does NOT run an LLM, publish app changes, access private "
                "project records or promise uninterrupted monitoring.\n")
        if old:
            updated = self.api.edit_issue(old["number"], body=body, state="open")
            self.actions.append(f"Heartbeat refreshed in issue #{old['number']}")
        else:
            updated = self.api.create_issue("[OFA] Watchtower · latest public build status", body)
            self.actions.append(f"Heartbeat created in issue #{updated['number']}")
        return updated

    def find_incident(self, issues: list[dict], run_id: int) -> dict | None:
        marker = f"{INCIDENT_PREFIX}{run_id} -->"
        return next((i for i in issues if marker in (i.get("body") or "")), None)

    def diagnose(self, run_id: int) -> tuple[list[str], bool]:
        problems: list[str] = []
        safe_to_retry = True
        failed = [j for j in self.api.jobs(run_id) if j.get("conclusion") == "failure"]
        if not failed:
            return ["GitHub reported failure, but no failed job was available."], False
        for job in failed[:8]:
            bad_steps = [sanitize(s.get("name"), 100) for s in job.get("steps", [])
                         if s.get("conclusion") == "failure"]
            problems.append(sanitize(job.get("name", "job"), 50) + ": " +
                            (", ".join(bad_steps[:4]) or "step unknown"))
            if not bad_steps or any(not any(p in step for p in TRANSIENT_STEPS)
                                   for step in bad_steps):
                safe_to_retry = False
            if safe_to_retry:
                try:
                    log = self.api.log(int(job["id"]))
                except (urllib.error.URLError, TimeoutError, ValueError):
                    safe_to_retry = False
                    continue
                if not any(p.lower() in log.lower() for p in TRANSIENT):
                    safe_to_retry = False
        return problems[:8], safe_to_retry

    def process(self, run: dict, issues: list[dict] | None = None) -> None:
        # Treat webhook JSON as untrusted: exact source repo, branch, workflow and completion gate.
        source = (run.get("head_repository") or {}).get("full_name", "")
        if (run.get("name") != WORKFLOW_NAME or source.lower() != self.api.repo.lower()
            or run.get("head_branch") != "main" or run.get("status") != "completed"):
            self.actions.append("Ignored an out-of-scope workflow event")
            return
        run_id = int(run["id"])
        attempt = int(run.get("run_attempt", 1))
        result = run.get("conclusion")
        if issues is None:
            issues = self.api.issues()
        incident = self.find_incident(issues, run_id)
        if result == "success":
            if incident and incident.get("state") == "open":
                self.api.comment(incident["number"],
                    f"QA confirmation: the SAME workflow run succeeded on attempt {attempt}. "
                    "Closing this incident; no automatic code modifications were made.")
                self.api.edit_issue(incident["number"], state="closed")
                self.actions.append(f"Resolved incident #{incident['number']}")
            self.status(issues, run, note="Build succeeded. No manager intervention required.")
            return
        if result != "failure":
            self.status(issues, run, note=f"Observed {result}; no automatic retry.")
            return
        if incident:
            # Workflow re-runs keep the run ID; do not create duplicate work orders.
            self.actions.append(f"Existing incident #{incident['number']} retained")
            self.status(issues, run, note="Failure persists or retry in progress.")
            return
        problems, transient = self.diagnose(run_id)
        allowed = transient and attempt == 1 and os.getenv("OFA_ALLOW_ONE_TRANSIENT_RETRY") == "1"
        marker = f"{INCIDENT_PREFIX}{run_id} -->"
        url = f"https://github.com/{self.api.repo}/actions/runs/{run_id}"
        body = (f"{marker}\n## Engineering work order · run {run_id}\n\n"
                f"Evidence: [GitHub workflow run]({url}) · commit "
                f"`{sanitize(run.get('head_sha'), 40)}` · attempt {attempt}.\n\n"
                "### Failed work (job / step names only)\n" +
                "\n".join("- " + sanitize(x, 240) for x in problems) +
                "\n\n### Chief → Engineering → QA → Auditor\n"
                "1. Engineering: inspect failure evidence and prepare an isolated fix if needed.\n"
                "2. QA: independently run build and regression checks.\n"
                "3. Auditor: ensure no secrets, external expenditure or live-project changes.\n"
                f"4. Chief: {'authorize ONE infrastructure-only rerun' if allowed else 'hold for engineering investigation'}; "
                "no auto-code-patching in v0.1.\n\n"
                f"Auto retry permitted: **{'yes' if allowed else 'no'}**.\n")
        created = self.api.create_issue("[OFA] Engineering · failed public Android build", body)
        self.actions.append(f"Assigned engineering work order #{created['number']}")
        if allowed:
            # The incident is committed BEFORE rerun; an unsuccessful API call does not loop.
            try:
                self.api.retry_failed(run_id)
                self.api.comment(created["number"],
                    "Auditor: permitted one infrastructure-only rerun. "
                    "Re-run will produce a separate completion event for QA.")
                self.actions.append("Single permitted transient rerun dispatched")
            except (urllib.error.URLError, TimeoutError) as exc:
                self.api.comment(created["number"], "Retry dispatch failed; engineering review required.")
                self.actions.append("Rerun failed, escalated")
        self.status(issues, run, note=f"Engineering incident #{created['number']} assigned.")

    def sweep(self) -> None:
        # A scheduled wake-up without pretending an idle build pipeline is broken.
        runs = self.api.call("GET", self.api.base +
                            "/actions/workflows/build-pocket-hq.yml/runs?branch=main&per_page=20")
        chosen = next((r for r in runs.get("workflow_runs", [])
                       if r.get("status") == "completed"), None)
        if chosen:
            self.process(chosen)
        else:
            self.status(self.api.issues(), None, note="No completed build yet; awaiting first public build.")


def main() -> int:
    repo = os.getenv("GITHUB_REPOSITORY", "")
    api = Github(repo, os.getenv("GITHUB_TOKEN", ""))
    chief = Chief(api)
    mode = os.getenv("OFA_MODE", "sweep")
    if mode == "event":
        event_file = os.environ.get("GITHUB_EVENT_PATH", "")
        with open(event_file, encoding="utf-8") as file:
            event = json.load(file)
        chief.process(event.get("workflow_run") or {})
    elif mode == "sweep":
        chief.sweep()
    else:
        raise ValueError("Only event and sweep modes are authorized")
    for item in chief.actions:
        print("OFA:", item)
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("## OFA Chief of Staff · deterministic v0.1\n\n" +
                      "\n".join("- " + a for a in chief.actions) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
