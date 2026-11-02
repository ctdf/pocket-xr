# Pocket XR visual direction

Written on 30 October 2026 as a proposal, from a survey of what the Space and the phone drew at 0.5.66. Mike chose this
direction ("Light on glass", with a bundled font and no laser ray), and it was built from 0.5.67 on. The design tokens
live in core's `Look.kt`; the renderer, the trackpad's Views and the Compose theme all read them. The build plan below
is kept as it was written, so its version numbers may not match the releases exactly.

## Where it stands

**Glasses.**
- Windows are bare pictures with rounded corners: no rim, no shadow, no focus state. Hover only shows the controls.
- Window controls are loose 128 px discs, painted with Canvas, idle grey rgb(38,42,52), hover blue rgb(70,120,210).
  Each hover colour change re-uploads the icon textures.
- The launcher has its own palette (#1D2024 fills, #E3E2E6 text, lilac rims). The Control Center is a different
  blue-grey card (#3A7BFF accent). Notices are a dark bar that pops in and out with no fade.
- The cursor is a grey ring around a dark disc. There is no laser.
- There are five different blues, a magenta, a green and two reds. There are eight text whites and no shared tokens.
- Labels blend premultiplied pixels as straight alpha, so text can get a dark fringe.

**Phone.**
- The trackpad and the cover pad are flat near-black greys (#111/#161616/#1C1C1C). The pad shows no touch at all.
  Most Menu rows have no pressed state.
- The Compose screens are a separate blue Material3 theme with default purple containers showing through. They are
  mostly engineering readouts (vectors, "Perf:", adb lines) and are no longer reachable from the trackpad.
- The usage intro is a white card in an otherwise dark app.
- Six accent colours are in use across the phone.

**Performance facts that shape the design.**
- The display is 3840x1080 at 60 Hz. Side by side draws every window twice.
- The renderer has no depth buffer and no offscreen pass per eye.
- So background blur (frosted glass) is out. It would need an extra full-eye copy each frame.
- Hover by re-painting bitmaps is the main hitch risk: the Control Center re-uploads a 1600 px card on every hover change.

## The idea: "Light on glass"

The Aura is an optical see-through display: **black is transparent, and only light shows**. That means:

- A drop shadow is invisible. It is black, and black is the room.
- A dark panel reads as smoked glass.
- Edges and depth have to be drawn with light.

So the visual language is:

| | Rule |
|---|---|
| **Material** | *Smoked glass*: surfaces are a cool near-black at high alpha (seen as tinted glass), always with a **hairline light rim** (1.5 px, white 14%) that brightens with focus. The same material on the phone: near-black cards with a hairline rim, so the pad and the Space look like one product. |
| **Colour** | Neutral greys plus **one accent**, Flip blue (#5A93FF), for hover, focus, on and selected. "You are holding this" has its own reserved colour, a lighter accent (#8FB6FF since 0.5.96; it was magenta before). Red is used only for Close and Quit, green only for the palm menu. Text uses three whites: 92%, 64% and 40%. |
| **Depth** | Focus is shown with light, not shadow: the focused window gets a brighter rim and a faint blue **halo** just outside its edge (one quad, analytic, no blur pass). Windows behind dim slightly. Controls float as one glass **capsule** under the window, not as loose discs. |
| **Type** | One family everywhere, glasses and phone. Sizes in a fixed scale (12, 14, 16, 20, 28, 40 at phone dp, mapped to the same angular sizes in the glasses). Weights: Regular and Medium only. |
| **Motion** | One set of curves. Hover 120 ms. Enter 280 ms on an emphasised ease (0.2, 0, 0, 1). Exit 160 ms. Everything that appears fades and rises about 2%; nothing pops. Hover and press states are shader tints, so they never re-upload a texture. |
| **Touch (phone)** | The pad answers the finger: a soft glow under each finger and a ripple on tap. Every row has a pressed state. Haptics on Menu taps match the trackpad's 10 ms tick. |

## Builds, in order (each its own version, measured on the frame clock lines)

1. **0.5.67: foundation, almost invisible.**
   - A shared `Look` token object in core (colours, radii, type scale, durations, easings) that the renderer, the
     trackpad Views and the Compose theme all read.
   - Fix the label fringe (one blend mode).
   - A developer **Space snapshot**: a broadcast that saves both eyes to a PNG, because `screencap` of the glasses
     comes back black. That lets every later version be reviewed visually before it goes to Mike.
2. **0.5.68: windows.**
   - A hairline rim in the existing round-mask shader, which is almost free.
   - The focus halo and the dim behind.
   - The controls capsule with tint-uniform hover, so there are no re-uploads.
   - A softer resize bracket, and Space notices that fade.
3. **0.5.69: cursor.**
   - A crisp light ring with a small glow.
   - It eases bigger over windows and controls, and makes a press ripple.
   - Optional: a faint ray for Spatial Mouse (Mike's call).
4. **0.5.70: Control Center.**
   - The same glass card and tokens.
   - Curved to match the windows.
   - Hover drawn as an overlay quad instead of a repaint. This is also a performance win.
5. **0.5.71: launcher.**
   - Keeps its grid layout, re-coloured to the tokens: Flip blue focus and glass buttons.
   - A soft fade-and-rise for the grid.
6. **0.5.72: trackpad and cover.**
   - The new material, finger glow and ripple, pressed states.
   - One accent, switches instead of "X: ON" text.
   - A dark usage intro.
7. **0.5.73: phone screens.**
   - The Compose theme is built from the tokens.
   - The engineering screens move behind Developer.
   - An adaptive launcher icon.

**Guard rails.**
- The halo is drawn for the focused window only.
- There is no blur pass.
- Each version's glasses frame clock and pacing lines are compared with 0.5.66 before it goes to Mike.
