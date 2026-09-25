package app.marlboroadvance.mpvex.ui.browser.networkstreaming.clients

import android.util.Log
import app.marlboroadvance.mpvex.domain.network.NetworkConnection
import app.marlboroadvance.mpvex.domain.network.NetworkFile
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.transport.tcp.async.AsyncDirectTcpTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbClient(private val connection: NetworkConnection) : NetworkClient {
  companion object {
    private const val TAG = "SmbClient"

    @Volatile
    private var sharedSmbClient: SMBClient? = null

    // Bounds every SMB wait: the socket read timeout AND the deadline for connect/keepAlive/share
    // operations. A half-open session (the server or router silently killed an idle TCP while the
    // demuxer was reading purely from its cache) now surfaces an error in ~8s instead of pinning
    // the request thread for a full minute. Because a stalled read is a stall with zero bytes
    // arriving, this value also sets how much of mpv's demuxer cache a single drop can eat: every
    // second of it is a second of playback drained with nothing coming in.
    // Deliberately not tightened further — listing a large directory over a slow share must still
    // be allowed to finish.
    private const val SMB_TIMEOUT_MS = 8000L

    // How long a share may sit idle before the next operation proactively reconnects instead of
    // risking a block on a connection the server/router dropped while the player read purely from
    // its demuxer cache. Lowered from 25s, but it MUST stay above NetworkStreamingProxy's
    // KEEPALIVE_INTERVAL_MS (15s): the keepalive refreshes the idle timer through withShare, so a
    // threshold below the keepalive cadence would make every single keepalive tick judge itself
    // "idle too long" and tear down the session an active stream is reading from.
    private const val IDLE_RECONNECT_THRESHOLD_MS = 20000L

    /**
     * How many consecutive mid-stream read failures a committed HTTP body may repair before it is
     * allowed to end.
     *
     * This is the difference between "the cache quietly refills" and "the viewer has to reopen the
     * video". NanoHTTPD copies the body from our stream on its own thread, so if the stream throws,
     * the body simply ends — and mpv reads a finished body as end-of-file at whatever position it
     * happened to reach, then (with `keep-open`) pauses for good. Reconnecting in place and
     * continuing from the same offset keeps the body alive instead. Bounded so a share that is
     * genuinely gone still gives up rather than looping forever.
     */
    private const val STREAM_REOPEN_MAX_ATTEMPTS = 3

    /** Hard cap on one mid-stream repair (reconnect + reopen) so a reader can never hang. */
    private const val STREAM_REOPEN_TIMEOUT_MS = 12000L

    /**
     * Absolute cap on repairs for one response body — a backstop for a session that re-opens
     * successfully but never delivers data. Each iteration costs a real reconnect, so this cannot
     * spin; it only has to stop a repair marathon on a share that is effectively unusable. Hit
     * means the body ends, which hands playback to the premature-EOF recovery (a source reload),
     * so even this path recovers rather than freezing.
     */
    private const val STREAM_REOPEN_TOTAL_MAX = 60

    // Detached IO scope used to tear down dead/superseded connections off the caller's
    // thread: on a half-open socket smbj's graceful LOGOFF/tree-disconnect blocks until
    // SO_TIMEOUT, which would otherwise re-introduce the very freeze we're removing.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getOrCreateClient(): SMBClient {
      return sharedSmbClient ?: synchronized(this) {
        sharedSmbClient ?: run {
          val config = SmbConfig.builder()
            .withTransportLayerFactory(AsyncDirectTcpTransportFactory())
            .withTimeout(SMB_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .withSoTimeout(SMB_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .withReadBufferSize(1024 * 1024)
            .withWriteBufferSize(1024 * 1024)
            .withTransactBufferSize(1024 * 1024)
            .withDialects(
              com.hierynomus.mssmb2.SMB2Dialect.SMB_3_1_1,
              com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0_2,
              com.hierynomus.mssmb2.SMB2Dialect.SMB_3_0,
              com.hierynomus.mssmb2.SMB2Dialect.SMB_2_1,
              com.hierynomus.mssmb2.SMB2Dialect.SMB_2_0_2,
            )
            .withDfsEnabled(false)
            .withMultiProtocolNegotiate(true)
            .withSigningRequired(false)
            .withEncryptData(false)
            .build()
          SMBClient(config).also { sharedSmbClient = it }
        }
      }
    }
  }

  // Serializes connect()/reconnect() for THIS client instance only. It used to be a
  // single companion-wide mutex, so one stalled reconnect (e.g. the streaming proxy
  // recovering from an idle-dropped session) blocked EVERY other SMB client app-wide,
  // including the file browser — that is the "exiting to the folder also freezes"
  // symptom. Per-instance locking lets the browser stay responsive while the proxy
  // reconnects, and vice versa.
  private val connectMutex = Mutex()

  // Timestamp (ms) of the last successful share operation, used to detect a connection
  // that has gone stale during a long idle period. Volatile: read/written from both the
  // proxy's NanoHTTPD worker threads and the IO dispatcher.
  @Volatile
  private var lastSuccessfulIoMs: Long = 0L

  // Set when a streaming read fails or returns no data on a connection we still believe is
  // alive. Because the actual reads happen OUTSIDE [withShare] (the returned InputStream is
  // read by the proxy / mpv), a dropped session is otherwise invisible until the next
  // withShare call — which may never come for a long-lived stream. This forces that next
  // withShare to reconnect instead of trusting the (lying) isConnected() flag.
  @Volatile
  private var streamBroken = false

  // The response body currently being served by this client, if any: the only stream that can be
  // repaired from the outside (see [requestStreamRepair]). Owned by HealingStream itself — it
  // registers on construction and unregisters on close — so it always points at the live body
  // rather than at one mpv has already abandoned.
  @Volatile
  private var activeStream: HealingStream? = null

  private var smbConnection: Connection? = null
  private var session: Session? = null
  private var diskShare: DiskShare? = null
  private var baseUrl: String = ""
  private var resolvedHostIp: String = ""
  // The actual SMB share name (first path segment only). SMB shares cannot contain slashes.
  private var shareName: String = ""
  // Optional sub-directory within the share that this connection is rooted at (no leading/trailing slash).
  private var basePath: String = ""

  override suspend fun connect(): Result<Unit> =
    connectMutex.withLock {
      try {
        withTimeout(SMB_TIMEOUT_MS) {
          withContext(Dispatchers.IO) {
            try {
              if (isConnected()) return@withContext Result.success(Unit)
              // A previous half-open connection may still be lingering; tear it down first.
              disconnect()
              val client = getOrCreateClient()
              val resolvedAddress = try {
                withTimeout(5000) { java.net.InetAddress.getByName(connection.host) }
              } catch (e: Exception) {
                return@withContext Result.failure(Exception("Host not found: ${connection.host}"))
              }
              val hostForUrl = resolvedAddress.hostAddress ?: connection.host
              resolvedHostIp = hostForUrl
              // connection.path may be "share", "share/sub/dir", "/share/sub/", or use backslashes.
              // The SMB share is ONLY the first segment; the remainder is the rooted sub-directory.
              val normalizedPath = connection.path.replace('\\', '/').trim('/')
              shareName = normalizedPath.substringBefore('/')
              basePath = normalizedPath.substringAfter('/', "").trim('/')
              baseUrl = "smb://${hostForUrl}${if (connection.port != 445) ":${connection.port}" else ""}/${shareName}"
              smbConnection = client.connect(hostForUrl, connection.port)
              val authContext = if (connection.isAnonymous) AuthenticationContext.anonymous()
                                else AuthenticationContext(connection.username, connection.password.toCharArray(), null)
              session = smbConnection!!.authenticate(authContext)
              diskShare = session!!.connectShare(shareName) as DiskShare
              Result.success(Unit)
            } catch (e: Exception) {
              disconnect(); Result.failure(e)
            }
          }
        }
      } catch (e: TimeoutCancellationException) {
        disconnect()
        Result.failure(Exception("SMB connect timed out after ${SMB_TIMEOUT_MS}ms", e))
      }
    }

  override suspend fun disconnect() {
    // Null the references first, then close the old objects on a detached scope. A graceful
    // close on a half-open socket sends LOGOFF/tree-disconnect and blocks until SO_TIMEOUT;
    // doing it inline would freeze the caller (and any reconnect that runs through here).
    val oldShare = diskShare; val oldSession = session; val oldConnection = smbConnection
    diskShare = null; session = null; smbConnection = null
    ioScope.launch {
      try { oldShare?.close() } catch (_: Exception) {}
      try { oldSession?.close() } catch (_: Exception) {}
      try { oldConnection?.close() } catch (_: Exception) {}
    }
  }

  override fun isConnected(): Boolean =
    session != null && smbConnection?.isConnected == true && diskShare?.isConnected == true

  /**
   * Ensures a live connection, forcing a full reconnect if the previous session/share
   * has gone stale (e.g. the server dropped an idle SMB session, which surfaces as
   * STATUS_ACCESS_DENIED on subsequent operations).
   */
  private suspend fun reconnect(): Result<Unit> {
    disconnect()
    return connect()
  }

  /**
   * Lightweight liveness ping driven by the streaming proxy on a fixed interval. A cheap
   * metadata round-trip keeps the server/router from dropping the idle TCP while the player
   * reads from its demuxer cache, and — because it runs through [withShare] — transparently
   * re-establishes a dropped session in the BACKGROUND, so the next real read/seek/switch is
   * instant instead of blocking until the SMB timeout fires.
   */
  override suspend fun keepAlive() {
    withContext(Dispatchers.IO) {
      try {
        withTimeout(SMB_TIMEOUT_MS) {
          withShare { it.folderExists(basePath) }
        }
      } catch (e: TimeoutCancellationException) {
        // Surface the timeout as a regular failure so the caller marks the stream broken.
        throw Exception("SMB keepalive timed out after ${SMB_TIMEOUT_MS}ms", e)
      }
    }
  }

  /**
   * Runs an operation against the disk share, transparently reconnecting and retrying
   * once if the share/session has been invalidated by the server.
   */
  private suspend fun <T> withShare(block: (DiskShare) -> T): Result<T> {
    var lastError: Exception? = null
    for (attempt in 0..1) {
      try {
        // If the share has sat idle past the threshold, the connection may have been
        // silently dropped while playback ran from the demuxer cache. isConnected()
        // can't detect that (it only reads smbj's local flag), so force a reconnect
        // instead of blocking on a half-dead socket until the SMB timeout fires.
        val idleTooLong = lastSuccessfulIoMs != 0L &&
          System.currentTimeMillis() - lastSuccessfulIoMs > IDLE_RECONNECT_THRESHOLD_MS
        when {
          idleTooLong || streamBroken -> {
            streamBroken = false
            withTimeout(SMB_TIMEOUT_MS) { reconnect().getOrThrow() }
          }
          !isConnected() -> withTimeout(SMB_TIMEOUT_MS) {
            (if (attempt == 0) connect() else reconnect()).getOrThrow()
          }
        }
        val ds = diskShare ?: throw IllegalStateException("Not connected")
        val result = withTimeout(SMB_TIMEOUT_MS) { block(ds) }
        lastSuccessfulIoMs = System.currentTimeMillis()
        streamBroken = false
        return Result.success(result)
      } catch (e: Exception) {
        lastError = e
        streamBroken = true
        // Drop the stale connection so the next attempt establishes a fresh session.
        disconnect()
      }
    }
    return Result.failure(lastError ?: Exception("Not connected"))
  }

  private fun getRelativePath(path: String): String {
    return when {
      path.startsWith("smb://") -> {
        try {
          val uri = java.net.URI(path)
          val pathParts = uri.path.trim('/').split('/', limit = 2)
          pathParts.getOrNull(1) ?: ""
        } catch (e: Exception) {
          val pathAfterProtocol = path.substringAfter("smb://")
          val pathPart = pathAfterProtocol.substringAfter("/")
          val pathParts = pathPart.trim('/').split('/', limit = 2)
          pathParts.getOrNull(1) ?: ""
        }
      }
      else -> path.trim('/')
    }
  }

  /**
   * Resolves a browse path to a path relative to the connected share, substituting the
   * connection's rooted sub-directory ([basePath]) when browsing the connection root.
   */
  private fun resolveSharePath(path: String): String {
    val rel = getRelativePath(path)
    return if (rel.isEmpty()) basePath else rel
  }

  override suspend fun listFiles(path: String): Result<List<NetworkFile>> =
    withContext(Dispatchers.IO) {
      withShare { ds ->
        val relativePath = resolveSharePath(path)
        val rawFiles = ds.list(relativePath)
        rawFiles.mapNotNull { fileInfo ->
          val fileName = fileInfo.fileName
          if (fileName == "." || fileName == ".." || fileName.endsWith("$")) return@mapNotNull null
          val isDirectory = fileInfo.fileAttributes and 0x10 != 0L
          NetworkFile(
            name = fileName,
            path = if (relativePath.isEmpty()) "smb://${resolvedHostIp}/${shareName}/${fileName}"
                   else "smb://${resolvedHostIp}/${shareName}/${relativePath}/${fileName}",
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else fileInfo.endOfFile,
            lastModified = fileInfo.lastWriteTime.toEpochMillis(),
            mimeType = if (!isDirectory) getMimeType(fileName) else null,
          )
        }
      }
    }

  override suspend fun getFileSize(path: String): Result<Long> =
    withContext(Dispatchers.IO) {
      withShare { ds ->
        val file = ds.openFile(resolveSharePath(path), EnumSet.of(AccessMask.GENERIC_READ), null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN, null)
        val size = file.fileInformation.standardInformation.endOfFile
        file.close()
        size
      }
    }

  /**
   * Ask the live stream (if any) to rebuild its SMB session in place, WITHOUT ending the HTTP
   * response body.
   *
   * This is the gentle form of "reload the cache". It is called by the playback watchdog at the
   * moment the network layer stops producing bytes while the demuxer still wants data — i.e. while
   * the demuxer cache has started draining but is still far from empty. Only the SMB session
   * underneath is replaced: mpv's stream layer never notices, so there is no seek, no decoder
   * rebuild, no skipped content and no visible pause. A source reload (which is what used to be
   * the only remedy) restarts the whole pipeline; this does not touch playback at all.
   *
   * @return false when nothing is streaming through this client, in which case there is nothing
   *   to repair and the caller must not count an attempt.
   */
  fun requestStreamRepair(): Boolean = activeStream?.requestRepair() ?: false

  override suspend fun getFileStream(path: String, offset: Long): Result<InputStream> =
    withContext(Dispatchers.IO) {
      val relativePath = resolveSharePath(path)
      withShare { ds -> openReadOnlyFile(ds, relativePath) }.map { initialFile ->
        // The body of this HTTP response is committed to the client the moment serve() returns,
        // and NanoHTTPD copies it from this stream on its own thread. So if the stream throws, the
        // body ENDS — and mpv reads a finished body as end-of-file at whatever position it had
        // reached, reports a truncated file, and (with `keep-open`) pauses for good. It cannot
        // re-request a body it already finished, which is why a dropped SMB session used to be
        // terminal for the session.
        //
        // Repair the session in place and carry on from the same offset instead: the body never
        // ends early, mpv keeps filling its demuxer cache, and the viewer sees nothing at all.
        // This is the same capability the players that read SMB directly (Kodi/VLC) get for free
        // from their own I/O layer.
        val raw: InputStream = HealingStream(relativePath, offset, initialFile)
        // Wrap with 1MB buffer for high-speed sequential access (restores the dozens of MB/s).
        val buffered: InputStream = BufferedInputStream(raw, 1024 * 1024)
        buffered
      }
    }

  /**
   * The response body of one HTTP request: a seekable SMB reader that repairs its own session
   * rather than ending the body.
   *
   * Two things make it self-healing:
   *  - a failed or empty read reconnects and re-opens the file at [currentPosition], so bytes keep
   *    flowing and mpv never sees a truncated stream (a finished body reads as end-of-file, and
   *    with `keep-open` mpv then pauses for good — that was the permanent freeze);
   *  - [requestRepair] lets the playback watchdog force that repair *before* the demuxer cache is
   *    drained, which is what keeps the recovery invisible: mpv's stream layer never notices, so
   *    there is no seek, no decoder rebuild, no skipped content and nothing to see on screen.
   *
   * Only the most recently opened instance is treated as "live" ([activeStream]).
   */
  private inner class HealingStream(
    private val relativePath: String,
    offset: Long,
    initialFile: com.hierynomus.smbj.share.File,
  ) : InputStream() {
    private var currentPosition = offset
    @Volatile private var file: com.hierynomus.smbj.share.File = initialFile
    /** Consecutive failed repairs; bounds a share that is genuinely gone. */
    private var repairs = 0
    /** Hard backstop against a session that re-opens fine but never delivers data. */
    private var totalRepairs = 0
    @Volatile private var closed = false
    /** Set by [requestRepair] (playback watchdog thread), consumed by [read]. */
    @Volatile private var repairRequested = false

    init {
      activeStream = this
    }

    override fun read(): Int {
      val b = ByteArray(1)
      return if (read(b, 0, 1) == 1) b[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (closed) return -1
      while (true) {
        // Repair BEFORE reading, not after failing, when either
        //  - the watchdog asked for it (the network layer stopped producing bytes while mpv still
        //    wanted data: see NetworkStreamingProxy.requestStreamRepair), or
        //  - the session is already known to be gone (the keepalive tore it down), which turns
        //    "first read parks on a closed socket" into "reconnect, then read".
        // Either way only the SMB session underneath is replaced; the HTTP body lives on.
        repairIfNeeded()
        try {
          val read = file.read(b, currentPosition, off, len)
          if (read > 0) {
            currentPosition += read
            // These reads happen OUTSIDE withShare, so this is the only place that reflects
            // real streaming I/O. Refresh the idle timer (throttled) so the idle-reconnect
            // check never tears down a stream that is actively reading.
            val now = System.currentTimeMillis()
            if (now - lastSuccessfulIoMs > 1000L) lastSuccessfulIoMs = now
            return read
          }
          if (read < 0) return -1 // genuine end of file: let the body finish normally
          // read == 0: the session looks alive but no data is arriving. Treat it as a stall.
        } catch (e: Exception) {
          // The SMB session died mid-stream; fall through to the repair path below.
        }
        // Reached when a read failed or returned no data on a session that still claims to be
        // connected. Tell the control path so the NEXT withShare() also reconnects, then repair
        // in place and retry — the response body stays alive.
        streamBroken = true
        if (repairs >= STREAM_REOPEN_MAX_ATTEMPTS) {
          // Unrepairable even after repeated attempts: end the body. The premature-EOF
          // recovery in PlayerActivity then takes over as the last resort.
          throw IOException("SMB stream unrecoverable at offset $currentPosition ($relativePath)")
        }
        repair()
      }
    }

    /**
     * Rebuilds the session when [repairRequested] is set or the session is known to be gone.
     *
     * Bounded by the same consecutive-failure budget as the read path: a share that cannot be
     * re-established must end the body (and with it playback) rather than loop, so the
     * premature-EOF recovery can take over. A loop here would leave the response parked with no
     * bytes and no error — the freeze, only harder to see.
     */
    private fun repairIfNeeded() {
      val needed = repairRequested || !isConnected()
      repairRequested = false
      if (!needed) return
      if (repairs >= STREAM_REOPEN_MAX_ATTEMPTS) {
        throw IOException("SMB stream unrecoverable at offset $currentPosition ($relativePath)")
      }
      repair()
    }

    /**
     * Asks for a repair without blocking the caller.
     *
     * Called from the playback watchdog, whose probe thread must answer within
     * STALL_TICK_TIMEOUT_MS, so the blocking part is detached: closing the current file handle is
     * what unblocks a read already parked on a session the server silently dropped (such a read
     * otherwise waits out SO_TIMEOUT, and every second of that is a second of demuxer cache
     * consumed with nothing coming in). The reconnect itself is done by [read] on this stream's
     * own thread, which is the only thread allowed to replace [file].
     *
     * @return false when this stream is already closed, i.e. there is nothing to repair.
     */
    fun requestRepair(): Boolean {
      if (closed) return false
      repairRequested = true
      val stale = file
      ioScope.launch { runCatching { stale.close() } }
      return true
    }

    /**
     * Rebuilds the session and re-opens the file so the body can continue from
     * [currentPosition]. [read] is blocking, so the suspend reconnect is bridged with
     * runBlocking on the response's own thread.
     *
     * A repair that succeeds clears [repairs]: the give-up budget counts CONSECUTIVE failures
     * (a share that is really gone), not lifetime repairs. Counting lifetime repairs meant a long
     * film whose server reaps an idle session every few minutes exhausted the budget on healthy
     * recoveries and ended the body — the very freeze this class exists to prevent.
     */
    private fun repair() {
      if (totalRepairs >= STREAM_REOPEN_TOTAL_MAX) {
        throw IOException("SMB stream gave up after $totalRepairs repairs ($relativePath)")
      }
      totalRepairs++
      val repaired = runCatching {
        runBlocking {
          withTimeout(STREAM_REOPEN_TIMEOUT_MS) {
            val share = reopenStreamingShare().getOrThrow()
            val fresh = openReadOnlyFile(share, relativePath)
            val stale = file
            file = fresh
            runCatching { stale.close() }
          }
        }
      }.isSuccess
      if (repaired) repairs = 0 else repairs++
      Log.w(
        TAG,
        "stream repair at offset $currentPosition (ok=$repaired, " +
          "consecutive failures=$repairs, total=$totalRepairs)",
      )
    }

    override fun close() {
      closed = true
      if (activeStream === this) activeStream = null
      runCatching { file.close() }
    }
  }

  /** Opens [relativePath] read-only; shared by the first open and the mid-stream repair path. */
  private fun openReadOnlyFile(
    share: DiskShare,
    relativePath: String,
  ): com.hierynomus.smbj.share.File =
    share.openFile(
      relativePath,
      EnumSet.of(AccessMask.GENERIC_READ),
      null,
      EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
      SMB2CreateDisposition.FILE_OPEN,
      null,
    )

  /**
   * Tears the dead session down and builds a fresh one so a live HTTP body that lost its session
   * can keep reading from where it stopped. The caller turns a failure into a give-up, which ends
   * the body.
   */
  private suspend fun reopenStreamingShare(): Result<DiskShare> =
    try {
      disconnect()
      withTimeout(SMB_TIMEOUT_MS) { connect().getOrThrow() }
      val share = diskShare ?: throw IllegalStateException("SMB not connected after reopen")
      Result.success(share)
    } catch (e: Exception) {
      Result.failure(e)
    }

  override suspend fun deleteFile(path: String): Result<Unit> =
    withContext(Dispatchers.IO) {
      withShare { ds ->
        val relativePath = resolveSharePath(path)
        val isDirectory = ds.folderExists(relativePath)
        deleteRecursive(ds, relativePath, isDirectory)
      }
    }

  private fun deleteRecursive(ds: DiskShare, relativePath: String, isDirectory: Boolean) {
    // Clear attributes to normal first (helpful for Read-only items)
    try {
      // 0x80L is FILE_ATTRIBUTE_NORMAL
      ds.setFileInformation(relativePath, FileBasicInformation(null, null, null, null, 0x80L))
    } catch (_: Exception) {}

    if (isDirectory) {
      ds.list(relativePath).forEach { fileInfo ->
        val name = fileInfo.fileName
        if (name != "." && name != "..") {
          val subPath = if (relativePath.isEmpty()) name else "$relativePath\\$name"
          val isSubDir = (fileInfo.fileAttributes and 0x10L) != 0L
          deleteRecursive(ds, subPath, isSubDir)
        }
      }
      ds.rmdir(relativePath, false) // false because we already cleared children
    } else {
      ds.rm(relativePath)
    }
  }

  private fun getMimeType(fileName: String): String? {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    return when (extension) {
      "mp4", "m4v" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "avi" -> "video/x-msvideo"
      "mov" -> "video/quicktime"
      "wmv" -> "video/x-ms-wmv"
      "flv" -> "video/x-flv"
      "webm" -> "video/webm"
      "mpeg", "mpg" -> "video/mpeg"
      "3gp" -> "video/3gpp"
      "ts" -> "video/mp2t"
      else -> null
    }
  }
}
