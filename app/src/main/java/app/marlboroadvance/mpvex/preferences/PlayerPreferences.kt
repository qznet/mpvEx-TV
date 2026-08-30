
package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum
import app.marlboroadvance.mpvex.ui.player.PlayerOrientation
import app.marlboroadvance.mpvex.ui.player.RepeatMode
import app.marlboroadvance.mpvex.ui.player.VideoAspect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Pre-1.4.0 on-disk format. Kept only to migrate the `custom_buttons` preference. */
@Serializable
private data class LegacyCustomButton(
  val id: String = "",
  val label: String = "",
  val command: String = "",
  val enabled: Boolean = true,
)

/**
 * Promote a v1.4.0-style flat list of {label, command} entries into the 8-slot
 * [CustomButtonSlots] format. Commands are wrapped in `mp.command([==[...]==])` so the
 * legacy body still works when it's now evaluated as Lua source.
 */
private fun migrateLegacyCustomButtons(raw: String): CustomButtonSlots {
  val legacy = runCatching {
    kotlinx.serialization.json.Json.decodeFromString<List<LegacyCustomButton>>(raw)
  }.getOrDefault(emptyList())
  if (legacy.isEmpty()) return CustomButtonSlots()
  val slots = MutableList<CustomButton?>(CustomButtonSlots.SLOT_COUNT) { null }
  legacy.forEachIndexed { i, old ->
    if (i < slots.size) {
      slots[i] = CustomButton(
        id = old.id.ifBlank { "legacy_$i" },
        title = old.label,
        content = wrapLegacyCommand(old.command),
        enabled = old.enabled,
      )
    }
  }
  return CustomButtonSlots(slots)
}

private fun wrapLegacyCommand(command: String): String {
  val trimmed = command.trim()
  if (trimmed.isBlank()) return ""
  // If the body already looks like Lua (mp. *, local, require, function), don't wrap.
  val looksLikeLua =
    trimmed.startsWith("mp.") ||
      trimmed.startsWith("local ") ||
      trimmed.startsWith("require ") ||
      trimmed.startsWith("function ")
  if (looksLikeLua) return trimmed
  // Lua long-string literal — survives arbitrary quotes, newlines, brackets.
  return "mp.command([==[$trimmed]==])"
}

