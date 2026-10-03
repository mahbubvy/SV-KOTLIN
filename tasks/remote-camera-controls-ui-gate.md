# Remote camera menu: scoped antislop delivery gate

Scope: the new viewer camera menu, selected/pending text, and eligibility states
for camera/lens controls. Existing product palette, navigation and photo shutter
are retained. Source review uses StreamViewerScreen.kt, CameraScreen.kt,
CameraBottomBar.kt and the remote-control spec. Device evidence is in
camera-stream-validation.md and scratch/remote-lens-*.log. Camera frames were
not inspected or captured. Landscape, large-font visual review and a physical
keyboard test are limitations; source layout/focus checks are distinguished below.

## Hard gate

- R-02 PASS: new menu/status strings contain no em dash.
- R-03 PASS: selector fills the available width; native menu bounds content;
  footer scrolls within half the window height. Device assertions check selector
  bounds and a minimum 48 dp height on CMF and Pixel.
- R-17 PASS: no statistics added; lens labels come from the camera catalog.
- R-18 PASS: no testimonials or identities added.
- R-23 PASS: reuse existing Material camera-switch/check icons; no new visual
  assets or navigation structure. Camera selection was explicitly authorized.
- R-24 PASS: no new navigation destination or links.
- R-25 PASS: TextPrimary on menu surface is 15.93:1; TextPrimary on viewer
  background is 17.91:1; secondary text on viewer background is 7.31:1.
- R-26 PASS: menu items call selectCamera; both devices acknowledge available
  choices. Current-selection taps dismiss without an extra hardware change.
- R-27 PASS: unavailable controls hide, preparing/pending controls disable,
  and failure/unknown outcomes state that the camera device should be checked.
- R-28 PASS: no FAQ introduced.
- R-32 PASS: native Material buttons/menu items retain focus and keyboard
  handling; the UI test exercises Back-key dismissal. No custom focus trap.
  Physical keyboard navigation was not exercised.
- R-33 PASS: interface changes were written directly in source with apply_patch.
- R-34 PASS: existing dark-only surface and theme tokens are retained; no theme toggle.
- R-35 PASS: app and instrumentation build; device tests open/dismiss the menu,
  select front/rear and every advertised rear option, Disconnect and restart.
- R-36 PASS: no new security/performance claims in the interface.
- R-37 PASS: direction declared in remote-camera-controls.md before UI work;
  existing dark/mint camera identity retained with dials 1 / 1 / 1.
- R-38 PASS: choices, active selection and pending states derive from actual
  camera availability and acknowledgments; no invented hardware options.

## Purpose gate

- R-01 PASS: no gradient or glow added.
- R-04 PASS: camera-switch icon opens camera choices; check icon marks the
  acknowledged selection. Existing Material icons serve these specific actions.
- R-06 PASS: existing Material body typography retained; no display treatment.
- R-07 PASS: no grid or background pattern added.
- R-08 PASS: no decorative arrows added.
- R-09 PASS: no capsule badge added.
- R-10 PASS: no glass or blur added.
- R-12 PASS: native popup elevation only distinguishes the active menu.
- R-13 PASS: no glow added.
- R-14 PASS: no feature cards added.
- R-19 PASS: native menu/pressed states only; no animation loop added.
- R-22 PASS: no illustrations added.

## Liveliness and craft

- Dials PASS: ENERGY 1 / RHYTHM 1 / MOTION 1 declared; compact secondary
  controls and native transitions match the existing camera surface.
- Focal point PASS: preview retains the flexible main area; controls are capped
  and scroll independently on short windows.
- Whitespace PASS: 8 dp internal spacing and 16 dp group padding separate
  status, selection and capture/disconnect actions.
- Accent PASS: mint remains the ready shutter accent; secondary choices use
  neutral text and outline. Disabled shutter colors use the same eligibility
  condition as click handling.
- Identity PASS: existing dark camera surface, compact controls and explicit
  native camera labels remain consistent with the rest of the camera UI.
- Design Read PASS: remote-camera-controls.md records direction and purpose.
- C-1 PASS: secondary menu groups available camera choices without competing
  with the live image or shutter.
- C-2 PASS: every new menu action maps to a bounded camera command.
- C-3 PASS: no filler section or decorative content added.
- C-4 PASS: bounded labels wrap within native menu sizing, controls scroll,
  ineligible actions disable, and Back can dismiss or leave. Source resilience
  checked; landscape/large-font screenshots are not claimed.
- C-5 PASS: device metadata and actual test outcomes are reported as such;
  physical field-of-view and mirroring remain unverified visual checks.
- R-05/R-11 PASS: existing spacing, Material button/menu shapes and type scales
  retained; new targets are 48 dp with 8/16 dp structural spacing.
