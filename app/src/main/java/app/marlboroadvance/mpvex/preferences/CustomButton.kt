package app.marlboroadvance.mpvex.preferences

import `is`.xyz.mpv.MPVLib
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * A user-defined "Lua button" surfaced on the player overlay.
 *
 * The model mirrors mpvRex's `CustomButton` so XML/JSON interchange is straightforward:
 *  - [title]             Display text.
 *  - [content]           Lua source run when the user taps the button.
 *  - [longPressContent]  Lua source run when the user long-presses the button.
 *  - [onStartup]         Lua source run once after mpv initialises.
 *  - [enabled]           Whether the slot is rendered at all.
 *
 * Execution wraps the user-supplied body in a `mp.register_script_message` callback,
 * writes it to a stable file under mpv's config-dir/scripts, then issues a matching
 * `script-message` so the running mpv instance fires the action.
 */
@Serializable
data class CustomButton(
  val id: String = UUID.randomUUID().toString(),
  val title: String,
  val content: String = "",
  val longPressContent: String = "",
  val onStartup: String = "",
  val enabled: Boolean = true,
)

/**
 * The eight fixed slots the player overlay uses to render custom Lua buttons.
 *
 * Order: L1..L4 (left column top-to-bottom) followed by R1..R4 (right column top-to-bottom).
 * Null entries are empty slots and are not rendered.
 */
@Serializable
data class CustomButtonSlots(
  val slots: List<CustomButton?> = List(SLOT_COUNT) { null },
) {
  companion object {
    const val SLOT_COUNT = 8
  }
}

/**
 * Process-wide host information CustomButton needs at runtime — the player writes to this
 * once mpv is up so that [executeLuaAction] can pick a stable, mpv-readable directory.
 */
object CustomButtonRuntime {
  /** Absolute path of the app's `filesDir`, set by [PlayerActivity] right after `setupMPV`. */
  var configDir: String = ""

  /** Fired by [PlayerActivity] on `setupMPV` so any [CustomButton.onStartup] code runs once. */
  fun fireStartups(buttons: List<CustomButton>) {
    buttons.forEach { btn ->
      if (btn.enabled) btn.executeOnStartup()
    }
  }
}

/**
 * Tap action: run [CustomButton.content]. The current user's body is assumed to be Lua
 * that talks to mpv (`mp.command`, `mp.osd_message`, …).
 */
fun CustomButton.executeTap() = executeLuaAction("tap", content)

/** Long-press action: run [CustomButton.longPressContent]. */
fun CustomButton.executeLongPress() = executeLuaAction("long", longPressContent)

/** Startup action: run [CustomButton.onStartup]. Called once after mpv is ready. */
fun CustomButton.executeOnStartup() = executeLuaAction("startup", onStartup)

/**
 * Wraps [body] in a self-contained `mp.register_script_message` handler, writes it under
 * `<configDir>/scripts/`, tells mpv to (re)load it, and finally fires the message so the
 * body runs in the live mpv instance.
 *
 * The file name and message id are derived from this button's [CustomButton.id] AND the
 * [action] (`tap` / `long` / `startup`) so every button keeps its own handler — without
 * this, two buttons with tap actions would overwrite each other's script.
 *
 * The same file is rewritten on every call, so editing the body in the settings screen
 * never requires restarting playback — the next tap picks up the new code.
 */
private fun CustomButton.executeLuaAction(action: String, body: String) {
  if (body.isBlank()) return
  val configDir = CustomButtonRuntime.configDir
  if (configDir.isBlank()) return

  val scriptsDir = File(configDir, "scripts").apply { mkdirs() }
  val fileTag = "custom_button_${id}_$action"
  val scriptFile = File(scriptsDir, "$fileTag.lua")
  val messageId = fileTag

  scriptFile.writeText(buildHandlerScript(messageId, body))
  // Re-load the script — mpv will replace any previously registered handler with this id.
  MPVLib.command("load-script", scriptFile.absolutePath)
  // Fire the message; if the script is being loaded for the first time this also covers
  // the brief window where the handler isn't yet registered (mpv buffers script-message
  // deliveries until scripts finish initialising, so this is safe in practice).
  MPVLib.command("script-message", messageId)
}

/**
 * Build the small wrapper script:
 *   local mp = require 'mp'
 *   mp.register_script_message('cb_xxx', function()
 *     <body indented two spaces>
 *   end)
 *
 * Indenting the user body keeps it visually separate from the harness code, and avoids
 * any "return" statements at the top level accidentally closing the wrapper.
 */
private fun buildHandlerScript(messageId: String, body: String): String = buildString {
  appendLine("local mp = require 'mp'")
  appendLine("mp.register_script_message('$messageId', function()")
  body.lineSequence().forEach { line ->
    append("  ")
    appendLine(line)
  }
  appendLine("end)")
}
