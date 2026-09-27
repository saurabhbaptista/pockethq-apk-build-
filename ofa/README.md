# OFA Brainstem v0.1 — public GitHub Watchtower
This is the **first implemented worker**, not a fully deployed AI organization. It is a rule-based manager that reacts to the existing public Android build and wakes twice hourly to review its latest completed build. No additional paid services or model API calls.

**Implemented:** Watchtower reads GitHub's own completed build events; Chief creates/updates a persistent public status issue; Engineering receives a de-duplicated work order for failures; policy controller permits at most one automatically retried, verifiably transient infrastructure failure; QA confirms the same workflow run succeeded after retry. All other failures are left for investigation. Unit tests run before any authenticated operation.

**Not implemented:** autonomous code patching, autonomous LLM reasoning, private project access, automatic app installation, durable production-grade scheduling or venture creation.

This PUBLIC repository is restricted to generic Android shell and infrastructure health. NEVER publish the app's private bundled dashboard, original signing key, project notes, customer correspondence, trade logs, account credentials, or business records in GitHub Issues or code.

Source files are published as readable UTF-8 by the one-shot bootstrap, verified against offline-built SHA-256 values. The original compressed public-only copies are kept temporarily to tolerate a first-run race.

Security: the public build is the only monitored workflow; scoped repository token; default branch only; no arbitrary subprocess execution of log content; no unrestricted retry; no PR checkout in the privileged Watchtower. This is an initial GitHub-Issues ledger, not a private database.
