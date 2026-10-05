package com.secretvault.app.core.worker

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.image.key
import com.secretvault.app.core.image.stickerBitmap
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.player.DecryptingMediaDataSource
import com.secretvault.app.core.player.EncryptedMediaDataSource
import com.secretvault.app.core.processing.Clip
import com.secretvault.app.core.processing.Framing
import com.secretvault.app.core.processing.MuteAudioProcessor
import com.secretvault.app.core.processing.Placement
import com.secretvault.app.core.processing.Sticker
import com.secretvault.app.core.processing.VideoEditPlan
import com.secretvault.app.core.processing.VideoProject
import com.secretvault.app.core.processing.VideoSegment
import com.secretvault.app.data.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface VideoEditState {
    /** [progress] is 0..1, or null while it can't be measured yet. */
    data class Running(val stage: Stage, val progress: Float?) : VideoEditState
    data class Done(val item: MediaItem) : VideoEditState
    data class Failed(val message: String) : VideoEditState

    enum class Stage { CUTTING, ENCRYPTING }
}

/**
 * Cuts vault videos into a new vault item. Runs in an app-wide scope so a save keeps going after
 * the vault locks and the editor screen is gone; the plaintext temp file lives in app-private
 * cache only until encryption finishes.
 */
@OptIn(UnstableApi::class)
class VideoEditManager(
    private val context: Context,
    private val cryptoEngine: VaultCryptoEngine,
    private val mediaRepository: MediaRepository,
    private val mediaSaveQueue: MediaSaveQueue
) {
    // Transformer must be driven from a Looper thread; Main is the simplest one we have.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _state = MutableStateFlow<VideoEditState?>(null)
    val state: StateFlow<VideoEditState?> = _state.asStateFlow()

    // ponytail: wake lock only, same as MediaSaveQueue; Android can still freeze a backgrounded
    // process on long edits. A foreground service would fix that but needs a visible notification,
    // which would reveal the vault behind the decoy.
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
        ?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SecretVault:VideoEdit")
        ?.apply { setReferenceCounted(false) }

    init {
        // A killed process can leave a plaintext cut behind; never keep it.
        context.cacheDir.listFiles { _, name -> name.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }
    }

    /** Single-video edit: keeps [segments] of [item] (the per-video Edit button). */
    fun start(item: MediaItem, segments: List<VideoSegment>) {
        if (segments.isEmpty()) return
        run(segments.map { Clip(item, it.endMs, it.startMs, it.endMs) }, emptyList(), fitToFirst = false, item.sizeBytes * 2, item.albumId) { names ->
            VideoEditPlan.editedFileName(item.filename, names)
        }
    }

    /**
     * The multi-clip editor's unsaved project and playhead, so a vault lock doesn't lose the edit. Memory only: it
     * holds vault item references, never decrypted content, and is gone with the process.
     */
    var draft: VideoProject? = null
        private set
    var draftPositionMs = 0L
        private set

    fun keepDraft(project: VideoProject, positionMs: Long) {
        draft = project.takeIf { it.clips.isNotEmpty() }
        draftPositionMs = positionMs
    }

    fun clearDraft() = keepDraft(VideoProject(), 0L)

    /** Multi-clip editor: joins the project's clips into one new video in the first clip's album. */
    fun start(project: VideoProject) {
        val clips = project.clips.ifEmpty { return }
        // Clips can share a source, so count each source once.
        val sourceBytes = clips.distinctBy { it.media.id }.sumOf { it.media.sizeBytes }
        // Saved, so there's nothing left to come back to, even if the vault locked during the save.
        run(clips, project.stickers, fitToFirst = true, sourceBytes * 2, clips.first().media.albumId, onSaved = ::clearDraft) {
            "Edit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        }
    }

    private fun run(
        clips: List<Clip>, stickers: List<Sticker>, fitToFirst: Boolean, neededBytes: Long, albumId: String,
        onSaved: () -> Unit = {}, name: (Set<String>) -> String
    ) {
        if (job?.isActive == true) return
        // Room for the plaintext cut plus its encrypted copy; a re-encode can outgrow the source a little.
        if (context.cacheDir.usableSpace < neededBytes) {
            _state.value = VideoEditState.Failed("Not enough storage to save the edited video.")
            return
        }
        _state.value = VideoEditState.Running(VideoEditState.Stage.CUTTING, null)
        job = scope.launch {
            val temp = File(context.cacheDir, "$TEMP_PREFIX${System.currentTimeMillis()}.mp4")
            try {
                wakeLock?.acquire(30 * 60 * 1000L)
                val result = export(clips, stickers, fitToFirst, temp)
                _state.value = VideoEditState.Running(VideoEditState.Stage.ENCRYPTING, 0f)
                val saved = mediaSaveQueue.saveVideo(
                    tempFile = temp,
                    durationMs = result.durationMs.takeIf { it > 0 } ?: clips.sumOf { it.durationMs },
                    albumId = albumId,
                    name = name(mediaRepository.getMedia().first().mapTo(HashSet()) { it.filename })
                ) { progress -> _state.value = VideoEditState.Running(VideoEditState.Stage.ENCRYPTING, progress) }
                onSaved()
                _state.value = VideoEditState.Done(saved)
            } catch (e: CancellationException) {
                _state.value = null
                throw e
            } catch (e: ExportException) {
                _state.value = VideoEditState.Failed("This phone couldn't edit this video (${e.errorCodeName}).")
            } catch (e: Exception) {
                e.printStackTrace()
                _state.value = VideoEditState.Failed("Couldn't save the edited video.")
            } finally {
                temp.delete()
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    /** Only meaningful while cutting; once encryption starts the new item is committed. */
    fun cancel() {
        val running = _state.value as? VideoEditState.Running ?: return
        if (running.stage == VideoEditState.Stage.CUTTING) job?.cancel()
    }

    fun clearResult() {
        if (_state.value !is VideoEditState.Running) _state.value = null
    }

    /**
     * [fitToFirst]: every clip is scaled into the first clip's upright frame (black bars), and the output always has
     * an audio track, so clips of different shapes, muted clips and clips without sound can follow each other.
     */
    private suspend fun export(clips: List<Clip>, stickers: List<Sticker>, fitToFirst: Boolean, output: File): ExportResult {
        val frame = if (fitToFirst) withContext(Dispatchers.IO) { uprightSize(clips.first().media) } else null
        val items = clips.map { clip ->
            EditedMediaItem.Builder(
                androidx.media3.common.MediaItem.Builder()
                    .setUri(Uri.fromFile(File(clip.media.encryptedPath)))
                    .setClippingConfiguration(
                        androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.startMs)
                            .setEndPositionMs(clip.endMs)
                            .build()
                    )
                    .build()
            ).apply {
                if (frame != null || clip.muted) setEffects(Effects(
                    if (clip.muted) listOf(MuteAudioProcessor(listOf(VideoSegment(0L, Long.MAX_VALUE / 2_000)))) else emptyList(),
                    frame?.let { (width, height) ->
                        listOfNotNull(
                            Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT),
                            // Colour after fitting and before framing, like the preview: its shader covers the fitted
                            // picture, bars included, and framing then moves the coloured result.
                            clip.adjustments.takeUnless { it.isNone }?.let { SingleColorLut.createFromCube(it.lutCube()) },
                            clip.framing.takeIf { it != Framing() }?.let { FramingTransformation(it, width, height) })
                    }
                        ?: emptyList()
                ))
            }.build()
        }

        val done = CompletableDeferred<ExportResult>()
        val transformer = Transformer.Builder(context)
            .setAssetLoaderFactory(
                DefaultAssetLoaderFactory(
                    context,
                    DefaultDecoderFactory.Builder(context).build(),
                    Clock.DEFAULT,
                    DefaultMediaSourceFactory(EncryptedMediaDataSource.Factory(cryptoEngine)),
                    DataSourceBitmapLoader(context)
                )
            )
            // Media3 1.5.1's trim optimization probes the URI outside our decrypting asset loader.
            .experimentalSetTrimOptimizationEnabled(false)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(exportResult)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()

        // Stickers go on the joined video, after every clip has been fitted into the frame.
        val overlays = if (frame == null) emptyList() else withContext(Dispatchers.IO) {
            val bitmaps = stickers.map { it.source.key }.distinct().associateWith { key ->
                stickerBitmap(cryptoEngine, stickers.first { it.source.key == key }.source)
            }
            stickers.mapNotNull { sticker -> bitmaps[sticker.source.key]?.let { StickerOverlay(it, sticker, frame.first) } }
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items))
            .apply { if (fitToFirst) experimentalSetForceAudioTrack(true) }
            .apply { if (overlays.isNotEmpty()) setEffects(Effects(emptyList(), listOf(OverlayEffect(overlays)))) }
            .build()
        transformer.start(composition, output.absolutePath)
        val progress = ProgressHolder()
        try {
            while (true) {
                withTimeoutOrNull(200L) { done.await() }?.let { return it }
                if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _state.value = VideoEditState.Running(VideoEditState.Stage.CUTTING, progress.progress / 100f)
                }
            }
        } finally {
            if (!done.isCompleted) transformer.cancel()
        }
    }

    /** The video's width × height as shown (rotation applied), rounded to even numbers for the encoder. */
    fun uprightSize(media: MediaItem): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            DecryptingMediaDataSource(cryptoEngine, File(media.encryptedPath)).use { source ->
                retriever.setDataSource(source)
                fun meta(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
                val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val turned = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0
                val (w, h) = if (turned) height to width else width to height
                if (w <= 0 || h <= 0) FALLBACK_FRAME else (w / 2 * 2) to (h / 2 * 2)
            }
        } catch (e: Exception) {
            FALLBACK_FRAME
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val TEMP_PREFIX = "video_edit_"
        val FALLBACK_FRAME = 1080 to 1920
    }
}

