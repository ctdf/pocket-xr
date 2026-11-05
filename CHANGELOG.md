# Changelog

Each release lists what changed for people using Pocket XR. Versions follow [semantic versioning](https://semver.org/).

## Unreleased

- A small "Buy me a beer" card at the bottom of Pocket XR Settings opens Mike's Ko-fi page in the browser. It's never
  required, and nothing pops up or reminds you. The Google Play build leaves it out.
- A Head tracking switch in Settings. Off, Pocket XR stops reading the glasses' motion sensor and windows stay in front
  of you and turn with your head; on, they stay put as you turn. The choice is kept.
- "Send logs to Mike" in Settings and on the head tracking diagnostics screen. It puts Pocket XR's own logs, the head
  tracking diagnostics, the app version, your phone model, Android version and glasses into one zip and opens an email
  to connect@mikehaye.com with it attached, for you to add a few words to and send. Email addresses, account names,
  Wi-Fi names, Bluetooth device names, MAC addresses and IP addresses (other than the glasses' own link) are taken out
  first. Nothing is sent until you tap send in your email app.

## 1.1.12: head tracking on XREAL Air glasses, ready for Google Play

- Head tracking (Body Anchor, 3DoF) now works on the XREAL Air, Air 2, Air 2 Pro and Air 2 Ultra. These models send
  their motion sensor over USB HID, not over the USB network the One family uses, so before this version Body Anchor
  still said 3DoF but the Space stayed head locked and windows turned with your head. Pocket XR now reads their sensor
  directly, and Android asks once for USB access when the glasses are plugged in. (Not yet tested by the project on
  these glasses.)
- A `PocketXR-Tracking` log line every second while head tracking runs: the glasses found, where the head pose comes
  from, the tracking mode, the sensor rate, the pose, and in plain words what is wrong when there is no pose. The
  Glasses report includes the newest line.
- Pocket XR and the add-on are prepared for Google Play (docs/PLAY.md).
- The add-on has its own screen. Before its accessibility service or notification access is switched on, it says what
  each one reads, what it is used for and that nothing leaves the phone, and it goes on to Android's page only when you
  tap "Agree and turn on". Pocket XR's buttons for both open that screen.
- Notification access is switched on by you in Android's own page, after the add-on's explanation, instead of silently
  through Shizuku. An add-on from before this version is still turned on through Shizuku.
- A copy of Pocket XR from Google Play gets its updates from Google Play: it hides "Check for updates" and links to the
  add-on's Play page.
- A privacy policy: [PRIVACY.md](PRIVACY.md).
- The add-on is version 1.1.

## 1.1.10: one-finger scroll, Exit, clearer head tracking diagnostics

- One-finger scroll on Spatial Mouse: slide one finger on the pad to scroll the window under the dot, and the dot holds
  still while you scroll. A tap still clicks and press and hold still drags. It's on by default, with a switch in the
  Menu, and the tutorial and the hint mention it. (#35)
- Exit Pocket XR: a new Exit action on the Pocket XR notification and an Exit Pocket XR row at the end of the trackpad
  Menu. It closes the Space, hand and head tracking and the notification, and nothing opens by itself again until you
  open Pocket XR. The tutorial's last page says where it is.
- Head tracking diagnostics now has Copy all and Share buttons that copy or share the whole screen as text, not just
  one line.
- Glasses that send head tracking over USB HID (like XREAL's Air family, and the newer XREAL-made glasses on USB
  3318:0442) are now named as such in the diagnostics and the Glasses report, instead of blaming a missing USB network.
  Pocket XR does not read head tracking over HID yet, so on these glasses the Space stays head locked.
- The "no USB network" line names the phone's own system: One UI only on Samsung phones, Android on others.
- The Glasses report lists each USB interface with its type (HID, CDC NCM, audio and so on), and says which head
  tracking link the glasses use.
- When a second window of an app (like Chrome) takes the app from your first window while Menu, Open a new instance
  is off, the first window now says so and offers Turn on and reopen, instead of saying the app can only show one
  window. That message now shows only when the switch is already on. The Menu row explains what the switch is for.

## 1.1.9: easier setup, Spatial Mouse on the Retroid Nova, head tracking on Android 11

- Spatial Mouse now aims with the top edge of the screen as you hold it, whichever way the system turned the screen.
  On the Retroid Nova, 1.1.8 still went portrait and tilting the left side moved the dot up and down. Now it stays
  landscape and tilting the top edge does. (#33)
- Head tracking now also works on phones that reach the glasses over their other USB network link, as some Android 11
  phones do.
- New users now get set up as they go. On XREAL One glasses (One, One Pro, 1S), the first-run tutorial starts with a
  short Glasses setup page: use Follow mode, turn Stabilizer off, then look left and right to check. It also shows the
  first time a new model is plugged in, and any time from Menu, Glasses setup. If the glasses look like they're in
  Anchor mode, a card offers that page in one tap. (#31)
- The tutorial has a Spatial Mouse page that shows how to hold your device: upright on a phone, sideways like a
  controller on a landscape handheld. (#33)
- Apps that can only show one window, like Chrome on Android 11, no longer leave a window saying Reopen / Close. It now
  says the app moved to your newest window, with one button to close the old one.

## 1.1.8: Follow mode on the One, Spatial Mouse on landscape handhelds

- On XREAL One glasses, the hint now says to use Follow mode (not Anchor) with Stabilizer off. In Anchor mode the
  glasses hold the picture still themselves, on top of Pocket XR's head tracking, so windows moved the wrong way on
  each turn. (#31)
- On handhelds whose screen is landscape by nature, like the Retroid Nova, Spatial Mouse now stays landscape and aims
  with the top edge. Before, it turned the screen to portrait and aimed with the side edge. Phones work as before. (#33)

## 1.1.7: level calibration for the One Pro

- The level calibration now takes glasses tilted up to 40 degrees. The XREAL One Pro's is steeper than the Aura's, so
  it was refused, and its windows sat too high or too low. Run the level calibration once after updating.

## 1.1.6: Developer settings you can find

- The trackpad's Menu ends with a "Developer settings" switch. Before, the Developer card only opened with a hidden
  long press on the Menu's title.

## 1.1.5: recording and reports on more phones

- Recording the glasses could save an empty video on some phones (seen on a Retroid). It now uses a video surface
  those phones accept, and if nothing was recorded it says so instead of "Saved".
- The Glasses report is shared as a file too, so a file manager can save it to Downloads.

## 1.1.4: One Pro windows stay level

- On the XREAL One Pro, windows rolled as you turned your head. Its factory calibration was applied in a way only
  checked on the Aura; Pocket XR now uses its own level calibration there, as on the One.
- The Glasses report shows the factory calibration's numbers.

## 1.1.3: allow in Shizuku with one tap

- When Shizuku is running but hasn't allowed Pocket XR yet, the trackpad now says so, and one tap opens Shizuku's
  allow dialog. Before, the button was only under Menu, Pocket XR Settings, Spatial windows.

## 1.1.2: landscape handhelds

- On a device whose screen is landscape by nature, such as a Retroid, Pocket XR starts on the Horizontal Trackpad.
  Before, it started on the Vertical one, which turned the screen on its side. A page you chose is kept.
- The first tutorial fits short screens.
- Pocket XR no longer closes at start when Android refuses its log folder.

## 1.1.1: Android 11 to 13

- On Android 11, Pocket XR closed as soon as the glasses were plugged in (seen on a Galaxy S20 FE). It now runs there.
- App windows in the Space now open on Android 11 to 13 too. Before, they needed Android 14, and the Space opened
  empty on older phones. Tested on Android 11 and 13; there, windows run at the refresh rate Android gives them.

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
