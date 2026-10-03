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

## Implementation and validation

Local commits: e5689e2 (zoom), 5610f21 (focus), 8dd0343 (microphone).
The hardware zoom ratio is relative to the selected camera/lens. The reported
minimum may be below 1x; callers use that range rather than imposing a 1x floor.
Focus acknowledgment confirms a completed metering request, not optical sharpness.
Microphone changes require existing audio permission and an idle camera; recording
or saving keeps the microphone control disabled. No new remote permission prompt.

App and test APK builds pass, with 117 unit tests. The stale-camera guard was
temporarily reversed: all three interaction policy tests failed. After restoring
it, the complete unit suite passed. Repository lint remains at the preceding
41 errors and 115 warnings. No baseline suppression or unrelated lint fix added.

Live-tested app APK SHA256:
5AD080360998B5534F3854214294B926F8052C309657E0F3A4FDD09DD4C8758E
Both CMF A015 and Pixel 5 received this APK using update installation; vault data
was retained. The device test harness was subsequently rebuilt separately to
correct its assumption that Pixel zoom must stop at exactly 1x.

CMF camera -> Pixel viewer: both instrumented roles pass. Actual two-finger
pinches and named zoom actions change local and remote hardware zoom. Remote
tap focus is accepted, microphone OFF/ON/OFF synchronizes to the host, and zoom
and focus remain available during recording while microphone changes are blocked.
The encrypted test recording and thumbnail save successfully: 1920x1080,
90-degree portrait metadata, 11.588 seconds, no audio track, and 688 video samples
at 59.4419 FPS measured from presentation timestamps. Preview continues during
and after recording; disconnect and fresh stream start/stop complete.
Evidence: scratch/camera-interactions-cmf-send.log and
scratch/camera-interactions-pixel-view.log. Test clips remain in the Camera album;
the harness does not remove personal media and restores streaming PIN settings.

Pixel camera -> CMF viewer: both instrumented roles pass the same controls,
actual pinch gestures, continued rendering, silent encrypted save and restart.
The Pixel clip is 3840x2160, 90-degree portrait metadata, 11.913 seconds, no audio
track, and 716 video samples at 60.0166 FPS from presentation timestamps.
Evidence: scratch/camera-interactions-pixel-send.log and
scratch/camera-interactions-cmf-view.log. Viewer layout was inspected in
scratch/camera-interactions-cmf-viewer.png with camera imagery hidden. Screenshot
protection is restored by the test's finally block.

After these live checks, a final viewer refinement dims the disabled microphone
and disables it when remote recording is unavailable. App build and all 117 unit
tests pass again; both phones receive the final APK with SHA256:
4DC12762221A31043E045D0162786E0CCD818D4DF138DF5498FE9A58E977B86D
The complete live recording suite was not repeated for this final UI-only change.

Review: correctness covers finite/range validation, stale IDs, saving and recording
guards, crop/rotation mapping and acknowledged hardware controls. Shared gesture,
mapping and protocol/policy helpers follow existing module boundaries; camera
orchestration remains in CameraScreen. Input messages are bounded, commands remain
inside authenticated encrypted sessions, and camera callbacks recheck foreground,
unlocked vault and stream ownership. Conflated queues bound gesture traffic;
camera operations await completion without blocking the UI thread. No database,
backup format or media encryption change. Audio-on recording metadata, every
physical lens, landscape, physical keyboard and large-font layouts were not
separately exercised in this increment.

The two-phone checks run with tasks/test-live-stream.ps1, test class
com.secretvault.app.stream.StreamLiveUiDeviceTest#discoversAndPairsTheNormalCameraThroughUi,
RemoteInteractions, RemoteVideo and NoStreamPin. Select native/1080p60 for CMF,
camerax/4k60 for Pixel; reverse Sender and Viewer to check both roles.