/**
 * One sticker on the exported video. Hidden (alpha 0) outside its time range. Times are counted from the first
 * frame this overlay sees, so they're the output timeline whatever offset the frames' timestamps start at.
 */
@OptIn(UnstableApi::class)
private class StickerOverlay(private val bitmap: Bitmap, private val sticker: Sticker, private val frameWidth: Int) : BitmapOverlay() {
    private var firstUs = C.TIME_UNSET
    private val hidden = OverlaySettings.Builder().setAlphaScale(0f).build()
    private var last: Pair<Placement, OverlaySettings>? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        if (firstUs == C.TIME_UNSET) firstUs = presentationTimeUs
        val ms = (presentationTimeUs - firstUs) / 1_000
        if (ms < sticker.startMs || ms >= sticker.endMs) return hidden
        val placement = sticker.placementAt(ms)
        last?.let { (p, settings) -> if (p == placement) return settings }
        val scale = placement.widthFraction * frameWidth / bitmap.width
        return OverlaySettings.Builder()
            // The overlay starts at its own pixel size; scale it to its share of the frame's width.
            .setScale(scale, scale)
            // Anchors are -1..1 with y pointing up; the model's fractions run from the top-left.
            .setBackgroundFrameAnchor(placement.centerX * 2 - 1, 1 - placement.centerY * 2)
            // Media3 turns counter-clockwise; the model turns clockwise.
            .setRotationDegrees(-placement.angle)
            .build()
            .also { last = placement to it }
    }
}

/** Turns, zooms and moves one clip inside the output frame; whatever it uncovers is black. Keeps the frame size. */
@OptIn(UnstableApi::class)
private class FramingTransformation(framing: Framing, width: Int, height: Int) : MatrixTransformation {
    private val matrix = android.graphics.Matrix().apply {
        // The matrix works in -1..1 on both axes with y up, so turn in a space stretched to the frame's real shape.
        val aspect = width.toFloat() / height
        postScale(aspect, 1f)
        postRotate(-framing.angle)
        postScale(framing.zoom / aspect, framing.zoom)
        postTranslate(framing.offsetX * 2, -framing.offsetY * 2)
    }

    override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix = matrix
}
