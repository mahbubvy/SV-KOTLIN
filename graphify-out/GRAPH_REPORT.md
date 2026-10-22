# Graph Report - gemini  (2026-10-16)

## Corpus Check
- Large corpus: 233 files · ~538,279 words. Semantic extraction will be expensive (many Claude tokens). Consider running on a subfolder.

## Summary
- 3079 nodes · 8692 edges · 157 communities (86 shown, 71 thin omitted)
- Extraction: 97% EXTRACTED · 3% INFERRED · 0% AMBIGUOUS · INFERRED: 297 edges (avg confidence: 0.85)
- Token cost: host-agent input/output usage was not exposed by the tools; no external LLM API was called. AST extraction uses no LLM tokens.

## Community Hubs (Navigation)
- Stream Protocol Validation
- Album Selection Components
- Vault Database Access
- Encrypted Gallery Device Tests
- Gallery Viewer State
- Application Key Management
- Generated Device Fixtures
- Feature Specifications Overview
- Video Edit Plans
- Main Screen Layouts
- Vault Navigation Weather
- Stream Camera Rendering
- Stream Session Verification
- Transfer Wire Protocol
- Camera Lifecycle Focus
- OpenGL Preview Rendering
- TLS Pairing Authentication
- Authentication State Tests
- Preview Orientation Geometry
- Media Playback Components
- Transfer Backup Workers
- Video Effect Export
- Media Viewer State
- Video Project Operations
- Photo Image Rendering
- Media Action Controls
- Backup Manifest Interoperability
- Video Export State
- Vault Session Lifetime
- Camera Gallery Toolbars
- Photo Editor Panels
- Camera Frame Rate Capabilities
- Media Repository Model
- Encrypted Image Preview
- Backup Export Pipeline
- Backup Record Cryptography
- Cryptography Test Assertions
- Camera Capture Modes
- Vault Streaming Encryption
- Backup Item Callbacks
- Media Ingestion Files
- Gallery Zoom Geometry
- Authenticated Cipher Framing
- Vault PIN Settings
- Backup Restore Pipeline
- Image Colour Adjustments
- Project Timeline Unit Tests
- Album Repository Model
- Sticker Project Data
- File Transfer Receiver
- Gallery Loading State
- Custom High FPS Camera
- File Transfer Sender
- Backup Restore Controls
- Device Camera Selection
- Video Blur Shader
- Editor Feature Specifications
- Backup Archive Reading
- Device Media Import
- Audio Mute Processing
- Photo Crop Geometry
- Navigation Screen Routes
- PIN Focus Indicators
- Wi-Fi Transfer Device Tests
- Encrypted Playback Data Source
- Gallery Loading Regression Tests
- Bluetooth Stream Discovery
- Camera Interaction Geometry
- Face Tracking Models
- Project Editor Controls
- Authenticated Storage Regression Tests
- Photo Colour Filters
- Encrypted Capture Save Queue
- Gallery Album Components
- Temporary Media Sharing
- Face Blur Processing
- Discovery Resolution Callbacks
- Network Service Registration
- Streaming Validation History
- Transfer Gallery Validation
- Live Stream UI Tests
- Content Fingerprint Deduplication
- Custom Camera Session Setup
- Sticker Bitmap Loading
- Timeline Sticker Gestures
- Video Face Detection
- Editor Effect Preview
- Camera Slider Device Tests
- Recording Quality Defaults
- Persistent Streaming PIN
- Media Viewer Toolbar
- Streaming Pairing Design
- Editor Integration Evidence
- Photo Edit Math Tests
- Camera Capture Callbacks
- Discovery State Handling
- Local Network Stream Discovery
- Public Stream Endpoint Metadata
- Album ViewModel Operations
- File Transfer Screens
- TLS Pairing Device Tests
- Stable Gallery Thumbnails
- Encrypted Playback Regression Tests
- Vault PIN Regression Tests
- Gallery Slider Validation
- Gallery Scrolling Benchmark
- Camera Interaction Validation
- Media Sort Ordering
- Photo Capture Zoom Callbacks
- Vault File Naming
- File Transfer State
- Editor Layout Validation
- Camera Repeating Requests
- Activity Session Lifecycle
- Camera Preview Host
- Sticker Display Geometry
- Timeline Drag Modes
- Backup Consumer Interface
- Remote Camera Delivery Evidence
- Gallery Performance History
- Streaming PIN Device Tests
- Ultrawide Lens Selection Tests
- Thumbnail Sampling Tests
- Playback Size Listeners
- Media Insertion Interface
- Bluetooth Server Callbacks
- Photo Editor Tabs
- Sticker Resize Grips
- Cipher Framing Tests
- Prohibition Mosaic Sticker
- Backup Screen Modes
- Gallery Media Filters
- Playback State Listeners
- Remote Viewer Controls
- Orange Embroidery Sticker
- Pink Sewing Button Sticker
- Grayscale Grid Sticker
- Circular Mosaic Sticker
- Censored Mosaic Sticker
- Age Restriction Mosaic Sticker
- Heart Mosaic Sticker
- Question Mark Mosaic Sticker
- Rectangular Mosaic Sticker
- Editor Panel Modes
- Project README Stub

