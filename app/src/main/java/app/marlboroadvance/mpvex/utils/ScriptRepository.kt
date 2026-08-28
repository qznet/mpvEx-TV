package app.marlboroadvance.mpvex.utils

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import java.io.File

/**
 * Single access layer for the Lua/JS script feature.
 *
 * mpvEx-TV can read scripts from two different places:
 *  - the SAF folder the user picked in Advanced settings (a `content://` tree URI), or
 *  - a plain on-device path (defaults to `/storage/emulated/0/mpv/scripts/`).
 *
 * mpv's config-dir is the app's internal filesDir, so scripts must be copied into
 * `filesDir/scripts` to be auto-loaded — see PlayerActivity's sync. This repository is what
 * decides *which* scripts that should be, and lets the manager UI read/write them without
 * caring which of the two modes is active.
 */
object ScriptRepository {
  /** Extensions mpv auto-loads from `<config-dir>/scripts`. */
  val SCRIPT_EXTENSIONS = setOf("lua", "js")

  private const val DEFAULT_SCRIPTS_PATH = "/storage/emulated/0/mpv/scripts/"
  private const val DEFAULT_MPV_PATH = "/storage/emulated/0/mpv/"

  /** Where scripts currently live. */
  sealed interface Location {
    /** SAF tree (or its `scripts` subfolder) picked by the user. */
    data class Saf(val dir: DocumentFile) : Location

    /** Plain filesystem directory, e.g. /storage/emulated/0/mpv/scripts/. */
    data class Path(val dir: File) : Location
  }

  /**
   * Resolves the active script location. A configured and readable SAF tree wins; otherwise
   * the plain path is used.
   */
  fun location(context: Context, prefs: AdvancedPreferences): Location {
    val uri = prefs.mpvConfStorageUri.get()
    if (uri.isNotBlank()) {
      val tree =
        runCatching { DocumentFile.fromTreeUri(context, uri.toUri()) }.getOrNull()
      if (tree != null && tree.exists() && tree.canRead()) {
        return Location.Saf(scriptsSubdir(tree))
      }
    }
    return Location.Path(scriptsDirFromPrefs(prefs))
  }

  /** Sorted names of every Lua/JS script available at the active location. */
  fun listScripts(context: Context, prefs: AdvancedPreferences): List<String> =
    when (val loc = location(context, prefs)) {
      is Location.Saf ->
        loc.dir.listFiles()
          .filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in SCRIPT_EXTENSIONS }
          .mapNotNull { it.name }
          .sorted()

      is Location.Path ->
        loc.dir.listFiles { f -> f.isFile && f.extension.lowercase() in SCRIPT_EXTENSIONS }
          ?.map { it.name }
          ?.sorted()
          ?: emptyList()
    }

  /** Reads a script's text, or null when it is missing/unreadable. */
  fun readScript(context: Context, prefs: AdvancedPreferences, name: String): String? =
    runCatching {
      when (val loc = location(context, prefs)) {
        is Location.Saf ->
          loc.dir.findFile(name)?.let { file ->
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
              ?.toString(Charsets.UTF_8)
          }

        is Location.Path ->
          File(loc.dir, name).takeIf { it.isFile }?.readText()
      }
    }.getOrNull()

  /** Writes (or overwrites) a script. Returns false on any failure, e.g. no write access. */
  fun writeScript(
    context: Context,
    prefs: AdvancedPreferences,
    name: String,
    content: String,
  ): Boolean = runCatching {
    when (val loc = location(context, prefs)) {
      is Location.Saf -> {
        val existing = loc.dir.findFile(name)
        val target =
          existing
            ?: loc.dir.createFile("application/octet-stream", name)
              ?.also { if (it.name != name) it.renameTo(name) }
            ?: return false

        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
          out.write(content.toByteArray())
          out.flush()
        } ?: return false
        true
      }

      is Location.Path -> {
        if (!loc.dir.exists() && !loc.dir.mkdirs()) return false
        File(loc.dir, name).writeText(content)
        true
      }
    }
  }.getOrDefault(false)

  /** Deletes a script. Returns false when it did not exist or could not be removed. */
  fun deleteScript(context: Context, prefs: AdvancedPreferences, name: String): Boolean =
    runCatching {
      when (val loc = location(context, prefs)) {
        is Location.Saf -> loc.dir.findFile(name)?.delete() ?: false
        is Location.Path -> File(loc.dir, name).delete()
      }
    }.getOrDefault(false)

  /**
   * Copies a script into the cache dir so it can be handed to an external app via
   * FileProvider. Returns null when the script could not be read.
   */
  fun copyToCache(context: Context, prefs: AdvancedPreferences, name: String): File? =
    runCatching {
      val cacheFile = File(context.cacheDir, name)
      when (val loc = location(context, prefs)) {
        is Location.Saf -> {
          val source = loc.dir.findFile(name) ?: return null
          context.contentResolver.openInputStream(source.uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
          } ?: return null
        }

        is Location.Path -> File(loc.dir, name).copyTo(cacheFile, overwrite = true)
      }
      cacheFile
    }.getOrNull()

  /** Human-readable location, shown in the UI so the user knows where to drop files. */
  fun describe(context: Context, prefs: AdvancedPreferences): String =
    when (val loc = location(context, prefs)) {
      is Location.Saf -> loc.dir.uri.toString()
      is Location.Path -> loc.dir.absolutePath
    }

  /** True when a usable script directory exists. */
  fun isAvailable(context: Context, prefs: AdvancedPreferences): Boolean =
    when (val loc = location(context, prefs)) {
      is Location.Saf -> loc.dir.exists() && loc.dir.canRead()
      is Location.Path -> loc.dir.isDirectory
    }

  /**
   * Prefers a `scripts` subfolder (case-insensitive), falling back to the tree root so a
   * flat mpv folder still works.
   */
  private fun scriptsSubdir(tree: DocumentFile): DocumentFile =
    tree.listFiles().firstOrNull {
      it.isDirectory && it.name?.equals("scripts", ignoreCase = true) == true
    } ?: tree

  /** The configured scripts path, falling back to `<mpv dir>/scripts`. */
  private fun scriptsDirFromPrefs(prefs: AdvancedPreferences): File {
    val configured = prefs.mpvScriptsDir.get().ifBlank { DEFAULT_SCRIPTS_PATH }
    File(configured).takeIf { it.isDirectory }?.let { return it }

    val base = prefs.mpvConfStoragePath.get().ifBlank { DEFAULT_MPV_PATH }
    return File(base, "scripts")
  }
}
