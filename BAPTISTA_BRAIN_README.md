# Baptista Brain — Family Arena 1.5.0

## State

An offline-playable Android game with a cinematic, original graphics/motion rebuild. It retains **package `com.baptista.brain`**, **localStorage key `baptista_brain_family_v1`**, **schema 1**, and **original signing identity**. Android `versionCode` increases from **4 to 5**. On-device data retention through an APK upgrade has not been physically tested; always export Settings → Backup before updating. **Do not uninstall.**

## Play

- First launch on each device presents a family member selector, with later changes only in Settings/top-right avatar.
- Home features original procedural Aether motion, featured daily puzzle, compact six game modes, and scoreboard — no persistent profile-card grid.
- Puzzle stage adds tactile response, answer burst animation, a three-slot animated code vault, two hints, and worked solutions. Reduced-motion preference is supported. Graphics are written for this app; no proprietary game assets or source copied.
- Six modes retained: Codebreaker, Family Relay, Sprint, Mind Duel, Academy and Expert. Score timer remains **bonus-only**. Daily puzzles remain playable without a clock. Questions use the v1.4 progressive difficulty model.
- Existing immutable score events and completed match IDs remain intact; local import/export retains original semantics.

## Firebase Realtime Database — integration READY, NOT CONNECTED

**Live leaderboard does not work until a Firebase project/database is provisioned and configured.** This zip includes the production-target Android REST/SSE client, but no Firebase project, database URL, API key, or network credentials. Do not label it synced before a real three-device test.

The database can be created on Firebase Spark (check current quota and permitted project usage). To activate:
1. Create a Firebase project. Add a Web app and get its **Web API key**. This key is not a secret; never use admin/service-account keys in a phone.
2. Create a **Realtime Database** (not Firestore). Note its exact HTTPS database root URL, e.g. `https://your-project-default-rtdb.asia-southeast1.firebasedatabase.app`.
3. Enable **Firebase Authentication → Sign-in methods → Anonymous**. This app uses Firebase Anonymous Auth via Identity Toolkit; it stores the refresh token privately in Android SharedPreferences.
4. Apply the exact security rules in `firebase/database.rules.json` to the new Realtime Database. **Do not enable public read/write rules**. Test them with Firebase Rules Simulator before inviting the family.
5. In the APK, Settings → Family Live leaderboard: paste Web API key and Database URL; generate a long cryptographically random room code. Connect. On other phones, use the **same three fields**, selecting the correct family member on first launch.
6. Keep the room code private. Room membership is controlled by possession of the unguessable room code and anonymous Firebase authentication; this is **not** individually revocable account-based access. A compromised code requires starting a new room. Do not use for sensitive information. Avoid sharing it publicly.

The Android native bridge validates allowed Firebase HTTPS hosts, uses REST for conditional per-event immutable writes, and reads changes through SSE while app is active. It never downloads scripts. Existing local answer records are uploaded, and remote records merge by unique id, without overwriting local scores. In case of lost connection, local storage remains authoritative on that device and pending events are retried on reconnect. Token/stream expiry is handled by reconnecting. There is no authenticated end-to-end sync claim until the Firebase service has been configured and live-tested on two or more devices.

**Important security scope:** the included rules constrain record creation and fields, but anybody who knows the room code and has a Firebase anonymous session could submit fabricated scores. This is suitable for trusted family play, not prize tournaments, public deployment, or storing private files. Avoid publishing the live room code in public repos. You can later replace room-capability access with UID membership enrollment if needed.

## Build/verification

- Project branch: `baptista-brain-build` (not OFA `main`). Gradle 8.9 / Java 17 / Android 34 / AGP 8.7.3.
- `node android/tests/smoke.cjs` validates generator and game flow.
- `python qa/browser_qa.py` validates layouts and interactions at mobile 360, 390, 430, and 800 CSS px with localStorage mocked. Browser screenshot is NOT Android runtime testing.
- Android GitHub Actions compiles an unsigned, zipaligned APK. Release APK must be signed using the prior private keystore, and then compared with v1.4 for application ID, version code, and signer certificate.
- Firebase auth, event streaming, security rules, simultaneous devices, and persistence across actual installed update are **not yet end-to-end verified**.
- No recurring background automation was created; this build completes inside this request rather than continuing overnight after the response.
