# Pocket Bridge contract v1 — public, no project data

The public repository contains only the offline Android shell and interoperability documentation. Do not commit actual project statuses, backups, OAuth credentials or the private APK signing key.

## Status envelope

```json
{
  "schema": "pockethq.project-status",
  "schemaVersion": 1,
  "kind": "status-snapshot",
  "messageId": "unique-message-id",
  "source": {"app": "Example Exporter", "mode": "manual-reviewed", "authority": "proposal-only"},
  "exportedAt": "2026-01-01T12:00:00Z",
  "scope": {"projectIds": ["example"], "fields": ["status", "next"]},
  "projects": [
    {"id": "example", "name": "Example Project", "status": "Planning",
     "next": "Demonstrate schema validation.", "updatedAt": "2026-01-01T12:00:00Z"}
  ]
}
```

Status is required. Summary and next action are optional and must be listed in scope.fields. A file may contain only the selected project IDs. Do not include notes, account data, trade logs, credentials, or commands. The application enforces size limits, a strict field allowlist, permitted status values, and unique message/project IDs.

Each receiving app must match existing project IDs, compare individual fields, treat older source revisions as potential conflicts, preview the changes and require explicit approval. A local recovery copy is written before applying accepted modifications. Unknown projects are never silently created. Importing this file cannot trigger network requests, trades, emails, automation jobs or arbitrary code.

This is a transport contract, not authentication, live synchronization or an agent protocol. In future versions, per-project adapters should emit this envelope from read-only sources; an independently permissioned command queue will be required before any automated external actions.
