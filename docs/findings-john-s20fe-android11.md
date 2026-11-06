# John's S20 FE on Android 11: head tracking and Chrome windows (1.1.8)

Tester: John Davis, Samsung Galaxy S20 FE 5G (SM-G781V), Android 11 (API 30), XREAL One (USB 3318:0438).
Report of 2026-11-03 21:44 UTC, Pocket XR 1.1.5 (136), glasses plugged in.

## 1. Head tracking: "No route to 169.254.2.1 on eth0"

### What the code does

- `GlassesNetwork.scan` lists every Android `Network` with a 169.254 address or an ethernet/USB transport, plus kernel
  interfaces with a link-local IPv4 address that Android made no `Network` for.
- `HeadTracker.run` (the `reachable` filter before the `NoRoute` branch) keeps only networks whose addresses or routes hold
  169.254.2.1 (`Found.reachable`, `GlassesReach.hasRoute`), and kernel interfaces not yet in `noRoute`. With none left it
  sets `HeadTrackingStatus.NoRoute(names)`, whose summary is exactly John's line, and waits with `waitFor(Long.MAX_VALUE, ...)`.
- `GlassesWarp.openControl` (used by `GlassesDisplayControl`) uses the same filter, so with no route the control link
  never connects and retries every 30 s: John's "Control link: not opened ... tried again every 30 s". Serial, 2D/3D, EDID
  and factory calibration all come over that link, so they are "not read" for the same reason.

### Most likely cause

The XREAL One offers two USB network links: NCM (the phone at 169.254.2.10/24, the glasses at 169.254.2.1, IMU and control)
and ECM (the phone at 169.254.1.10/24 by DHCP from the glasses at 169.254.1.1), as the Beam Pro reference rig and the
Flip's 0.4.8 log showed (eth1, `169.254.1.10/24`, route `169.254.1.0/24`, server 169.254.1.1). On the Flip and the Beam Pro the NCM link is eth0 and ECM is eth1.

John's report names one interface only, eth0, and says "No route", not a connect error. That fits eth0 being the ECM link:
an address of 169.254.1.10/24 and a route of 169.254.1.0/24 hold no way to 169.254.2.1, so `hasRoute` is false and nothing
is ever tried. The likely reason only the ECM link comes up is that the S20 FE's Android 11 kernel has no `cdc_ncm` driver
(or Android does not manage the NCM interface), so the ECM link becomes the first, and only, eth interface.

This is read from the report and the code, not seen on the phone. What was checked and set aside:

- The 1.1.1 `TRANSPORT_USB` guard (`HeadTracker.watchNetworks`) only affects the network callback, which is best effort
  (`waitFor` also polls every 3 s). `TRANSPORT_USB` does not exist on API 30, so a USB network there is an ethernet one, and
  `GlassesNetwork.transports` already guards it too. Nothing that worked on API 30 was skipped: 1.1.0 crashed before it.
- Bind-to-network (`Network.socketFactory`, API 21) and the static fallback (`GlassesEthernetLink`, through Shizuku) need
  nothing newer than API 30. The static fallback only runs when the scan is empty, so it never ran here.
- A NetworkRequest from the app is probably not needed: as far as known, Android 11's ConnectivityService keeps an
  unvalidated Ethernet network while its score as validated beats the default network's, and the Flip kept its ECM network
  with no gateway. A DHCP lease on the NCM link would then have given a reachable network. Not checked on Android 11.