## God Nodes (most connected - your core abstractions)
1. `MediaItem` - 95 edges
2. `VaultCryptoEngine` - 68 edges
3. `VideoProject` - 47 edges

## Surprising Connections (you probably didn't know these)
- `1.1.1 optimized streaming release` --semantically_similar_to--> `Normal-camera Wi-Fi streaming`  [INFERRED] [semantically similar]
  CHANGELOG.md → SPEC-camera-stream-test.md
- `Optimized timing and regression limits` --semantically_similar_to--> `Outstanding stream acceptance evidence`  [INFERRED] [semantically similar]
  tasks/gallery-scroll-performance.md → tasks/camera-stream-validation.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Editors preserve originals and retain only an in-memory editing session** — spec_photo_editor_photo_workflow, spec_photo_editor_lock_session, spec_video_editor_plus_project, spec_video_editor_plus_session_recovery [INFERRED 0.85]
- **Recorded checks distinguish completed evidence from untested acceptance** — tasks_todo_partial_backlog, tasks_camera_stream_validation_outstanding_acceptance, tasks_gallery_scroll_performance_measurement_limit, tasks_video_editor_test_status_pending_features [INFERRED 0.85]
- **Generated fixtures support private media verification** — tasks_gallery_improvements_gallery_evidence, tasks_gallery_scroll_performance_benchmark, tasks_pr2_pr3_validation_transfer_device_evidence, tasks_video_editor_layout_ui_gate_privacy_evidence [INFERRED 0.85]
- **Archive integrity, bounded parsing and staging precede publication** — svbackup_export_spec_and_interoperability_tag_chain, svbackup_export_spec_and_interoperability_summary_eof, svbackup_export_spec_and_interoperability_safe_restore, svbackup_kotlin_importer_handoff_bounded_parser, svbackup_kotlin_importer_handoff_staged_publication [INFERRED 0.95]
- **Hardware and durable-save acknowledgments gate visible camera state** — tasks_camera_interactions_hardware_zoom, tasks_remote_camera_controls_advertised_selection, tasks_remote_camera_controls_recording_save, tasks_stream_pairing_protocol_command_slot [INFERRED 0.95]

## Communities (157 total, 71 thin omitted)

### Community 0 - "Stream Protocol Validation"
Cohesion: 0.05
Nodes (31): interactionAllowed(), hex(), StreamCameraOption, StreamCameraRequest, StreamCameraResult, StreamCameraState, StreamConfig, StreamFrame (+23 more)

### Community 3 - "Vault Database Access"
Cohesion: 0.06
Nodes (5): AlbumDao, AlbumWithMediaCount, MediaDao, MediaEntity, VaultDatabase

### Community 4 - "Encrypted Gallery Device Tests"
Cohesion: 0.07
Nodes (12): GalleryDeviceTest, Factory, Fetcher, AlbumRepository, MediaRepository, Factory, Fetcher, MediaRepository (+4 more)

### Community 5 - "Gallery Viewer State"
Cohesion: 0.05
Nodes (6): MoveMediaDialog(), ShareProgressDialog(), InfoRow(), MediaInfoDialog(), ZoomablePhotoView(), MediaViewerScreen()

