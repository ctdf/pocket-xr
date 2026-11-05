# Play listing images

Every image here comes from Pocket XR itself. None is a video frame.

| File | Size | Source | SHA-256 |
| --- | --- | --- | --- |
| images/icon-512.png | 512 x 512 | The launcher icon's own vectors (app/src/main/res/drawable/ic_launcher_background.xml and ic_launcher_foreground.xml), cropped to the brand square's 90 unit view (docs/brand/pocket-xr-logo-square.svg), drawn at 4096 x 4096 and Lanczos-downsampled | cd02c73da2f966351de6c5fe34e195f722596711aa0475f894af5d83b280cb98 |
| images/feature-graphic-1024x500.png | 1024 x 500 | The icon's three panes and the app's Inter font (app/src/main/res/font) on the app's page colour (core Look.PAGE, #0C0D10) with an ACCENT_DEEP glow, composed at 4096 x 2000 and Lanczos-downsampled | 2e156a07b1ee4ba5bfcc93751ed50319e3e253081b62487597f669c0155b3efc |
| images/phone/01-trackpad-tutorial.png | 1200 x 2400 | The trackpad's first-run tutorial | 44b54287ec0872ee0f7705f86531dd080863c268cf51fc545e46cca15d10c6a9 |
| images/phone/02-trackpad.png | 1200 x 2400 | The trackpad | b624e724f6db149af33accad74351356cf81cbe27ec85f915c68fb283a5be828 |
| images/phone/03-trackpad-menu.png | 1200 x 2400 | The trackpad's Menu | 3953ddd651af842fa9e49c95475f8b5ac0514b84128d1142ad383d58917894ac |
| images/phone/04-hands.png | 1200 x 2400 | The Hands screen and the pointer card | a314953a88de8c8d3819efdc3adafb36d835d2e8eaa348de17c64e62419c56ae |
| images/phone/05-disclosure-pointer.png | 1200 x 2400 | The add-on's accessibility disclosure and consent | 54a16cc2d96ccdb05f648d908f67f6d64d9de41081522048728975d87ed4ed84 |
| images/phone/06-spatial.png | 1200 x 2400 | The Spatial windows screen | ac24e156d915a3b38a1d457e574b1a43a09eb6b8fd06c9a82ee909ed75cbd5dc |

## How the screenshots were taken

The Galaxy Z Flip 7 was reachable over adb, but it was running another XR session (the ALVR client) and its Pocket XR
is signed with the release key, so this build could not be installed over it without Mike's approval. The screenshots
were therefore taken with `adb exec-out screencap` on the Android emulator (Medium_Phone_API_35, 1080 x 2400, with a
1920 x 1080 secondary display standing in for the glasses), running the R8-shrunk release build of Pocket XR 1.1.11
(142) and the add-on 1.1 (2) from release/play. The status bar is the emulator's.

Play allows at most a 2:1 aspect ratio, and the emulator's screen is 9:20. Each screenshot is padded at the sides to
1200 x 2400 with its own edge colour (the page background); the app's pixels are untouched and nothing is cropped.

The Space itself (the windows in the glasses) can only be captured on the glasses. Pocket XR's own "Record the glasses"
gives a clean 1080p video; a still from such a recording would be a video frame, so none is used here.
