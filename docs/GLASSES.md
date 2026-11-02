# Glasses support

This page is for people running Pocket XR on their glasses. It covers which glasses the app has been tried on, what to
set on the glasses themselves, and what to send when something is wrong.

## Support

| Glasses | Status | Hand tracking | Notes |
| --- | --- | --- | --- |
| XREAL Aura | Tested | Yes, through its camera | The model the app was built on. |
| XREAL One | Tested (30 October 2026) | Not yet: possible with the XREAL Eye add-on (help wanted) | Turn **Stabilizer off**, see below. |
| XREAL One Pro | Community testing | Not yet: possible with the XREAL Eye add-on (help wanted) | Treated as a One with a 57 degree view. Not run by the project yet. |
| XREAL 1S | Community testing | No | Treated as a One. Its view is not published, so the One's 50 degrees is used. |
| Other glasses | Help wanted | No | Plain fallbacks; see "Unknown glasses" below. |

The app reads which glasses are plugged in from what they report to Android: the display's name and product id, and
the USB product id. The model is logged when the Space opens, and the Glasses report gives it.

## Settings on the glasses themselves

### XREAL One (and likely the One Pro and 1S): Stabilizer off

**Turn Stabilizer off in the glasses' own menu** (with the buttons on the glasses).

With Stabilizer on, the windows lag each head turn and then glide back into place when the head stops. The One steadies
its own picture against the head while Pocket XR already moves the picture to match the head, so the two fight.

Found by Mike on his One on 30 October 2026: "it was the xreal ones Stabilizer setting in the glasses causing the issue
because it was on. i turned it off". The One Pro and 1S have the same menu; that they need the same setting is
assumed, not yet confirmed.

The app cannot read or change this setting. It says it on the glasses for the first three sessions on a One-family
pair, and the Glasses report lists it under "Check on the glasses".

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