### Community 6 - "Application Key Management"
Cohesion: 0.06
Nodes (6): DatabaseKeyManagerTest, KeyStoreManager, DatabaseKeyManager, SecretVaultApp, BroadcastReceiver, KeyStoreManagerTest

### Community 7 - "Generated Device Fixtures"
Cohesion: 0.08
Nodes (5): BackupRestoreDeviceTest, ContextWrapper, VideoMaskDeviceTest, AlbumEntity, SecretVaultTheme()

### Community 8 - "Feature Specifications Overview"
Cohesion: 0.05
Nodes (12): Changelog, SecretVault current features snapshot, Chunked AES-256-GCM media with wrapped per-file keys, Discoverable SV camera streaming specification, Certificate-bound J-PAKE streaming authentication, Direct SV file sharing specification, SVF1 transfer protocol, SVBACK01 export architecture and interoperability specification (+4 more)

### Community 9 - "Video Edit Plans"
Cohesion: 0.08
Nodes (12): VideoEditorDeviceTest, VideoEditMode, REMOVE_SECTION, TRIM, VideoEditPlan, VideoSegment, loadTimelineFrames(), SavingDialog() (+4 more)

### Community 11 - "Vault Navigation Weather"
Cohesion: 0.06
Nodes (19): AuthScreen(), findFragmentActivity(), BiometricAuthHelper, AuthenticationCallback, WeatherHeader(), HourlyItem(), WeatherHourlyStrip(), MetricCard() (+11 more)

### Community 12 - "Stream Camera Rendering"
Cohesion: 0.07
Nodes (8): StreamCameraDeviceTest, StreamRendererDeviceTest, StreamDecoder, onError(), onInputBufferAvailable(), onOutputBufferAvailable(), onOutputFormatChanged(), StreamEncoder

### Community 13 - "Stream Session Verification"
Cohesion: 0.08
Nodes (5): StreamCodecDeviceTest, send(), wifi(), NetworkCallback, StreamPairingTest

### Community 14 - "Transfer Wire Protocol"
Cohesion: 0.09
Nodes (17): exactly(), FilterInputStream, ItemHeader, Offer, parse(), readEnd(), readFlag(), readHash() (+9 more)

### Community 16 - "OpenGL Preview Rendering"
Cohesion: 0.12
Nodes (3): Input, NativeInput, StreamPreviewRenderer

### Community 17 - "TLS Pairing Authentication"
Cohesion: 0.08
Nodes (7): PairingPurpose, SHARE, STREAM, StreamPairing, Host, StreamTls, X509TrustManager

### Community 18 - "Authentication State Tests"
Cohesion: 0.09
Nodes (12): AuthEvent, AuthMode, LOCKED_OUT, SETUP_CONFIRM_PIN, SETUP_ENTER_PIN, UNLOCK_PIN, AuthUiState, AuthViewModel (+4 more)

### Community 19 - "Preview Orientation Geometry"
Cohesion: 0.08
Nodes (8): StreamPreviewDeviceTest, StreamSharedCameraDeviceTest, SurfaceTextureListener, CameraStreamScreen(), SurfaceTextureListener, fitPreview(), streamPreviewTransform(), SurfaceTextureListener

### Community 22 - "Video Effect Export"
Cohesion: 0.08
Nodes (3): FramingTransformation, StickerOverlay, Listener

### Community 23 - "Media Viewer State"
Cohesion: 0.09
Nodes (3): MediaViewerUiState, MediaViewerViewModel, MediaViewerViewModelTest

### Community 25 - "Photo Image Rendering"
Cohesion: 0.17
Nodes (15): PhotoRenderDeviceTest, colourAndGrain(), drawPaintStrokes(), drawStrokes(), frameSize(), gpuPhotoPreview(), grained(), hidingCopy() (+7 more)

### Community 26 - "Media Action Controls"
Cohesion: 0.11
Nodes (7): KeypadActionButton(), KeypadNumberButton(), PinKeypad(), ActionButton(), SelectionBottomBar(), MediaViewerBottomBar(), ViewerActionItem()

