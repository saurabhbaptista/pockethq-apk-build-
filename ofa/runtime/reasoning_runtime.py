"""OFA provider-agnostic reasoning runtime v0.1. Public-safe; no credentials or private data."""
from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Callable, Mapping, Protocol
import hashlib, json, time

class Provider(Protocol):
    def invoke(self, request: Mapping[str, Any], *, timeout_seconds: float) -> Mapping[str, Any]: ...

class Store(Protocol):
    def seen(self, run_key: str) -> bool: ...
    def persist(self, run_key: str, record: Mapping[str, Any]) -> None: ...

@dataclass(frozen=True)
class RuntimePolicy:
    allowed_tools: frozenset[str] = frozenset()
    max_attempts: int = 2
    timeout_seconds: float = 30.0
    max_tool_calls: int = 4
    require_evidence_for_completed: bool = False

@dataclass
class MemoryStore:
    records: dict[str, Mapping[str, Any]] = field(default_factory=dict)
    def seen(self, run_key: str) -> bool: return run_key in self.records
    def persist(self, run_key: str, record: Mapping[str, Any]) -> None: self.records[run_key] = dict(record)

class NoModelProvider:
    def invoke(self, request: Mapping[str, Any], *, timeout_seconds: float) -> Mapping[str, Any]:
        return {"status":"waiting","summary":"No reasoning provider configured.","tool_calls":[],"usage":{"input_units":0,"output_units":0}}

def _run_key(task: Mapping[str, Any]) -> str:
    stable={"id":task.get("id"),"version":task.get("version",1),"objective":task.get("objective","")}
    return hashlib.sha256(json.dumps(stable,sort_keys=True,separators=(",",":")).encode()).hexdigest()

def run_task(task: Mapping[str, Any], provider: Provider | None, store: Store, tools: Mapping[str, Callable[..., Any]], policy: RuntimePolicy) -> Mapping[str, Any]:
    if not 1 <= policy.max_attempts <= 5: raise ValueError("max_attempts must be 1..5")
    if not 0 < policy.timeout_seconds <= 120: raise ValueError("timeout_seconds must be 0..120")
    key=_run_key(task)
    if store.seen(key): return {"status":"duplicate","run_key":key}
    provider=provider or NoModelProvider()
    started=time.monotonic(); attempts=0; tool_count=0; evidence=[]; usage={"input_units":0,"output_units":0}; last_error=None
    while attempts < policy.max_attempts:
        attempts += 1
        remaining=policy.timeout_seconds-(time.monotonic()-started)
        if remaining <= 0: last_error="runtime_timeout"; break
        try:
            response=dict(provider.invoke({"task":dict(task),"evidence":evidence},timeout_seconds=remaining))
            for k in usage: usage[k]+=int(response.get("usage",{}).get(k,0) or 0)
            calls=response.get("tool_calls",[]) or []
            if len(calls)+tool_count > policy.max_tool_calls: raise RuntimeError("tool_call_budget_exceeded")
            for call in calls:
                name=str(call.get("name",""))
                if name not in policy.allowed_tools or name not in tools: raise PermissionError(f"tool_not_allowed:{name}")
                if time.monotonic()-started >= policy.timeout_seconds: raise TimeoutError("runtime_timeout")
                out=tools[name](**dict(call.get("args",{})))
                evidence.append({"tool":name,"result":out}); tool_count += 1
            if calls: continue
            final_status=str(response.get("status","completed"))
            if final_status=="completed" and policy.require_evidence_for_completed and not evidence:
                raise RuntimeError("completion_without_evidence")
            record={"status":final_status,"summary":str(response.get("summary",""))[:2000],"attempts":attempts,"tool_calls":tool_count,"usage":usage,"evidence":evidence}
            store.persist(key,record); return {**record,"run_key":key}
        except (PermissionError, TimeoutError) as exc:
            last_error=str(exc); break
        except Exception as exc:
            last_error=f"{type(exc).__name__}:{exc}"
    record={"status":"failed","summary":"Reasoning run stopped safely.","attempts":attempts,"tool_calls":tool_count,"usage":usage,"evidence":evidence,"error":last_error}
    store.persist(key,record); return {**record,"run_key":key}
