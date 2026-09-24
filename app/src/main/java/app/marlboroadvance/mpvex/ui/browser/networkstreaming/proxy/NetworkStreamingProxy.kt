package app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy

import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClient
import app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients.NetworkClientFactory
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Local HTTP proxy server that enables seeking for network streaming protocols
 */
class NetworkStreamingProxy private constructor() : NanoHTTPD("127.0.0.1", 0) {

  companion object {
    private const val TAG = "NetworkStreamingProxy"

    // Ping active network sessions on this cadence so an idle NAS/router never gets the
    // chance to silently drop the TCP while the player is reading from its demuxer cache.
    // Must stay comfortably below typical idle-drop windows (~25s+ observed on this NAS).
    private const val KEEPALIVE_INTERVAL_MS = 15_000L

    // Hard ceiling for any single keepalive ping. A half-open SMB session can otherwise block
    // the loop (and therefore every other connection) indefinitely.
    private const val KEEPALIVE_TIMEOUT_MS = 15_000L

    @Volatile
    private var instance: NetworkStreamingProxy? = null

    fun getInstance(): NetworkStreamingProxy {
      return instance ?: synchronized(this) {
        instance ?: NetworkStreamingProxy().also {
          it.start()
          instance = it
        }
      }
    }

    fun stopInstance() {
      synchronized(this) {
        instance?.let { proxy ->
          proxy.stop()
          proxy.cleanup()
          instance = null
        }
      }
    }
  }

  // Store active connections and their clients
  private val activeStreams = ConcurrentHashMap<String, StreamInfo>()
  // Global cache of network clients to reuse connections (Connection Pooling)
  private val clientCache = ConcurrentHashMap<Long, NetworkClient>()

  // Background keepalive that holds streaming sessions open for the whole playback session.
  private val keepAliveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile
  private var keepAliveJob: Job? = null

  data class StreamInfo(
    val connection: NetworkConnection,
    val filePath: String,
    var client: NetworkClient,
    var fileSize: Long = -1L,
    var mimeType: String = "video/mp4",
    var title: String? = null,
  )

  /**
   * Register a stream for proxying
   */
  fun registerStream(
    streamId: String,
    connection: NetworkConnection,
    filePath: String,
    fileSize: Long = -1L,
    mimeType: String = "video/mp4",
    title: String? = null,
  ): String {
    // Reuse existing client for this connection ID to prevent connection exhaustion
    val client = clientCache.getOrPut(connection.id) {
      NetworkClientFactory.createClient(connection)
    }

    val streamInfo = StreamInfo(
      connection = connection,
      filePath = filePath,
      client = client,
      fileSize = fileSize,
      mimeType = mimeType,
      title = title,
    )

    activeStreams[streamId] = streamInfo
    ensureKeepAlive()

    return "http://127.0.0.1:$listeningPort/$streamId"
  }