### Community 27 - "Backup Manifest Interoperability"
Cohesion: 0.15
Nodes (7): SvBackupAlbum, SvBackupManifest, SvBackupWriter, CrossCompatibilityTest, SvBackupWriterTest, SvBackupConsumer, SvBackupConsumer

### Community 28 - "Video Export State"
Cohesion: 0.11
Nodes (9): VideoProjectHistory, Done, Failed, Running, Stage, CUTTING, ENCRYPTING, VideoEditManager (+1 more)

### Community 30 - "Camera Gallery Toolbars"
Cohesion: 0.11
Nodes (3): GalleryTopBar(), SortMenuDropdown(), SortMenuItem()

### Community 31 - "Photo Editor Panels"
Cohesion: 0.14
Nodes (19): ChipIcon(), editorChipColors(), BrushMode, BLUR, MOSAIC, PAINT, BrushSettings, BrushSizeCircle() (+11 more)

### Community 32 - "Camera Frame Rate Capabilities"
Cohesion: 0.12
Nodes (5): CameraLensOption, supportsVideoFrameRate(), CameraViewModel, CameraMicrophoneTest, VideoFrameRateTest

### Community 33 - "Media Repository Model"
Cohesion: 0.12
Nodes (3): MediaItem, MediaRepository, VaultMediaRepository

### Community 34 - "Encrypted Image Preview"
Cohesion: 0.13
Nodes (10): decodeEncryptedImage(), MediaPreview, plainStream(), InputStream, readEncryptedBytes(), writeEncryptedPreview(), writeEncryptedThumbnail(), applyExifOrientation() (+2 more)

### Community 35 - "Backup Export Pipeline"
Cohesion: 0.10
Nodes (10): BackupExportManager, BackupExportProgress, BackupExportResult, BackupScope, ChunkedDecryptedCollector, FullVault, SelectedAlbums, SelectedMedia (+2 more)

### Community 36 - "Backup Record Cryptography"
Cohesion: 0.19
Nodes (3): SvBackupCrypto, SvBackupMediaProvider, SvBackupCryptoTest

### Community 38 - "Camera Capture Modes"
Cohesion: 0.10
Nodes (15): CameraMode, PHOTO, VIDEO, FlashMode, AUTO, OFF, ON, VideoOrientation (+7 more)

### Community 40 - "Backup Item Callbacks"
Cohesion: 0.11
Nodes (6): SvBackupItem, SvBackupSummary, SvBackupConsumer, SvBackupConsumer, SvBackupConsumer, SvBackupConsumer

### Community 41 - "Media Ingestion Files"
Cohesion: 0.15
Nodes (8): SecureMemory, createGalleryThumbnail(), encryptedGalleryThumbnail(), galleryFrame(), MediaType, PHOTO, VIDEO, ExifSanitizer

### Community 42 - "Gallery Zoom Geometry"
Cohesion: 0.16
Nodes (6): boundedMediaPan(), fittedMediaSize(), mediaZoomGestures(), MediaZoomState, rememberMediaZoomState(), MediaZoomStateTest

### Community 43 - "Authenticated Cipher Framing"
Cohesion: 0.19
Nodes (4): ChunkedCipherStream, InputStream, StreamFooter, StreamHeader

### Community 44 - "Vault PIN Settings"
Cohesion: 0.13
Nodes (4): PinManager, ChangePinDialog(), PinField(), SettingsScreen()

### Community 45 - "Backup Restore Pipeline"
Cohesion: 0.13
Nodes (6): BackupImportManager, OutputStream, SvBackupConsumer, BackupImportProgress, BackupImportResult, StagedMediaEntry

### Community 46 - "Image Colour Adjustments"
Cohesion: 0.13
Nodes (13): Adjustment, BRIGHTNESS, CONTRAST, EXPOSURE, FADE, HIGHLIGHTS, SATURATION, SHADOWS (+5 more)

### Community 47 - "Project Timeline Unit Tests"
Cohesion: 0.16
Nodes (3): Framing, snapAngle(), VideoProjectTest

### Community 49 - "Album Repository Model"
Cohesion: 0.11
Nodes (3): Album, AlbumRepository, VaultAlbumRepository

### Community 50 - "Sticker Project Data"
Cohesion: 0.18
Nodes (11): Blur, BlurStyle, BLUR, PIXELATE, Emoji, Keyframe, Pack, Photo (+3 more)

