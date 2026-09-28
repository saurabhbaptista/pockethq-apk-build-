import unittest
from ofa.runtime.reasoning_runtime import MemoryStore, NoModelProvider, RuntimePolicy, run_task

class RuntimeTests(unittest.TestCase):
    def test_no_model_fallback(self):
        r=run_task({"id":"1","objective":"sample"},None,MemoryStore(),{},RuntimePolicy())
        self.assertEqual(r["status"],"waiting")
        self.assertEqual(r["tool_calls"],0)

    def test_duplicate_suppression(self):
        s=MemoryStore(); task={"id":"1","version":1,"objective":"sample"}
        run_task(task,None,s,{},RuntimePolicy())
        r=run_task(task,None,s,{},RuntimePolicy())
        self.assertEqual(r["status"],"duplicate")

if __name__=="__main__":
    unittest.main()


class RuntimeSafetyTests(unittest.TestCase):
    def test_unlisted_tool_is_rejected(self):
        class P:
            def invoke(self, request, *, timeout_seconds):
                return {"tool_calls":[{"name":"unknown_tool","args":{}}]}
        r=run_task({"id":"safety-1"},P(),MemoryStore(),{},RuntimePolicy())
        self.assertEqual(r["status"],"failed")
        self.assertIn("tool_not_allowed",r["error"])

    def test_tool_budget_is_bounded(self):
        class P:
            def invoke(self, request, *, timeout_seconds):
                return {"tool_calls":[{"name":"safe","args":{}},{"name":"safe","args":{}}]}
        calls=[]
        r=run_task({"id":"safety-2"},P(),MemoryStore(),{"safe":lambda **kwargs:calls.append(1)},RuntimePolicy(allowed_tools=frozenset({"safe"}),max_tool_calls=1))
        self.assertEqual(r["status"],"failed")
        self.assertEqual(calls,[])

    def test_provider_retries_are_bounded(self):
        class P:
            def invoke(self, request, *, timeout_seconds):
                raise RuntimeError("provider unavailable")
        r=run_task({"id":"safety-3"},P(),MemoryStore(),{},RuntimePolicy(max_attempts=2))
        self.assertEqual(r["status"],"failed")
        self.assertEqual(r["attempts"],2)
