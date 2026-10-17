# Baptista Brain — Family Game Night v1.4.0

Android application ID `com.baptista.brain`, versionCode **4**, versionName `1.4.0`. This is an in-place upgrade for signed v1.0 through v1.2. Must be signed using the *existing* private Baptista Brain release signing certificate. Never commit keystore or passwords to source control.

## Family-focused gameplay
- Every timed mode now uses a **bonus window per question**. Clock reaching zero eliminates only the optional speed bonus, never the question, round or family member's turn. Untimed Academy and Daily remain untimed.
- Rounds finish after a defined count: 10 solo, 12 relay (4 per family member), 15 versus (5 per person), 8 expert, 10 Academy, 1 daily and 3 Codebreaker (1 per person).
- Regular 10-question rounds progress through 2 easy warm-ups, 6 intermediate puzzles and 2 harder questions. Family Relay uses 1 easy / 2 intermediate / 1 harder per person; Versus uses 1 easy / 3 intermediate / 1 harder per person. Relaxed pacing adds bonus-window seconds and does not cap completion.
- New co-op Crack the Code: 12 hand-verified, uniquely solvable three-digit mysteries; 4 exact clues, limited guessing, two hints, worked explanations and rotation across all three profiles.
- Retained illustrated brain teasers (clock, hourglass, switches, coins, ropes, jugs, bridge, river), memory/attention/logic/analogy/arithmetic, competition leaderboard, and zero-score Academy.

## Progress preservation
- **Same localStorage key** `baptista_brain_family_v1` and schema `1`. No destructive migrations.
- Unique scoring events, automatic persistence and JSON backup merge; `code` events are accepted in the event/match parser.
- Uninstalling, clearing data or signing with a different release certificate can lose progress. Export Settings → family backup before updating.
- Cloud sync is NOT present; scores on different phones stay separate until you import backups.

## Verification
- `node android/tests/smoke.cjs` — stochastic generator, scoring and question flows.
- `python qa/v14_regression.py` — real Chromium interaction, 12 code puzzle unique-solution proof, 4 screen widths, bonus-window expiry in all 5 timed modes, code input/retries/handoffs, previous v1.2 score restoration.
- Build using Gradle 8.9 and Android Gradle Plugin 8.7.3 on JDK 17 in `.github/workflows/baptista-brain-apk.yml`.
- GitHub CI compiles an **unsigned, zipaligned** release. Sign externally using the original private keystore; compare signing certificate and application/version metadata against v1.2.
- Emulator and physical-device upgrade/persistence remain distinct acceptance tests and are NOT implied by browser QA or Gradle success.