- A kernel interface with no Android network would also end in `NoRoute` (its first connect fails with ENETUNREACH, since
  app sockets are routed by netd's per-network tables). Android 11 turns a DHCP lease into a network, so this is less likely.

### "Not retrying until the network changes" on Android 11

`waitFor(Long.MAX_VALUE, ...)` ends only on a changed scan fingerprint (looked at every 3 s), a kick (the glasses' display
added, "Try again"), or the tracker stopping. With a steady eth0 it waits for as long as the Space is up. That is right for
a link that can never reach 169.254.2.1, but it also keeps an interface that once answered EHOSTUNREACH (glasses still
booting) out until a replug. Not changed in 1.1.8; a capped backoff (60 s) instead of forever would cover it.

### Fix in 1.1.8

- `OneImuProtocol.ECM_HOST` = 169.254.1.1. `GlassesReach.host` gives a network's glasses address: 169.254.2.1 when it
  reaches the NCM link, else 169.254.1.1 when it reaches the ECM link. `GlassesReach.pick` keeps the ECM address only when no
  network reaches 169.254.2.1, so the Flip (eth0 and eth1 both up) is tried exactly as before.
- `GlassesNetwork.Found.host`, `Route.host`: head tracking (`HeadTracker.openRoute`), the control link
  (`GlassesWarp.openControl`) and the Ethernet bring-up's probe connect to the route's host.
- The `NoRoute` line now carries the interfaces' IPv4 addresses ("eth0 169.254.1.10/24"), so the next report says which
  link it was.

Unknown: whether the glasses serve 52998 and 52999 on 169.254.1.1. Nebula's own library talks to 169.254.1.1 first for its
OTA socket, which suggests the services listen on both links, but this is not confirmed. If they do not, John's next report
changes from "No route" to a timeout or refusal on 169.254.1.1, which settles it either way.

Tests: `HeadRetryTest.theEcmLinkReachesTheGlassesAt169_254_1_1`, `theEcmLinkIsUsedOnlyWhenNoNetworkReachesTheNcmLink`.

### What to ask John (1.1.8 or later, glasses plugged in, Space open)

1. The glasses report and a screenshot of the Head tracking diagnostics screen (networks seen, attempts).
2. If he can use adb: `adb shell ip addr`, `adb shell ip route show table all`, `adb shell ip rule`,
   `adb shell dumpsys ethernet`, `adb shell dumpsys connectivity | grep -A3 eth`, and
   `adb shell "ls /sys/class/net; ls /sys/bus/usb/drivers"` (whether `cdc_ncm` is there).

## 2. Several Chrome windows: only one shows, the others say Reopen / Close

### Chain

- `SpaceRenderer.addApp`: with "Open a new instance" off, a second open of the same app brings the first window back. A
  second Chrome window comes from that switch being on, a saved layout, Reopen or Switch app.
- `PanelHost.open` makes a virtual display (`PanelDisplays.flags`; on API 30 `PanelHostService.create` uses `legacyConfig`,
  which changes only how the display is built) and calls `PanelHostService.launchNew`.
- `PanelDisplays.launchCommand`: `am start --display N -a MAIN -c LAUNCHER -f 0x18200000` (NEW_TASK | MULTIPLE_TASK |
  RESET_TASK_IF_NEEDED) for a new instance, `0x10200000` otherwise, through Shizuku. No NEW_DOCUMENT, no extras, no per-app
  path. There is no task id per window and nothing moves tasks back.
- `SpaceRenderer.checkTasks` polls the display's tasks; `EmptyPanelDetector` (`PanelGuards.kt`) marks a display with no
  visible task as App closed after its grace period, and `PanelCard.AppClosed` shows Reopen and Close.

### Cause

MULTIPLE_TASK only makes a new task if the activity finally started allows it. Chrome's launcher entry forwards to its
tabbed browser activity, which keeps one task (Chrome's own multi-window, more than one browser window, came with Android 12
and only for its second activity in split screen on 11). So the second `am start --display M` brings Chrome's one task to
display M; display N is left with no task and shows App closed. Reopen moves the task back and empties the other window.
This is Chrome's behaviour as known, not read from this repo; a device log would show `launch Chrome on display M` then
`panel ... Chrome: display N has no task ...; App closed`, and `dumpsys activity activities` one Chrome task.

### Fixable?

Not reliably on Android 11. Options, none in 1.1.8:
- Chrome's second activity (`org.chromium.chrome.browser.ChromeTabbedActivity2`) directly: at most two windows, and the shell
  may not be allowed to start it.
- A better card: when a launch of package P on display M empties another window of P, say "Chrome allows one window on
  this Android version" instead of offering Reopen, which only flips Chrome between windows. Pure logic for `:core` next to
  `EmptyPanelDetector`.
