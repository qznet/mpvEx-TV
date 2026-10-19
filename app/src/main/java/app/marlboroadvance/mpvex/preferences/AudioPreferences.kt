package app.marlboroadvance.mpvex.preferences

import androidx.annotation.StringRes
import app.marlboroadvance.mpvex.R
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore
import app.marlboroadvance.mpvex.preferences.preference.getEnum

/**
 * Passthrough strategy. Only meaningful when [AudioPreferences.passthroughEnabled] is on.
 * - [Quality]: keep bitstream passthrough at all times. Changing playback speed is then
 *   NOT applied to the audio (the compressed stream cannot be time-stretched), so speed
 *   changes silently fall back to whatever the AVR does with the bitstream.
 * - [Speed]: when the playback speed deviates from 1.0x, mpv drops back to PCM decoding so
 *   that mpv's own time-stretch (scaletempo) can actually change the tempo. This sacrifices
 *   surround/Atmos bitstream passthrough while a non-1.0x speed is active.
 */
enum class PassthroughMode(
  @StringRes val title: Int,
  val value: String,
) {
  Quality(R.string.pref_audio_passthrough_mode_quality, "quality"),
  Speed(R.string.pref_audio_passthrough_mode_speed, "speed"),
}

class AudioPreferences(
  preferenceStore: PreferenceStore,
) {
  val preferredLanguages = preferenceStore.getString("audio_preferred_languages")

  /**
   * Master audio passthrough switch.
   *
   * OFF (default): audio is always decoded to PCM and played through the TV speakers — this is
   * what most users want day to day. ON: compressed bitstreams (AC3/E-AC3/DTS/TrueHD/DTS-HD) are
   * sent untouched over HDMI to an AVR/receiver. Requires the TV to actually expose an HDMI
   * passthrough path (most do when an AVR is connected).
   */
  val passthroughEnabled = preferenceStore.getBoolean("audio_passthrough_enabled", false)

  /**
   * Strategy used when [passthroughEnabled] is ON. See [PassthroughMode]. Ignored when OFF.
   */
  val passthroughMode = preferenceStore.getEnum("audio_passthrough_mode", PassthroughMode.Quality)
  val defaultAudioDelay = preferenceStore.getInt("audio_delay_default")
  val audioPitchCorrection = preferenceStore.getBoolean("audio_pitch_correction", true)
  val audioChannels = preferenceStore.getEnum("audio_channels", AudioChannels.AutoSafe)
  val volumeBoostCap = preferenceStore.getInt("audio_volume_boost_cap", 30)
  val automaticBackgroundPlayback = preferenceStore.getBoolean("automatic_background_playback", false)
  val volumeNormalization = preferenceStore.getBoolean("audio_volume_normalization", false)

  /**
   * Computes the mpv `audio-spdif` value that should be in effect for a given playback speed.
   *
   * - Passthrough OFF  -> ""  (decode to PCM, TV speakers).
   * - Passthrough ON + Quality mode            -> [PASSTHROUGH_FORMATS] always.
   * - Passthrough ON + Speed mode + speed != 1.0 -> ""  (drop to PCM so mpv can time-stretch).
   * - Passthrough ON + Speed mode + speed == 1.0 -> [PASSTHROUGH_FORMATS].
   *
   * Returns the *effective* string (blank means PCM/downgraded), so callers can also tell whether
   * passthrough is currently active purely from the result.
   */
  fun effectivePassthroughSpdif(currentSpeed: Double): String {
    if (!passthroughEnabled.get()) return ""
    if (passthroughMode.get() == PassthroughMode.Speed &&
      kotlin.math.abs(currentSpeed - 1.0) > 0.001
    ) {
      return ""
    }
    return PASSTHROUGH_FORMATS
  }

  companion object {
    /**
     * Compressed bitstream formats offered for HDMI passthrough. mpv sends these untouched to the
     * receiver. Multi-channel PCM (the fallback when passthrough is off or downgraded) is handled by
     * mpv's normal decoder + the OS audio mix, so it does not need to be listed here.
     */
    const val PASSTHROUGH_FORMATS = "ac3,eac3,dts,truehd,dts-hd"
  }
}

enum class AudioChannels(
  @StringRes val title: Int,
  val property: String,
  val value: String,
) {
  Auto(R.string.pref_audio_channels_auto, "audio-channels", "auto-safe"),
  AutoSafe(R.string.pref_audio_channels_auto_safe, "audio-channels", "auto"),
  Mono(R.string.pref_audio_channels_mono, "audio-channels", "mono"),
  Stereo(R.string.pref_audio_channels_stereo, "audio-channels", "stereo"),
  ReverseStereo(R.string.pref_audio_channels_stereo_reversed, "af", "pan=[stereo|c0=c1|c1=c0]"),
}