### Community 55 - "Backup Restore Controls"
Cohesion: 0.11
Nodes (7): BackupActionRow(), BackupRestoreScreen(), SelectAlbumsBackupSheet(), SelectMediaBackupSheet(), BackupPasswordDialog(), BackupProgressDialog(), ExportBackupDialog()

### Community 57 - "Device Camera Selection"
Cohesion: 0.13
Nodes (4): LensFacing, BACK, FRONT, CaptureCallback

### Community 58 - "Video Blur Shader"
Cohesion: 0.14
Nodes (3): BlurUniforms, BlurRegionsEffect, Program

### Community 60 - "Backup Archive Reading"
Cohesion: 0.21
Nodes (3): SvBackupConsumer, SvBackupReader, SvBackupReaderTest

### Community 61 - "Device Media Import"
Cohesion: 0.16
Nodes (4): BatchImportManager, ImportProgress, ImportResult, ImportProgressDialog()

### Community 64 - "Navigation Screen Routes"
Cohesion: 0.11
Nodes (14): Auth, Camera, CameraStream, DecoyWeather, MediaViewer, PhotoEditor, ReceiveFiles, Screen (+6 more)

### Community 69 - "Bluetooth Stream Discovery"
Cohesion: 0.20
Nodes (4): StreamBleDeviceTest, StreamBleDiscovery, AdvertiseCallback, StreamViewerScreen()

### Community 70 - "Camera Interaction Geometry"
Cohesion: 0.19
Nodes (5): pinchedCameraZoom(), sensorPointInFrame(), streamFocusPoint(), cameraInteractionGestures(), CameraInteractionMathTest

### Community 71 - "Face Tracking Models"
Cohesion: 0.33
Nodes (5): FaceBox, FaceSample, FaceTrack, faceTracks(), FaceTracksTest

### Community 72 - "Project Editor Controls"
Cohesion: 0.28
Nodes (11): formatTime(), AdjustPanel(), FoundFacesDialog(), LabeledSlider(), PanelButton(), SizePanel(), StickerPicker(), Tool() (+3 more)

### Community 74 - "Photo Colour Filters"
Cohesion: 0.13
Nodes (10): PhotoFilter, COOL, DRAMATIC, FADE, MONO, NOIR, SEPIA, VINTAGE (+2 more)

### Community 76 - "Gallery Album Components"
Cohesion: 0.16
Nodes (5): AlbumsTab(), CreateAlbumDialog(), MediaGridItem(), ThumbnailRefreshEffect(), GalleryScreen()

### Community 81 - "Streaming Validation History"
Cohesion: 0.18
Nodes (12): Compact viewer portrait validation, Camera stream validation chronology, Five-minute CMF-to-Pixel stream evidence, Outstanding stream acceptance evidence, Capture-free prototype capability evidence, Original live failure remains unconfirmed, Revised R1–R14 streaming plan, Discoverable streaming task backlog (+4 more)

### Community 82 - "Transfer Gallery Validation"
Cohesion: 0.14
Nodes (10): Gallery improvements, Persisted Favorites collection, 107 tests and CMF gallery device checks, Gallery improvements scoped UI gate, Gallery UI certification limits, Gallery gesture, Favorites and thumbnail gate, Recording pause and direct file sharing validation, PR2 paused-state wire compatibility (+2 more)

### Community 86 - "Sticker Bitmap Loading"
Cohesion: 0.31
Nodes (5): emojiBitmap(), gifBytes(), movie(), stickerBitmap(), StickerImage

### Community 87 - "Timeline Sticker Gestures"
Cohesion: 0.17
Nodes (6): drawBox(), snapAngle90(), StickerHandles(), stickerLanes(), Timeline(), TimelineState

### Community 88 - "Video Face Detection"
Cohesion: 0.28
Nodes (6): decodeSamples(), faceCrop(), FaceScanResult, FoundFace, scanFaces(), smallUpright()

### Community 89 - "Editor Effect Preview"
Cohesion: 0.19
Nodes (3): BlurPreview, lookEffect(), PhotoLook

