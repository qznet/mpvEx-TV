package app.marlboroadvance.mpvex.utils.media

import android.net.Uri
import java.net.URI
import java.net.URLEncoder

object NetworkMediaIdUtils {
  private val networkSchemes = setOf("smb", "ftp", "ftps", "webdav", "webdavs")

  data class MpvnasRef(
    val connectionId: Long,
    val canonicalPath: String,
    val displayName: String?,
  )

  fun canonicalizeNetworkPath(rawPath: String?): String? {
    if (rawPath.isNullOrBlank()) return null
    var normalized = rawPath.trim()
    networkSchemes.forEach { scheme ->
      val legacyPrefix = "/$scheme://"
      if (normalized.startsWith(legacyPrefix, ignoreCase = true)) {
        normalized = "$scheme://${normalized.removePrefix(legacyPrefix)}"
        return@forEach
      }
    }

    val uri = runCatching { URI(normalized) }.getOrNull() ?: return normalized
    val scheme = uri.scheme?.lowercase() ?: return normalized
    if (scheme !in networkSchemes) return normalized

    val host = uri.host?.lowercase() ?: return normalized
    val authority = buildString {
      uri.userInfo?.let {
        append(it)
        append("@")
      }
      append(host)
      if (uri.port != -1) {
        append(":")
        append(uri.port)
      }
    }

    val rawPathPart = uri.rawPath.orEmpty().ifBlank { "/" }
    return URI(
      scheme,
      authority,
      rawPathPart,
      uri.rawQuery,
      null,
    ).toString()
  }

  fun buildPlaybackKey(canonicalPath: String): String = canonicalPath

  fun buildMpvnasUri(
    connectionId: Long,
    canonicalPath: String,
    displayName: String?,
  ): Uri {
    val encodedPath = URLEncoder.encode(canonicalPath, "UTF-8")
    val encodedName = displayName?.takeIf { it.isNotBlank() }?.let {
      URLEncoder.encode(it, "UTF-8")
    }
    val query = buildString {
      append("path=")
      append(encodedPath)
      if (encodedName != null) {
        append("&name=")
        append(encodedName)
      }
    }
    return Uri.parse("mpvnas://$connectionId?$query")
  }

  fun parseMpvnasUri(uri: Uri): MpvnasRef? {
    if (!uri.scheme.equals("mpvnas", ignoreCase = true)) return null
    val connectionId = uri.host?.toLongOrNull() ?: return null
    val queryPath = uri.getQueryParameter("path")
    val queryName = uri.getQueryParameter("name")

    val canonicalPath = if (!queryPath.isNullOrBlank()) {
      canonicalizeNetworkPath(queryPath)
    } else {
      canonicalizeNetworkPath(uri.path)
    } ?: return null

    val displayName = queryName
      ?: fileNameFromPath(canonicalPath)

    return MpvnasRef(
      connectionId = connectionId,
      canonicalPath = canonicalPath,
      displayName = displayName,
    )
  }

  fun fileNameFromPath(path: String?): String {
    val canonical = canonicalizeNetworkPath(path) ?: return "Network Stream"
    return canonical.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "Network Stream"
  }

  fun parentPath(path: String): String {
    val canonical = canonicalizeNetworkPath(path) ?: path
    val idx = canonical.lastIndexOf('/')
    return if (idx <= canonical.indexOf("://") + 2) canonical else canonical.substring(0, idx)
  }
}
