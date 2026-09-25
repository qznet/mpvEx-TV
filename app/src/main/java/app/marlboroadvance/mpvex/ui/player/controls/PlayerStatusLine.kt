package app.marlboroadvance.mpvex.ui.player.controls

import android.os.Debug
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.preference.collectAsState
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.SmbStats
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * One compact line of live playback diagnostics, drawn in the top-left corner of the player.
 *
 * Drawn by the app's Compose overlay instead of mpv's OSD because:
 *  - the SMB counters ([SmbStats]) only exist inside the app — mpv's `cache-speed` measures the
 *    loopback hop to the local HTTP proxy, so it cannot show the real share-side throughput;
 *  - the per-item switches, font size and refresh rate are app settings anyway.
 * (mpv's OSD does work on this fork's HW+ path: the app hands mpv a second ANativeWindow via
 * `android-osd-wid` and mpv renders OSD/OSC/subtitles into it. That surface sits *below* the
 * Compose overlay, so a Lua OSD line and this one overlap if both are in the top-left corner —
 * use one or the other.)
 *
 * Two families of numbers are shown and they answer different questions:
 *  - mpv-side state (`cache-*`, drops, fps, speed, progress) — what the player is doing;
 *  - SMB-side throughput ([SmbStats], read at the share itself) — whether bytes are still arriving.
 *    The `ratio` field (SMB rate / source bitrate) is the decisive one: below 1x the cache is
 *    guaranteed to drain, however healthy everything else looks.
 */
