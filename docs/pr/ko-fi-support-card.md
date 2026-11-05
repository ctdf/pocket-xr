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

## Screenshots needed

1. GitHub build, phone: Pocket XR Settings scrolled to the bottom, card visible (Advanced closed).
2. GitHub build, glasses: the settings screen in a Space window, card visible, with the trackpad cursor or hand pointer
   on the button.
3. Tapping the button: Ko-fi's page open in the browser.
4. Play build (`-Ppocketxr.play=true`), phone: the same screen ending at Advanced, no card.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