- R-15/R-16 PASS: existing native type hierarchy and restrained weight retained.
- R-20/R-21 PASS: live camera choices determine the controls; no dashboard shell.
- R-29/R-30 PASS: reuse established dark, mint and neutral tokens without
  expanding the palette or adding a new type family.
- R-31 PASS: selected check marks and status text report real state; no status dot.

This is a scoped interface gate, not a claim that repository lint passes.
Recorded lint remains 41 errors and 116 warnings outside this UI increment.

## Viewer capture placement, 2026-10-03

User direction: move the photo capture button to the right above the lens selector.
The existing dark/mint identity and ENERGY 1 / RHYTHM 1 / MOTION 1 remain.

- R-03/R-05/R-35, C-4: source review places capture at the right of the status
  row, before the full-width lens selector. Status text takes remaining width and
  wraps; the existing capped, scrollable footer handles short windows. The capture
  target stays 64 dp, with a 16 dp horizontal gap. Device geometry and large-font
  visual checks for this placement have not been repeated.
- R-04/R-23/R-26/R-27/R-32, C-1/C-2: reuse the same CameraAlt icon, Take photo
  accessibility description, Material IconButton, capture callback, readiness guard
  and disabled colors. No new asset, command or navigation destination.
- R-25/R-34/R-37, C-3/C-5: existing colors and styling retained. Preview remains
  the flexible primary area; Disconnect occupies the full width below the selector.
  No new copy or claims. Remaining gate items above are unchanged by this relocation.
- Validation: `:app:assembleDebug` passed; reviewed the diff and whitespace.
  This is source/build evidence, not a fresh device test or a lint pass.

## Remote recording controls, 2026-10-03

Scope: Record video / Stop recording, elapsed time, save status and control
eligibility on camera and viewer. Direction remains dark/mint, native Material
controls, ENERGY 1 / RHYTHM 1 / MOTION 1. Existing photo placement is retained.

- R-02/R-17/R-18/R-28/R-36/R-38, C-5: no em dash, invented claims, testimonials
  or filler. Availability follows successful camera binding; recording duration
  follows CameraX stats and save confirmation follows encryption/database success.
  FHD30 is the requested mode and both test devices produce approximately 29.9 FPS.
- R-03/R-05/R-11/R-35, C-4: new record controls are full-width with 48 dp minimum
  height, 8/16 dp group spacing and the existing capped scrollable footer. Status
  text wraps; the preview remains flexible. Both-phone runtime tests activate
  controls and existing selector bounds checks pass on Pixel-camera/CMF-viewer.
  Landscape/large-font physical review has not been performed.
- R-04/R-23/R-24/R-26/R-27/R-32, C-1/C-2: reuse Material Videocam/Stop icons,
  native buttons and the existing session. No generated assets or new navigation.
  Explicit command text supplies accessibility names; pending/saving buttons
  disable. Photo/lens controls disable during recording; camera has a local stop.
  Start, stop, Disconnect and background cleanup pass; no new focus trap. A
  physical keyboard test is not claimed. Existing menu Back dismissal passed.
