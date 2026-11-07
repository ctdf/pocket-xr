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
Anchor. On a phone, XREAL's own app does no harm while closed; if it is open, close it from Recents.
Android does not tell an app which other apps are running, so Pocket XR says this whenever such an app is installed.

The app cannot read the mode either. The one hint it has is the panel duty the glasses answer when the control link opens
(10065, already read for Sharper turns): about 90 % in head lock, and 16 to 32 % in the glasses' own world lock modes
(measured on the Aura). A One-family pair that answers 40 % or less is taken as likely in Anchor mode, and the trackpad
shows a card with one tap to the Glasses setup check, once per run of the app and never once the check has passed. That a One in Anchor mode reads low is
inferred, not measured, so the card says "may be". No other control message is sent to find out.

### XREAL Aura: Spatial Anchor

Pocket XR turns **Spatial Anchor** off each time it connects to the Aura. It sits where the One has Stabilizer, and on, it
fights the Space's head tracking. Found by Mike on 6 November 2026, after a long hunt for shake that it was causing. To the
firmware it is the translation switch: 10404 reads it and 10405 sets it. The firmware's EIS switch is something else.

### Steady mode: the stillest picture

**Steady mode** is on the trackpad, in the launcher beside Recentre, and on the cover screen beside Keyboard, on the Aura and the One series. It turns Spatial Anchor off, leaves 3D, turns on
**Ultrawide** and sets the largest screen size Ultrawide offers. The glasses then hold one flat canvas in the room by
themselves. Nothing the phone draws can shake it, turning your head looks across it, and text stays sharp. Tap it again for
3D and windows all around you. Each mode keeps its own screen size, so nothing has to be put back. Ultrawide turned on in
the glasses' own settings works too: Pocket XR sees the change and draws the canvas.

- It is flat, so there is no depth.
- Windows fill the canvas side by side, and go back where you had them when you leave. In Ultrawide
  set from the glasses' own menu they do the same. **Recentre** brings the canvas in front of you, and the glasses slide it there.

Android shows Ultrawide and 3D as the same 3840 x 1080, so the app reads the glasses' own 2D or 3D state each time the
display changes. It sends the glasses no mode in Ultrawide: any space mode or 3D request switches it off. So the app asks
for 3D once a plug-in, again if the glasses drop it straight away, and after that the mode is the user's.

Tried on the Aura and not kept: letting the glasses hold one 3D frame and re-pinning it (a blink at each change, because the
firmware restarts the picture), recentring through a turn (steppy), recentring after one (a slide, and windows arrive
late), a firmer still lock (a catch at the start of a turn, and a picture frozen on the display is not fixed to the room),
Spatial Anchor with a smoothed head (worse), and sliding windows in at the canvas edge (the windows you look at move).

Steady mode is tested on the Aura only. It should work on the One, One Pro and 1S, but hasn't been tried on them: please open an issue if it doesn't. There it reads and changes those glasses' settings only once you tap it.

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
