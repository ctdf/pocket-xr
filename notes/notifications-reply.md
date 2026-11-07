# "How do I get notifications in setup?" (research notes)

Checked on origin/main at 23305cd, which is now **1.2.0** (app/build.gradle.kts:20), not 1.1.17. The notification code
is the same at the v1.1.17 tag (core/.../spatial/ControlSheets.kt has the same "needs notification access" text there).

## What "notifications" means in Pocket XR

1. **Your phone's notifications in the glasses.** Supported, but only through the optional **Pocket XR add-on**
   (a second APK, `pocketxr-addon-x.y.apk`). They show on a **Notifications page** inside the Space's Control Center,
   in the glasses. It is a list, not pop ups: up to 5 newest (ControlSheets.kt:54), "N more on your phone", tap one to
   open its app, X to dismiss, Clear all. No heads up banner or toast appears in the glasses when one arrives (no such
   code found). Pocket XR's own notifications are filtered out (addon/.../NotesListener.kt:86).
2. **Pocket XR's own notifications on the phone** (POST_NOTIFICATIONS, app/src/main/AndroidManifest.xml:12): the quiet
   "ready for the glasses" notification with Exit (GlassesReadyService.kt:51-63), the hand tracking one with Stop
   (hands/HandTrackingService.kt:56-68), and "Pocket XR x.y.z is ready" for updates (README.md:170-174,
   TrackpadActivity.kt:1040-1065). Android 13+ asks once: on the main screen (MainActivity.kt:185-200), with the Hands
   camera ask (HandsActivity.kt:267), and with the first update banner. Not related to mirroring.

## Evidence for (1)

- Moved out of Pocket XR into the add-on in 1.1.0 because Play Protect blocks sideloaded apps with notification access:
  CHANGELOG.md:253-259, README.md:142-159, addon/build.gradle.kts:8-9, app/.../platform/AddonLink.kt:23-24.
- Listener: addon/src/main/AndroidManifest.xml:60-67, addon/.../NotesListener.kt:18-45.
- Page with "Pocket XR needs notification access..." and the "Allow notification access" button:
  core/.../spatial/ControlSheets.kt:95-101, app/.../spatial/ControlCenterPainter.kt:279.
- The button: no add-on means "Install the Pocket XR add-on first: <url>"; otherwise it opens the add-on's disclosure
  screen on the phone ("finish on the phone's screen"): app/.../phone/PhoneNotifications.kt:48-54.
- Add-on screen: "Agree and turn on" opens Android's notification access page for the add-on:
  addon/.../SetupActivity.kt:94-112; texts in core/.../Disclosure.kt:36-47. Since 1.1.11 the user switches it on
  (CHANGELOG.md:158-162).
- The page opens from the bell **Notifications** round button in the Space launcher (core/.../XrLauncher.kt:321-327,
  app/.../spatial/SpaceRenderer.kt:3509, LauncherGl.kt:542).
- Privacy: PRIVACY.md:45-47 (stays on the phone).

## Setup flow

- README setup step 3 says "Optional: install the add-on ... for the Notifications page" (README.md:105).
- The glasses setup check (1.1.16, GlassesCheck.kt / GlassesCheckCard.kt) does **not** mention notifications or the add-on.
- Pocket XR's Hands screen only has the add-on's **pointer** (accessibility) button, plus a "Restricted setting" hint for
  it (ui/HandsScreen.kt:244-252). There is no notifications button in the phone app; the only in-app way in is the
  Space's Notifications page.
- Opening the add-on's own app icon shows both cards, including notification access (SetupActivity.kt:57-62).

## Steps for a user

1. Install the add-on from the add-on releases (README.md:162-167). If Play Protect blocks it, `adb install` or pause
   Play Protect scanning for the install.
2. With the glasses on, open the Space, press the bell Notifications button in the launcher, then "Allow notification
   access". Or just open the "Pocket XR add-on" app on the phone.
3. On the phone, read the add-on's screen and tap "Agree and turn on", then switch it on in Android's page.
4. Manual route (Android, not in the repo): Settings, then Apps, then Special app access, then Notification access (on
   Samsung: Settings, then Apps, then the three dot menu, then Special access, then Notification read, reply and
   control), then Pocket XR add-on. If it's greyed out as a restricted setting, open the add-on's App info, the three dot
   menu, Allow restricted settings, and try again (same fix the Hands screen gives for the pointer).

## Limits

- Needs the add-on; Pocket XR alone shows "Install the Pocket XR add-on first".
- List only, 5 shown, no pop ups or sound in the glasses; replies are not supported (only open and dismiss).
- No battery or Do Not Disturb setting is needed by the code; DND still hides what Android hides.
- Nothing in the repo says pop up notifications in the glasses are planned.

## Draft reply (Mike's voice)

Notifications come from the free Pocket XR add-on, a second small app on the releases page.  Once it's installed, press the bell Notifications button in the Space, tap Allow notification access, then tap Agree and turn on on your phone.  You can also turn it on by hand: open Settings, then Apps, then Special app access, then Notification access, and switch on Pocket XR add-on.  They show as a list on that Notifications page in the glasses, not as pop ups.
