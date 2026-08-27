package app.marlboroadvance.mpvex.preferences

import `is`.xyz.mpv.MPVLib
import kotlinx.serialization.Serializable

/**
 * A user-defined button that runs an arbitrary mpv command when tapped on the player overlay.
 *
 * Buttons are persisted as an ordered list; [enabled] toggles visibility and the list order
 * itself defines display/render order (the settings screen reorders by swapping elements).
 *
 * @param id       Stable unique id (used as Compose key / reorder anchor).
 * @param label    Display text shown on the pill (e.g. "片头").
 * @param command  Raw mpv command string, e.g. "seek 90" or "cycle pause".
 * @param enabled  Whether the button is shown on the player overlay.
 */
@Serializable
data class CustomButton(
  val id: String,
  val label: String,
  val command: String,
  val enabled: Boolean = true,
) {
  /**
   * Execute this button's mpv command.
   *
   * Dispatch is routed through [MPVLib.command], which links to the FongMi native JNI symbol
   * `Java_is_xyz_mpv_MPVLib_command` (vararg String overload kept for qznet UI ergonomics).
   * A blank/empty command is a no-op.
   */
  fun execute() {
    val tokens = tokenize(command.trim())
    if (tokens.isNotEmpty()) {
      MPVLib.command(*tokens.toTypedArray())
    }
  }
}

/**
 * Split an mpv command string into tokens, honouring double-quoted arguments that may
 * contain spaces (e.g. `set title "my clip"` -> ["set", "title", "my clip"]).
 */
private fun tokenize(raw: String): List<String> {
  val tokens = mutableListOf<String>()
  val sb = StringBuilder()
  var inQuotes = false
  for (ch in raw) {
    when {
      ch == '"' -> inQuotes = !inQuotes
      ch.isWhitespace() && !inQuotes -> {
        if (sb.isNotEmpty()) {
          tokens.add(sb.toString())
          sb.clear()
        }
      }
      else -> sb.append(ch)
    }
  }
  if (sb.isNotEmpty()) tokens.add(sb.toString())
  return tokens
}
