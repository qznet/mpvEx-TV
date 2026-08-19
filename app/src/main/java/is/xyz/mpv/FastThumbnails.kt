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
 * Fast thumbnail generation using mpv's screenshot API (native) with fallback to
 * MediaMetadataRetriever.
 *
 * Usage: FastThumbnails.generateAsync(context, uri, dimension)
 */
object FastThumbnails {
    private const val TAG = "FastThumbnails"

    /**
     * Suspending version of generate.
     * First tries the native mpv screenshot API; falls back to MediaMetadataRetriever.
     * @param context Android context
     * @param uri URI of the media file (local file:// or content://)
     * @param dimension Target thumbnail dimension (width and height, the thumbnail will be a square of this size)
     * @return Bitmap or null if generation failed
     */
    suspend fun generateAsync(
        context: Context,
        uri: Uri,
        dimension: Int = 256
    ): Bitmap? = withContext(Dispatchers.IO) {
        // Try native mpv screenshot first
        val nativeBmp = try {
            MPVLib.grabThumbnail(dimension)
        } catch (e: Exception) {
            null
        }

        if (nativeBmp != null) {
            return@withContext nativeBmp
        }

        // Fallback to MediaMetadataRetriever (only works for local files)
        generateWithMediaStore(context, uri, dimension)
    }

    /**
     * Generate thumbnail using MediaMetadataRetriever.
     * Only works for local content:// or file:// URIs.
     */
    private fun generateWithMediaStore(
        context: Context,
        uri: Uri,
        dimension: Int
    ): Bitmap? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            // Try to extract a frame at 10% of the duration (usually a meaningful frame)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val seekToMs = (durationMs * 0.1).toLong()

            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    seekToMs * 1000, // microseconds
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    dimension,
                    dimension
                )
            } else {
                @Suppress("DEPRECATION")
                retriever.getFrameAtTime(seekToMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }

            retriever.release()
            frame
        } catch (e: Exception) {
            android.util.Log.w(TAG, "MediaMetadataRetriever failed for $uri", e)
            null
        }
    }
}
