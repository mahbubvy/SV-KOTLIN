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
