# Changelog

Each release lists what changed for people using Pocket XR. Versions follow [semantic versioning](https://semver.org/).

## 1.1.1: no crash at plug-in on Android 11

- On Android 11, Pocket XR closed as soon as the glasses were plugged in (seen on a Galaxy S20 FE). It now runs there.

## 1.1.0: the add-on, so Pocket XR installs everywhere

- In some countries Google Play Protect blocks installing a sideloaded app that uses an accessibility service or
  notification access. Pocket XR now uses neither. Both moved to a second, optional app: the Pocket XR add-on. Pocket XR
  itself installs everywhere, and the Space, the trackpad and hand tracking in the Space work without the add-on.
- The add-on gives you the cursor outside the Space (flat DeX mode) and the Notifications page. If you use either,
  install the add-on from its own release and turn it on again: its pointer under Accessibility, and notification
  access from the Notifications page. Pocket XR's Hands screen shows what's missing.
- Glasses whose display name the phone shows differently (seen on an Honor Magic V5 with an XREAL One Pro) were taken
  for unknown glasses, and the picture could be upside down. Pocket XR now also uses the glasses' USB id.
- Updated build tools and libraries (Android Gradle Plugin 9.5, Gradle 9.8.2, Compose 1.12).

## 1.0.1: crash reports

- If Pocket XR closes unexpectedly, the next time you open it, it offers to send a crash report: by email or as a
  GitHub bug, both filled in already. You see the report before it's sent, and nothing is sent unless you send it.
  It holds the error, the app version, your phone model and Android version.
- The README explains the Play Protect block some countries show at install ("This app can request access to
  sensitive data"), and how to install with adb instead.

## 1.0.0 (2 November 2026): the first public release

Pocket XR was developed privately through 107 test versions on a Galaxy Z Flip 7 with XREAL Aura and One glasses. This
is the first version shared with everyone.

### Windows in space

- Any Android app opens as a window around you, at its own size and distance. Windows can be dragged by their edges,
  resized from a corner, curved, and pushed further or pulled closer.
- Snapping, including into the space between two windows, resized to fit.
- Adjust all: move, push, pull or resize every window together, from the Menu, the Control Center, or by holding both
  hands up.
- Arrange layouts for any number of windows, and presets that reopen a set of windows exactly where they were.
- Window buttons: Back, Reopen, Switch app (keeps the window's place and size), Wide view, Close.
- Wide view gives phone-shaped apps a landscape screen.
- A launcher with search, the date and time, and the phone's battery, cell signal and Wi-Fi.
- A Control Center: layouts, presets, Wi-Fi, Bluetooth, notifications, quick settings and recentre.
- Record the glasses: a clean 1080p video of what you see, saved to Movies/Pocket XR.

### Seeing it

- 3D side by side, with each window at a real depth. Pocket XR switches the glasses to 3D by itself where they allow
  it.
- Head tracking from the glasses' own motion sensor, with prediction and paced to the glasses' display, so windows
  stay put.
- A design made for see-through glasses: dark glass, light rims, depth for what you're pointing at.

### Controls

- A phone trackpad with tap, two-finger scroll and pinch, three-finger swipes and a scroll strip, plus buttons for
  the keyboard, recentre, and moving, resizing, pushing, pulling and curving a window. It also works on a Galaxy Z
  Flip's cover screen.
- The Spatial Mouse: point the phone like a laser.
- Hand tracking on glasses with a camera (XREAL Aura): point, pinch, drag, two-handed resize.
- Typing from the phone's keyboard into the focused window.

### Glasses

- Tested on the XREAL Aura and the XREAL One. The One Pro and 1S are expected to work and are in community testing.
- A Glasses report (Pocket XR Settings, Advanced), for reporting how your glasses work.
- Each model's own field of view, sensor axes and calibration.

### The app

- Settings for spatial windows, hands and the trackpad, each with a reset to Pocket XR's defaults.
- An update check against GitHub Releases, at most once a day, which can be turned off. Updates also come through
  Obtainium.
