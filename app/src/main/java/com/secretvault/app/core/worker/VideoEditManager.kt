package com.secretvault.app.core.worker

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.player.EncryptedMediaDataSource
import com.secretvault.app.core.processing.VideoEditPlan
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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

    fun start(item: MediaItem, segments: List<VideoSegment>) {
        if (job?.isActive == true || segments.isEmpty()) return
        // Room for the plaintext cut plus its encrypted copy; a re-encode can outgrow the source a little.
        if (context.cacheDir.usableSpace < item.sizeBytes * 2) {
            _state.value = VideoEditState.Failed("Not enough storage to save the edited video.")
            return
        }
        _state.value = VideoEditState.Running(VideoEditState.Stage.CUTTING, null)
        job = scope.launch {
            val temp = File(context.cacheDir, "$TEMP_PREFIX${System.currentTimeMillis()}.mp4")
            try {
                wakeLock?.acquire(30 * 60 * 1000L)
                val result = export(item, segments, temp)
                _state.value = VideoEditState.Running(VideoEditState.Stage.ENCRYPTING, 0f)
                val saved = mediaSaveQueue.saveVideo(
                    tempFile = temp,
                    durationMs = result.durationMs.takeIf { it > 0 } ?: segments.sumOf { it.durationMs },
                    albumId = item.albumId,
                    name = VideoEditPlan.editedFileName(
                        item.filename,
                        mediaRepository.getMedia().first().mapTo(HashSet()) { it.filename }
                    )
                ) { progress -> _state.value = VideoEditState.Running(VideoEditState.Stage.ENCRYPTING, progress) }
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

    private suspend fun export(item: MediaItem, segments: List<VideoSegment>, output: File): ExportResult {
        val uri = Uri.fromFile(File(item.encryptedPath))
        val clips = segments.map { segment ->
            EditedMediaItem.Builder(
                androidx.media3.common.MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(segment.startMs)
                            .setEndPositionMs(segment.endMs)
                            .build()
                    )
                    .build()
            ).build()
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
            // Re-encodes only the frames around the cut instead of the whole clip. Media3 supports
            // it for a single clip, so removing a middle section re-encodes the full video.
            .experimentalSetTrimOptimizationEnabled(clips.size == 1)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(exportResult)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()

        transformer.start(Composition.Builder(EditedMediaItemSequence(clips)).build(), output.absolutePath)
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

    private companion object {
        const val TEMP_PREFIX = "video_edit_"
    }
}
