package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

/**
 * Live transfer statistics for the SMB layer, fed by the healing stream that performs the reads.
 *
 * mpv's own counters (`cache-speed`, `demuxer-cache-state/raw-input-rate`) describe the loopback
 * hop between the local HTTP proxy and mpv, so they over-report whenever a read is served from the
 * proxy's own buffered pre-read. These counters sit at the SMB boundary instead, which is the only
 * place where "the NAS is slow" can be told apart from "the proxy/mpv side is slow".
 *
 * Mutations happen on the response thread that reads the share; [snapshot] is called from the
 * player's status-line loop. The player only ever streams one file, so the counters are reset by
 * [onStreamOpened].
 */
object SmbStats {
  /** Sampling window. A rate is only published once a full window has elapsed. */
  private const val WINDOW_MS = 1_000L

  /** How many completed windows the recent average/minimum/maximum are computed over. */
  private const val HISTORY = 10

  /** A consistent read of every counter, taken once per UI refresh. */
  data class Snapshot(
    val active: Boolean,
    /** Bytes per second over the last completed window; 0 once reads have stopped. */
    val bytesPerSecond: Long,
    val recentAverageBytesPerSecond: Long,
    val recentMinimumBytesPerSecond: Long,
    val recentMaximumBytesPerSecond: Long,
    val totalBytes: Long,
    val repairs: Int,
    val millisSinceLastRead: Long,
    val millisSinceStreamOpen: Long,
    /** Byte offset the reader has reached in the file. */
    val position: Long,
  ) {
    /**
     * True when the stream is open but no byte has arrived for more than one window — the exact
     * condition the playback watchdog treats as "the reader stalled" and repairs in place.
     */
    val stalled: Boolean
      get() = active && millisSinceLastRead > 1_000L
  }

  private val lock = Any()

  private var active = false
  private var streamOpenedAtMs = 0L
  private var lastReadAtMs = 0L
  private var position = 0L
  private var totalBytes = 0L
  private var windowBytes = 0L
  private var windowStartMs = 0L
  private var completedBytesPerSecond = 0L
  private var repairs = 0
  private val history = LongArray(HISTORY)
  private var historyPos = 0
  private var historyFilled = 0

  /** Called when a new response body starts streaming a file from [offset]. */
  fun onStreamOpened(offset: Long) {
    val now = System.currentTimeMillis()
    synchronized(lock) {
      active = true
      streamOpenedAtMs = now
      lastReadAtMs = now
      position = offset
      totalBytes = 0L
      windowBytes = 0L
      windowStartMs = now
      completedBytesPerSecond = 0L
      repairs = 0
      history.fill(0L)
      historyPos = 0
      historyFilled = 0
    }
  }

  /** Called by the streaming read path after [count] bytes were read, reaching [positionAfterRead]. */
  fun onBytesRead(count: Int, positionAfterRead: Long) {
    if (count <= 0) return
    val now = System.currentTimeMillis()
    synchronized(lock) {
      if (!active) {
        active = true
        streamOpenedAtMs = now
        windowStartMs = now
      }
      totalBytes += count
      windowBytes += count
      position = positionAfterRead
      lastReadAtMs = now
      val elapsed = now - windowStartMs
      if (elapsed >= WINDOW_MS) {
        completedBytesPerSecond = windowBytes * 1000L / elapsed
        pushWindow(completedBytesPerSecond)
        windowBytes = 0L
        windowStartMs = now
      }
    }
  }

  /** Called when the stream rebuilt its SMB session underneath a still-running HTTP body. */
  fun onRepairSucceeded() {
    synchronized(lock) { repairs++ }
  }

  /** Called when the response body is done with the share. */
  fun onStreamClosed() {
    synchronized(lock) {
      active = false
      // Publish the partial window, otherwise a short read would report a rate of 0.
      if (windowBytes > 0L) {
        val elapsed = System.currentTimeMillis() - windowStartMs
        if (elapsed > 0L) {
          completedBytesPerSecond = windowBytes * 1000L / elapsed
          pushWindow(completedBytesPerSecond)
        }
        windowBytes = 0L
      }
    }
  }

  /** Forgets everything, e.g. when playback stops. */
  fun reset() {
    synchronized(lock) {
      active = false
      totalBytes = 0L
      windowBytes = 0L
      completedBytesPerSecond = 0L
      repairs = 0
      history.fill(0L)
      historyPos = 0
      historyFilled = 0
      streamOpenedAtMs = 0L
      lastReadAtMs = 0L
      position = 0L
    }
  }

  fun snapshot(now: Long = System.currentTimeMillis()): Snapshot = synchronized(lock) {
    var sum = 0L
    var min = Long.MAX_VALUE
    var max = 0L
    for (i in 0 until historyFilled) {
      val value = history[i]
      sum += value
      if (value < min) min = value
      if (value > max) max = value
    }
    val sinceLastRead = now - lastReadAtMs
    // Reads stopped: report 0 rather than the last healthy window, so a stalled stream cannot
    // masquerade as a healthy one for the rest of playback.
    val current = if (active && sinceLastRead > WINDOW_MS) 0L else completedBytesPerSecond
    Snapshot(
      active = active,
      bytesPerSecond = current,
      recentAverageBytesPerSecond = if (historyFilled == 0) 0L else sum / historyFilled,
      recentMinimumBytesPerSecond = if (historyFilled == 0) 0L else min,
      recentMaximumBytesPerSecond = max,
      totalBytes = totalBytes,
      repairs = repairs,
      millisSinceLastRead = if (lastReadAtMs == 0L) 0L else sinceLastRead,
      millisSinceStreamOpen = if (streamOpenedAtMs == 0L) 0L else now - streamOpenedAtMs,
      position = position,
    )
  }

  /** Caller must hold [lock] (the public entry points all do). */
  private fun pushWindow(value: Long) {
    history[historyPos] = value
    historyPos = (historyPos + 1) % HISTORY
    if (historyFilled < HISTORY) historyFilled++
  }
}
