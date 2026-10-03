# Camera interactions

Requested 2026-10-03 after gallery completion. Implement and save each increment,
then build/install the combined update on both phones.

1. Local and viewer pinch zoom changes the camera hardware, including while
   recording, within the selected camera's reported range. Coalesce gesture
   updates so only the latest target waits behind a pending command. Show the
   acknowledged ratio; reset queued gestures on camera/lens changes. Include the
   CMF native 1080p60 path without changing recording quality.
2. Viewer tap-to-focus maps only the visible image to the camera preview,
   accounting for rotation, fit and encoder letterboxing. Ignore taps on black
   margins/control overlays. Preserve native/local focus and show a brief target.
3. Viewer microphone toggle selects recording audio before starting a clip;
   synchronize local state and actual permission. Do not change audio tracks or
   rebind cameras during recording/saving. No remote permission prompt.

Use the existing encrypted, foreground/unlocked stream and sequential command
slot. Add bounded interaction messages and endpoint versions so older viewers do
not attempt to read new messages. Reject stale camera IDs and nonfinite/out-of-
range values. Acknowledgments follow the camera operation, never just a tap.

Design Read: native SV camera and monitor for personal use, dark/mint Material
controls, ENERGY 1 / RHYTHM 1 / MOTION 1. Retain the compact viewer layout; use a
small actual zoom readout, focus target and the existing microphone icon as a
48 dp toggle. Reuse existing focus feedback and restrained scrims over imagery.

Verification: protocol/mapping/bounds tests, app build, two-device roles where
available, zoom while recording, lens/quality regression, audio track checks and
encrypted save. Device lock/camera-scene readiness remains local. Record actual
device results and the scoped UI gate before delivery.

Implementation sources:
https://developer.android.com/media/camera/camerax/configuration
https://developer.android.com/reference/androidx/camera/view/PreviewView
https://developer.android.com/reference/android/hardware/camera2/CaptureRequest