### Community 91 - "Recording Quality Defaults"
Cohesion: 0.21
Nodes (7): defaultVideoMode(), isCmfPhone1(), VideoMode, FHD_30, FHD_60, UHD_30, UHD_60

### Community 95 - "Streaming Pairing Design"
Cohesion: 0.17
Nodes (8): Protected pairing and dual discovery evidence, Discoverable camera streaming implementation plan, Stream targets and honest measurement, Historical debug invitation prototype, Local camera pairing and control protocol, Certificate-bound four-digit J-PAKE pairing, Optional PIN open stream session binding, Version-gated remote camera control messages

### Community 96 - "Editor Integration Evidence"
Cohesion: 0.17
Nodes (10): PR5 PR6 PR7 integration review, 158 tests and generated CMF integration evidence, Editor integration hardware limits, Photo transform, crop and stroke-order corrections, Preserved extracted timeline and grouped history, Video editor test status 2026-10-06, Editor hardware coverage exclusions, Owner-confirmed CMF editor features (+2 more)

### Community 103 - "File Transfer Screens"
Cohesion: 0.47
Nodes (9): Body(), KeepScreenOn(), PrimaryButton(), Progress(), ReceiveFilesScreen(), SendFilesScreen(), size(), StopWhenHidden() (+1 more)

### Community 109 - "Gallery Slider Validation"
Cohesion: 0.20
Nodes (5): Optimized timing and regression limits, Gallery stability and camera zoom slider, 120 tests and Pixel gallery/slider checks, Gallery slider scoped UI gate, Gallery loading and camera slider gate

### Community 111 - "Camera Interaction Validation"
Cohesion: 0.25
Nodes (7): 117 tests and bidirectional interaction evidence, Camera interactions, Final microphone refinement verification limit, Over-image camera control contrast, Camera UI gate coverage limits, Camera interaction scoped UI gate, Camera gesture and microphone UI gate

### Community 112 - "Media Sort Ordering"
Cohesion: 0.25
Nodes (6): MediaRepository, SortOrder, NAME_A_Z, NAME_Z_A, NEWEST_FIRST, OLDEST_FIRST

### Community 115 - "File Transfer State"
Cohesion: 0.25
Nodes (7): AwaitingAccept, Done, Failed, Idle, Transferring, TransferState, Waiting

### Community 116 - "Editor Layout Validation"
Cohesion: 0.29
Nodes (6): Remote camera scoped delivery gate chronology, Compact viewer portrait screenshot and contrast gate, Video editor layout and history, 143 tests and both-phone generated editor checks, Approved fixed-playhead editing timeline, Approved editor and picker UI delivery gate

### Community 120 - "Sticker Display Geometry"
Cohesion: 0.43
Nodes (5): covers(), Shown, shownAt(), toPicture(), turned()

### Community 121 - "Timeline Drag Modes"
Cohesion: 0.29
Nodes (7): Drag, CLIP_END, CLIP_START, SCRUB, STICKER_END, STICKER_MOVE, STICKER_START

### Community 123 - "Remote Camera Delivery Evidence"
Cohesion: 0.38
Nodes (5): Remote quality, flash and encrypted-save evidence, Reference-based compact viewer layout, Remote camera controls, Highest supported saved quality and remote flash, Camera menu scoped delivery gate

### Community 124 - "Gallery Performance History"
Cohesion: 0.29
Nodes (4): Camera folder scrolling performance, Reverted thumbnail performance experiments, Final semantics refinement and input limits, Video editor scoped delivery gate

### Community 131 - "Photo Editor Tabs"
Cohesion: 0.40
Nodes (5): PhotoTab, ADJUST, CROP, DRAW, FILTERS

### Community 132 - "Sticker Resize Grips"
Cohesion: 0.40
Nodes (5): Grip, CORNER, EDGE, ROTATE, SIDE

### Community 135 - "Prohibition Mosaic Sticker"
Cohesion: 0.50
Nodes (4): Grayscale square mosaic, Vivid green exterior background, Pixel mosaic with prohibition symbol, Red-orange prohibition ring and slash

### Community 136 - "Backup Screen Modes"
Cohesion: 0.50
Nodes (4): BackupSubScreen, MAIN, SELECT_ALBUMS, SELECT_MEDIA

