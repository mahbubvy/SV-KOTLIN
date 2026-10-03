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
