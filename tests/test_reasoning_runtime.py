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