### Community 137 - "Gallery Media Filters"
Cohesion: 0.50
Nodes (4): MediaFilterType, ALL, PHOTOS, VIDEOS

### Community 141 - "Orange Embroidery Sticker"
Cohesion: 0.67
Nodes (3): Embroidery style thread texture, Orange slice sticker image, Orange cross section with white pith and seeds

### Community 142 - "Pink Sewing Button Sticker"
Cohesion: 0.67
Nodes (3): Cream thread crossed through four button holes, Glossy pink four-hole sewing button, Darker pink polka-dot pattern

### Community 143 - "Grayscale Grid Sticker"
Cohesion: 0.67
Nodes (3): Fine line texture, Grayscale block mosaic, Grayscale pixel grid GIF

### Community 144 - "Circular Mosaic Sticker"
Cohesion: 0.67
Nodes (3): Circular boundary and green surround, Grayscale block mosaic, Grid pixel 02 sticker image

### Community 145 - "Censored Mosaic Sticker"
Cohesion: 0.67
Nodes (3): Diagonal CENSORED brushstroke banner, Grayscale pixel mosaic, CENSORED pixel mosaic sticker

### Community 146 - "Age Restriction Mosaic Sticker"
Cohesion: 0.67
Nodes (3): 18+ symbol centered on a pale rounded square, Pixel grid sticker (first-frame-only inspection), Monochrome block grid with fine glitch-like line texture

### Community 147 - "Heart Mosaic Sticker"
Cohesion: 0.67
Nodes (3): Grayscale rectangular mosaic, Heart silhouette, Grayscale pixel mosaic heart sticker

### Community 148 - "Question Mark Mosaic Sticker"
Cohesion: 0.67
Nodes (3): Grayscale question mark sticker, Circular grayscale pixel mosaic, White question mark

### Community 149 - "Rectangular Mosaic Sticker"
Cohesion: 0.67
Nodes (3): Grayscale rectangular pixel grid, Black white and gray palette, Irregular rectangular mosaic

### Community 150 - "Editor Panel Modes"
Cohesion: 0.67
Nodes (3): Panel, ADJUST, SIZE

## Knowledge Gaps
- **131 isolated node(s):** `FullVault`, `SelectedAlbums`, `SelectedMedia`, `PHOTO`, `VIDEO` (+126 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 617 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **71 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **How does `MediaItem` connect gallery, editing, backup and file transfer?**
  _High betweenness centrality (0.077) - this node is a cross-community bridge._
- **Are the inferred links between `MediaItem` and gallery device tests correct?**
  _`MediaItem` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Where do `FullVault`, `SelectedAlbums` and `SelectedMedia` enter backup export?**
  _131 weakly-connected nodes found - possible documentation gaps or missing edges._

## Coverage and integrity limits

- Generated locally from the working tree on 2026-10-16, including uncommitted backup deduplication and Home menu fixes. This is a navigation aid, not a new correctness or security audit.
- Kotlin extraction was partial for GalleryThumbnailManager.kt, FaceTracks.kt, CameraStreamEncoder.kt, StreamSession.kt and TransferProtocol.kt. These are parser limitations, not compiler failures.
- The detector did not model 16 resource/build/binary files, including AndroidManifest.xml, XML resources, ProGuard rules and the version catalog.
- The test video was not transcribed: optional faster-whisper support is unavailable. GIF assets were inspected at their first frame only.
- Graph health warning: 2,754 edges reference endpoints absent from the extraction node list; 76 self-loop edges; 246 same-endpoint edges collapse in this undirected graph. Some references are unresolved imports. Inspect source before relying on a path as a runtime call chain. See graph-health.json for exact diagnostics.
- External API token cost: none. Host-agent token usage is unmetered here; numeric zero placeholders in cache/cost data do not mean zero session usage.
- graphify-out/ was written into the Git working tree and is currently untracked. Ignore it or commit only intended shared outputs.

## Estimated context benchmark

The CLI benchmark estimates 153,950 corpus words (~205,266 naive tokens), ~1,655 tokens per graph query, and 124.0x context reduction. These are static context-size estimates, not measured host-agent session usage or a runtime accuracy guarantee.
