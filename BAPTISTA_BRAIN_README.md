# Baptista Brain — Family Arena v1.2.0

## Why this release exists
The original Gemini game was too visually rudimentary. Version 1.1 shifted too far toward a static editorial / premium-landing-page style. Version 1.2 is an actual mobile game, with a compact game-first dashboard, a featured daily illustrated brain teaser, colourful multiplayer tiles, visual challenge boards, selectable answers, two progressive hints and detailed solutions.

## Important Android update contract
- Application ID: `com.baptista.brain` — UNCHANGED from v1.1.0.
- New version code: **3**, new version name: `1.2.0`.
- LocalStorage persistence key: **`baptista_brain_family_v1`** — UNCHANGED.
- JSON schema version: **1** — unchanged, optional `day` added to each score event for once-per-day scoring.
- Release signing: MUST sign with the SAME private release key used for v1.1.0. Do not make, replace or commit another key.
- Android manifest no new permissions; all puzzles bundled offline.
- Scores are event-based; backups merge by unique record IDs; daily points are awarded at most once per local calendar day per player.
- Native backup import/export uses Android Storage Access Framework (`AndroidBridge`) from the unchanged MainActivity.

## Modes
- Daily puzzle — a family brain teaser rotated once each local day, with original illustrated clock, hourglass, switches, balance coins, rope, jugs, bridge or river. Each person gets one scored attempt (65 full points, lower with hints); repeat attempts are practice only.
- Brain Sprint — timed solo mix of eight cognitive disciplines + occasional authored brain teasers.
- Family Relay — all three profiles on one phone; rotating turns.
- Versus Mode — three separate rounds, points comparison.
- Puzzle Academy — 10 untimed exercises, two progressive hints, full solutions, no competitive points.
- Expert Arena — faster/harder questions, score multiplier, separate leaderboard view.

## Backups & migration
Before installing updates, export JSON from Settings and keep a copy outside the app. An update signed with the same release certificate and package ID should retain Android WebView local data; however device upgrade/persistence must be tested on installed hardware separately. Never uninstall the app merely to upgrade. Uninstall or clear data may erase progress.

Multi-device real-time cloud sync is not implemented. JSON backup exchange/merge is the available option. This version does not modify the original Gemini ntfy source.

## Test evidence
- `node android/tests/smoke.cjs` — scripted 3,000-question generator, hint/reasoning and JSON round-trip checks.
- `python qa/smoke.py` — screenshot-led Chromium (HTML injected without external networking) at mobile/tablet sizes; navigation, hint buttons, solution and Academy continuation.
- `python qa/regression.py` — daily scoring, replay anti-farming, original v1.1 schema backward compatibility, all five-mode smoke.
- A Chromium-based simulated DOM with mocked `localStorage` is a browser interaction test, NOT an actual Android emulator/install/upgrade test.

## Source overview
`web/` is the only canonical web source and is copied into `android/app/src/main/assets/` before building. `teasers.js` contains original vector illustrations and answer-keyed puzzles, alongside the existing procedural cognitive categories in `app.js`.

No external libraries, paid services, CDN assets, server-side logic, or permissions are required to play.