@Composable
fun PlayerStatusLine(modifier: Modifier = Modifier) {
  val preferences = koinInject<PlayerPreferences>()
  val enabled by preferences.statusLineEnabled.collectAsState()
  val fontSizeSp by preferences.statusLineFontSizeSp.collectAsState()
  val refreshMs by preferences.statusLineRefreshMs.collectAsState()
  val withBackground by preferences.statusLineBackground.collectAsState()

  var text by remember { mutableStateOf("") }
  var stalled by remember { mutableStateOf(false) }

  LaunchedEffect(enabled, refreshMs) {
    if (!enabled) {
      text = ""
      return@LaunchedEffect
    }
    while (true) {
      val build = withContext(Dispatchers.IO) { buildStatusLine(preferences) }
      text = build.text
      stalled = build.stalled
      delay(refreshMs.coerceIn(MIN_REFRESH_MS, MAX_REFRESH_MS).toLong())
    }
  }

  if (!enabled || text.isEmpty()) return

  Text(
    text = text,
    color = if (stalled) Color(0xFFFFB300) else Color.White,
    fontSize = fontSizeSp.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP).sp,
    // Monospace keeps the columns from jittering as the digits change.
    fontFamily = FontFamily.Monospace,
    maxLines = 1,
    softWrap = false,
    overflow = TextOverflow.Ellipsis,
    modifier =
      modifier
        .background(
          if (withBackground) Color(0x99000000) else Color.Transparent,
          RoundedCornerShape(6.dp),
        )
        .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

/** Refresh bounds; the slider in the settings uses the same range. */
private const val MIN_REFRESH_MS = 250
private const val MAX_REFRESH_MS = 2000
private const val MIN_FONT_SIZE_SP = 8
private const val MAX_FONT_SIZE_SP = 28

/** mpv reports less than this per second while actively reading — treat it as a stall. */
private const val STALL_BYTES_PER_SECOND = 64.0 * 1024.0

private data class StatusLineBuild(val text: String, val stalled: Boolean)

private class MemorySample(val sysUsedBytes: Long, val appPssBytes: Long)

/**
 * Whole-device memory (as the script this replaces did) plus this process' PSS, which is where the
 * mpv demuxer cache actually lives — the system-wide number alone cannot tell whether a cache
 * change helped.
 */
private fun sampleMemory(): MemorySample {
  var sysUsedBytes = 0L
  runCatching {
    var totalKb = -1L
    var availableKb = -1L
    for (line in File("/proc/meminfo").readLines()) {
      when {
        line.startsWith("MemTotal:") -> totalKb = firstNumberKb(line)
        line.startsWith("MemAvailable:") -> availableKb = firstNumberKb(line)
      }
      if (totalKb > 0 && availableKb > 0) break
    }
    if (totalKb > 0 && availableKb > 0) sysUsedBytes = (totalKb - availableKb) * 1024L
  }
  var appPssBytes = 0L
  runCatching {
    val info = Debug.MemoryInfo()
    Debug.getMemoryInfo(info)
    appPssBytes = info.totalPss.toLong() * 1024L
  }
  return MemorySample(sysUsedBytes, appPssBytes)
}

private fun firstNumberKb(line: String): Long =
  line.filter { it.isDigit() }.toLongOrNull() ?: -1L

/**
 * Reads every enabled field. Runs off the main thread; mpv property reads are thread-safe and the
 * JNI layer attaches the calling thread itself.
 */
private fun buildStatusLine(preferences: PlayerPreferences): StatusLineBuild {
  val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
  // Nothing loaded yet: an empty line is rendered as nothing at all, so the corner stays clean
  // between files and on the library screens.
  if (duration <= 0.0) return StatusLineBuild("", false)

  val memory = if (preferences.statusLineShowSysMemory.get() || preferences.statusLineShowAppMemory.get()) {
    sampleMemory()
  } else {
    null
  }

  val parts = ArrayList<String>(16)

  if (preferences.statusLineShowSysMemory.get()) {
    val used = memory?.sysUsedBytes ?: 0L
    if (used > 0) parts.add("SYS " + formatBytes(used))
  }
  if (preferences.statusLineShowAppMemory.get()) {
    val used = memory?.appPssBytes ?: 0L
    if (used > 0) parts.add("APP " + formatBytes(used))
  }

  val cacheSeconds = MPVLib.getPropertyDouble("demuxer-cache-duration") ?: -1.0
  val cacheBytes = MPVLib.getPropertyDouble("demuxer-cache-state/fw-bytes") ?: -1.0
  if (preferences.statusLineShowCache.get() && cacheSeconds >= 0.0) {
    parts.add(String.format(Locale.US, "BUF %.1fs", cacheSeconds))
  }
  if (preferences.statusLineShowCacheBytes.get() && cacheBytes >= 0.0) {
    parts.add(formatBytes(cacheBytes.toLong()))
  }

  // Bitrate: the real video track first, else the file average (which is what a container without
  // per-track bitrate can offer).
  val videoBitrate = MPVLib.getPropertyDouble("video-bitrate") ?: 0.0
  var sourceBitrate = videoBitrate
  var bitrateLabel = "VID "
  if (sourceBitrate <= 0.0) {
    val fileSize = MPVLib.getPropertyDouble("file-size") ?: 0.0
    if (fileSize > 0.0 && duration > 0.0) {
      sourceBitrate = fileSize * 8.0 / duration
      bitrateLabel = "AVG "
    }
  }
  if (preferences.statusLineShowBitrate.get() && sourceBitrate > 0.0) {
    parts.add(bitrateLabel + formatMbps(sourceBitrate))
  }

  // SMB throughput, measured at the share. Hidden entirely for local files.
  val smb = SmbStats.snapshot()
  if (smb.active) {
    if (preferences.statusLineShowSmbRate.get()) {
      parts.add("SMB " + formatByteRate(smb.bytesPerSecond))
    }
    if (preferences.statusLineShowSmbAverage.get()) {
      parts.add("avg " + formatByteRate(smb.recentAverageBytesPerSecond))
    }
    if (preferences.statusLineShowSmbMinimum.get()) {
      parts.add("min " + formatByteRate(smb.recentMinimumBytesPerSecond))
    }
    if (preferences.statusLineShowRatio.get() && sourceBitrate > 0.0) {
      val reference =
        if (smb.recentAverageBytesPerSecond > 0L) {
          smb.recentAverageBytesPerSecond.toDouble()
        } else {
          smb.bytesPerSecond.toDouble()
        }
      parts.add(String.format(Locale.US, "ratio %.1fx", reference * 8.0 / sourceBitrate))
    }
    if (preferences.statusLineShowRepairs.get()) {
      parts.add("FIX " + smb.repairs)
    }
    if (preferences.statusLineShowTotalRead.get() && smb.totalBytes > 0L) {
      parts.add("READ " + formatBytes(smb.totalBytes))
    }
  }

  if (preferences.statusLineShowDrops.get()) {
    val drops = MPVLib.getPropertyDouble("frame-drop-count") ?: 0.0
    parts.add("DRP " + drops.toLong())
  }
  if (preferences.statusLineShowFps.get()) {
    val fps = MPVLib.getPropertyDouble("estimated-vf-fps") ?: 0.0
    if (fps > 0.0) parts.add(String.format(Locale.US, "%.1ffps", fps))
  }
  if (preferences.statusLineShowSpeed.get()) {
    val speed = MPVLib.getPropertyDouble("speed") ?: 1.0
    parts.add(String.format(Locale.US, "x%.2f", speed))
  }
  if (preferences.statusLineShowTime.get()) {
    val position = MPVLib.getPropertyDouble("time-pos") ?: 0.0
    parts.add(formatDuration(position) + "/" + formatDuration(duration))
  }
  if (preferences.statusLineShowProgress.get()) {
    val percent = MPVLib.getPropertyDouble("percent-pos") ?: 0.0
    parts.add(String.format(Locale.US, "%.0f%%", percent))
  }

  // The decisive pair for a stalling stream: demuxer-cache-idle means "the cache is full and mpv
  // deliberately stopped reading" (healthy sawtooth, must never be flagged), so a genuine stall is
  // "not idle" AND nothing arriving — exactly the condition the playback watchdog repairs.
  var stalled = false
  if (preferences.statusLineShowCacheState.get()) {
    val idle = MPVLib.getPropertyBoolean("demuxer-cache-idle") ?: true
    val cacheSpeed = MPVLib.getPropertyDouble("cache-speed") ?: 0.0
    stalled = !idle && cacheSpeed < STALL_BYTES_PER_SECOND
    parts.add(if (idle) "C idle" else if (stalled) "C STALL" else "C read")
  } else {
    stalled = smb.stalled
  }

  if (preferences.statusLineShowDecoder.get()) {
    val decoder = MPVLib.getPropertyString("hwdec-current")
    if (!decoder.isNullOrBlank()) parts.add("HW " + decoder)
  }
  if (preferences.statusLineShowResolution.get()) {
    val width = MPVLib.getPropertyDouble("width") ?: 0.0
    val height = MPVLib.getPropertyDouble("height") ?: 0.0
    if (width > 0.0 && height > 0.0) {
      parts.add(String.format(Locale.US, "%.0fx%.0f", width, height))
    }
  }

  return StatusLineBuild(parts.joinToString(" | "), stalled)
}

private fun formatBytes(bytes: Long): String {
  if (bytes <= 0L) return "0"
  val kb = bytes / 1024.0
  if (kb < 1024.0) return String.format(Locale.US, "%.0fK", kb)
  val mb = kb / 1024.0
  if (mb < 1024.0) return String.format(Locale.US, "%.0fM", mb)
  return String.format(Locale.US, "%.2fG", mb / 1024.0)
}

private fun formatByteRate(bytesPerSecond: Long): String {
  if (bytesPerSecond <= 0L) return "0KB/s"
  val kb = bytesPerSecond / 1024.0
  if (kb < 1024.0) return String.format(Locale.US, "%.0fKB/s", kb)
  return String.format(Locale.US, "%.1fMB/s", kb / 1024.0)
}

private fun formatMbps(bitsPerSecond: Double): String =
  String.format(Locale.US, "%.2fMbps", bitsPerSecond / 1_000_000.0)

private fun formatDuration(seconds: Double): String {
  val total = seconds.toLong().coerceAtLeast(0L)
  val hours = total / 3600L
  val minutes = (total % 3600L) / 60L
  val secs = total % 60L
  return if (hours > 0L) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
  } else {
    String.format(Locale.US, "%d:%02d", minutes, secs)
  }
}