class PlayerPreferences(
  preferenceStore: PreferenceStore,
) {
  val orientation = preferenceStore.getEnum("player_orientation", PlayerOrientation.Video)
  val invertDuration = preferenceStore.getBoolean("invert_duration")
  val holdForMultipleSpeed = preferenceStore.getFloat("hold_for_multiple_speed", 2f)
  val showDynamicSpeedOverlay = preferenceStore.getBoolean("show_dynamic_speed_overlay", true)
  val showDoubleTapOvals = preferenceStore.getBoolean("show_double_tap_ovals", true)
  val showSeekTimeWhileSeeking = preferenceStore.getBoolean("show_seek_time_while_seeking", true)
  val usePreciseSeeking = preferenceStore.getBoolean("use_precise_seeking", false)

  val brightnessGesture = preferenceStore.getBoolean("gestures_brightness", true)
  val volumeGesture = preferenceStore.getBoolean("volume_brightness", true)
  val pinchToZoomGesture = preferenceStore.getBoolean("pinch_to_zoom_gesture", true)
  val horizontalSwipeToSeek = preferenceStore.getBoolean("horizontal_swipe_to_seek", true)
  val horizontalSwipeSensitivity = preferenceStore.getFloat("horizontal_swipe_sensitivity", 0.05f)

  val customAspectRatios = preferenceStore.getStringSet("custom_aspect_ratios", emptySet())

  val defaultSpeed = preferenceStore.getFloat("default_speed", 1f)
  val speedPresets =
    preferenceStore.getStringSet(
      "default_speed_presets",
      setOf("0.25", "0.5", "0.75", "1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0", "3.5", "4.0"),
    )
  val displayVolumeAsPercentage = preferenceStore.getBoolean("display_volume_as_percentage", true)
  val swapVolumeAndBrightness = preferenceStore.getBoolean("display_volume_on_right")
  val showLoadingCircle = preferenceStore.getBoolean("show_loading_circle", true)
  val savePositionOnQuit = preferenceStore.getBoolean("save_position", true)

  val closeAfterReachingEndOfVideo = preferenceStore.getBoolean("close_after_eof", true)

  val rememberBrightness = preferenceStore.getBoolean("remember_brightness")
  val defaultBrightness = preferenceStore.getFloat("default_brightness", -1f)

  val allowGesturesInPanels = preferenceStore.getBoolean("allow_gestures_in_panels")
  val showSystemStatusBar = preferenceStore.getBoolean("show_system_status_bar")
  val showSystemNavigationBar = preferenceStore.getBoolean("show_system_navigation_bar")
  val reduceMotion = preferenceStore.getBoolean("reduce_motion", true)
  val playerTimeToDisappear = preferenceStore.getInt("player_time_to_disappear", 4000)

  val defaultVideoZoom = preferenceStore.getFloat("default_video_zoom", 0f)
  val panAndZoomEnabled = preferenceStore.getBoolean("pan_and_zoom_enabled", false)

  val includeSubtitlesInSnapshot = preferenceStore.getBoolean("include_subtitles_in_snapshot", false)

  val playlistMode = preferenceStore.getBoolean("playlist_mode", true)
  val playlistViewMode = preferenceStore.getBoolean("playlist_view_mode_list", true) // true = list, false = grid

  val useWavySeekbar = preferenceStore.getBoolean("use_wavy_seekbar", true)

  val customSkipDuration = preferenceStore.getInt("custom_skip_duration", 90)

  // ==================== Auto skip intro / outro (TV series) ====================

  /** Master switch for automatically skipping a TV episode's intro and outro. */
  val skipIntroOutroEnabled = preferenceStore.getBoolean("skip_intro_outro_enabled", false)

  /**
   * Intro length in seconds. When playback of a file starts at a position at or before
   * this many seconds, playback jumps straight to this position.
   */
  val skipIntroSeconds = preferenceStore.getInt("skip_intro_seconds", 60)

  /**
   * Outro length in seconds. Once playback passes 95% of the duration and the remaining
   * time is within this window, the player advances to the next episode.
   */
  val skipOutroSeconds = preferenceStore.getInt("skip_outro_seconds", 60)

  /**
   * User-defined Lua buttons shown on the player overlay. Up to 8 fixed slots (L1..L4, R1..R4).
   * Older installs persisted a flat `List<CustomButton>`; the deserializer migrates that
   * legacy format on the fly, wrapping any plain mpv command in `mp.command([==[...]==])`
   * so it still works when the body is now evaluated as Lua.
   */
  val customButtons = preferenceStore.getObject(
    key = "custom_buttons",
    defaultValue = CustomButtonSlots(),
    serializer = { Json.encodeToString(it) },
    deserializer = { str ->
      if (str.isBlank()) {
        CustomButtonSlots()
      } else {
        // New format first; fall back to the v1.4.0 flat list on decode failure.
        runCatching { Json.decodeFromString<CustomButtonSlots>(str) }.getOrNull()
          ?: migrateLegacyCustomButtons(str)
      }
    },
  )

  /** Vertical offset (dp) of the custom buttons row from the bottom of the screen. */
  val customButtonsBottomMargin = preferenceStore.getInt("custom_buttons_bottom_margin", 70)

  val repeatMode = preferenceStore.getEnum("repeat_mode", RepeatMode.OFF)
  val shuffleEnabled = preferenceStore.getBoolean("shuffle_enabled", false)

  // New: autoplay next video when current file ends
  val autoplayNextVideo = preferenceStore.getBoolean("autoplay_next_video", true)

  val autoPiPOnNavigation = preferenceStore.getBoolean("auto_pip_on_navigation", false)

  val keepScreenOnWhenPaused = preferenceStore.getBoolean("keep_screen_on_when_paused", false)

  // Back key behavior
  // First back press hides the player controls (clears the UI) instead of exiting.
  val clearUiOnBackPress = preferenceStore.getBoolean("clear_ui_on_back_press", true)
  // Require a second back press within a short window to exit the player.
  val exitOnDoubleBackPress = preferenceStore.getBoolean("exit_on_double_back_press", true)

  // Persist aspect ratio setting (default to Fit)
  val defaultVideoAspect = preferenceStore.getEnum("default_video_aspect", VideoAspect.Fit)
  val defaultCustomAspectRatio = preferenceStore.getObject(
    key = "default_custom_aspect_ratio",
    defaultValue = -1.0,
    serializer = { it.toString() },
    deserializer = { it.toDoubleOrNull() ?: -1.0 }
  )

}
