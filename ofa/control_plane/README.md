# OFA private control plane v0.1

This public file defines **schema only**. It contains no private user/project data, credentials, API keys or Supabase identifiers.

The private OFA backend will provide:
- durable task queue with leases, retries and idempotency;
- agent registry and authority levels;
- CEO↔Chief, Chief↔Managers, Worker Floor and Auditor channels;
- append-only operational events;
- protected-action approval queue;
- explicit policies for agent creation and self-modification.

The first Android client will be read-mostly. Autonomous writes must occur server-side; never embed a Supabase service-role key in the APK.

Deployment is intentionally blocked until the user explicitly chooses the Supabase organization, as required by the connected Supabase management tool.
