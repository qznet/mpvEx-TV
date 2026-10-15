
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

  // ==================== Cache / memory limits (low-RAM devices) ====================

  /**
   * Master switch for conservative mpv cache limits.
   *
   * On low-RAM devices (<=3GB, typical for Android TV boxes) the demuxer cache keeps
   * growing during long playback until the OS kills the process, which surfaces as a
   * freeze + black screen that can't be exited. When enabled we cap the cache with the
   * values below and let mpv pause briefly to refill instead of running out of memory.
   * The brief pause is the same "short stall then continues" behaviour webhtv shows;
   * it is far better than a hard freeze.
   */
  val lowMemoryMode = preferenceStore.getBoolean("low_memory_mode", true)

  /**
   * Forward buffer target in seconds (mpv `cache-secs`).
   *
   * Tuned for a ~3GB Android TV box that has roughly 1GB of free RAM during playback:
   * a 30s forward target plus the larger readahead below gives mpv enough cushion to
   * ride over transient reader/demuxer stalls instead of draining the buffer to 0 and
   * freezing. The byte cap in [demuxerMaxBytesMib] is the real bound; at typical
   * 1080p HEVC bitrates 30s is only ~15MiB, well under it.
   */
  val cacheSecs = preferenceStore.getInt("cache_secs", 30)

  /**
   * How far ahead the demuxer reads, in seconds (mpv `demuxer-readahead-secs`).
   *
   * Default raised from 20s to 60s so the demuxer keeps a deeper pre-read queue. On a
   * 3GB TV this is the single most effective knob against the "buffer slowly shrinks to
   * 0 and playback stops" symptom, because it lets the decoder keep consuming from the
   * queue while a brief read hiccup refills it.
   */
  val cacheReadaheadSecs = preferenceStore.getInt("cache_readahead_secs", 60)

  /**
   * Max in-memory demuxer cache in MiB (mpv `demuxer-max-bytes`).
   *
   * Forward budget only — [demuxerMaxBackBytesMib] is a SECOND budget, so the real peak is the
   * sum of the two. Default lowered from 256 to 192 MiB after measuring a 3GB TV with ~1GB free
   * during playback: 192 + 48 keeps the peak at ~240MiB and leaves ~410MiB for the decoder, GPU
   * and the app. The UI slider allows up to 512 MiB for devices with more headroom.
   */
  val demuxerMaxBytesMib = preferenceStore.getInt("demuxer_max_bytes_mib", 192)

  /**
   * Max cached already-played data in MiB (mpv `demuxer-max-back-bytes`).
   *
   * Default lowered from 64 to 48 MiB: back-buffer is a separate budget that adds to
   * [demuxerMaxBytesMib], and 48MiB already makes short backward seeks instant. Raising it buys
   * very little compared with the RAM it costs on a 3GB box.
   */
  val demuxerMaxBackBytesMib = preferenceStore.getInt("demuxer_max_back_bytes_mib", 48)

  /** Pause playback when the buffer runs low and resume after it refills (mpv `cache-pause`). */
  val cachePause = preferenceStore.getBoolean("cache_pause", true)

  /** Seconds the buffer must stay below threshold before pausing (mpv `cache-pause-wait`). */
  val cachePauseWait = preferenceStore.getInt("cache_pause_wait", 3)

  /**
   * Minimum seconds of cache to build before playback starts / after a seek (mpv
   * `demuxer-cache-wait`). Default raised from 1 to 2s: on SMB the first read burst is the least
   * predictable moment, and one extra second there costs almost nothing on a seek while removing
   * most of the "black frame, then catch up" starts.
   */
  val demuxerCacheWait = preferenceStore.getInt("demuxer_cache_wait", 2)

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

  // ==================== Playback status line (one-line on-screen diagnostics) ====================
  //
  // mpv's own OSD cannot be used for this: the HW+ path runs vo=mediacodec_embed, which renders
  // nothing itself, and the app's separate OSD surface is reserved for subtitles/OSC. The line is
  // therefore drawn by the app's Compose overlay in the top-left corner.

  /** Master switch for the one-line info overlay drawn in the top-left corner while playing. */
  val statusLineEnabled = preferenceStore.getBoolean("status_line_enabled", true)

  /** Text size of the status line, in sp. */
  val statusLineFontSizeSp = preferenceStore.getInt("status_line_font_size_sp", 12)

  /** How often the line is recomputed, in milliseconds. */
  val statusLineRefreshMs = preferenceStore.getInt("status_line_refresh_ms", 1000)

  /** Draw a translucent plate behind the text so it stays readable over bright scenes. */
  val statusLineBackground = preferenceStore.getBoolean("status_line_background", true)

  /** Whole-device used memory, read from /proc/meminfo. */
  val statusLineShowSysMemory = preferenceStore.getBoolean("status_line_mem_sys", true)

  /** This app's own memory footprint (PSS), which is where the mpv cache actually lives. */
  val statusLineShowAppMemory = preferenceStore.getBoolean("status_line_mem_app", false)

  /** Seconds of demuxer cache ahead of the play position (`demuxer-cache-duration`). */
  val statusLineShowCache = preferenceStore.getBoolean("status_line_cache", true)

  /** Bytes held in the forward demuxer cache. */
  val statusLineShowCacheBytes = preferenceStore.getBoolean("status_line_cache_bytes", true)

  /** Bitrate of the current video track, falling back to file size / duration. */
  val statusLineShowBitrate = preferenceStore.getBoolean("status_line_bitrate", true)

  /** SMB read rate, measured at the SMB boundary rather than at the local proxy hop. */
  val statusLineShowSmbRate = preferenceStore.getBoolean("status_line_smb", true)

  /** Average SMB rate over the last 10 sampling windows. */
  val statusLineShowSmbAverage = preferenceStore.getBoolean("status_line_smb_avg", false)

  /** Lowest SMB rate seen in the last 10 sampling windows — where stalls show up first. */
  val statusLineShowSmbMinimum = preferenceStore.getBoolean("status_line_smb_min", false)

  /** SMB rate divided by the source bitrate: below 1x the cache is guaranteed to drain. */
  val statusLineShowRatio = preferenceStore.getBoolean("status_line_ratio", true)

  /** Dropped video frames. */
  val statusLineShowDrops = preferenceStore.getBoolean("status_line_drops", true)

  /** Actual render frame rate (`estimated-vf-fps`). */
  val statusLineShowFps = preferenceStore.getBoolean("status_line_fps", false)

  /** Playback speed multiplier. */
  val statusLineShowSpeed = preferenceStore.getBoolean("status_line_speed", true)

  /** Playback percentage. */
  val statusLineShowProgress = preferenceStore.getBoolean("status_line_progress", true)

  /** Position / total duration. */
  val statusLineShowTime = preferenceStore.getBoolean("status_line_time", false)

  /** In-place SMB session repairs performed by the healing stream during this file. */
  val statusLineShowRepairs = preferenceStore.getBoolean("status_line_repairs", true)

  /** Whether the demuxer is idle (cache full, healthy) or reading with nothing arriving (stall). */
  val statusLineShowCacheState = preferenceStore.getBoolean("status_line_cache_state", false)

  /** Hardware decoder actually in use. */
  val statusLineShowDecoder = preferenceStore.getBoolean("status_line_decoder", false)

  /** Video resolution as decoded. */
  val statusLineShowResolution = preferenceStore.getBoolean("status_line_resolution", false)

  /** Bytes pulled from the share for the current file. */
  val statusLineShowTotalRead = preferenceStore.getBoolean("status_line_total_read", false)
}
