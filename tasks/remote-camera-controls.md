# Remote camera controls

The viewer controls the camera phone through its existing paired TLS stream.
Photo capture, camera/lens selection and remote video start/stop are complete.

## Camera and lens selection

- The camera advertises only front/back cameras that CameraX reports available
  and rear lens options from the existing camera catalog. No device roles or
  lens availability are hardcoded.
- The viewer selects an explicit option from a camera menu. Current selection,
  pending and failure states reflect camera acknowledgments, including changes
  made locally on the camera phone.
- A selection reuses the ordinary camera rebind/zoom path and the active stream.
  Success follows camera readiness and applied zoom; no fallback lens is reported
  as the requested lens. Photo capture and selection share one pending command.
- Commands accept only advertised bounded IDs while the camera screen is active
  and the vault is unlocked. Malformed, overlapping or replayed requests end the
  session. Timeouts never retry a command automatically.
- Protected and optional-PIN modes both support selection. PIN-off permits any
  connected local viewer to use the same available controls, as Settings states.
- Preserve video recording defaults, saved media and streaming orientation.
  No recording, microphone or extra media capture is added in this increment.

## UI direction and verification

Keep the existing dark viewer surface, restrained mint shutter accent, Material
type and native menu. The live image remains the focal point. A labeled 48 dp
camera menu is a secondary control; selection text conveys the active camera.
ENERGY 1 / RHYTHM 1 / MOTION 1: compact controls, structural 8/16 dp spacing,
native pressed/menu states, no decoration or new visual assets.

Verify bounded protocol messages and mixed video/control ordering with unit
tests; build app and device tests, assess lint against the existing baseline.
On both phones, select front, rear and every advertised rear lens, confirm the
camera model and applied zoom match the viewer acknowledgment, check upright
live preview, local selection synchronization, Disconnect and restart. Physical
field-of-view differences require user observation; metadata alone is insufficient.

## Completed checkpoint

The camera/lens increment is built, installed and tested in both directions,
with open CMF-camera and protected Pixel-camera sessions. The CMF reports front
plus one rear choice; Pixel reports front plus two rear choices. Available choices,
acknowledgments, local changes, retained recording defaults, menu touch-target
bounds and session cleanup pass. All 96 unit tests pass. Lint retains the recorded
41 errors and 116 warnings. See camera-stream-validation.md and the scoped UI gate.

Remote video start/stop now uses visible camera-side recording state and confirmed
save through the existing encrypted queue. Simultaneous streaming/recording was
verified on both phones; enable recording only after successful camera binding.

## Remote video increment

- Bind streaming Preview + ImageCapture + VideoCapture at 1080p30. Advertise
  recording only after the selected camera successfully binds the combination;
  otherwise keep preview/photos available and show recording unavailable.
  Ordinary recording defaults remain untouched. Microphone follows the camera's
  existing setting and permission; the viewer shows its actual audio state.
- Explicit start/stop commands share the sequential request IDs and pending-command
  guard with photos and camera selection. Do not retry timed-out commands.
- Start confirmation follows CameraX's Start event. Stop confirmation follows
  Finalize and successful encrypted media/thumbnail/database storage. Display
  elapsed recording time and saving state on both devices. Disable photo/lens
  changes while recording or saving. Stop locally or remotely; disconnect,
  background and navigation stop the recording and enqueue its save.
- New endpoint versions prevent older viewers from reading unknown video messages.
  Verify protocol bounds/order, lifecycle cleanup, two-phone start/stop and an
  encrypted saved clip. No saved-video FPS/orientation claim without device evidence.
- UI retains dark/mint styling, 48 dp minimum controls, native icons/menu, and
  restrained recording color. Keep the relocated photo button above the lens menu.

Completed 2026-10-03: app/test builds and 97 unit tests pass. Both roles work,
including protected Pixel and PIN-off CMF sessions, photo regression, Pixel 0.6×,
normal stop, viewer disconnect and camera background. Saved test clips are
1920×1080, have portrait rotation metadata and approximately 29.9 FPS measured
from MP4 sample timestamps. See camera-stream-validation.md for exact evidence
and remote-camera-controls-ui-gate.md for the scoped interface review.

## Highest-quality recording and remote light

Requested next increment: remove the FHD30-only recording restriction and add
remote flash control. Stream transport stays 720p30; the saved video uses the
highest supported resolution/FPS by default, with an explicit viewer quality menu.

- Reuse the existing VideoMode presets and per-camera size/frame-duration/range
  checks. Rank resolution before FPS. Validate camera binding; never acknowledge
  an unbound requested quality as applied. Ordinary recording preferences remain
  separate. Lens changes refresh the available quality list.
- CMF's native 1080p60 path already accepts a stream renderer. Integrate it with
  encrypted recording acknowledgments and lifecycle stop. Its two-output session
  does not offer still capture; show photo unavailable in that selected mode.
  CameraX 60 FPS streaming also omits the still output so JPEG capture does not
  constrain sensor FPS. Select a 30 FPS mode to use remote photo capture.
- Quality changes share the existing bounded sequential command slot, only while
  idle, and acknowledge after rebind/readiness. No automatic retry.
- Flash menu uses Off / Auto / On. Auto applies automatic still flash; On also
  enables continuous light for preview/video. Advertise only a camera with a flash
  unit. Allow light changes while recording; acknowledge applied torch state.
  Local and remote light changes synchronize; teardown turns the torch off.
- Use newer endpoint versions for settings messages. Retain encrypted pairing,
  foreground/unlocked guards, native menus and 48 dp controls. Verify actual saved
  resolution/FPS, lens switching, torch state, photos, and record/disconnect cleanup.

Completed 2026-10-03: quality and flash controls built and installed on both
phones. Pixel defaults to 4K60 and CMF defaults to 4K30; both also record the
selected 1080p60 while streaming. MP4 sample timestamps measure approximately
58.9–59.7 FPS for 60 FPS modes and 29.9 FPS for CMF 4K30. Remote flash ON/OFF
passes before/during recording, and front-camera flash disables. Protected
pairing recovery, photo capture at 30 FPS, Pixel 0.6× and local/remote camera
synchronization pass. CMF native background recording finalizes and encrypts.
98 unit checks pass; lint retains 41 errors / 116 warnings. Exact evidence and
visual/testing limits are in camera-stream-validation.md and the scoped UI gate.

## Viewer layout reference, 2026-10-03

Match the user's supplied layout while retaining the native dark/mint theme.
Center the connected device name above a larger rounded preview. Put quality,
flash and camera/lens menus in a single compact row inside its bottom edge;
keep a full-width mint Record action and outlined Disconnect below the preview.
The preview's top-right corner shows the selected saved-video quality and a
microphone icon. Keep camera/session mechanics and command eligibility intact.

Replace persistent routine confirmations with short accessible snackbars. Keep
connection/command errors visible and retain recording time and save progress.
Photo capture remains above the lens control at 30 FPS. Explain the existing
60 FPS photo restriction inside the quality menu, rather than on the idle view.
Menu controls reflow vertically below 320 dp available width or above 1.3 font
scale; they can scroll inside the preview. Disconnect stays outside that scroll.
