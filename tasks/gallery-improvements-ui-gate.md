# Gallery changes: scoped antislop delivery gate

Reading: private media gallery for the app owner, retaining the native dark/mint
design. ENERGY 1 / RHYTHM 1 / MOTION 1. Mode: during implementation, as previously
selected. Scope: zoom gestures/accessibility, Favorites actions/collection, and
thumbnail presentation. Existing unrelated screens are not recertified.

Evidence: 107 passing unit tests; GalleryDeviceTest passes both tests on CMF;
scratch/gallery-cmf.log and the synthetic-only scratch/gallery-cmf.png. The
encrypted portrait-video fixture, photo circle, pinch/double-tap, continued video
playback, rotation reset, favorite add/remove, Home collection and Back navigation
are exercised. Pixel app installation succeeds; its live test awaits local unlock.
Physical keyboard, landscape and large-font screenshots are not claimed.

## Hard gate

- R-02 PASS: new Favorites/zoom/preview strings contain no em dash.
- R-03 PASS: CMF grid screenshot has bounded square cells and no overflow;
  headers retain weighted, ellipsized titles; controls keep their native sizing.
- R-17/R-18 PASS: no statistics, testimonials or identities introduced.
- R-23 PASS: requested Favorites navigation and existing Material heart icons;
  synthetic media is confined to test fixtures and clearly named Gallery test.
- R-24 PASS: Favorites opens the existing gallery/viewer routes with a filter.
- R-25 PASS: TextPrimary/dark is 17.91:1; secondary/surface is 6.50:1;
  mint favorite hearts have an 80% black scrim even over bright images.
- R-26 PASS: device checks activate Favorites and toggle its persisted state;
  gestures and accessible Reset zoom change the real media transformation.
- R-27 PASS: loading placeholders and photo errors retained; video without a
  thumbnail displays a preview-loading icon; Favorites has an empty state and
  failed favorite writes keep the previous state with an error message.
- R-28 PASS: no FAQ or filler content added.
- R-32 PASS: native focusable IconToggleButton/IconButton actions retained;
  zoom exposes named accessibility actions; device test invokes Reset zoom.
  Physical keyboard operation has not been tested.
- R-33 PASS: UI edits made directly with apply_patch.
- R-34 PASS: established dark-only theme retained; no new theme switch.
- R-35 PASS: app/test builds pass and CMF click/gesture test covers all added
  interactions. Unit tests cover zoom/pan bounds, failure and filtering paths.
- R-36 PASS: no security or performance marketing claims added to the UI.
- R-37 PASS: existing dark/mint direction and explicit 1/1/1 dials retained.
- R-38 PASS: Favorites reflects database state; media names/counts reflect actual
  items. No invented user content is presented as real data.

## Purpose gate

- R-01 PASS: no new gradient/glow; existing viewer gradient supports controls
  over media. A solid scrim gives the new heart stable contrast on bright photos.
- R-04 PASS: heart marks Favorites; the video icon identifies preview loading.
- R-06 PASS: native app typography retained; no display or monospace treatment.
- R-07/R-08 PASS: no decorative background pattern or arrow treatment added.
- R-09 PASS: small heart badge identifies saved favorites without repeated text.
- R-10/R-12/R-13 PASS: no glass, blur, blanket shadows or glow introduced.
- R-14 PASS: square media cells retain the gallery's existing uniform grid.
- R-19 PASS: direct gesture response and native pressed states match MOTION 1.
- R-22 PASS: no illustration added.

## Liveliness and craftsmanship

- Dials PASS: ENERGY 1 / RHYTHM 1 / MOTION 1; restrained native controls.
- Focal point PASS: media remains primary; heart and zoom add small actions.
- Whitespace PASS: existing 8/16 dp grouping retained; badges use 8 dp inset.
- Accent PASS: mint distinguishes a marked favorite and existing primary actions.
- Identity PASS: dark media surface, mint selection and Material controls match SV.
- Design Read PASS: direction recorded above and in gallery-improvements.md.
- C-1 PASS: heart reduces retrieval effort; bounded zoom supports inspection;
  sharper square crops improve recognition without stretching source geometry.
- C-2 PASS: all added controls have verified behavior and acknowledged states.
- C-3 PASS: no decorative section or template content added.
- C-4 PASS: source bounds zoom and clips media, leaves video controls independent,
  prevents pager swipes while enlarged, and resets on rotation/page change.
  Gallery keeps its existing scrollable grid. CMF gestures verify these paths.
- C-5 PASS: synthetic fixture evidence and untested configurations are explicit.
- R-05/R-11 PASS: established grid/card shapes retained; native action targets
  are 48 dp. The 16 dp favorite badge is an indicator, not a separate tap target.
- R-15/R-16 PASS: action labels say Favorites, Add/Remove, Zoom and Reset zoom.
- R-20/R-21 PASS: changes serve the private media gallery and preserve user style.
- R-29/R-30 PASS: same dark/mint/neutral palette; no product-clone shell added.
- R-31 PASS: each new visual decision has its function recorded above.

## Review limits

Repository lint still reports 41 pre-existing errors and 115 warnings; this gate
does not claim a repository-wide lint pass. Favorites persist locally but existing
v1 backup manifests do not carry favorite marks. Older thumbnails refresh only
when visible and unlocked. Small originals are not upscaled. Physical keyboard,
landscape and large-font visual checks remain outside the recorded device test.
