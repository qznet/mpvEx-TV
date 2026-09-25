package app.marlboroadvance.mpvex.presentation.crash

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.system.exitProcess

class GlobalExceptionHandler(
  private val context: Context,
  private val activity: Class<*>,
) : Thread.UncaughtExceptionHandler {
  override fun uncaughtException(
    t: Thread,
    e: Throwable,
  ) {
    if (isSmbTransportTeardownRace(e)) {
      // smbj throws this from one of its OWN io threads: AsyncDirectTcpTransport's write
      // completion callback re-enters startAsyncWrite() after the transport was already marked
      // disconnected, which happens whenever a transport is torn down while a write is still
      // queued. It says nothing about this app's state — the transport was being discarded — and
      // the thread that dies is one pooled io worker, so restarting the app for it costs the
      // viewer their playback and fixes nothing. Log it and let the thread die.
      Log.w(TAG, "ignoring smbj transport teardown race on thread ${t.name}", e)
      return
    }
    val intent = Intent(context, activity)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    intent.putExtra("exception", e.stackTraceToString())
    context.startActivity(intent)
    exitProcess(0)
  }

  /**
   * True for the one known-benign smbj race, and only for it.
   *
   * The message comes from `AsyncDirectTcpTransport.startAsyncWrite()`; the smbj frame requirement
   * keeps an unrelated `IllegalStateException` that happens to carry the same text out of the
   * filter. `SmbClient` no longer provokes this (its teardown sends no packet), so hitting it means
   * a socket died mid-write — still not a reason to kill the app, because the session underneath is
   * already being replaced by the repair path.
   */
  private fun isSmbTransportTeardownRace(e: Throwable): Boolean {
    if (e !is IllegalStateException || e.message != "Transport is not connected") return false
    return e.stackTrace.any { it.className.startsWith("com.hierynomus.smbj.") }
  }

  private companion object {
    private const val TAG = "GlobalExceptionHandler"
  }
}
