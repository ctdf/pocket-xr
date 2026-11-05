# Contributing to Pocket XR

Thanks for helping. The most useful contributions:
- glasses reports from models marked "Community testing";
- bug reports with logs;
- small, focused pull requests.

## Reporting a bug or your glasses

- **Glasses.** In the app, open the Menu, then **Pocket XR Settings**, **Advanced** and **Glasses report**. It saves a text file and opens the share sheet, so
  you can paste it into a "Glasses report" issue. The glasses' serial appears only as its last four characters.
- **Bugs.** Use the bug template. Say:
  - your phone and Android version;
  - your glasses;
  - the Pocket XR version (Menu, then Pocket XR Settings);
  - what you did, what you expected, and what happened.

  A log helps most:

  ```bash
  adb logcat -d -v time -s 'FlipSpaces/*' 'FlipSpacesHands' > pocketxr.log
  ```

  (The log tags still use the app's old name.)

## Building

You need:
- JDK 17;
- the Android SDK (compile SDK 37), with the NDK and CMake 3.22.1;
- Gradle 9.8.2.

```bash
gradle :core:test          # the unit tests (fast, no device)
gradle :app:assembleDebug  # the app
```

- **`core/`** is plain Kotlin with no Android dependencies, so almost all logic is tested on the JVM: window layout and
  snapping, head tracking fusion and prediction, frame pacing, gestures, and the glasses' protocols. Put new logic
  here when you can, with a test.
- **`app/`** is the Android side: rendering (OpenGL ES 3), displays, input, services and the phone's screens.
- **No phone?** `docs/EMULATOR_TESTING.md` shows how to run on an Android emulator with replayed hands.

## Pull requests

1. **Open an issue first** for anything larger than a small fix, so we can agree on the approach.
2. **Keep a PR to one change**, with a test in `core/` where it can have one.
3. **`gradle :core:test` must pass.** CI runs it, along with the build and the clean-source check below.
4. **Sign off your commits** (the DCO, below).
5. **Say what you tested on**: the phone, the glasses, or the emulator.

Every PR is reviewed by a code owner before it merges; see GOVERNANCE.md.

## Code style

- Kotlin, in the style of the surrounding code.
- **Comments say why**, not what: the reason a value is what it is, what was measured, what broke before. The code's
  comments start with the version that made the change (for example `0.5.88:`), with the reason after it. New code
  follows the same habit.
- **Names describe behaviour**, not their origin.
- **Our own work only.** Pocket XR is written from scratch. Don't add code, assets, text or values taken from other
  products, including other companies' XR apps or SDKs. Glasses protocol facts must come from your own captures or
  public open projects, and say so. `tools/check-clean.sh` (run in CI) catches the obvious slips.

## Developer Certificate of Origin (DCO)

By contributing, you certify the [Developer Certificate of Origin 1.1](https://developercertificate.org/): you wrote
the contribution, or otherwise have the right to submit it under the project's licence. To show this, add a sign-off
line to each commit:

```bash
git commit -s -m "Fix the snap between two windows"
```

That adds `Signed-off-by: Your Name <you@example.com>`. No other agreement is needed. Your contribution stays yours,
licensed under the GPLv3 like the rest of the project.
