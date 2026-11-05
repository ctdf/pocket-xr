# PR draft: a "Buy me a beer" Ko-fi card

## What

- **App:** a support card at the very bottom of Pocket XR Settings (the phone's settings home, also reached from the
  trackpad Menu's Pocket XR Settings). It uses the screen's own glass card, Inter type and the one Flip blue accent: a
  line-drawn beer mug on a glass chip (`res/drawable/ic_beer.xml`, a vector), "Enjoying Pocket XR? Buy me a beer",
  "Never required. Pocket XR is free for everyone.", and a 48 dp "Buy me a beer on Ko-fi" button that opens
  https://ko-fi.com/mikehaye in the browser. No popup, reminder or timer.
- **Google Play:** a new `BuildConfig.PLAY_BUILD` flag, set with `-Ppocketxr.play=true`. The repository had no Play
  flavor, docs/PLAY.md or release script, so this is a Gradle property in the same style as `-Ppocketxr.official=true`.
  The card shows only when `Support.showCard(BuildConfig.PLAY_BUILD)` is true, that is in every build but the Play one.
  A Play release must be built with `-Ppocketxr.play=true` (add it to the private release script's Play path).
- **README:** the passing Ko-fi line is now a short Support section with a shields.io Ko-fi badge.
  `.github/FUNDING.yml` already had `ko_fi: mikehaye`, so GitHub's Sponsor button is unchanged.
- **CHANGELOG:** an Unreleased entry.

## Why

Mike wants an easy, friendly way for people to tip him. Play's payments policy does not allow a link for tips to the
developer outside Play billing inside a Play app, so the Play build hides the card.

## Tests

- `core`: `SupportTest` (the card shows only when not a Play build, the link, the copy has no dashes or "we").
- `app`: `SupportCardBuildTest` checks `BuildConfig.PLAY_BUILD` follows the Gradle property and hides the card. Run it
  both ways: `gradle :app:testDebugUnitTest` and `gradle :app:testDebugUnitTest -Ppocketxr.play=true`.

## Screenshots

Taken on an API 33 emulator set to 1080x2340 at 420 dpi, debug builds (`docs/screenshots/ko-fi/`):

1. `phone-github-card.png` and `phone-github-card-closeup.png`: GitHub build, Pocket XR Settings scrolled to the
   bottom, card visible under Advanced.
2. `phone-github-card-font-1.3.png`: the same at 1.3x font size. The button had a fixed 48 dp height, which cut
   "Ko-fi" off the label (`phone-github-card-font-1.3-before-fix.png`); it is now a 48 dp minimum and the label wraps.
3. `phone-play-no-card.png`: Play build (`-Ppocketxr.play=true`), the page ends at Advanced with no card.

Still needed: the card in a Space window on the glasses (the emulator has no Shizuku or glasses tracking, so only flat
mode's home showed on its second display), and Ko-fi open in the browser after a tap.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
