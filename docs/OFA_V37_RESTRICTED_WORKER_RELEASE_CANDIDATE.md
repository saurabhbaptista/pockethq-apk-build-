# OFA v3.7 Restricted Worker Release Candidate

**Status: RELEASE-CANDIDATE SOURCE ONLY; NOT A PRODUCTION OR IN-PLACE UPGRADE.**

This branch preserves the public Android shell and adds only bounded CEO-to-worker evidence controls. The private OFA dashboard is injected offline by its owner after official Android compilation and before signing with the pre-existing OFA certificate. The private dashboard and signing key MUST NOT be committed.

## Verified engineering gates

- Official GitHub Actions Java compile, Android APK assembly and version code 22: passed.
- Public-shell confidentiality/placeholder check: passed.
- Worker Controls JavaScript syntax + 12 mocked DOM/native bridge tests: passed.
- Existing OFA v3.6 native auth, office refresh, approvals, and Chief message APIs remain present.
- Backend read-only CEO Worker Release Snapshot deployed, including real task state and artifact-integrity display. CEO and negative authorization SQL tests passed.
- Isolated private v3.6 dashboard copy, recovered from owner-provided signed APK and SHA-256 pinned, accepted the additive worker panel; private HTML integration smoke test passed.
- The existing verified restricted-worker Auth identity remains **disabled**; no durable autonomous worker is installed.

CI: https://github.com/saurabhbaptista/pockethq-apk-build-/actions/runs/37848448157

## New native APIs

All methods are `@JavascriptInterface` inside the pre-existing `OFACloud` bridge. All use the existing CEO Auth access token (not the worker's token) and fixed audited Supabase RPC routes:

- `refreshWorkerRelease()`: CEO-only read snapshot
- `queueEvidenceJob(requestId,marker)`: allowlisted bounded evidence job; blocked server-side when worker not ready
- `getEvidenceJob(taskId)`: CEO-only evidence job status
- `cancelEvidenceJob(taskId)`: CEO-only cancellation; **not** a proof the physical worker stopped

No arbitrary shell, secrets API, worker activation, trading or project-specific commands are exposed.

## Private dashboard integration

The owner-held v3.6 private HTML is patched **only locally** by an audited SHA-256-pinned additive script. The Worker Operations panel is attached to the Chief page without replacing the globe, existing workflow or auth. It reads server evidence and does not equate a displayed status with a verified execution.

## Release blockers (must be verified before saying "fully ready")

1. First authenticated external evidence-only worker canary must complete claim, renew, artifact checksum, duplicate protection, stale lease and revocation acceptance. The production worker remains disabled because an execution safeguard blocked activation; **do not bypass the safeguard**.
2. Original private signing certificate **and private key** must be available to sign an upgraded APK (existing signer certificate identity must remain identical). Never sign with a debug/new key and advise an uninstall.
3. Private HTML must be injected into the newly compiled unsigned APK entirely **offline**, zipaligned, signed and verified with Android APK Signature Scheme v2/v3 using the original key. The public GitHub artifact is only an **unsigned shell**.
4. Physical Samsung Galaxy S26 Ultra acceptance: in-place install without data loss; new Chief panel; CEO auth; logout/refresh; offline/resume; globe/weather; project history; approvals; no JS errors or horizontal overflow.
5. Persistent worker (service account credential storage, restart recovery, heartbeat freshness, revocation, per-project allowlists and bounded network privileges) requires independent safe deployment after canary, not inferred from a single test.
6. No autonomous access to unrelated projects is included in this release.

The public branch remains unmerged and is not itself a deployable private APK.
