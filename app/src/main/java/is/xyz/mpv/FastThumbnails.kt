package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fast thumbnail generation for the media library grid.
 *
 * qznet's ThumbnailRepository calls:
 *   FastThumbnails.initialize(context)
 *   FastThumbnails.generateAsync(path, positionSec, dimension, useHwDec = false)
 *
 * We extract a frame at [positionSec] using MediaMetadataRetriever (works for local
 * files and http URLs). The mpv-native path is not available outside an active playback
 * session, so it is intentionally not used here; on failure ThumbnailRepository falls
 * back to MediaStore.
 */
object FastThumbnails {
    private const val TAG = "FastThumbnails"
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun generateAsync(
        path: String,
        positionSec: Double,
        dimension: Int,
        useHwDec: Boolean = false,
    ): Bitmap? = withContext(Dispatchers.IO) {
        @Suppress("UNUSED_PARAMETER")
        val _hw = useHwDec
        try {
            val retriever = MediaMetadataRetriever()
            when {
                path.startsWith("content://") -> {
                    val ctx = appContext
                    if (ctx != null) {
                        retriever.setDataSource(ctx, Uri.parse(path))
                    } else {
                        retriever.setDataSource(path)
                    }
                }
                path.startsWith("file://") -> retriever.setDataSource(path)
                else -> retriever.setDataSource(File(path).absolutePath)
            }

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val seekUs = if (positionSec > 0) {
                (positionSec * 1000.0 * 1000.0).toLong()
            } else {
                (durationMs * 0.1 * 1000.0).toLong()
            }

            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    seekUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    dimension,
                    dimension,
                )
            } else {
                @Suppress("DEPRECATION")
                retriever.getFrameAtTime(seekUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            retriever.release()
            frame
        } catch (e: Exception) {
            android.util.Log.w(TAG, "generateAsync failed for $path", e)
            null
        }
    }
}