  fun resolveDisplayName(url: String): String? {
    try {
      val uri = android.net.Uri.parse(url)
      val streamId = uri.path?.removePrefix("/")?.split("/")?.firstOrNull()
      if (!streamId.isNullOrEmpty()) {
        return activeStreams[streamId]?.title
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error resolving display name for $url", e)
    }
    return null
  }

  fun unregisterStream(streamId: String) {
    // We don't disconnect the client here because it's shared in the cache
    activeStreams.remove(streamId)
  }

  /**
   * Force a brand-new client for the stream's connection, dropping the cached one. The proxy's
   * `getFileStream` trusts [NetworkClient.isConnected], which lies on a half-open socket (the
   * server/router silently dropped an idle SMB session while playback ran from the demuxer cache),
   * so a reload/seek never reconnects and the cache drains to 0 — playback freezes permanently.
   * Calling this before a reload guarantees the next `getFileStream` builds a fresh SMB session
   * instead of reusing the dead one.
   */
  fun forceReconnect(streamId: String) {
    val streamInfo = activeStreams[streamId] ?: return
    val connId = streamInfo.connection.id
    val old = clientCache.remove(connId)
    old?.let { stale ->
      // Detach the teardown: a graceful close on a half-open socket blocks until SO_TIMEOUT,
      // and the caller (watchdog) must not wait for it.
      ioScope.launch {
        try {
          stale.disconnect()
        } catch (_: Exception) {
          // best-effort; the socket is already dead
        }
      }
    }
    // Re-create a fresh client immediately so the next serve() doesn't block on a reconnect
    // that depends on the (lying) isConnected() flag.
    streamInfo.client = clientCache.getOrPut(connId) {
      NetworkClientFactory.createClient(streamInfo.connection)
    }
    Log.w(TAG, "forceReconnect: dropped stale client for stream $streamId (conn $connId)")
  }

  /**
   * Create a brand-new stream entry for the same underlying file. This is stronger than
   * [forceReconnect]: it gives mpv a fresh proxy URL, which forces a new HTTP connection and
   * therefore a completely new SMB read path. Without this, mpv can keep reusing the same
   * HTTP connection whose `serve()` thread is wedged on a dead SMB session, so the cache stays
   * at 0 even though a new SMB socket is technically ESTABLISHED. Returns the new proxy URL or
   * `null` if the old stream is no longer registered.
   */
  fun rotateStreamId(oldStreamId: String): String? {
    val oldInfo = activeStreams.remove(oldStreamId) ?: return null
    val connId = oldInfo.connection.id
    clientCache.remove(connId)?.let { stale ->
      ioScope.launch {
        try {
          stale.disconnect()
        } catch (_: Exception) {}
      }
    }
    val newStreamId = "${connId}_${System.currentTimeMillis()}_${oldInfo.filePath.hashCode()}_${UUID.randomUUID().toString().take(8)}"
    val freshClient = clientCache.getOrPut(connId) {
      NetworkClientFactory.createClient(oldInfo.connection)
    }
    activeStreams[newStreamId] = oldInfo.copy(client = freshClient)
    Log.w(TAG, "rotateStreamId: $oldStreamId -> $newStreamId (conn $connId)")
    return "http://127.0.0.1:$listeningPort/$newStreamId"
  }

  /**
   * Starts the background keepalive loop if it isn't already running. Each tick pings every
   * connection that currently backs an active stream so a dropped session is detected and
   * re-established off the playback path — turning the old "switch/seek freezes for ~15-60s"
   * into a near-instant operation.
   *
   * Each ping runs in its own coroutine with a hard timeout so a single half-open session
   * cannot block the entire loop (and therefore every other connection) indefinitely.
   */
  private fun ensureKeepAlive() {
    if (keepAliveJob?.isActive == true) return
    synchronized(this) {
      if (keepAliveJob?.isActive == true) return
      keepAliveJob = keepAliveScope.launch {
        while (isActive) {
          delay(KEEPALIVE_INTERVAL_MS)
          // Keep warm only the connections that currently back an active stream.
          val activeConnectionIds = activeStreams.values.mapTo(HashSet()) { it.connection.id }
          activeConnectionIds.map { id ->
            async(Dispatchers.IO) {
              val client = clientCache[id] ?: return@async
              try {
                withTimeout(KEEPALIVE_TIMEOUT_MS) {
                  client.keepAlive()
                }
              } catch (e: Exception) {
                Log.w(TAG, "Keepalive failed for connection $id", e)
              }
            }
          }.awaitAll()
        }
      }
    }
  }

  private fun cleanup() {
    keepAliveJob?.cancel()
    keepAliveJob = null
    activeStreams.clear()
    clientCache.values.forEach { client ->
      runBlocking {
        try {
          client.disconnect()
        } catch (_: Exception) {}
      }
    }
    clientCache.clear()
  }

  override fun serve(session: IHTTPSession): Response {
    val uri = session.uri
    val streamId = uri.removePrefix("/").split("/").firstOrNull()
    if (streamId.isNullOrEmpty()) {
      return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")
    }

    val streamInfo = activeStreams[streamId] ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Stream not found")

    val rangeHeader = session.headers["range"]

    return try {
      if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
        handleRangeRequest(session, streamInfo, rangeHeader)
      } else {
        handleFullRequest(session, streamInfo)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error serving request for stream $streamId: ${streamInfo.filePath}", e)
      newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
    }
  }

  private fun handleRangeRequest(session: IHTTPSession, streamInfo: StreamInfo, rangeHeader: String): Response {
    val rangeValue = rangeHeader.removePrefix("bytes=")
    val parts = rangeValue.split("-")
    val start = parts[0].toLongOrNull() ?: 0L
    
    if (streamInfo.fileSize < 0) {
      streamInfo.fileSize = runBlocking {
        if (!streamInfo.client.isConnected()) streamInfo.client.connect()
        streamInfo.client.getFileSize(streamInfo.filePath).getOrDefault(-1L)
      }
    }

    val fileSize = streamInfo.fileSize
    val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLongOrNull() else null
    val rangeEnd = end ?: (fileSize - 1)
    val contentLength = if (fileSize > 0) rangeEnd - start + 1 else -1L

    val inputStream = runBlocking {
      if (!streamInfo.client.isConnected()) streamInfo.client.connect()
      streamInfo.client.getFileStream(streamInfo.filePath, start).getOrNull()
    } ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to open stream")

    val response = if (contentLength > 0) {
        newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, streamInfo.mimeType, inputStream, contentLength)
    } else {
        // Fallback for unknown size
        newChunkedResponse(Response.Status.PARTIAL_CONTENT, streamInfo.mimeType, inputStream)
    }

    response.addHeader("Accept-Ranges", "bytes")
    if (fileSize > 0) {
      response.addHeader("Content-Range", "bytes $start-$rangeEnd/$fileSize")
    }
    return response
  }

  private fun handleFullRequest(session: IHTTPSession, streamInfo: StreamInfo): Response {
    if (streamInfo.fileSize < 0) {
      streamInfo.fileSize = runBlocking {
        if (!streamInfo.client.isConnected()) streamInfo.client.connect()
        streamInfo.client.getFileSize(streamInfo.filePath).getOrDefault(-1L)
      }
    }

    val inputStream = runBlocking {
      if (!streamInfo.client.isConnected()) streamInfo.client.connect()
      streamInfo.client.getFileStream(streamInfo.filePath, 0).getOrNull()
    } ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to open stream")

    val response = if (streamInfo.fileSize > 0) {
        newFixedLengthResponse(Response.Status.OK, streamInfo.mimeType, inputStream, streamInfo.fileSize)
    } else {
        newChunkedResponse(Response.Status.OK, streamInfo.mimeType, inputStream)
    }

    response.addHeader("Accept-Ranges", "bytes")
    return response
  }
}
