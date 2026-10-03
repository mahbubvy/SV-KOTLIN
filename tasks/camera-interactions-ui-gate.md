# Camera controls: scoped antislop delivery gate

Direction: existing SV dark/mint camera and compact viewer. ENERGY 1 / RHYTHM 1 /
MOTION 1, during implementation as previously selected. Scope: camera gestures,
zoom readout, focus target and microphone toggle. Other screens are not recertified.

Evidence: 117 unit tests, app/test builds, and four passing instrumented roles
across CMF -> Pixel and Pixel -> CMF. See camera-interactions.md for exact clip
metadata, test logs, installed APK hashes and limits. Actual two-finger gestures,
named zoom actions, focus taps, microphone OFF/ON/OFF and recording guards were
exercised. The image-hidden viewer screenshot was visually inspected. A final
disabled-icon tint/unavailable-recording guard was rebuilt, unit-tested and
installed; the full live suite was not repeated for that UI refinement.

## Hard gate

- R-02 PASS: new product strings contain no em dash.
- R-03 PASS: both device tests check control bounds/order; the CMF viewer screenshot
  shows the top readout/microphone and bottom menus within the preview and viewport.
- R-17 PASS: no product statistics or unsupported quantitative claims introduced.
- R-18 PASS: no testimonials or fictional identities introduced.
- R-23 PASS: requested controls reuse Material microphone icons and existing focus
  feedback; no new visual asset or navigation structure introduced.
- R-24 PASS: existing camera/viewer routes retained, with real command callbacks.
- R-25 PASS: new over-image controls use a 90% dark scrim; primary text, mint and
  muted icon contrast are 13.77:1, 5.68:1 and 4.94:1 over white imagery. Existing button
  borders and palette are retained.
- R-26 PASS: actual local/viewer pinch and zoom actions affect hardware; focus taps
  are accepted; mic changes synchronize with the host before a recording starts.
- R-27 PASS: connecting/disconnected states retained; operation failure is visible;
  unsupported controls and recording/saving conflicts disable the relevant action.
- R-28 PASS: no FAQ or filler content introduced.
- R-32 PASS: native focusable 48 dp microphone toggle and named Zoom in/out
  accessibility actions; tests invoke the actions. Physical keyboard not exercised.
- R-33 PASS: UI changes made directly with apply_patch.
- R-34 PASS: established dark-only palette retained; no theme switch introduced.
- R-35 PASS: app/test builds and recorded two-device click/gesture checks exercise
  the added interactions. Live-tested and final UI-refinement builds are identified.
- R-36 PASS: no security, performance or optical-focus marketing claims added.
- R-37 PASS: existing design direction and explicit 1/1/1 dials recorded before UI.
- R-38 PASS: zoom/quality/microphone status comes from the selected camera session.

## Purpose gate

- R-01 PASS: restrained dark scrim stabilizes contrast over changing camera images.
- R-04 PASS: microphone/MicOff represent recording audio using the existing icon set.
- R-06 PASS: native typography retained; readout uses 12/14 sp without display styling.
- R-07 PASS: no decorative pattern introduced.
- R-08 PASS: no decorative arrows introduced.
- R-09 PASS: zoom readout provides the current camera ratio without redundant text.
- R-10 PASS: no glass effect introduced.
- R-12 PASS: no blanket shadows introduced.
- R-13 PASS: no glow introduced.
- R-14 PASS: existing compact control hierarchy retained; no feature-card template.
- R-19 PASS: gesture response is direct; existing brief focus feedback reused.
- R-22 PASS: no illustration introduced.

## Liveliness and craftsmanship

- Dials PASS: ENERGY 1 / RHYTHM 1 / MOTION 1 matches native restrained controls.
- Focal point PASS: camera imagery remains primary; readout and audio control stay small.
- Whitespace PASS: 8/12/16 dp grouping retained; microphone target is 48 dp.
- Accent PASS: mint identifies microphone-on and existing primary recording action.
- Identity PASS: SV dark media surface, mint actions and Material controls retained.
- Design Read PASS: direction documented before implementation in camera-interactions.md.
- C-1 PASS: zoom inspects framing; focus selects a metering target; mic selects clip audio.
- C-2 PASS: controls have actual callbacks and recorded device checks; disabled states
  block unavailable or conflicting requests and the final mic tint marks that state.
- C-3 PASS: no section added for decoration or template completeness.
- C-4 PASS: bounded gestures, stale-ID checks, camera-generation resets, scrims,
  clipped preview and scrollable existing controls preserve operation across state changes.
- C-5 PASS: measurements are confined to verification records and supported by test logs.
- R-05 PASS: compact camera composition retained, without a generic page template.
- R-11 PASS: existing rounded preview, small readout and native button shapes retained.
- R-15 PASS: named actions say Zoom in/out and Microphone on/off.
- R-16 PASS: no marketing buzzwords introduced.
- R-20 PASS: changes follow the owner's established SV camera layout.
- R-21 PASS: established dark design retained per the owner's previous direction.
- R-29 PASS: existing dark/mint/neutral palette retained.
- R-30 PASS: no unrelated product shell or imitation introduced.
- R-31 PASS: each visual addition serves framing, metering feedback or audio state.

Limits: repository lint remains at 41 existing errors/115 warnings. Landscape,
large-font and physical-keyboard visual checks, all physical lenses and audio-on
clip metadata are not claimed. A completed focus request does not guarantee that
the scene is optically sharp. Digital zoom remains within each lens's reported range.
