# Gallery improvements

Requested 2026-10-03. Implement in order, then build/install the combined APK.
Retain the existing native dark/mint design and vault encryption.

1. Photo/video viewing zoom: pinch from 1×, double-tap zoom/reset, bounded pan,
   preserve rotation/playback and stop page swipes while enlarged. Reset on media
   or viewing-orientation changes. Provide accessible zoom actions.
2. Favorites: mark/unmark photos/videos in the viewer, persist in the existing
   isFavorite field, show a Favorites collection on Home and badges in grids.
   Preserve album navigation and selection; avoid a database migration.
3. Thumbnails: preserve actual decoded aspect/orientation, generate sharper
   bounded previews for camera/import/restore, and refresh older visible previews
   without blocking the grid or decrypting whole videos into memory/plaintext.
   Keep thumbnail files encrypted and upgrade them without changing originals.

Check each increment with compile/tests and save it locally. Build/install after
all three are complete; verify both phones where available. Record UI gate and
remaining verification limits before delivery.

Queued afterward: camera/local and remote pinch zoom; remote tap-to-focus;
remote microphone toggle. These controls are not part of the gallery increments.

Zoom increment: shared 1–5× pinch/double-tap zoom, fitted-content pan bounds,
rotation/page reset, accessible zoom actions and pager guard compile successfully.
101 unit tests passed. A reversed letterbox condition fails the pan-bound test;
restored source passes. Physical gesture/playback checks wait for the final APK.
Implementation follows Android's Compose multitouch/transformable API:
https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch

Favorites increment: persisted mark/unmark through a targeted DAO update, viewer
heart/acknowledged state, grid badge and Home Favorites shortcut. Favorites view
filters live repository results and prunes hidden selections; viewer opens only
marked items and Back retains the Home collection state. 105 unit tests pass,
including persistence failure and filtering. Dropping the toggle negation fails
the persistence regression; restored code passes. Existing backup v1 manifests
do not contain favorites; this increment preserves their wire format.

Thumbnail increment: center-crop actual decoded pixels to a square, at most
512 px, with JPEG quality 90. Video frame geometry replaces the encoded-metadata
scaling that squeezed portrait frames. Camera, import and restore use the same
crop policy. Visible older grid/album previews upgrade one at a time off the main
thread, using encrypted random access and a guarded thumbnail-only database
update. The old preview remains until publishing succeeds; original media is
unchanged. No full-video plaintext copy is needed for this refresh.

107 unit tests pass. Replacing the thumbnail sampling OR condition with AND
fails both normal-photo detail and wide-image memory-bound tests; restored code
passes. CMF device tests pass: physical two-finger pinch and double-tap for photos
and encrypted video, playback progression while zoomed, rotation/reset, Favorites
navigation/mark removal, and encrypted preview upgrade. A synthetic white circle
stays round after cropping; original encrypted fixtures remain byte-for-byte
unchanged. Only owned test fixtures are removed after each test.

App/test APK builds pass. Lint retains the existing 41 errors, with warnings
reduced from 116 to 115; no errors originate in the new gallery helpers. This is
not a clean repository lint result. The final APK is installed on CMF and Pixel.
Pixel live verification is waiting for local screen unlock.

Final APK SHA-256:
2ECF1D658710B9765E508A46E9D3E2E1E09012B167121D6779C7C5EEFF8E01EF
