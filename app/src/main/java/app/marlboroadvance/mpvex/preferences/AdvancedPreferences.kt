package app.marlboroadvance.mpvex.preferences

import app.marlboroadvance.mpvex.BuildConfig
import app.marlboroadvance.mpvex.preferences.preference.PreferenceStore

class AdvancedPreferences(
  preferenceStore: PreferenceStore,
) {
  val mpvConfStorageUri = preferenceStore.getString("mpv_conf_storage_location_uri")
  val mpvConf = preferenceStore.getString("mpv.conf")
  val inputConf = preferenceStore.getString("input.conf")

  /**
   * Default on-device MPV directory, used when the user has not picked a folder via the
   * system picker. mpv's config-dir is the app's internal filesDir, so this directory is
   * synced into it before playback starts.
   */
  val mpvConfStoragePath =
    preferenceStore.getString(
      "mpv_conf_storage_path",
      "/storage/emulated/0/mpv/",
    )

  /**
   * Directory Lua/JS scripts are read from. Contents are synced into
   * `<config-dir>/scripts` so mpv auto-loads them.
   */
  val mpvScriptsDir =
    preferenceStore.getString(
      "mpv_scripts_dir",
      "/storage/emulated/0/mpv/scripts/",
    )

  val verboseLogging = preferenceStore.getBoolean("verbose_logging", BuildConfig.BUILD_TYPE != "release")

  val enabledStatisticsPage = preferenceStore.getInt("enabled_stats_page", 0)

  val enableRecentlyPlayed = preferenceStore.getBoolean("enable_recently_played", true)

}
