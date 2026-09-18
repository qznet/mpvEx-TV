package app.marlboroadvance.mpvex.ui.player

/**
 * Records whether *we* (the user, the PiP control, the media notification or the sleep
 * timer) asked mpv to pause, as opposed to mpv pausing on its own (e.g. `cache-pause`
 * when the buffer starves, or a wedged stream).
 *
 * The stall watchdog must never auto-resume a pause the user asked for, but it must be
 * able to recover from a pause mpv entered by itself. Reading mpv's `paused-for-cache`
 * alone turned out to be unreliable during the "buffer drains to 0 then never resumes"
 * freeze, so every place that pauses on behalf of the user records it here.
 */
object UserPauseState {
  @Volatile
  var pausedByApp: Boolean = false
}
