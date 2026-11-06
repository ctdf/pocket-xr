# PR draft: "Buy me a beer" at the top of the trackpad's Menu

1.0.1 added a Ko-fi card at the bottom of Pocket XR Settings. 1.1.15 moves it to the very top of the trackpad's Menu
(the sidebar that opens from the trackpad's Menu button) as one slim row, and takes the card out of Settings.

## What

- **The row:** a 48 dp pill right under the POCKET XR wordmark, in the Menu cards' own fill (`Look.CARD`) and hairline
  rim (`Look.RIM`), radius 24. A small line-drawn beer mug (`res/drawable/ic_beer.xml`, 18 dp at 70 %), "Buy me a beer"
  in the Menu's 14 sp row text, and a quieter 12 sp "Ko-fi" at its end. No accent colour, badge or animation, so it
  reads as part of the Menu rather than an ad. TalkBack reads it as one button: "Buy me a beer on Ko-fi. Opens
  ko-fi.com in the browser." A tap gives the Menu's press light and tick, closes the Menu and opens
  https://ko-fi.com/mikehaye in the browser (`SupportLink`).
- **Order:** `Support.menuTop` (core) decides the top of the Menu: the Ko-fi row first, then, when there is a newer
  version, the "Update available" row from 1.1.15's update work as the first row of the function card under it. The
  function card's top gap shrinks from 25 to 12 dp when the pill is there, so the real items move down by about 55 dp.
- **Settings:** the card is gone. Settings has no About section, so no link is left there.
- **Google Play:** unchanged. `BuildConfig.PLAY_BUILD` (`-Ppocketxr.play=true`) leaves the row out.
- **README:** the Support section says where the row is in the app. **CHANGELOG:** an Unreleased entry.

## Tests

- `core`: `SupportTest`: the row shows only outside the Play build, Ko-fi first and the update row after it, only the
  update row in the Play build, the link, and the copy (short, no dashes, no "we").
- `app`: `SupportMenuRowTest` (Robolectric) opens a real `PadScreen` Menu: the Ko-fi row is its first row with its
  TalkBack label and a 48 dp minimum, the update row follows it when a version is offered, a tap starts a VIEW intent for
  https://ko-fi.com/mikehaye, and in the Play build there is no Ko-fi row. Run it both ways:
  `gradle :app:testDebugUnitTest` and `gradle :app:testDebugUnitTest -Ppocketxr.play=true`.

## Screenshots

The files in `docs/screenshots/ko-fi/` (`phone-github-card*.png`, `phone-play-no-card.png`) show the 1.0.1 card in
Settings and are kept for the record; that card no longer exists.

Still needed for 1.1.15: the Menu opened on the trackpad in the GitHub build (with and without an update), the same at
1.3x font size, and the Play build's Menu without the row, plus Ko-fi open in the browser after a tap.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