- R-25/R-34/R-37: existing theme retained. TextPrimary/background contrast is
  17.91:1; recording Stop text (VaultError #FF5252 / #121212) is 5.87:1. No new
  palette, gradient, texture, blur, card or animation treatment.
- R-33: interface edits were applied directly with apply_patch. Build and 97
  unit tests pass; lint remains 41 errors / 116 warnings. Verification details
  and current APK hash are in camera-stream-validation.md.
- Purpose gate R-01/R-06–R-10/R-12–R-14/R-19/R-22, quality locks
  R-15/R-16/R-20/R-21/R-29–R-31 and C-3: previous scoped findings are unchanged;
  added controls serve recording and recovery, without decoration or filler.
- Liveliness/craft: mint photo capture remains the primary capture accent; video
  is a secondary outline control, with restrained red for Stop. The camera image
  stays the focal point and selected/pending states remain tied to real responses.

Scoped source/build/device interaction gate complete with visual/keyboard limits
stated above; this does not claim an exhaustive visual audit or clean lint.

## Remote video quality and flash, 2026-10-03

Scope: camera-reported quality menu, Off/Auto/On flash, availability and applied
state. Direction remains native dark/mint, ENERGY 1 / RHYTHM 1 / MOTION 1.

- R-01/R-06–R-10/R-12–R-14/R-19/R-22, C-1/C-2/C-3: the new controls select
  saved-video quality and camera light. Reuse existing camera presets and icons;
  no new navigation, decorative asset, filler section or new visual vocabulary.
- R-02/R-17/R-18/R-28/R-36/R-38, C-5: modes come from camera capabilities and
  binding, with actual-mode confirmation. Flash completion reflects CameraX
  completion or native capture results. Explain Auto as photos only and On as
  continuous light. Explicitly direct users to 30 FPS for photo capture. Defaults
  and measured video resolution/FPS are recorded in camera-stream-validation.md.
- R-03/R-05/R-11/R-35, C-4: quality and flash use a two-column row, minimum
  48 dp controls, 8/16 dp spacing, native menus and wrapping text. Footer controls
  scroll within half the screen. Disconnect is outside that scroll container so
  recovery remains visible. Do not show unavailable controls before pairing or
  after failure. These fixes address two device-test layout failures; retest
  evidence is recorded in the validation report.
- R-04/R-23/R-24/R-26/R-27/R-32: explicit native menu labels, selected check marks,
  polite applied/pending messages, readiness guards and disabled states. Quality
  and lens changes disable while recording; flash remains available then, but
  disables during save/other commands. Front-camera no-flash behavior is checked.
  Device checks verify quality-control bounds and touch height. No keyboard,
  large-font or landscape visual audit is claimed; FLAG_SECURE is retained.
- R-15/R-16/R-20/R-21/R-25/R-29–R-31/R-34/R-37: retain existing Material type,
  shapes, neutral outlines, mint capture accent and red Stop. No added gradients,
  blur, texture or animation. Existing text/background contrast is unchanged;
  selected state comes from camera responses rather than decorative status dots.
- R-33: edits use apply_patch, app/test builds succeed, protocol mutation is
  caught by the regression test, and 98 unit tests pass. Lint retains the existing
  41 errors / 116 warnings. Device checks cover highest defaults, selected 60 FPS,
  flash during recording, post-stop rendering and CMF background save.

Scoped source/build/device gate with stated visual limits; camera image remains
the primary area, with compact controls and persistent recovery below it.

## Reference-based viewer layout, 2026-10-03

Scope: user's supplied portrait layout, translated into the existing dark/mint
theme. ENERGY 1 / RHYTHM 1 / MOTION 1; no new assets or motion.

- R-01/R-06–R-10/R-12–R-14/R-19/R-22, C-1/C-2/C-3: the centered device name,
  larger preview and one bottom menu row follow the reference. Native quality,
  flash and lens icons communicate the controls. Record is mint/solid;
  Disconnect remains an outlined recovery action below it. Routine confirmations
  use short snackbars; connection/command failures remain visible.
- R-02/R-17/R-18/R-28/R-36/R-38, C-5: quality comes from acknowledged camera
  state. Microphone status uses an accessible icon; recording time/save progress
  remain visible. Photo capture remains available at 30 FPS, with the existing
  60 FPS restriction explained inside the quality menu. No invented capabilities.
- R-03/R-05/R-11/R-35, C-4: 8/12/16 dp spacing, 12/14/16/20 sp type and
  48–64 dp controls. Device checks assert quality touch height, horizontal bounds,
  quality/lens shared row, menus above Record and Disconnect below Record.
  Controls stack below 320 dp available width or above 1.3 font scale, with a
  scrollable area inside the preview. Physical landscape/large-font/narrow-width
  checks remain unperformed; normal portrait bounds and actual Pixel screenshot
  were reviewed. No keyboard test is claimed.
- R-04/R-23/R-24/R-26/R-27/R-32: native menus/check marks and explicit accessible
  names remain. Command guards, pending/disabled states and persistent Disconnect
  are retained. Quality selection, flash ON/OFF and Record/Stop/Disconnect pass
  CMF-camera/Pixel-viewer live checks. Preview continues rendering during and
  after recording; encryption/save acknowledgment passes. The test-only screenshot
  restores FLAG_SECURE and image visibility in finally; production protection
  remains enabled.
- R-15/R-16/R-20/R-21/R-25/R-29–R-31/R-34/R-37: retain app colors/type/shapes,
  without gradients, blur, decorative cards or animations. Computed WCAG ratios:
  dark text/mint 7.39:1; dark text/red Stop 5.87:1; secondary text/surface 6.50:1;
  muted functional outline/surface 5.72:1; primary text/dark background 17.91:1.
  Screenshot review found inherited white Record text; corrected it to the
  button's content color and visually verified the resulting dark text.
- R-33: interface edits use apply_patch. App/test builds pass, 98 unit tests pass,
  and lint retains the existing 41 errors / 116 warnings. Reversing the new
  width condition leaves unit tests green: they do not cover Compose reflow.
  The condition was restored before producing/installing the final APK. Runtime
  layout assertions and screenshots supply portrait evidence, not a unit-level
  responsive-layout guarantee.

Scoped portrait source/build/device/visual gate with the stated accessibility
and responsive verification limits. Screenshot: scratch/pixel-stream-viewer-compact.png.
Reverse-role checks also pass on the CMF viewer: Pixel front/back and 0.6×/1×,
local selection synchronization, 30 FPS photo capture/save and Disconnect.
The CMF photo-control screenshot was reviewed; both installed APK hashes match.
