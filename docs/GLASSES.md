# Glasses support

This page is for people running Pocket XR on their glasses. It covers which glasses the app has been tried on, what to
set on the glasses themselves, and what to send when something is wrong.

## Support

| Glasses | Status | Hand tracking | Notes |
| --- | --- | --- | --- |
| XREAL Aura | Tested | Yes, through its camera | The model the app was built on. |
| XREAL One | Tested (30 October 2026) | Not yet: possible with the XREAL Eye add-on (help wanted) | Use **Follow mode** with **Stabilizer off**, see below. |
| XREAL One Pro | Community testing | Not yet: possible with the XREAL Eye add-on (help wanted) | Treated as a One with a 57 degree view. Not run by the project yet. |
| XREAL 1S | Community testing | No | Treated as a One. Its view is not published, so the One's 50 degrees is used. |
| Other glasses | Help wanted | No | Plain fallbacks; see "Unknown glasses" below. |

The app reads which glasses are plugged in from what they report to Android: the display's name and product id, and
the USB product id. The model is logged when the Space opens, and the Glasses report gives it.

## Settings on the glasses themselves

### XREAL One, One Pro and 1S: Follow mode, Stabilizer off

**Use Follow mode, not Anchor, and turn Stabilizer off in the glasses' own menu** (with the buttons on the glasses).

In Anchor mode the glasses hold their whole picture still in the room by themselves. Pocket XR already turns the windows
against the head, so they move twice: opposite to each turn and nod, as far again. Things fixed to the view, like the
level calibration's green line, look pinned in the room instead. Seen on a One Pro (issue 31).

With Stabilizer on, the windows lag each head turn and then glide back into place when the head stops. The One steadies
its own picture against the head while Pocket XR already moves the picture to match the head, so the two fight.

Found by Mike on his One on 30 October 2026: "it was the xreal ones Stabilizer setting in the glasses causing the issue
because it was on. i turned it off". The One Pro needs the same (issue 31), and so does the 1S: on a Beam Pro its
windows turned faster than the room and were cut off past an angle until the glasses left Anchor mode (issue 12,
thejoeba spotted the Anchor and Follow setting as the cause).

The buttons, in XREAL's own words for the One, One Pro and 1S ([tutorials.xreal.com](https://tutorials.xreal.com/docs/glasses/one-series/first-use/buttons/)):
press **X** once to switch between Follow and Anchor; press **X** twice to open or close the menu; **Stabilizer** is
under **Spatial Screen** (under **Display** on older firmware); in the menu **+** and **-** move, **X** picks and **X**
twice goes back. Holding **X** recentres the picture.

The app cannot change these settings. It shows the **Glasses setup check** on the phone (`GlassesSetup` and
`GlassesCheck` in `core/.../spatial/`): by itself the first time a new model connects, and any time from Pocket XR
Settings or the trackpad's Menu. It lists only the steps that model needs (none for the Air, the Aura or glasses the app
does not know), what on the host can also move the picture (a Beam Pro's spatial view, XREAL's apps on a phone), then a
10 second head turn while Pocket XR measures its own head pose, and two questions on what the windows did. A failure
names the one setting to change. Once it passes it never opens by itself again; a skip also stops it opening by itself.
The One series is told from its name or, failing that, its USB product (One Pro 0x0435/0x0436, One 0x0437/0x0438, 1S
0x043D/0x043E). The app also says the setting on the glasses for the first three sessions, and the Glasses report lists
it under "Check on the glasses".

### Beam Pro and XREAL's apps

A Beam Pro runs XREAL's own spatial view, which turns the picture with your head as Pocket XR does. Close it before
opening the Space from Pocket XR, or the windows move twice. The Beam Pro may also switch One series glasses back to
Anchor. On a phone, XREAL's app (Nebula and the like) does no harm while closed; if it is open, close it from Recents.
Android does not tell an app which other apps are running, so Pocket XR says this whenever such an app is installed.

The app cannot read the mode either. The one hint it has is the panel duty the glasses answer when the control link opens
(10065, already read for Sharper turns): about 90 % in head lock, and 16 to 32 % in the glasses' own world lock modes
(measured on the Aura). A One-family pair that answers 40 % or less is taken as likely in Anchor mode, and the trackpad
shows a card with one tap to the Glasses setup check, once per run of the app and never once the check has passed. That a One in Anchor mode reads low is
inferred, not measured, so the card says "may be". No other control message is sent to find out.

### 3D mode

The Space wants the glasses in their 3D side by side mode (3840 x 1080). On the Aura and the One, the app switches them
itself over their control link. On glasses without one, the Space shows a hint to use the glasses' own 3D button.

## What differs between models (in the app)

`GlassesModel` in `core/.../spatial/GlassesProfile.kt` holds:
- the field of view;
- whether there is a camera;
- the default sensor axes;
- the setup hint above.

The settings below are saved per model, so one pair never gets another's:
- the screen size;
- the sensor axes;
- the level calibration;
- the gyro biases.

The factory calibration is saved per pair (by serial).

**Sensor axes.** The One's sensor is turned half round about the side axis from the Aura's: "x right, y down, z front".
Its head tracking stream is the same One-family stream as the Aura's, at 1000 reports a second. If a model turns the
wrong way, the head tracking screen has the axis list. The Glasses report and the head attitude log line ("tilt error"
should stay at a degree or two) help find the right one.

## Unknown glasses

Glasses the app does not know are drawn with the One's view, no camera and the Aura's sensor axes. If their head
tracking stream answers, head tracking works. If they have no control link, they stay in 2D until switched with their
own button.

## Reporting a problem

In the trackpad's Menu, tap **Glasses report**. It saves a text file to
`Android/data/com.hayeco.flipspaces/files/reports/` and opens the share sheet. Paste it into the GitHub issue. It holds:
- the model;
- the display modes;
- the USB IDs;
- whether head tracking, the control link, 3D and the calibration worked.

The glasses' serial appears only as its last four characters.
