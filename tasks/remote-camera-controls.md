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
