package app.marlboroadvance.mpvex.ui.player

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import app.marlboroadvance.mpvex.R
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.marlboroadvance.mpvex.database.entities.NetworkTrackProfileEntity
import app.marlboroadvance.mpvex.database.entities.PlaybackStateEntity
import app.marlboroadvance.mpvex.database.repository.NetworkTrackProfileRepository
import app.marlboroadvance.mpvex.databinding.PlayerLayoutBinding
import app.marlboroadvance.mpvex.domain.playbackstate.repository.PlaybackStateRepository
import app.marlboroadvance.mpvex.preferences.AdvancedPreferences
import app.marlboroadvance.mpvex.preferences.AudioPreferences
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.preferences.CustomButtonRuntime
import app.marlboroadvance.mpvex.preferences.DecoderPreferences
import app.marlboroadvance.mpvex.preferences.PlayerPreferences
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.controls.PlayerControls
import app.marlboroadvance.mpvex.ui.theme.MpvexTheme
import app.marlboroadvance.mpvex.utils.ScriptRepository
import app.marlboroadvance.mpvex.utils.history.RecentlyPlayedOps
import app.marlboroadvance.mpvex.utils.media.HttpUtils
import app.marlboroadvance.mpvex.utils.media.NetworkMediaIdUtils
import app.marlboroadvance.mpvex.utils.media.SubtitleOps
import app.marlboroadvance.mpvex.utils.storage.FileTypeUtils
import app.marlboroadvance.mpvex.utils.storage.FileFilterUtils
import com.github.k1rakishou.fsaf.FileManager
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Main player activity that handles video playback using the MPV library.
 *
 * This activity manages:
 * - Video playback using MPV library
 * - System UI visibility (immersive mode)
 * - Audio focus management
 * - Picture-in-Picture (PiP) mode
 * - Background playback service
 * - MediaSession for external controls (Android Auto, Bluetooth, etc.)
 * - Playback state persistence and restoration
 * - Subtitle and audio track management
 * - Hardware key event handling
 *
 * @see PlayerViewModel for UI state management
 * @see MediaPlaybackService for background playback functionality
 */
@Suppress("TooManyFunctions", "LargeClass")
class PlayerActivity :
  AppCompatActivity(),
  PlayerHost {
  // ==================== ViewModels and Bindings ====================

  /**
   * View model for managing player UI state.
   */
  private val viewModel: PlayerViewModel by viewModels<PlayerViewModel> {
    PlayerViewModelProviderFactory(this)
  }

  /**
   * Binding for the player layout.
   */
  private val binding by lazy { PlayerLayoutBinding.inflate(layoutInflater) }

  /**
   * Observer for MPV events.
   */
  private val playerObserver by lazy { PlayerObserver(this) }

  // ==================== Dependency Injection ====================

  /**
   * Repository for managing playback state.
   */
  private val playbackStateRepository: PlaybackStateRepository by inject()

  /**
   * Repository for managing playlists.
   */
  private val playlistRepository: app.marlboroadvance.mpvex.database.repository.PlaylistRepository by inject()

  /**
   * Repository for persisting per-directory network track preferences.
   */
  private val networkTrackProfileRepository: NetworkTrackProfileRepository by inject()

  /**
   * Preferences for player settings.
   */
  private val playerPreferences: PlayerPreferences by inject()

  /**
   * Preferences for audio settings.
   */
  private val audioPreferences: AudioPreferences by inject()

  /**
   * Preferences for subtitle settings.
   */
  private val subtitlesPreferences: SubtitlesPreferences by inject()

  /**
   * Preferences for advanced settings.
   */
  private val advancedPreferences: AdvancedPreferences by inject()

  /**
   * Preferences for browser settings.
   */
  private val browserPreferences: BrowserPreferences by inject()

  /**
   * Preferences for decoder/rendering settings.
   */
  private val decoderPreferences: DecoderPreferences by inject()

  /**
   * Repository for managing network connections.
   */
  private val networkRepository: app.marlboroadvance.mpvex.repository.NetworkRepository by inject()

  /**
   * Manager for file operations.
   */
  private val fileManager: FileManager by inject()

  /**
   * Track selector for automatic audio/subtitle selection
   */
  private val trackSelector: TrackSelector by lazy {
    TrackSelector(audioPreferences, subtitlesPreferences)
  }

  // ==================== Views ====================

  /**
   * The MPV player view.
   */
  val player by lazy { binding.player }

  // ==================== State Management ====================

  /**
   * Current video file name being played.
   */
  private var fileName = ""

  /**
   * Unique identifier for the current media, used for saving/loading playback state.
   * For network streams, this includes a hash of the URI to ensure uniqueness.
   */
  private var mediaIdentifier = ""
  // Cached share root for the active network connection (e.g. smb://host/share).
  // Populated asynchronously from the connection config; until it's ready we treat
  // network media as NOT being in the source root so skipping stays enabled.
  private var networkShareRoot: String? = null
  // Set when we auto-advance to the next playlist item, so handleFileLoaded can
  // make sure the freshly loaded file actually starts playing (SMB/network streams
  // can otherwise stay paused after loadfile).
  private var shouldResumeAfterLoad = false
  // Deferred resume-seek target (seconds). Set when a saved playback position is
  // loaded; applied on the first MPV_EVENT_PLAYBACK_RESTART after the file loads
  // because setting time-pos synchronously inside handleFileLoaded races with mpv
  // resetting the timeline to 0, which made resume-from-position never take effect.
  private var pendingResumeSeek: Int? = null
  private var resumeSeekApplied = false

  // ==================== Auto skip intro / outro ====================
  // All three are per-file and reset in handleFileLoaded(), so every episode is evaluated
  // exactly once at the start (intro) and once after the 95% mark (outro).

  /** Set once the intro check has run for this file; prevents re-skipping on later seeks. */
  private var introSkipApplied = false

  /**
   * Media identifier of the file the intro was last evaluated for. Used to re-arm the
   * once-per-file intro guard when a *new* file starts, so the intro is re-evaluated for
   * every episode (including auto-played ones) even if the FILE_LOADED reset is missed.
   */
  private var lastIntroSkipMediaId = ""

  // Audio/subtitle track selected right before a pause. Some TV firmwares (e.g. TCL/MStar)
  // drop the active track across the pause/resume cycle, leaving playback silent until the
  // user re-picks the track. We snapshot the selection here and restore it on resume.
  private var audioTrackBeforePause = -1
  private var subTrackBeforePause = -1

  /** Set once the outro has triggered (or been ruled out) for this file. */
  private var outroSkipTriggered = false

  /**
   * Playlist of URIs for sequential playback
   */
  internal var playlist: List<Uri> = emptyList()

  /**
   * Current index in the playlist
   */
  internal var playlistIndex: Int = 0

  /**
   * Shuffled order of playlist indices (when shuffle is enabled)
   */
  private var shuffledIndices: List<Int> = emptyList()

  /**
   * Current position in shuffled playlist (when shuffle is enabled)
   */
  private var shuffledPosition: Int = 0

  /**
   * Playlist ID for tracking play history (optional, only for custom playlists)
   */
  private var playlistId: Int? = null

  /**
   * Tracks the starting offset of the loaded playlist window in the full playlist.
   * Used for windowed loading to prevent ANR with large playlists.
   */
  private var playlistWindowOffset: Int = 0

  /**
   * Total count of items in the full playlist (when using windowed loading).
   * -1 means unknown or not using windowed loading.
   */
  var playlistTotalCount: Int = -1
    private set

  /**
   * Indicates whether the current playlist is an M3U playlist sourced from database.
   * Used to skip thumbnail/metadata extraction for network streams.
   */
  private var isM3uPlaylist: Boolean = false

  /**
   * Title mapping for remote/network playlist items.
   */
  private val playlistTitles = java.util.concurrent.ConcurrentHashMap<Uri, String>()

  /**
   * Original network file path mapping for remote/network playlist items.
   */
  private val playlistNetworkFilePaths = java.util.concurrent.ConcurrentHashMap<Uri, String>()

  /**
   * Stream IDs registered with NetworkStreamingProxy that need to be cleaned up.
   */
  private val registeredStreamIds = java.util.concurrent.CopyOnWriteArrayList<String>()

  /**
   * Helper for managing Picture-in-Picture mode.
   */
  private lateinit var pipHelper: MPVPipHelper

  private var isReady = false // Single flag: true when video loaded and ready
  private var isUserFinishing = false
  // Back-key behavior: window (ms) within which a second back press exits the player
  private val backPressExitWindowMs = 2000L
  private var lastBackArmTime = 0L
  private var isManualBackgroundPlayback = false // Track manual background playback trigger
  private var noisyReceiverRegistered = false
  private var mpvInitialized = false // Track MPV initialization state

  // ----- Playback stall watchdog (mediacodec_embed random-hang recovery) -----
  // Random black-screen freeze on low-RAM TV boxes (e.g. TCL Android 9, 3GB): the
  // video freezes, the app cannot be exited, and memory is fine (~60% at death) —
  // mpv's event loop is stuck in the mediacodec_embed / MediaCodec path so even
  // MPVLib.destroy() cannot run. This watchdog polls time-pos; if time-pos has not
  // advanced for STALL_THRESHOLD_MS it nudges the decoder and, failing that, reloads
  // the source — spaced by a cooldown and capped, so a hard deadlock is never turned
  // into a thrash loop.
  //
  // Every recovery step here is deliberately NON-DESTRUCTIVE: if it does not help, the
  // viewer must not be able to tell that it ran. Rebuilding the VO (vo=null back to
  // mediacodec_embed) and cycling `vid` no/auto are gone for good — on the TCL TV those
  // two permanently desynced audio, video and subtitles, and a recovery that breaks
  // playback is worse than the freeze it was meant to cure.
  private var stallWatchdogJob: Job? = null
  private var lastProgressTimePos = 0.0
  private var lastProgressMs = 0L
  private var lastRecoveryMs = 0L
  private var stallAttempts = 0
  /** Consecutive watchdog probes that mpv failed to answer inside the tick timeout. */
  private var stallWedgedTimeouts = 0
  /** Set once the "recovery gave up" notice has been shown for the current stall episode. */
  private var stallGaveUpNotified = false
  /** How many times we have rebuilt/reloaded the source for the current stall episode. */
  private var stallRebuilds = 0
  /**
   * True while [reloadCurrentMediaFrom] is re-opening the CURRENT source as a recovery. Such a
   * reload raises MPV_EVENT_FILE_LOADED like any other load, so [rebaseStallWatchdogForNewFile]
   * needs to be told the difference: a recovery reload must keep counting against
   * [STALL_REBUILD_MAX] instead of being treated as a brand-new file and resetting the budget.
   *
   * Written from the recovery coroutine and read from mpv's event thread, hence @Volatile.
   */
  @Volatile
  private var recoveryReloadPending = false
  /**
   * Set as soon as the CURRENT file has advanced at all. Until it is set a frozen `time-pos`
   * means "mpv has not shown a frame for this file yet", not "playback stalled": opening a
   * stream (SMB through the localhost proxy, or a slow local file) routinely takes tens of
   * seconds, and judging a stall during that window sent files that were merely still opening
   * down the recovery ladder.
   */
  private var hasProgressedSinceLoad = false
  // Wedge count kept on the companion object (mpvRebuildCount) below, not here.
  private var lastHeartbeatMs = 0L
  /**
   * The watchdog probes mpv through JNI. If the mpv core wedges, that probe itself blocks
   * and the watchdog silently dies — which is exactly how the "buffer drains to 0 and never
   * recovers" freeze went undetected. Every tick therefore runs on a dedicated thread with a
   * hard timeout; a timeout means "mpv is not answering" and is logged and escalated.
   */
  @Volatile
  private var watchdogExecutor: ExecutorService = Executors.newSingleThreadExecutor()
  private val STALL_WATCHDOG_INTERVAL_MS = 2000L
  // A stall must outlast a slow SMB read plus the `cache-pause` window. 10s tripped on
  // ordinary network jitter and turned a hiccup into a recovery; 20s does not.
  private val STALL_THRESHOLD_MS = 20_000L
  private val STALL_RECOVERY_COOLDOWN_MS = 12_000L
  private val STALL_MAX_ATTEMPTS = 4              // 1 nudge, 2/3 reload source, 4 give up
  private val STALL_REBUILD_MAX = 2               // source rebuilds per stall episode
  private val STALL_ROUND_RESET_MS = 120_000L     // after a long quiet round, allow a fresh ladder
  // How long playback must run healthily before the source-rebuild budget counts as fresh
  // again. Without this the budget was "rebuilds per loaded file", which is wrong for the
  // long NAS files this player exists for: a 4-hour SMB movie whose server drops the session
  // every ~30 minutes needs far more than two rebuilds, so the third drop gave up for good
  // ("自动恢复未成功，请按播放键或重新打开该视频") on a perfectly good file.
  private val STALL_BUDGET_REFRESH_MS = 60_000L
  // The probe timeout must be generous: a busy demuxer (opening a large SMB file) can take
  // seconds to answer without being wedged.
  private val STALL_TICK_TIMEOUT_MS = 6_000L
  /** Escalating recreates the Activity (restarts playback), so require repeated timeouts. */
  private val STALL_WEDGED_TIMEOUTS = 2
  private val STALL_HEARTBEAT_MS = 30_000L
  // ---- Demuxer cache guard: repair the network session BEFORE the buffer runs dry ----------
  //
  // Signals taken from what mpv actually exposes (verified in player/command.c):
  //  - `cache-speed` (int64, bytes/s over a 1s window) is the read rate between the cache and the
  //    lower (network) layer; mpv documents it as the same thing as
  //    demuxer-cache-state/raw-input-rate. It is the one signal that says "bytes are arriving"
  //    rather than "the buffer happens to be large".
  //  - `demuxer-cache-idle` means "the cache is filled to the requested amount and mpv is NOT
  //    reading more data". That is the healthy sawtooth: mpv stops reading on purpose once the
  //    buffer target is reached, and from then on the cache shrinks from consumption alone. Never
  //    act on that.
  //  - A dead reader (session reaped by the NAS or router, read parked on a half-open socket)
  //    shows the opposite: not idle (mpv still wants data) with a ~zero input rate.
  //  - `demuxer-cache-duration` / `demuxer-cache-time` are only logged, never decided on: mpv's own
  //    manual calls the duration guess "very unreliable" and "often not available at all".
  /** Input rate at or below which the network layer counts as producing nothing. */
  private val CACHE_GUARD_STALL_BPS = 64 * 1024
  /** How long the input rate must stay there (while mpv still wants data) before repairing. */
  private val CACHE_GUARD_STAGNANT_MS = 8_000L
  /** Minimum gap between proactive transport repairs. */
  private val CACHE_GUARD_COOLDOWN_MS = 30_000L
  /** Repairs allowed in one burst; a long healthy stretch makes the budget fresh again. */
  private val CACHE_GUARD_MAX = 6
  private val CACHE_GUARD_BUDGET_REFRESH_MS = 300_000L
  /** Since when the input rate has been stagnant, or 0 when it is healthy. */
  private var cacheStagnantSinceMs = 0L
  private var lastCacheGuardMs = 0L
  private var cacheGuardRepairs = 0
  private var cacheGuardExhaustedLogged = false
  private var savePlaybackStateJob: kotlinx.coroutines.Job? = null // Track ongoing save job
  private var savePlaybackStateJobIdentifier: String? = null // Media identifier the ongoing save belongs to
  private var audioFocusActive = false // Whether we currently hold audio focus
  private var wasPlayingBeforePause = false // Track if video was playing before pause

  /**
   * Custom CoroutineScope for operations that must survive Activity lifecycle cancellation (e.g. saving state).
   */
  private val playerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

  // ==================== Background Playback ====================

  /**
   * Reference to the background playback service.
   */
  private var mediaPlaybackService: MediaPlaybackService? = null

  /**
   * Tracks whether we're currently bound to the background playback service.
   */
  private var serviceBound = false

  // ==================== MediaSession ====================

  /**
   * MediaSession for integration with system media controls, Android Auto, and Wear OS.
   */
  private lateinit var mediaSession: MediaSession

  /**
   * Tracks whether MediaSession has been successfully initialized.
   */
  private var mediaSessionInitialized = false

  /**
   * Builder for MediaSession playback states.
   */
  private lateinit var playbackStateBuilder: PlaybackState.Builder

  // ==================== Audio Focus ====================

  /**
   * Audio focus request for API 26+.
   */
  private var audioFocusRequest: AudioFocusRequest? = null

  /**
   * Callback to restore audio focus after it's been lost and regained.
   */
  private var restoreAudioFocus: () -> Unit = {}

  // ==================== Broadcast Receivers ====================

  /**
   * Receiver for handling noisy audio events.
   */
  private val noisyReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(
        context: Context?,
        intent: Intent?,
      ) {
        if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
          viewModel.pause()
          window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
      }
    }

  /**
   * Listener for audio focus changes.
   */
  private val audioFocusChangeListener =
    AudioManager.OnAudioFocusChangeListener { focusChange ->
      when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
          -> {
          // Save current state to restore later
          val oldRestore = restoreAudioFocus
          val wasPlayerPaused = viewModel.paused ?: false
          viewModel.pause()
          restoreAudioFocus = {
            oldRestore()
            if (!wasPlayerPaused) viewModel.unpause()
          }
        }

        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
          // Lower volume temporarily
          MPVLib.command("multiply", "volume", "0.5")
          restoreAudioFocus = {
            MPVLib.command("multiply", "volume", "2")
          }
        }

        AudioManager.AUDIOFOCUS_GAIN -> {
          // Restore previous audio state
          restoreAudioFocus()
          restoreAudioFocus = {}
        }

        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
          Log.d(TAG, "Audio focus request failed")
        }
      }
    }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    setContentView(binding.root)

    // OPTIMIZATION: Set volume control stream so hardware buttons control media volume
    volumeControlStream = AudioManager.STREAM_MUSIC

    setupMPV()
    MediaPlaybackService.createNotificationChannel(this)
    setupAudio()
    setupBackPressHandler()
    setupPlayerControls()
    setupPipHelper()
    setupMediaSession()

    playlistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
    playlistIndex = intent.getIntExtra("playlist_index", 0)

    // Load playlist from intent extras first (fast path - backward compatibility)
    playlist = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    // If playlist is empty but playlist_id is provided, load asynchronously from database
    // Load all items - LazyColumn handles pagination/virtualization efficiently
    if (playlist.isEmpty() && playlistId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          // Check if this is an M3U playlist
          val playlistEntity = playlistRepository.getPlaylistById(pid)
          isM3uPlaylist = playlistEntity?.isM3uPlaylist ?: false

          // Load all items - LazyColumn will handle virtualization/pagination efficiently
          val items = playlistRepository.getPlaylistItemsAsUris(pid)
          val totalCount = items.size

          withContext(Dispatchers.Main) {
            playlist = items
            playlistWindowOffset = 0
            playlistTotalCount = totalCount
            Log.d(TAG, "Loaded all $totalCount items from playlist $pid (isM3U: $isM3uPlaylist)")
            // Re-initialize shuffle now that playlist is available
            if (viewModel.shuffleEnabled.value) {
              onShuffleToggled(true)
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to load playlist from database", e)
        }
      }
    }

    val dataUri = intent.data ?: intent.getStringExtra("uri")?.toUri()
    val isMpvnas = dataUri?.scheme == "mpvnas"

    if (isMpvnas) {
      intent.data = dataUri
      lifecycleScope.launch(Dispatchers.Main) {
        val traceId = "launch_${System.currentTimeMillis()}_${dataUri?.hashCode()}"
        val launchStartedAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "SMB trace[$traceId] onCreate mpvnas launch start uri=$dataUri")
        val resolvedUri = withContext(Dispatchers.IO) {
          dataUri?.let { resolveMpvnasUri(it) }
        }
        if (resolvedUri != null) {
          intent.data = resolvedUri
          
          fileName = getFileName(intent)
          if (fileName.isBlank()) {
            fileName = intent.data?.lastPathSegment ?: "Unknown Video"
          }
          mediaIdentifier = getMediaIdentifier(intent, fileName)

          if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
            val networkFilePath = intent.getStringExtra("network_file_path")
            val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)
            if (networkFilePath != null && networkConnectionId != -1L) {
              generatePlaylistFromNetworkFolder(networkConnectionId, networkFilePath)
            }
          }

          setHttpHeadersFromExtras(intent.extras)
          val playableUri = getPlayableUri(intent)
          Log.d(
            TAG,
            "SMB trace[$traceId] onCreate resolved playableUri=$playableUri totalBeforePlay=${SystemClock.elapsedRealtime() - launchStartedAt}ms",
          )
          playableUri?.let { uri ->
            Log.d(TAG, "MPV dispatch[player.playFile] uri=$uri")
            player.playFile(uri)
          }
        } else {
          Log.e(TAG, "Failed to resolve mpvnas URI: ${intent.data}")
          finish()
        }
      }
    } else {
      // Only auto-generate playlist from folder if playlist mode is enabled and no playlist_id
      if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
        val path = parsePathFromIntent(intent)
        val networkFilePath = intent.getStringExtra("network_file_path")
        val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

        if (networkFilePath != null && networkConnectionId != -1L) {
          generatePlaylistFromNetworkFolder(networkConnectionId, networkFilePath)
        } else if (path != null) {
          generatePlaylistFromFolder(path)
        }
      }

      // Extract fileName early so it's available when video loads
      fileName = getFileName(intent)
      if (fileName.isBlank()) {
        fileName = intent.data?.lastPathSegment ?: "Unknown Video"
      }
      mediaIdentifier = getMediaIdentifier(intent, fileName)

      // Set HTTP headers (including referer) BEFORE playing the file
      setHttpHeadersFromExtras(intent.extras)

      getPlayableUri(intent)?.let(player::playFile)
    }

    // Only set orientation immediately if NOT in Video mode
    // For Video mode, wait for video-params/aspect to become available
    if (playerPreferences.orientation.get() != PlayerOrientation.Video) {
      setOrientation()
    }

    // Apply persisted shuffle state after playlist is loaded
    viewModel.applyPersistedShuffleState()

    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
  }

  override fun attachBaseContext(newBase: Context?) {
    if (newBase == null) {
      super.attachBaseContext(null)
      return
    }

    val originalConfiguration = newBase.resources.configuration
    val contextToUse =
      if (originalConfiguration.fontScale == 1f) {
        newBase
      } else {
        val updatedConfiguration = Configuration(originalConfiguration).apply { fontScale = 1f }
        val configurationContext = newBase.createConfigurationContext(updatedConfiguration)
        val configurationDisplayMetrics = configurationContext.resources.displayMetrics
        configurationDisplayMetrics.scaledDensity = updatedConfiguration.fontScale * configurationDisplayMetrics.density
        configurationContext
      }

    super.attachBaseContext(contextToUse)
  }

  private fun setupBackPressHandler() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        @RequiresApi(Build.VERSION_CODES.P)
        override fun handleOnBackPressed() {
          handleBackPress()
        }
      },
    )
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun handleBackPress() {
    // Dismiss overlays first
    if (viewModel.sheetShown.value != Sheets.None) {
      viewModel.sheetShown.update { Sheets.None }
      viewModel.showControls()
      return
    }

    if (viewModel.panelShown.value != Panels.None) {
      viewModel.panelShown.update { Panels.None }
      viewModel.showControls()
      return
    }

    // Check if auto PIP is enabled - enter PIP mode instead of finishing
    if (playerPreferences.autoPiPOnNavigation.get() && isReady) {
      pipHelper.enterPipMode()
      return
    }

    val clearUiOnBack = playerPreferences.clearUiOnBackPress.get()
    val exitOnDoubleBack = playerPreferences.exitOnDoubleBackPress.get()
    val uiVisible = viewModel.controlsShown.value
    val now = System.currentTimeMillis()

    // Step 1: the first back press clears the UI (hides player controls) when enabled
    if (clearUiOnBack && uiVisible) {
      viewModel.hideControls()
      lastBackArmTime = now
      return
    }

    // UI is already hidden (or clear-UI is disabled): decide how to exit
    if (!exitOnDoubleBack) {
      isUserFinishing = true
      finish()
      return
    }

    // Double-back-to-exit: a second back press inside the window exits
    if (now - lastBackArmTime <= backPressExitWindowMs) {
      isUserFinishing = true
      finish()
    } else {
      lastBackArmTime = now
      android.widget.Toast.makeText(
        this,
        R.string.toast_press_back_again_to_exit,
        android.widget.Toast.LENGTH_SHORT,
      ).show()
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupPlayerControls() {
    binding.controls.setContent {
      MpvexTheme {
        PlayerControls(
          viewModel = viewModel,
          onBackPress = {
            isUserFinishing = true
            finish()
          },
          modifier = Modifier,
        )
      }
    }
  }

  /**
   * Initializes the Picture-in-Picture helper.
   */
  private fun setupPipHelper() {
    pipHelper = MPVPipHelper(activity = this, mpvView = player)
  }

  private fun setupAudio() {
    audioPreferences.audioChannels.get().let {
      runCatching {
        MPVLib.setPropertyString(it.property, it.value)
      }.onFailure { e ->
        Log.e(TAG, "Error setting audio channels: ${it.property}=${it.value}", e)
      }
    }

    if (!serviceBound) {
      audioFocusRequest =
        AudioFocusRequest
          .Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(
            AudioAttributes
              .Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
              .build(),
          ).setOnAudioFocusChangeListener(audioFocusChangeListener)
          .setAcceptsDelayedFocusGain(true)
          .setWillPauseWhenDucked(true)
          .build()
      requestAudioFocus()
    }
  }

  /**
   * @return true if audio focus was granted immediately, false otherwise
   */
  override fun requestAudioFocus(): Boolean {
    val req = audioFocusRequest ?: return false
    val result = audioManager.requestAudioFocus(req)
    return when (result) {
      AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
        restoreAudioFocus = {}
        audioFocusActive = true
        true
      }

      AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
        restoreAudioFocus = { requestAudioFocus() }
        false
      }

      else -> {
        restoreAudioFocus = {}
        false
      }
    }
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    // Enter PIP mode when user presses home button if auto PIP is enabled
    if (playerPreferences.autoPiPOnNavigation.get() && isReady && !isFinishing) {
      pipHelper.enterPipMode()
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onDestroy() {
    Log.d(TAG, "PlayerActivity onDestroy")

    if (registeredStreamIds.isNotEmpty()) {
      val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance()
      registeredStreamIds.forEach { proxy.unregisterStream(it) }
      registeredStreamIds.clear()
    }

    runCatching {
      // OPTIMIZATION: Prevent any further UI updates or callbacks
      isReady = false

      // Only stop the service if we're not doing manual background playback
      if ((isUserFinishing || isFinishing) && !isManualBackgroundPlayback) {
        if (serviceBound) {
          runCatching { unbindService(serviceConnection) }
          serviceBound = false
        }
        stopService(Intent(this, MediaPlaybackService::class.java))
        mediaPlaybackService = null
      }

      // Wait for any pending save operation to complete before destroying MPV
      // This prevents the race condition where the save coroutine tries to access
      // MPV properties after MPVLib.destroy() has been called
      savePlaybackStateJob?.let { job ->
        Log.d(TAG, "Waiting for save playback state job to complete...")
        runCatching {
          // Wait for the save to finish, but bound it so a slow network/DB write can't
          // ANR the main thread during teardown.
          kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(3000) { job.join() }
          }
        }
        Log.d(TAG, "Save playback state job completed")
      }

      playerScope.cancel()

      cleanupMPV()
      cleanupAudio()
      cleanupReceivers()
      releaseMediaSession()
    }.onFailure { e ->
      Log.e(TAG, "Error during onDestroy", e)
    }

    super.onDestroy()
  }

  private fun cleanupMPV() {
    stopStallWatchdog()
    if (!mpvInitialized) return

    player.isExiting = true

    // Stop media notification service when activity is destroyed
    endBackgroundPlayback()

    // Don't cleanup MPV if we're doing manual background playback
    if (!isFinishing || isManualBackgroundPlayback) return

    runCatching {
      MPVLib.removeObserver(playerObserver)

      if (isReady) {
        // Pause playback first to reduce thread activity
        MPVLib.setPropertyBoolean("pause", true)

        // Send quit command to gracefully shut down MPV
        MPVLib.command("quit")

        // Wait briefly for MPV to process quit and clean up internal threads
        // This prevents race conditions where hardware UI threads try to access
        // mutexes/queues that are destroyed by MPVLib.destroy()
        // We use a short blocking wait here as onDestroy is already on the main thread
        // and this ensures proper cleanup before activity destruction
        Thread.sleep(100)

        // Force-release the video output so the mediacodec_embed VO tears down its
        // MediaCodec synchronously, instead of leaving the release to run inside destroy()
        // while the VO thread is being torn down. Rationale: the viewer reports a black
        // picture when a video is re-opened (and even after leaving and re-entering the app),
        // which points at a device-level MediaCodec that was never returned - MediaCodec
        // instances are a small, device-wide pool, and each session that ends while wedged can
        // leak one. This is a teardown-only mitigation for that black screen; it is NOT the
        // cause of, and does not affect, the flicker seen while a file is opening.
        runCatching {
          MPVLib.setPropertyString("vo", "null")
          MPVLib.detachOsdSurface()
          MPVLib.detachSurface()
        }
        // Give mpv's VO thread time to actually release the codec before we destroy the
        // native context out from under it.
        Thread.sleep(400)
      }

      // Now safe to destroy MPV as internal threads have had time to shut down
      MPVLib.destroy()
      // The MPVLib property StateFlows are process-level singletons that are lazily
      // observed once and never re-observed. Drop them so the next player session
      // re-registers observeProperty against the fresh native instance instead of
      // sticking at the previous file's end state (decoder/speed locked, seekbar at end).
      MPVLib.clearPropertyFlows()
      mpvInitialized = false
    }.onFailure { e ->
      Log.e(TAG, "Error cleaning up MPV", e)
    }
  }

  // ------------------------------------------------------------------
  // Playback stall watchdog (mediacodec_embed random-hang recovery)
  // ------------------------------------------------------------------

  private fun startStallWatchdog() {
    if (!mpvInitialized) return
    stallWatchdogJob?.cancel()
    lastProgressTimePos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
    lastProgressMs = System.currentTimeMillis()
    stallAttempts = 0
    stallRebuilds = 0
    stallWedgedTimeouts = 0
    stallGaveUpNotified = false
    stallWatchdogJob = playerScope.launch {
      while (isActive) {
        delay(STALL_WATCHDOG_INTERVAL_MS)
        // Run the probe on a dedicated thread with a hard timeout: if the mpv core wedges,
        // the JNI property query blocks forever and a plain call here would kill the
        // watchdog silently (exactly the failure mode seen on the TCL TV).
        val tick =
          runCatching {
            watchdogExecutor.submit {
              runCatching { tickStallWatchdog() }
                .onFailure { Log.w(TAG, "stall watchdog tick threw", it) }
            }
          }.getOrNull() ?: continue
        val wedged =
          try {
            tick.get(STALL_TICK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            false
          } catch (e: TimeoutException) {
            true
          } catch (e: Exception) {
            Log.w(TAG, "stall watchdog: probe failed", e)
            false
          }
        if (wedged) {
          tick.cancel(true)
          // That thread is stuck inside libmpv and can never be reused.
          watchdogExecutor.shutdownNow()
          watchdogExecutor = Executors.newSingleThreadExecutor()
          stallWedgedTimeouts++
          Log.w(
            TAG,
            "stall watchdog: mpv did not answer within ${STALL_TICK_TIMEOUT_MS}ms " +
              "($stallWedgedTimeouts/$STALL_WEDGED_TIMEOUTS consecutive) - core may be wedged",
          )
          // One unanswered probe can be nothing more than a busy demuxer (slow SMB open).
          // Escalating recreates the Activity, which restarts playback from the saved
          // position, so require repeated timeouts before paying that price.
          if (stallWedgedTimeouts >= STALL_WEDGED_TIMEOUTS) rebuildPlayerInstance()
        } else {
          stallWedgedTimeouts = 0
        }
      }
    }
  }

  private fun stopStallWatchdog() {
    stallWatchdogJob?.cancel()
    stallWatchdogJob = null
  }

  /**
   * Point the stall watchdog at a freshly loaded file: drop the previous file's position
   * baseline and forget every recovery decision made for it.
   *
   * Called from [handleFileLoaded], not from [startStallWatchdog]: the watchdog is started once
   * per Activity (setupMPV), while a single Activity plays many files in a row — the app is
   * singleTask, so opening another video arrives through onNewIntent, and auto-advance loads the
   * next episode in the same instance. Each of those restarts `time-pos` from 0, which without
   * this reset reads as "the position went backwards", i.e. as a freeze.
   */
  private fun rebaseStallWatchdogForNewFile(isRecoveryReload: Boolean = false) {
    lastProgressTimePos = 0.0
    lastProgressMs = System.currentTimeMillis()
    lastRecoveryMs = 0L
    stallAttempts = 0
    hasProgressedSinceLoad = false
    // Only a genuinely different file earns a fresh rebuild budget. A recovery reload also ends
    // in MPV_EVENT_FILE_LOADED, so resetting here would wipe the budget with the very reload it
    // is supposed to count: [STALL_REBUILD_MAX] could never be reached and a source that is
    // genuinely unreadable would be reloaded forever instead of giving up. The budget is
    // refreshed by healthy playback instead — see [STALL_BUDGET_REFRESH_MS].
    if (!isRecoveryReload) {
      stallRebuilds = 0
      stallGaveUpNotified = false
    }
    recoveryReloadPending = false
    // Per-stream cache-guard state: the input-rate window and the repair budget describe the
    // stream that is being replaced, not the Activity.
    cacheStagnantSinceMs = 0L
    lastCacheGuardMs = 0L
    cacheGuardRepairs = 0
    cacheGuardExhaustedLogged = false
  }

  private fun tickStallWatchdog() {
    if (!mpvInitialized || player.isExiting || isFinishing) return
    val now = System.currentTimeMillis()
    // No file loaded (idle / pre-load screen) -> nothing to watch.
    val path = MPVLib.getPropertyString("path")
    if (path.isNullOrBlank()) {
      heartbeat(now, "no media loaded")
      return
    }
    val pos = MPVLib.getPropertyDouble("time-pos")
    if (pos == null) {
      heartbeat(now, "time-pos unavailable")
      return
    }
    val paused = MPVLib.getPropertyBoolean("pause") == true
    val pausedByApp = UserPauseState.pausedByApp
    val pausedForCache = MPVLib.getPropertyBoolean("paused-for-cache") == true
    if (now - lastHeartbeatMs >= STALL_HEARTBEAT_MS) {
      lastHeartbeatMs = now
      Log.d(
        TAG,
        "stall watchdog heartbeat: pos=$pos frozen=${(now - lastProgressMs) / 1000}s " +
          "pause=$paused pausedByApp=$pausedByApp pausedForCache=$pausedForCache " +
          "attempts=$stallAttempts rebuilds=$stallRebuilds path=$path",
      )
    }
    // The position moved BACKWARDS: a different file started, or the source restarted. The app
    // is singleTask, so opening another video reuses this Activity through onNewIntent and
    // `time-pos` restarts from 0 while the baseline still holds the previous file's position.
    // The "is it moving" test below can then not be satisfied until the new file has played
    // past the old one, which looks exactly like a permanent freeze and used to send healthy
    // files down the recovery ladder (a reload plus a jump to the old file's position, and at
    // one point an Activity recreate). Rebase for the new stream instead of recovering.
    if (pos < lastProgressTimePos - 1.0) {
      Log.w(
        TAG,
        "stall watchdog: position went backwards (baseline=${lastProgressTimePos}s, now=${pos}s)" +
          " - new stream, rebasing",
      )
      rebaseStallWatchdogForNewFile()
      lastProgressTimePos = pos
      return
    }
    if (pos - lastProgressTimePos > 0.3) {
      // Progressing normally: keep baseline fresh and clear the attempt count.
      lastProgressTimePos = pos
      lastProgressMs = now
      stallAttempts = 0
      // This file has proved it can play, so from now on a freeze is real and recoverable.
      hasProgressedSinceLoad = true
      // Playback is moving, so no pause we recorded can still be in effect (self-heals a
      // stale flag, e.g. after loadfile silently unpauses).
      UserPauseState.pausedByApp = false
      // Healthy playback refreshes the rebuild budget so the cap is "consecutive rebuilds"
      // rather than "ever this process".
      mpvRebuildCount = 0
      // Same idea for the source-rebuild budget: once playback has run healthily for a while,
      // the previous freeze is over and the next one deserves its own attempts. See
      // [STALL_BUDGET_REFRESH_MS].
      if (now - lastRecoveryMs >= STALL_BUDGET_REFRESH_MS) {
        stallRebuilds = 0
        stallGaveUpNotified = false
      }
      // Playback is running and consuming the buffer: the only place a proactive transport repair
      // may be issued from (see [tickCacheGuard]). It runs AFTER the healthy bookkeeping above, so
      // a repair can never be mistaken for a stall.
      tickCacheGuard(now, path)
      return
    }
    // time-pos is frozen. A pause the user asked for (directly, via PiP, via the
    // notification or via the sleep timer) is NEVER a stall, regardless of the mpv
    // `paused-for-cache` flag: if the app paused on the user's behalf we must never
    // auto-resume or run the recovery ladder. `paused-for-cache` can be true at the
    // same instant the user pauses (cache-pause is enabled for every source), and
    // treating that as a stall made the watchdog resume a manually-paused video and
    // then re-open the file (observed: phone local playback auto-resumed + jumped).
    if (paused && pausedByApp) {
      lastProgressTimePos = pos
      lastProgressMs = now
      return
    }
    // `keep-open` (enabled in MPVView) makes mpv pause at the end of a file through
    // handle_keep_open() -> set_pause_state(true) in player/playloop.c. That path sets ONLY
    // opts->pause — it never raises `paused-for-cache`, whose value is computed separately
    // from the underrun state. When the source dies mid-file the demuxer simply runs out of
    // input, so mpv reports end-of-file far from the real end and pauses exactly like that.
    // The manual-pause guard below then read it as a deliberate pause and returned: the
    // ladder never ran and playback stayed frozen for good — the "stops after ~30 min,
    // position frozen ~50%, cache near 0, no live proxy connection, play key does nothing"
    // freeze. Detect that shape (end-of-file reached far from the real end) before the guard,
    // so the ladder gets a chance to rebuild the source. A genuine pause by the viewer cannot
    // match it: `eof-reached` stays false for the whole file.
    val prematureEnd =
      isPrematureSourceEnd(
        pos,
        MPVLib.getPropertyDouble("duration") ?: 0.0,
        MPVLib.getPropertyBoolean("eof-reached") == true,
      )
    // Structural guard, independent of our own bookkeeping: `cache-pause` is the only way
    // mpv pauses itself, and while it holds playback it always raises `paused-for-cache`.
    // So a pause with a healthy cache can only mean "someone deliberately paused this" —
    // the viewer, the notification, PiP, the sleep timer or our own background logic.
    // Never second-guess that, whatever `pausedByApp` happens to say. Relying on the flag
    // alone kept letting manual pauses through (the media key is delivered down several
    // paths, and a race between them cleared the flag), after which the watchdog resumed
    // the video and ran the recovery ladder on top of it — the reported
    // "I pressed pause and it started playing again / jumped ahead".
    if (paused && !pausedForCache && !prematureEnd) {
      lastProgressTimePos = pos
      lastProgressMs = now
      return
    }
    // Frozen, but this file has never produced a frame: it is still opening. A stream can take
    // far longer to open than the grace window, so reloading here would throw away the progress
    // the open has already made and start it again from scratch.
    if (!hasProgressedSinceLoad) {
      heartbeat(now, "waiting for the first frame of this file")
      return
    }
    // Frozen. Only act after the grace window and the recovery cooldown.
    if (now - lastProgressMs < STALL_THRESHOLD_MS) return
    if (now - lastRecoveryMs < STALL_RECOVERY_COOLDOWN_MS) return
    if (stallAttempts >= STALL_MAX_ATTEMPTS) {
      // Ladder exhausted. Rather than silently giving up for the rest of the session (which
      // is how the old code behaved), allow a fresh ladder after a long quiet period.
      if (now - lastRecoveryMs < STALL_ROUND_RESET_MS) return
      stallAttempts = 0
    }
    lastRecoveryMs = now
    stallAttempts++
    Log.w(
      TAG,
      "stall watchdog: frozen ${(now - lastProgressMs) / 1000}s at $pos " +
        "(pause=$paused pausedByApp=$pausedByApp pausedForCache=$pausedForCache) -> attempt #$stallAttempts",
    )
    // Reached only for pauses mpv made on its own behalf — cache-pause, or `keep-open` at a
    // premature end of file (every deliberate pause returned above) — so resume first: the
    // recovery steps below would be issued against a paused player and do nothing.
    if (paused) {
      runCatching { MPVLib.setPropertyBoolean("pause", false) }
        .onFailure { Log.w(TAG, "stall watchdog: failed to resume playback", it) }
      UserPauseState.pausedByApp = false
    }
    // This TV's logd drops every Java log line the app writes, so a Toast is the only
    // feedback that lets the viewer tell "the watchdog is acting" apart from "nothing
    // happened" while the freeze is being recovered.
    runOnUiThread {
      android.widget.Toast
        .makeText(
          this,
          if (stallAttempts <= 1) "播放卡住，正在自动恢复…" else "仍无画面，正在重新加载片源…",
          android.widget.Toast.LENGTH_SHORT,
        )
        .show()
    }
    if (prematureEnd) {
      // The source is already gone (mpv ended the file early), so step 1's decoder nudge would
      // be issued against a stream that no longer exists. Go straight to a source rebuild.
      if (stallRebuilds >= STALL_REBUILD_MAX) {
        notifyRecoveryGaveUp()
      } else {
        stallRebuilds++
        reloadCurrentMediaFrom(lastProgressTimePos)
      }
    } else {
      recoverFromStall(stallAttempts)
    }
  }

  /**
   * Proactive transport repair: notice that the NETWORK LAYER has stopped producing bytes while
   * mpv still wants them, and have that session rebuilt in place — before the demuxer cache is
   * drained.
   *
   * Why the input rate and not a cache threshold: a buffer threshold (10% of the target, say)
   * only trips once the failure is already tens of seconds old and the remaining margin is thin,
   * so the remedy has to be near-instant to avoid a visible stall. The input rate instead reads
   * zero within the first seconds of the failure, while tens of seconds of video are still
   * buffered, so the repair has room to complete unseen. It also needs no threshold of its own:
   * a starved reader IS the failure.
   *
   * The repair itself keeps the HTTP body alive (see SmbClient.requestStreamRepair), so nothing
   * about playback restarts — no seek, no decoder rebuild, no skipped content, no reload toast.
   *
   * Only the local proxy can be repaired this way; for a local file, a direct URL or a stream with
   * no in-place repair this returns without touching anything.
   *
   * Deliberately disjoint from the stall ladder: it runs only while `time-pos` is ADVANCING. Once
   * playback has actually frozen the verified ladder (nudge -> source reload -> give up) owns the
   * situation and must not be raced by this.
   */
  private fun tickCacheGuard(now: Long, path: String) {
    val streamId = proxyStreamId(path)
    if (streamId == null) {
      cacheStagnantSinceMs = 0L
      return
    }
    // An early EOF means the source is already gone (the premature-EOF recovery owns that), and a
    // seek rebuilds the stream by itself; in both cases a zero input rate is expected, not a fault.
    if (MPVLib.getPropertyBoolean("eof-reached") == true ||
      MPVLib.getPropertyBoolean("seeking") == true
    ) {
      cacheStagnantSinceMs = 0L
      return
    }
    // null means the property is unavailable -> do not guess.
    val idle = MPVLib.getPropertyBoolean("demuxer-cache-idle")
    val speed = MPVLib.getPropertyInt("cache-speed")
    if (idle == null || speed == null) {
      cacheStagnantSinceMs = 0L
      return
    }
    if (idle) {
      // mpv stopped reading because the buffer is at its target: the healthy sawtooth. The cache
      // shrinking from consumption alone is expected here, so this is exactly the case that must
      // NOT be repaired.
      cacheStagnantSinceMs = 0L
      return
    }
    if (speed > CACHE_GUARD_STALL_BPS) {
      cacheStagnantSinceMs = 0L
      return
    }
    if (cacheStagnantSinceMs == 0L) {
      cacheStagnantSinceMs = now
      return
    }
    if (now - cacheStagnantSinceMs < CACHE_GUARD_STAGNANT_MS) return
    if (cacheGuardRepairs > 0 && now - lastCacheGuardMs >= CACHE_GUARD_BUDGET_REFRESH_MS) {
      // A long healthy stretch since the last repair: the budget counts a burst, not a lifetime.
      cacheGuardRepairs = 0
      cacheGuardExhaustedLogged = false
    }
    if (cacheGuardRepairs >= CACHE_GUARD_MAX) {
      if (!cacheGuardExhaustedLogged) {
        cacheGuardExhaustedLogged = true
        Log.w(
          TAG,
          "cache guard: $CACHE_GUARD_MAX transport repairs already used for this stream, " +
            "standing down (the stall ladder is the only recovery left)",
        )
      }
      return
    }
    if (now - lastCacheGuardMs < CACHE_GUARD_COOLDOWN_MS) return
    lastCacheGuardMs = now
    // Require a fresh window after every attempt, successful or not.
    cacheStagnantSinceMs = now
    val cachedSec =
      MPVLib.getPropertyDouble("demuxer-cache-duration")?.let { "%.1f".format(it) } ?: "n/a"
    val fillPct = MPVLib.getPropertyInt("cache-buffering-state")?.toString() ?: "n/a"
    val repaired =
      app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
        .activeInstanceOrNull()
        ?.requestStreamRepair(streamId) == true
    if (repaired) cacheGuardRepairs++
    Log.w(
      TAG,
      "cache guard: no input for ${CACHE_GUARD_STAGNANT_MS / 1000}s while mpv wants data " +
        "(rate=${speed / 1024}KiB/s idle=$idle pos=${lastProgressTimePos}s cached=${cachedSec}s " +
        "fill=${fillPct}%) -> in-place transport repair " +
        "#$cacheGuardRepairs/$CACHE_GUARD_MAX (accepted=$repaired)",
    )
  }

  /**
   * The proxy stream id behind [path], or null when [path] is not served by our local proxy (a
   * local file, a direct URL, a resume, ...). Only a proxied source has a session that can be
   * rebuilt underneath a live HTTP body, so everything else short-circuits on this.
   */
  private fun proxyStreamId(path: String?): String? {
    if (path == null) return null
    if (!path.startsWith("http://127.0.0.1:") && !path.startsWith("http://localhost:")) return null
    return path.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
  }

  /** Periodic liveness log so "the watchdog never ran" can be told apart from "it ran and
   *  decided not to act" — the absence of this distinction cost several blind debug rounds. */
  private fun heartbeat(now: Long, detail: String) {
    if (now - lastHeartbeatMs < STALL_HEARTBEAT_MS) return
    lastHeartbeatMs = now
    Log.d(TAG, "stall watchdog heartbeat: $detail (watchdog alive)")
  }

  /**
   * True when mpv reported end-of-file but the playhead is nowhere near the real end, i.e. the
   * SOURCE died mid-file rather than the file actually finishing.
   *
   * The margin is deliberately far wider than the 5s tolerance [handleEndOfFile] uses to decide
   * whether to advance to the next episode: a container whose reported `duration` overshoots
   * the real end by a handful of seconds is common, and treating that as a dead source would
   * reload the tail of such a file a couple of times instead of simply letting it end. A
   * source that genuinely dies mid-playback does so minutes — typically halfway — from the end,
   * so requiring the playhead to be a full minute short of `duration` separates the two cases
   * with a wide safety margin.
   */
  private fun isPrematureSourceEnd(
    pos: Double,
    dur: Double,
    eofReached: Boolean,
  ): Boolean = eofReached && dur > 0.0 && pos < dur - 60.0

  /**
   * The stream died before the real end of the file: mpv reports end-of-file at [pos] while
   * [dur] says there is far more to come. This is what a silently dropped SMB session looks
   * like from the player's side — the localhost proxy's read fails, the HTTP stream ends, and
   * the demuxer treats the truncated input as the end of the file.
   *
   * Letting this pass (the old behaviour) froze playback permanently, so it is handled as a
   * recovery instead of being merely logged: see the call site in [handleEndOfFile].
   *
   * Shares the watchdog's cooldown and rebuild budget, so a source that is genuinely
   * unreadable cannot turn this into a reload loop.
   */
  private fun recoverFromPrematureEof(pos: Double, dur: Double) {
    val now = System.currentTimeMillis()
    if (stallRebuilds >= STALL_REBUILD_MAX) {
      notifyRecoveryGaveUp()
      return
    }
    // A watchdog tick may already be handling this same freeze; let it own the attempt so the
    // source is not reloaded twice in a row.
    if (now - lastRecoveryMs < STALL_RECOVERY_COOLDOWN_MS) return
    lastRecoveryMs = now
    Log.w(
      TAG,
      "EOF guard: source died mid-file (pos=${"%.1f".format(pos)}s / " +
        "dur=${"%.1f".format(dur)}s) - rebuilding source " +
        "(rebuild #${stallRebuilds + 1}/$STALL_REBUILD_MAX)",
    )
    runOnUiThread {
      android.widget.Toast
        .makeText(this, "片源中断，正在从断点重新加载…", android.widget.Toast.LENGTH_SHORT)
        .show()
    }
    stallRebuilds++
    reloadCurrentMediaFrom(lastProgressTimePos)
  }

  /** Rebuild the source and reload at [positionSec]; see [reloadCurrentMediaFrom]. */
  private fun recoverFromStall(attempt: Int) {
    // A source reload is the only heavier step left, and it is capped per stall episode. Once
    // the budget is gone, stop rather than escalate into VO/decoder surgery.
    if (attempt >= 2 && stallRebuilds >= STALL_REBUILD_MAX) {
      notifyRecoveryGaveUp()
      return
    }
    when (attempt) {
      1 -> {
        // Gentlest: nudge ~0.5s ahead to re-initialise the MediaCodec decoder at the
        // nearest keyframe without touching the VO or the video track.
        // NOTE: with a RELATIVE seek mpv treats the target as the OFFSET, not as an
        // absolute position. This used to pass (time-pos + 0.5), so mpv added the
        // current position to itself — on the TCL TV that moved 1112s -> 2226s (the
        // position roughly doubled) and the user saw it as "auto fast-forward to much
        // later" right after a manual pause. The offset must stay small.
        runCatching { MPVLib.command("seek", "0.5", "relative+exact") }
      }
      2, 3 -> {
        // The stream itself is dead. Observed on a TCL TV: the SMB source is proxied over
        // localhost HTTP, the connection disappears, mpv runs out of buffered data and then
        // sits there starved — "playing" but time-pos frozen — without ever reporting an
        // error, so nothing but a reload can revive it. A local file takes the same path
        // (mpv simply re-opens the file URI), so this step is source-agnostic.
        //
        // VO rebuild and `vid` no/auto cycling are deliberately NOT used here: on the TCL TV
        // they permanently desynced audio, video and subtitles. A recovery that breaks
        // playback is worse than the freeze.
        stallRebuilds++
        reloadCurrentMediaFrom(lastProgressTimePos)
      }
      else -> notifyRecoveryGaveUp()
    }
  }

  /**
   * Ladder exhausted for this file: leave playback alone and say so once, instead of escalating.
   *
   * Escalating to an Activity recreate here was tried and reverted (it made things worse).
   * `recreate()` destroys the Activity with isFinishing == false, so [cleanupMPV] returns before
   * MPVLib.destroy() and the native mpv instance is never torn down. The recreated Activity then
   * runs initialize() against a still-live instance — MPVLib.create() has no guard — and the
   * player comes up black, having first made the picture flash while the Activity was swapped.
   * Recreating only becomes viable together with a real teardown of the mpv instance.
   */
  private fun notifyRecoveryGaveUp() {
    Log.w(
      TAG,
      "stall watchdog: recovery budget exhausted ($stallRebuilds source rebuilds for this " +
        "file) - leaving playback untouched; a play keypress or re-opening the file is needed",
    )
    if (stallGaveUpNotified) return
    stallGaveUpNotified = true
    runOnUiThread {
      android.widget.Toast
        .makeText(this, "自动恢复未成功，请按播放键或重新打开该视频", android.widget.Toast.LENGTH_LONG)
        .show()
    }
  }

  /**
   * Re-issue the currently loaded source and resume a couple of seconds past the stall
   * point. For SMB/WebDAV the path mpv holds is the local proxy URL, so this re-opens the
   * stream (a fresh HTTP range request) instead of only nudging the decoder.
   */
  private fun reloadCurrentMediaFrom(positionSec: Double) {
    val uri = MPVLib.getPropertyString("path")?.takeIf { it.isNotBlank() } ?: getPlayableUri(intent)
    if (uri == null) {
      Log.w(TAG, "stall watchdog: cannot rebuild source - no playable uri for the current media")
      return
    }
    // If the source is served through the localhost SMB/network proxy, a plain reload reuses
    // the same proxy URL. mpv can keep the same HTTP connection open, and that connection may
    // be pinned to a `serve()` thread that is wedged on a half-open SMB session (the socket is
    // still ESTABLISHED but no data is coming). The result is cache stays at 0 and playback
    // never resumes even though a fresh SMB socket technically exists. Rotating the stream ID
    // gives mpv a brand-new proxy URL, which forces a new HTTP connection and therefore a
    // completely fresh SMB read path.
    var loadUri: String = uri
    runCatching {
      val u = android.net.Uri.parse(uri)
      if (u.host == "127.0.0.1" || u.host == "localhost") {
        val sid = u.path?.removePrefix("/")?.substringBefore("/")?.takeIf { it.isNotEmpty() }
        sid?.let { oldSid ->
          val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy
            .getInstance()
          val newUrl = proxy.rotateStreamId(oldSid)
          if (!newUrl.isNullOrBlank()) {
            loadUri = newUrl
            registeredStreamIds.remove(oldSid)
            android.net.Uri.parse(newUrl).path?.removePrefix("/")?.substringBefore("/")?.let { newSid ->
              registeredStreamIds.add(newSid)
            }
          }
        }
      }
    }.onFailure { Log.w(TAG, "stall watchdog: proxy rotateStreamId failed", it) }
    // Resume from the last position where playback was actually advancing. [positionSec]
    // is the watchdog's `lastProgressTimePos`, captured before the stall. The live
    // `time-pos` collapses to ~0 when a stream dies (mpv fires EOF and resets the clock),
    // so using it would restart the file from the very beginning.
    val resumeAt = positionSec + 2.0
    Log.w(TAG, "stall watchdog: rebuilding source (rebuild #$stallRebuilds), resuming at ${resumeAt}s, uri=$loadUri")
    // Flag this load as a recovery BEFORE issuing it. loadfile raises MPV_EVENT_FILE_LOADED,
    // which runs handleFileLoaded() -> rebaseStallWatchdogForNewFile(), and that must not treat
    // the reload as a brand-new file.
    recoveryReloadPending = true
    runCatching { MPVLib.command("loadfile", loadUri) }
      .onFailure { Log.w(TAG, "stall watchdog: loadfile failed", it) }
    playerScope.launch {
      // A freshly (re)opened network stream (e.g. SMB via the localhost proxy) can take
      // longer than a fixed 2s to become seekable. If the absolute seek fires before
      // `duration` is known it is silently dropped and the file plays from 0. Wait for the
      // file to open, then seek, retrying for a few seconds.
      var seeked = false
      for (attempt in 1..6) {
        delay(1500)
        val dur = runCatching { MPVLib.getPropertyDouble("duration") }.getOrNull() ?: 0.0
        if (dur > 0.0) {
          runCatching { MPVLib.command("seek", resumeAt.toString(), "absolute") }
          seeked = true
          break
        }
      }
      if (!seeked) {
        Log.w(TAG, "stall watchdog: source rebuild did not open in time — will play from start")
      }
      runCatching { MPVLib.setPropertyBoolean("pause", false) }
      // Rebase the watchdog so a slow reload is not immediately read as another stall.
      lastProgressTimePos = resumeAt
      lastProgressMs = System.currentTimeMillis()
      lastRecoveryMs = System.currentTimeMillis()
      // Defensive clear: if the reload never produced MPV_EVENT_FILE_LOADED (loadfile failed),
      // the flag must not survive to suppress the budget reset of the next genuine file open.
      recoveryReloadPending = false
    }
  }

  /**
   * mpv stopped answering JNI property probes (hard wedge). A fresh instance is the only real
   * cure, but `recreate()` is STRUCTURALLY UNSAFE here and is BANNED as a recovery: during
   * recreate() `isFinishing` is false, so [cleanupMPV] returns at its guard BEFORE
   * `MPVLib.destroy()` (and before the force-release of the video output). The native mpv
   * instance is therefore never torn down, and the recreated Activity then calls
   * `MPVLib.create()` against that still-live (and wedged) instance — the picture flashes and
   * then comes up black (observed as c92527d, reverted by 24d7f2ff). The only safe move is to
   * give up and ask the user to restart the app, which performs a clean full teardown.
   *
   * Triggered when mpv stops answering JNI probes (hard wedge). Capped process-wide by
   * [MAX_MPV_REBUILDS] so we don't spam the user; the budget is refreshed to 0 on healthy
   * playback.
   */
  private fun rebuildPlayerInstance() {
    mpvRebuildCount++
    runCatching { saveVideoPlaybackState(fileName) }
    Log.w(
      TAG,
      "stall watchdog: mpv stopped answering JNI probes (hard wedge #$mpvRebuildCount/" +
        "$MAX_MPV_REBUILDS); recreate() is banned as recovery, asking user to restart the app",
    )
    if (stallGaveUpNotified) return
    stallGaveUpNotified = true
    runOnUiThread {
      android.widget.Toast
        .makeText(this, "播放器无响应，请重启应用", android.widget.Toast.LENGTH_LONG)
        .show()
    }
  }

  override fun abandonAudioFocus() {
    // Previously guarded by `restoreAudioFocus != {}`, which compared two distinct lambda
    // instances and was therefore always true. Track focus state explicitly instead.
    if (audioFocusActive) {
      audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
      restoreAudioFocus = {}
      audioFocusActive = false
    }
  }

  private fun cleanupAudio() {
    abandonAudioFocus()
  }

  private fun cleanupReceivers() {
    if (noisyReceiverRegistered) {
      runCatching {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onPause() {
    runCatching {
      val isInPip = isInPictureInPictureMode
      val shouldPause = (!audioPreferences.automaticBackgroundPlayback.get() && !isManualBackgroundPlayback) || 
                        (isUserFinishing && !isManualBackgroundPlayback)

      // OPTIMIZATION: Stop playback immediately if finishing to reduce cleanup overhead
      if (isFinishing && !isManualBackgroundPlayback) {
        saveVideoPlaybackState(fileName)
        viewModel.pause()
        // Tell MPV to stop processing to reduce busywork during cleanup
        MPVLib.command("stop")
      } else if (!isInPip && shouldPause) {
        wasPlayingBeforePause = !(viewModel.paused ?: true)
        viewModel.pause()
      }

      // Restore UI immediately when user is finishing for instant feedback
      if (isUserFinishing && !isInPip && !isManualBackgroundPlayback) {
        restoreSystemUI()
      }

      // OPTIMIZATION: Only save if not finishing (onDestroy will handle final save)
      if (!isFinishing) {
        saveVideoPlaybackState(fileName)
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onPause", e)
    }

    super.onPause()
    stopStallWatchdog()
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun finish() {
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finish", e)
    }

    super.finish()
  }

  // finishAndRemoveTask() was added in API 21, but since our minSdk is 26, it's always available
  override fun finishAndRemoveTask() {
    runCatching {
      // Don't restore UI during normal finish to prevent flickering
      // System will handle UI restoration automatically
      isReady = false
      isUserFinishing = true
      
      // Clean up service when finishing
      if (serviceBound || mediaPlaybackService != null) {
        endBackgroundPlayback()
      }
      
      setReturnIntent()
    }.onFailure { e ->
      Log.e(TAG, "Error during finishAndRemoveTask", e)
    }

    super.finishAndRemoveTask()
  }

  override fun onStop() {
    runCatching {
      pipHelper.onStop()
      saveVideoPlaybackState(fileName)

      if (noisyReceiverRegistered) {
        unregisterReceiver(noisyReceiver)
        noisyReceiverRegistered = false
      }

      // Handle background playback based on preferences
      val shouldAllowBackgroundPlayback = isManualBackgroundPlayback || 
                                          audioPreferences.automaticBackgroundPlayback.get()
      
      // Pause playback if background playback is not enabled and user is finishing
      if (!shouldAllowBackgroundPlayback && (isUserFinishing || isFinishing)) {
        viewModel.pause()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error during onStop", e)
    }

    super.onStop()
  }

  /**
   * Give memory back when the system asks for it.
   *
   * On low-RAM TVs (<=3GB) long playback can push the process into the critical zone.
   * Handing mpv's cached buffers back here costs a brief stall while it refills, which
   * is far better than the OS killing us mid-playback — that is what produced the
   * freeze + black screen that could no longer be exited.
   */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return
    runCatching {
      // Shrink the cache caps so mpv trims on the next read...
      MPVLib.setPropertyString("demuxer-max-bytes", "${8 * 1024 * 1024}")
      MPVLib.setPropertyString("demuxer-max-back-bytes", "0")
      // ...and drop the queued audio/video/demuxer buffers to free it right now.
      MPVLib.command("drop-buffers")
    }.onFailure { e ->
      Log.d(TAG, "onTrimMemory: could not release mpv buffers: ${e.message}")
    }
  }

  @RequiresApi(Build.VERSION_CODES.P)
  override fun onStart() {
    super.onStart()

    runCatching {
      setupWindowFlags()
      setupSystemUI()

      if (!noisyReceiverRegistered) {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
        noisyReceiverRegistered = true
      }

      if (playerPreferences.rememberBrightness.get()) {
        val brightness = playerPreferences.defaultBrightness.get()
        if (brightness != BRIGHTNESS_NOT_SET) {
          viewModel.changeBrightnessTo(brightness)
        }
      }
      
      // Reset manual background playback flag when returning to foreground
      isManualBackgroundPlayback = false
    }.onFailure { e ->
      Log.e(TAG, "Error during onStart", e)
    }
  }

  private fun setupWindowFlags() {
    pipHelper.updatePictureInPictureParams()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.setFlags(
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    )
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun setupSystemUI() {
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

    // Set status bar color for when it will be shown (with controls)
    if (playerPreferences.showSystemStatusBar.get()) {
      window.statusBarColor = android.graphics.Color.parseColor("#80000000") // Semi-transparent black
    }

    // Always start with status bar hidden - it will show when controls are shown
    try {
      windowInsetsController.apply {
        hide(WindowInsetsCompat.Type.statusBars())
        hide(WindowInsetsCompat.Type.navigationBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to setup system UI insets", e)
    }

    // Don't use LOW_PROFILE if we plan to show status bar with controls
    // LOW_PROFILE causes only icons to show without background
    @Suppress("DEPRECATION")
    binding.root.systemUiVisibility =
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        if (playerPreferences.showSystemStatusBar.get()) 0 else View.SYSTEM_UI_FLAG_LOW_PROFILE
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private fun restoreSystemUI() {
    // Clear flags first for immediate effect
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    // Set cutout mode before showing bars for smoother transition
    window.attributes.layoutInDisplayCutoutMode =
      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT

    // Update window insets configuration
    WindowCompat.setDecorFitsSystemWindows(window, true)

    // Restore default behavior and show bars in one go
    try {
      windowInsetsController.apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to restore system UI insets", e)
    }
  }

  /**
   * Initializes the MPV player with the necessary paths and observers.
   */
  private fun setupMPV() {
    // Copy essential files FIRST, before MPV initialization
    runCatching {
      Utils.copyAssets(this@PlayerActivity)
      syncFromUserMpvDirectory()
      Log.d(TAG, "MPV config and scripts prepared successfully")
    }.onFailure { e ->
      Log.e(TAG, "Error copying MPV config and scripts", e)
    }

    // NOW initialize MPV - it will find and load the scripts we just copied
    player.initialize(filesDir.path, cacheDir.path)
    mpvInitialized = true
    Log.d(TAG, "MPV initialized")
    // Do not rely on onResume() alone: if the watchdog never starts there is no recovery at
    // all, and a silent no-op there cost several blind debug rounds. Idempotent.
    startStallWatchdog()

    // Make the app's filesDir the runtime target for custom Lua buttons and run any
    // startup scripts once mpv is ready to receive script-message commands.
    CustomButtonRuntime.configDir = filesDir.path
    CustomButtonRuntime.fireStartups(playerPreferences.customButtons.get().slots.filterNotNull())

    // Watch progress so a configured outro window can auto-advance to the next episode.
    startOutroSkipMonitor()

    // Let a script ticked mid-playback load immediately, without restarting the video.
    observeScriptSelection()

    // Wire up the OSD surface for vo=mediacodec_embed (separate SurfaceView that
    // mpv renders subtitles/OSC to, while MediaCodec renders video to the main surface)
    binding.osdSurface?.let { osdSurface ->
        player.setOsdSurfaceView(osdSurface)
        Log.d(TAG, "OSD surface view wired up for mediacodec_embed")
    }

    // Add observer after initialization
    MPVLib.addObserver(playerObserver)
  }

  /**
   * Syncs ALL MPV assets from the user's configured MPV directory to internal storage.
   * Handles: mpv.conf, input.conf, scripts/, script-opts/, shaders/, fonts/
   *
   * Uses case-insensitive subfolder matching and falls back to root scanning
   * if standard subfolders don't exist. Falls back to preferences-based config
   * if no user directory is configured.
   */
  private fun syncFromUserMpvDirectory() {
    val mpvConfStorageUri = advancedPreferences.mpvConfStorageUri.get()

    // Try to open the user's MPV directory
    val tree = if (mpvConfStorageUri.isNotBlank()) {
      runCatching {
        DocumentFile.fromTreeUri(this, mpvConfStorageUri.toUri())
      }.getOrNull()?.takeIf { it.exists() && it.canRead() }
    } else null

    if (tree != null) {
      Log.d(TAG, "Syncing from user MPV directory: ${tree.uri}")
      syncConfigFiles(tree)
      syncFonts(tree)
      syncScriptsFromTree(tree)
      Log.d(TAG, "Full MPV directory sync completed")
    } else {
      // Fallback: read the default on-device directory, then the preferences copy.
      Log.d(TAG, "No MPV directory configured, syncing from default path")
      copyMPVConfigFromPreferences()
      syncConfigFromDefaultPath()
      syncScriptsFromDefaultPath()
    }
  }

  // ==================== Config Files Sync ====================

  /**
   * Syncs mpv.conf and input.conf from the user's MPV directory.
   * Also caches the content in preferences for the config editor.
   */
  private fun syncConfigFiles(tree: DocumentFile) {
    for (configName in listOf("mpv.conf", "input.conf")) {
      runCatching {
        val configFile = findFileCaseInsensitive(tree, configName)
        if (configFile != null && configFile.exists() && configFile.canRead()) {
          contentResolver.openInputStream(configFile.uri)?.use { input ->
            val content = input.bufferedReader().readText()
            File(filesDir, configName).writeText(content)
            // Cache in preferences for the config editor
            when (configName) {
              "mpv.conf" -> advancedPreferences.mpvConf.set(content)
              "input.conf" -> advancedPreferences.inputConf.set(content)
            }
            Log.d(TAG, "Synced config: $configName (${content.length} chars)")
          }
        } else {
          // Config not in directory, fall back to preferences
          val prefContent = when (configName) {
            "mpv.conf" -> advancedPreferences.mpvConf.get()
            "input.conf" -> advancedPreferences.inputConf.get()
            else -> ""
          }
          File(filesDir, configName).apply {
            if (!exists()) createNewFile()
            if (prefContent.isNotBlank()) writeText(prefContent)
          }
          Log.d(TAG, "Config not found in directory, used preferences: $configName")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing config: $configName", e)
      }
    }
  }

  // ==================== Fonts Sync ====================

  /**
   * Syncs font files (.ttf, .otf, .ttc, .woff, .woff2) from the user's MPV directory.
   * Looks in fonts/ subfolder first (case-insensitive), falls back to root.
   * Also syncs from the subtitle preferences font folder if set.
   */
  private fun syncFonts(tree: DocumentFile) {
    val internalFontsDir = File(filesDir, "fonts")
    internalFontsDir.mkdirs()

    val fontsSubdir = findSubdirCaseInsensitive(tree, "fonts")
    val sourceDir = fontsSubdir ?: tree
    val fontExtensions = setOf("ttf", "otf", "ttc", "woff", "woff2")
    var count = 0

    sourceDir.listFiles().forEach { file ->
      if (!file.isFile) return@forEach
      val name = file.name ?: return@forEach
      val ext = name.substringAfterLast('.', "").lowercase()
      if (ext !in fontExtensions) return@forEach

      val target = File(internalFontsDir, name)
      // Skip if font already exists (fonts can be large)
      if (target.exists()) return@forEach

      runCatching {
        contentResolver.openInputStream(file.uri)?.use { input ->
          target.outputStream().use { output ->
            input.copyTo(output)
          }
          count++
          Log.d(TAG, "Synced font: $name")
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing font: $name", e)
      }
    }

    // Also sync from subtitle preferences font folder if set
    runCatching {
      val fontsFolderUri = subtitlesPreferences.fontsFolder.get()
      if (fontsFolderUri.isNotBlank()) {
        val destDir = fileManager.fromPath("${filesDir.path}/fonts")
        if (!fileManager.exists(destDir)) {
          fileManager.createDir(fileManager.fromPath(filesDir.path), "fonts")
        }
        val fontsDir = fileManager.fromUri(fontsFolderUri.toUri())
        if (fontsDir != null && fileManager.exists(fontsDir)) {
          fileManager.copyDirectoryWithContent(fontsDir, destDir, false)
        }
      }
    }.onFailure { e ->
      Log.e(TAG, "Error syncing subtitle fonts: ${e.message}")
    }

    Log.d(TAG, "Fonts sync: $count file(s) from MPV directory")
  }

  // ==================== Helpers ====================

  // ==================== Scripts Sync ====================

  /** Script extensions mpv auto-loads from `<config-dir>/scripts`. */
  private val scriptExtensions = setOf("lua", "js")

  /**
   * Names of the scripts mpv should load: the user's selection, or nothing at all when the
   * script feature is switched off.
   */
  private fun enabledScriptNames(): Set<String> =
    if (advancedPreferences.enableLuaScripts.get()) {
      advancedPreferences.selectedLuaScripts.get()
    } else {
      emptySet()
    }

  /**
   * Watches the script selection so a script ticked during playback is loaded straight away
   * instead of only showing up after the next restart.
   */
  private fun observeScriptSelection() {
    lifecycleScope.launch {
      var previous = advancedPreferences.selectedLuaScripts.get()
      advancedPreferences.selectedLuaScripts.changes().collect { current ->
        val added = current - previous
        previous = current
        if (added.isEmpty() || !advancedPreferences.enableLuaScripts.get()) return@collect
        added.forEach { loadScriptAtRuntime(it) }
      }
    }
  }

  /**
   * Copies [scriptName] into mpv's config-dir/scripts and tells the running mpv instance to
   * load it now.
   */
  private fun loadScriptAtRuntime(scriptName: String) {
    if (!mpvInitialized || isFinishing) return

    lifecycleScope.launch(Dispatchers.IO) {
      val target: File? =
        runCatching {
          val targetDir = File(filesDir, "scripts").apply { mkdirs() }
          ScriptRepository.readScript(this@PlayerActivity, advancedPreferences, scriptName)
            ?.let { content -> File(targetDir, scriptName).writeText(content) }
          File(targetDir, scriptName).takeIf { it.isFile }
        }.getOrNull()

      if (target == null) {
        Log.w(TAG, "Runtime script load: could not read $scriptName")
        return@launch
      }

      withContext(Dispatchers.Main) {
        runCatching {
          MPVLib.command("load-script", target.absolutePath)
          Log.d(TAG, "Loaded script at runtime: $scriptName")
        }.onFailure { e ->
          Log.e(TAG, "Error loading script at runtime: $scriptName", e)
        }
      }
    }
  }

  /**
   * Copies the enabled Lua/JS scripts from the user's MPV directory into mpv's
   * config-dir/scripts so mpv picks them up automatically. Prefers a `scripts` subfolder
   * (case-insensitive) and falls back to the directory root.
   *
   * The target directory is wiped first — mpv loads *everything* it finds there, so a
   * de-selected script has to be removed, not just left behind.
   */
  private fun syncScriptsFromTree(tree: DocumentFile) {
    val targetDir = File(filesDir, "scripts").apply { mkdirs() }
    targetDir.listFiles()?.forEach { it.delete() }

    val selected = enabledScriptNames()
    if (selected.isEmpty()) {
      Log.d(TAG, "Scripts sync (SAF): no scripts selected")
      return
    }

    val sourceDir = findSubdirCaseInsensitive(tree, "scripts") ?: tree
    var count = 0

    sourceDir.listFiles().forEach { file ->
      if (!file.isFile) return@forEach
      val name = file.name ?: return@forEach
      val ext = name.substringAfterLast('.', "").lowercase()
      if (ext !in scriptExtensions) return@forEach
      if (!selected.contains(name)) return@forEach

      runCatching {
        contentResolver.openInputStream(file.uri)?.use { input ->
          File(targetDir, name).outputStream().use { output -> input.copyTo(output) }
          count++
        }
      }.onFailure { e ->
        Log.e(TAG, "Error syncing script: $name", e)
      }
    }

    Log.d(TAG, "Scripts sync (SAF): $count file(s)")
  }

  /**
   * Copies Lua/JS scripts straight from the configured default scripts directory
   * (defaults to `/storage/emulated/0/mpv/scripts/`) into mpv's config-dir/scripts.
   */
  private fun syncScriptsFromDefaultPath() {
    val dirPath =
      advancedPreferences.mpvScriptsDir.get().ifBlank { "/storage/emulated/0/mpv/scripts/" }
    val sourceDir = File(dirPath)
    if (!sourceDir.isDirectory) {
      Log.d(TAG, "Default scripts directory not available: $dirPath")
      return
    }
    syncScriptsFromDirectory(sourceDir)
  }

  /** Copies the enabled Lua/JS scripts from [sourceDir] into mpv's config-dir/scripts. */
  private fun syncScriptsFromDirectory(sourceDir: File) {
    val targetDir = File(filesDir, "scripts").apply { mkdirs() }
    targetDir.listFiles()?.forEach { it.delete() }

    val selected = enabledScriptNames()
    if (selected.isEmpty()) {
      Log.d(TAG, "Scripts sync (path): no scripts selected")
      return
    }

    val scripts =
      sourceDir.listFiles { f -> f.isFile && f.extension.lowercase() in scriptExtensions }
    if (scripts.isNullOrEmpty()) {
      Log.d(TAG, "No scripts found in ${sourceDir.path}")
      return
    }

    var count = 0
    scripts.forEach { script ->
      if (!selected.contains(script.name)) return@forEach
      runCatching {
        script.copyTo(File(targetDir, script.name), overwrite = true)
        count++
      }.onFailure { e ->
        Log.e(TAG, "Error copying script: ${script.name}", e)
      }
    }
    Log.d(TAG, "Scripts sync (path): $count file(s) from ${sourceDir.path}")
  }

  /**
   * Reads mpv.conf / input.conf from the default on-device directory so a plain path works
   * even when the user has not granted a SAF folder.
   */
  private fun syncConfigFromDefaultPath() {
    val dirPath =
      advancedPreferences.mpvConfStoragePath.get().ifBlank { "/storage/emulated/0/mpv/" }
    val sourceDir = File(dirPath)
    if (!sourceDir.isDirectory) {
      Log.d(TAG, "Default MPV directory not available: $dirPath")
      return
    }

    for (configName in listOf("mpv.conf", "input.conf")) {
      runCatching {
        val source = File(sourceDir, configName)
        if (!source.isFile) return@runCatching
        val content = source.readText()
        if (content.isBlank()) return@runCatching
        File(filesDir, configName).writeText(content)
        when (configName) {
          "mpv.conf" -> advancedPreferences.mpvConf.set(content)
          "input.conf" -> advancedPreferences.inputConf.set(content)
        }
        Log.d(TAG, "Synced $configName from default path (${content.length} chars)")
      }.onFailure { e ->
        Log.e(TAG, "Error syncing $configName from default path", e)
      }
    }
  }

  /**
   * Fallback: copies config from preferences when no user MPV directory is set.
   */
  private fun copyMPVConfigFromPreferences() {
    runCatching {
      File(filesDir, "mpv.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.mpvConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      File(filesDir, "input.conf").apply {
        if (!exists()) createNewFile()
        val content = advancedPreferences.inputConf.get()
        if (content.isNotBlank()) writeText(content)
      }
      // Ensure fonts directory exists even without user dir
      File(filesDir, "fonts").mkdirs()
    }.onFailure { e ->
      Log.e(TAG, "Error creating fallback config files", e)
    }
  }

  /**
   * Finds a subdirectory by name (case-insensitive) within a DocumentFile.
   */
  private fun findSubdirCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? =
    parent.listFiles().firstOrNull {
      it.isDirectory && it.name?.equals(name, ignoreCase = true) == true
    }

  /**
   * Finds a file by name (case-insensitive) within a DocumentFile.
   */
  private fun findFileCaseInsensitive(parent: DocumentFile, name: String): DocumentFile? =
    parent.listFiles().firstOrNull {
      it.isFile && it.name?.equals(name, ignoreCase = true) == true
    }

  override fun onResume() {
    super.onResume()
    updateVolume()
    startStallWatchdog()
  }

  /**
   * Updates the volume level to match the system volume.
   *
   * This method updates the current volume level by getting the current system volume
   * and adjusting the MPV volume accordingly. It ensures that the MPV volume is set
   * to the maximum allowed value if the system volume is lower than the maximum.
   */
  private fun updateVolume() {
    viewModel.currentVolume.update {
      audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).also { volume ->
        if (volume < viewModel.maxVolume) {
          viewModel.changeMPVVolumeTo(MAX_MPV_VOLUME)
        }
      }
    }
  }

  /**
   * Processes intent extras to set initial playback position, subtitles, and HTTP headers.
   *
   * This method checks the intent extras for the following keys:
   * - "position": The initial playback position in seconds.
   * - "subs": A list of subtitle URIs to add.
   * - "subs.enable": A list of subtitle URIs to enable.
   * - "headers": A list of HTTP headers to set for network playback.
   *
   * @param extras Bundle containing intent extras
   */
  private fun setIntentExtras(extras: Bundle?) {
    if (extras == null) return

    extras.getInt("position", POSITION_NOT_SET).takeIf { it != POSITION_NOT_SET }?.let {
      MPVLib.setPropertyInt("time-pos", it / MILLISECONDS_TO_SECONDS)
    }

    addSubtitlesFromExtras(extras)
    setHttpHeadersFromExtras(extras)
  }

  /**
   * Adds subtitle tracks from intent extras.
   *
   * This method checks the intent extras for the "subs" key, which contains a list
   * of subtitle URIs to add. It also checks for the "subs.enable" key, which contains
   * a list of subtitle URIs to enable.
   *
   * @param extras Bundle containing subtitle URIs
   */
  private fun addSubtitlesFromExtras(extras: Bundle) {
    if (!extras.containsKey("subs")) return

    val subList = Utils.getParcelableArray<Uri>(extras, "subs")
    val subsToEnable = Utils.getParcelableArray<Uri>(extras, "subs.enable")

    lifecycleScope.launch(Dispatchers.Default) {
      for (suburi in subList) {
        val subfile = suburi.resolveUri(this@PlayerActivity) ?: continue
        val flag = if (subsToEnable.any { it == suburi }) "select" else "auto"

        Log.v(TAG, "Adding subtitles from intent extras: $subfile")
        MPVLib.command("sub-add", subfile, flag)
      }
    }
  }

  /**
   * Sets HTTP headers from intent extras for network playback.
   *
   * This method checks the intent extras for the "headers" key, which contains a list
   * of HTTP headers to set. It sets the User-Agent header and any additional headers
   * specified in the list.
   *
   * Also automatically adds Referer header based on the URL origin if not already provided.
   *
   * @param extras Bundle containing HTTP headers
   */
  private fun setHttpHeadersFromExtras(extras: Bundle?) {
    // Build header map starting with auto-detected referer
    val headerMap = mutableMapOf<String, String>()

    // Automatically extract and set referer domain from the URL
    val uri = extractUriFromIntent(intent)
    if (uri != null && HttpUtils.isNetworkStream(uri)) {
      HttpUtils.extractRefererDomain(uri)?.let { referer ->
        headerMap["Referer"] = referer
        Log.d(TAG, "Auto-detected Referer: $referer")
      }
    }

    // Process headers from extras (these can override the auto-detected referer)
    extras?.getStringArray("headers")?.let { headers ->
      if (headers.isEmpty()) return@let

      if (headers[0].startsWith("User-Agent", ignoreCase = true)) {
        MPVLib.setPropertyString("user-agent", headers[1])
      }

      if (headers.size > 2) {
        headers
          .asSequence()
          .drop(2)
          .chunked(2)
          .filter { it.size == 2 }
          .forEach { (key, value) ->
            headerMap[key] = value
          }
      }
    }

    // Set all headers in MPV
    if (headerMap.isNotEmpty()) {
      val headersString = headerMap
        .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
        .joinToString(",")

      MPVLib.setPropertyString("http-header-fields", headersString)
      Log.d(TAG, "Set HTTP headers: $headersString")
    }
  }

  /**
   * Sets HTTP headers for a specific URI (used for playlist items).
   * Automatically extracts and sets the Referer header based on the URI origin.
   *
   * @param uri The URI to extract referer from and set headers for
   */
  private fun setHttpHeadersForUri(uri: Uri) {
    if (!HttpUtils.isNetworkStream(uri)) return

    val headerMap = mutableMapOf<String, String>()

    // Automatically extract and set referer domain from the URI
    HttpUtils.extractRefererDomain(uri)?.let { referer ->
      headerMap["Referer"] = referer
      Log.d(TAG, "Auto-detected Referer for playlist item: $referer")
    }

    // Set all headers in MPV
    if (headerMap.isNotEmpty()) {
      val headersString = headerMap
        .map { "${it.key}: ${it.value.replace(",", "\\,")}" }
        .joinToString(",")

      MPVLib.setPropertyString("http-header-fields", headersString)
      Log.d(TAG, "Set HTTP headers for playlist item: $headersString")
    }
  }

  /**
   * Parses the file path from the intent.
   *
   * This method checks the intent action and data to determine the file path.
   * It supports the following actions:
   * - ACTION_VIEW: The file path is contained in the intent data.
   * - ACTION_SEND: The file path is contained in the intent extras.
   *
   * @param intent The intent containing the file URI
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromIntent(intent: Intent): String? =
    when (intent.action) {
      Intent.ACTION_VIEW -> intent.data?.resolveUri(this)
      Intent.ACTION_SEND -> parsePathFromSendIntent(intent)
      else -> intent.getStringExtra("uri")
    }

  /**
   * Parses the file path from a SEND intent.
   *
   * This method checks the intent extras for the file path.
   *
   * @param intent The SEND intent
   * @return The resolved file path, or null if not found
   */
  private fun parsePathFromSendIntent(intent: Intent): String? =
    if (intent.hasExtra(Intent.EXTRA_STREAM)) {
      val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
      }
      uri?.resolveUri(this@PlayerActivity)
    } else {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        val uri = text.trim().toUri()
        if (uri.isHierarchical && !uri.isRelative) {
          uri.resolveUri(this)
        } else {
          null
        }
      }
    }

  /**
   * Extracts and resolves the file name from the intent.
   *
   * @param intent The intent containing the file URI
   * @return The display name of the file, or empty string if not found
   */
  private fun getFileName(intent: Intent): String {
    // First check if a custom title/filename was provided via intent extras
    intent.getStringExtra("title")?.let { return it }
    intent.getStringExtra("filename")?.let { return it }
    val mpvnasDisplayName = extractUriFromIntent(intent)
      ?.takeIf { it.scheme.equals("mpvnas", ignoreCase = true) }
      ?.let { NetworkMediaIdUtils.parseMpvnasUri(it)?.displayName }
    if (!mpvnasDisplayName.isNullOrBlank()) return mpvnasDisplayName

    intent.getStringExtra("network_file_path")
      ?.let(NetworkMediaIdUtils::fileNameFromPath)
      ?.takeIf { it.isNotBlank() && it != "Network Stream" }
      ?.let { return it }

    val uri = extractUriFromIntent(intent) ?: return ""

    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Extracts filename from URI, handling URL encoding and network URLs properly.
   * For network streams, returns a temporary name that will be updated async via HTTP headers.
   *
   * @param uri The URI to extract filename from
   * @return The extracted filename
   */
  private fun extractFileNameFromUri(uri: Uri): String {
    if (uri.scheme.equals("mpvnas", ignoreCase = true)) {
      val ref = NetworkMediaIdUtils.parseMpvnasUri(uri)
      return ref?.displayName ?: NetworkMediaIdUtils.fileNameFromPath(ref?.canonicalPath)
    }

    // For HTTP/HTTPS URLs, extract from path (will be updated async via HTTP headers)
    if (HttpUtils.isNetworkStream(uri)) {
      // Check if it's a proxy stream
      val host = uri.host?.lowercase()
      if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
        val proxyTitle = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance().resolveDisplayName(uri.toString())
        if (proxyTitle != null) {
          return proxyTitle
        }
        intent.getStringExtra("network_file_path")
          ?.let(NetworkMediaIdUtils::fileNameFromPath)
          ?.takeIf { it.isNotBlank() && it != "Network Stream" }
          ?.let { return it }
        return "Network Stream"
      }

      // Get the last path segment and decode URL encoding
      val path = uri.path ?: return uri.host ?: "Network Stream"
      val lastSegment = path.substringAfterLast("/")

      if (lastSegment.isNotBlank()) {
        // Decode URL encoding (e.g., %20 -> space)
        return try {
          java.net.URLDecoder.decode(lastSegment, "UTF-8")
            .substringBefore("?") // Remove query parameters
            .substringBefore("#") // Remove fragments (only for network streams)
            .takeIf { it.isNotBlank() } ?: uri.host ?: "Network Stream"
        } catch (e: Exception) {
          lastSegment
            .substringBefore("?")
            .substringBefore("#")
        }
      }

      // If no filename in path, use hostname
      return uri.host ?: "Network Stream"
    }

    // For file:// and content:// URIs - preserve # characters as they're part of the filename
    val lastSegment = uri.lastPathSegment?.substringAfterLast("/") ?: uri.path ?: "Unknown Video"
    
    // For local files, only decode URL encoding but preserve # characters
    return try {
      java.net.URLDecoder.decode(lastSegment, "UTF-8")
    } catch (e: Exception) {
      lastSegment
    }
  }

  /**
   * Gets the display title for a playlist item URI.
   *
   * @param uri The URI to get the title for
   * @return The display name/title of the file
   */
  internal fun getPlaylistItemTitle(uri: Uri): String {
    // Try content resolver first for content:// URIs
    getDisplayNameFromUri(uri)?.let { return it }

    // Extract filename from URL/URI
    return extractFileNameFromUri(uri)
  }

  /**
   * Plays a playlist item by index.
   *
   * @param index The index of the playlist item to play
   */
  internal fun playPlaylistItem(index: Int) {
    if (index in playlist.indices) {
      loadPlaylistItem(index)
    }
  }

  /**
   * Extracts the URI from the intent based on intent type.
   *
   * @param intent The intent to extract URI from
   * @return The extracted URI, or null if not found
   */
  private fun extractUriFromIntent(intent: Intent): Uri? =
    if (intent.type == "text/plain") {
      intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()
    } else {
      intent.data ?: if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
      }
    }

  /**
   * Queries the content resolver to get the display name for a URI.
   *
   * @param uri The URI to query
   * @return The display name, or null if not found
   */
  private fun getDisplayNameFromUri(uri: Uri): String? =
    runCatching {
      contentResolver
        .query(
          uri,
          arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
          null,
          null,
          null,
        )?.use { cursor ->
          if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.onFailure { e ->
      Log.e(TAG, "Error getting display name from URI", e)
    }.getOrNull()

  /**
   * Converts the intent URI to a playable URI string for MPV.
   *
   * @param intent The intent containing the file URI
   * @return A playable URI string, or null if unable to resolve
   */
  private fun getPlayableUri(intent: Intent): String? {
    val uri = parsePathFromIntent(intent) ?: return null
    return if (uri.startsWith("content://")) {
      uri.toUri().openContentFd(this)
    } else {
      uri
    }
  }

  /**
   * Handles device configuration changes.
   *
   * @param newConfig The new configuration
   */
  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    if (isReady) {
      handleConfigurationChange()
    }
  }

  /**
   * Handles configuration changes by updating video aspect ratio.
   */
  private fun handleConfigurationChange() {
    if (!isInPictureInPictureMode) {
      // Configuration changes don't affect aspect ratio
    } else {
      viewModel.hideControls()
    }
  }

  // ==================== MPV Event Observers ====================

  /**
   * Observer callback for MPV property changes (Long values).
   * Handles video width and height changes.
   *
   * @param property The property name that changed
   * @param value The new Long value
   */
  @Suppress("UnusedParameter")
  internal fun onObserverEvent(
    property: String,
    value: Long,
  ) {
    when (property) {
      "video-params/w",
      "video-params/h",
      "video-params/dw",
      "video-params/dh" -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return

        val aspect = player.getVideoOutAspect()
        Log.d(TAG, "Video dimension changed: $property, aspect: $aspect")
        pipHelper.updatePictureInPictureParams()
        // Update orientation when video dimensions change (fixes Video orientation mode)
        if (playerPreferences.orientation.get() == PlayerOrientation.Video && aspect != null) {
          setOrientation()
        }

        // Re-apply Anime4K shaders (check for resolution limit)
        player.applyAnime4KShaders()
      }
    }
  }

  /**
   * Observer callback for MPV property changes (Boolean values).
   * Handles pause state and end-of-file events.
   *
   * @param property The property name that changed
   * @param value The new Boolean value
   */
  internal fun onObserverEvent(
    property: String,
    value: Boolean,
  ) {
    when (property) {
      "pause" -> {
        handlePauseStateChange(value)
        // Ensure isReady is set when playback starts
        if (!value && !isReady) {
          isReady = true
        }
      }
      "eof-reached" -> handleEndOfFile(value)
    }
  }

  /**
   * Handles pause state changes by managing screen-on flag and MediaSession state.
   *
   * @param isPaused true if playback is paused, false if playing
   */
  private fun handlePauseStateChange(isPaused: Boolean) {
    if (isPaused) {
      // Snapshot the active tracks, but only when a real track is selected. This keeps a
      // previously captured (>0) value if the firmware already cleared aid/sid the instant
      // pause started, so the restore on resume still has something valid to re-apply.
      if (player.aid > 0) audioTrackBeforePause = player.aid
      if (player.sid > 0) subTrackBeforePause = player.sid
      // Only clear keep-screen-on if the preference is NOT enabled
      if (!playerPreferences.keepScreenOnWhenPaused.get()) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    } else {
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      // Reload the audio output on resume. Some Android TV firmwares (TCL/MStar) leave the
      // AudioTrack AO in a broken state after pause: the active audio track is lost AND the
      // audio clock drifts out of sync with the video. `ao-reload` tears down and recreates
      // the audio output at the current playback position, fixing both symptoms at once. It is
      // a no-op-cost, seamless operation on devices that don't exhibit the bug. Only run when a
      // real audio track was active when we paused (audioTrackBeforePause > 0), so audio-less
      // files are left untouched (the snapshot was reset to 0 on file load / when audio is off).
      if (audioTrackBeforePause > 0) {
        Log.d(TAG, "Reloading audio output after resume to repair dropped track / A-V desync")
        runCatching { MPVLib.command("ao-reload") }
      }
      // Belt-and-suspenders: if the firmware actually dropped the active audio/subtitle track
      // (aid/sid reset to a different value), re-attach it. This only fires when the track
      // really changed, so a healthy device (where ao-reload already restored everything) gets
      // no extra churn; on a broken firmware it mirrors the manual "re-select track" recovery.
      lifecycleScope.launch(Dispatchers.Main) {
        delay(120)
        if (audioTrackBeforePause > 0 && player.aid != audioTrackBeforePause) {
          Log.d(TAG, "Audio track still missing after ao-reload; re-attaching aid=$audioTrackBeforePause")
          runCatching { player.aid = audioTrackBeforePause }
        }
        if (subTrackBeforePause > 0 && player.sid != subTrackBeforePause) {
          Log.d(TAG, "Subtitle track still missing after ao-reload; re-attaching sid=$subTrackBeforePause")
          runCatching { player.sid = subTrackBeforePause }
        }
      }
      // Video counterpart of the repair above: a paused/resumed session can come back with
      // the video output dead (black screen, audio still playing). Give mpv a moment and
      // rebuild the VO + re-select the video track if the picture did not come back.
      lifecycleScope.launch(Dispatchers.Main) {
        delay(600)
        runCatching { player.recoverVideoOutputIfNeeded() }
      }
    }
    updateMediaSessionPlaybackState(!isPaused)
    runCatching {
      if (isInPictureInPictureMode) {
        pipHelper.updatePictureInPictureParams()
      }
    }.onFailure { /* Silently ignore PiP update failures */ }
  }

  /**
   * Handles end-of-file event by playing next in playlist if available, otherwise finishing activity if configured.
   *
   * @param isEof true if end of file reached
   */
  private fun handleEndOfFile(isEof: Boolean) {
    if (isEof) {
      // Guard against spurious end-of-file. mpv raises eof-reached both on a genuine end
      // of file AND when the stream is interrupted (e.g. SMB read error / idle timeout on
      // long files / TV cache cleared via adb) or when duration is misprobed. Blindly
      // auto-advancing to the next episode in those cases skips mid-playback (observed:
      // TCL 3GB TV, long SMB file jumps to next ~1h in; also reproduced by clearing the TV
      // cache, which kills the SMB socket and makes mpv report duration=0). Treat it as a
      // real end ONLY when we can positively confirm the playhead reached the end (known
      // duration AND pos within 5s of it). Otherwise the stream simply died — do NOT
      // advance.
      val dur = MPVLib.getPropertyDouble("duration") ?: 0.0
      val pos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
      val isRealEnd = dur > 0.0 && pos >= dur - 5.0
      if (!isRealEnd) {
        Log.w(
          TAG,
          "EOF guard: source ended early (pos=${"%.1f".format(pos)}s / " +
            "dur=${"%.1f".format(dur)}s, playlistIndex=$playlistIndex/${playlist.size}); " +
            "NOT advancing to next episode",
        )
        // Reporting the truncated stream is only half the job — the previous code stopped
        // here. Because `keep-open` pauses the player at this very instant through
        // set_pause_state(true), which does NOT raise `paused-for-cache`, the stall
        // watchdog's manual-pause guard stood down as well. Two independent guards both
        // declined to act, so playback stayed frozen until the video was re-opened by hand.
        // Rebuild the source from the last position that actually played instead — but only
        // when the playhead is genuinely far from the end, so that a container whose reported
        // duration overshoots the real end still simply finishes.
        if (isPrematureSourceEnd(pos, dur, isEof)) {
          recoverFromPrematureEof(pos, dur)
        } else {
          Log.w(TAG, "EOF guard: near the reported end of file - leaving playback alone")
        }
        return
      }
      Log.d(
        TAG,
        "EOF guard: real end-of-file confirmed (pos=${"%.1f".format(pos)}s / " +
          "dur=${"%.1f".format(dur)}s); advancing",
      )

      // Check if we should repeat the current file
      if (viewModel.shouldRepeatCurrentFile()) {
        MPVLib.command("seek", "0", "absolute")
        viewModel.unpause()
        return
      }

      // Handle playlist playback
      if (playlist.isNotEmpty()) {
        val hasNextItem = if (viewModel.shuffleEnabled.value) {
          shuffledPosition < shuffledIndices.size - 1
        } else {
          playlistIndex < playlist.size - 1
        }

        // Check if autoplay next video is enabled
        val autoplayEnabled = playerPreferences.autoplayNextVideo.get()

        if (hasNextItem && (autoplayEnabled || viewModel.shouldRepeatPlaylist())) {
          // Play next item in playlist
          playNext()
        } else if (viewModel.shouldRepeatPlaylist()) {
          // At end of playlist with repeat ALL: restart from beginning
          if (viewModel.shuffleEnabled.value) {
            // Regenerate shuffle order and start from beginning
            generateShuffledIndices()
            shuffledPosition = 0
            playlistIndex = shuffledIndices[0]
            loadPlaylistItem(playlistIndex)
          } else {
            // Normal mode: restart from index 0
            playlistIndex = 0
            loadPlaylistItem(0)
          }
        } else if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
          // No autoplay or no next item, end of playlist: close if setting is enabled
          finishAndRemoveTask()
        }
        // If autoplay is off and closeAfterReachingEndOfVideo is off, just stay on current video
      } else {
        // Single video playback (no playlist)
        if (playerPreferences.closeAfterReachingEndOfVideo.get()) {
          finishAndRemoveTask()
        }
      }
    }
  }

  /**
   * Observer callback for MPV property changes (MPVNode values).
   *
   * This method is called when an MPV property (with MPVNode value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new MPVNode value
   */
  internal fun onObserverEvent(
    property: String,
    value: MPVNode,
  ) {
    // Currently no MPVNode properties are handled
  }

  /**
   * Observer callback for MPV property changes (Double values).
   *
   * This method is called when an MPV property (with Double value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new Double value
   */
  internal fun onObserverEvent(
    property: String,
    value: Double,
  ) {
    // Handle Double properties
    when (property) {
      "video-params/aspect" -> {
        // Safety check: don't access MPV during cleanup
        if (!mpvInitialized || player.isExiting || isFinishing) return

        val aspect = player.getVideoOutAspect()
        Log.d(TAG, "video-params/aspect changed: $aspect")
        // Letterbox mediacodec_embed output (SurfaceView can't be letterboxed by mpv)
        player.applyEmbedAspectRatio(aspect)
        pipHelper.updatePictureInPictureParams()
        // Update orientation when video aspect ratio changes (fixes Video orientation mode)
        // BUT: Don't update if aspect is being overridden (stretch/custom aspect mode)
        // to prevent infinite orientation switching loop
        val aspectOverride = MPVLib.getPropertyDouble("video-aspect-override") ?: -1.0
        if (playerPreferences.orientation.get() == PlayerOrientation.Video && 
            aspect != null && 
            aspectOverride <= 0.0) {
          setOrientation()
        }
      }
    }
  }

  /**
   * Observer callback for MPV property changes (String values).
   *
   * This method is called when an MPV property (with String value) changes.
   * Extend this method to handle properties as needed.
   *
   * @param property The property name that changed
   * @param value The new String value
   */
  internal fun onObserverEvent(
    property: String,
    value: String,
  ) {
    // Currently no String properties are handled
  }

  /**
   * Observer callback for MPV property changes (no value parameter).
   * Handles properties with no value parameter.
   *
   * @param property The property name that changed
   */
  internal fun onObserverEvent(property: String) {
    // Currently no properties use this signature
  }

  /**
   * Handles MPV core events such as file loaded and playback restart.
   *
   * Called by the player when critical playback events occur.
   *
   * @param eventId The MPV event ID
   */
  internal fun event(eventId: Int) {
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
        handleFileLoaded()
        isReady = true
      }

      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
        player.isExiting = false
        if (!isReady) {
          isReady = true
        }
        // Apply a deferred resume-seek once playback has actually (re)started.
        // pendingResumeSeek is preloaded synchronously in handleFileLoaded (before this
        // event can fire on the main thread), so the target is always available here.
        // We only seek until the position is reached, then mark it applied so a later
        // user seek / natural restart never gets yanked back to the resume point.
        if (!resumeSeekApplied) {
          pendingResumeSeek?.let { target ->
            val cur = MPVLib.getPropertyInt("time-pos") ?: 0
            if (cur < target - 2) {
              Log.d(TAG, "Resume seek: applying deferred position $target (current $cur)")
              MPVLib.setPropertyInt("time-pos", target)
            } else {
              resumeSeekApplied = true
            }
          } ?: run { resumeSeekApplied = true }
        }

        // Auto skip intro — runs once per file at the first (re)start. Decoupled from the
        // resume system: it only triggers when playback is genuinely fresh (no deferred
        // resume-seek is pending) AND the playhead is still inside the intro window
        // (time-pos <= introSeconds). This makes EVERY episode skip its intro, including
        // auto-played ones, while a real resume (parked past the intro) is left untouched.
        // Re-arm the once-per-file guard whenever the media changes, so a missed
        // FILE_LOADED reset can never leave the guard stuck from the previous episode.
        if (mediaIdentifier != lastIntroSkipMediaId) {
          introSkipApplied = false
          lastIntroSkipMediaId = mediaIdentifier
        }
        if (!introSkipApplied) {
          introSkipApplied = true
          if (playerPreferences.skipIntroOutroEnabled.get()
            && !isCurrentMediaInSourceRoot()
            && pendingResumeSeek == null
          ) {
            val introSec = playerPreferences.skipIntroSeconds.get().coerceAtLeast(1)
            val cur = MPVLib.getPropertyInt("time-pos") ?: 0
            if (cur <= introSec) {
              Log.d(TAG, "Skip intro: jumping from ${cur}s to ${introSec}s")
              MPVLib.setPropertyInt("time-pos", introSec)
            }
          }
        }
      }
    }
  }

  // ==================== Auto skip intro / outro helpers ====================

  /**
   * Watches playback progress so the outro can be skipped once the remaining time falls
   * inside the configured window. Reacts to mpv's own [MPVLib.propInt] time-pos/duration
   * StateFlows (more reliable than polling getPropertyInt, which can momentarily report a
   * 0 duration and silently disable the check). Only ever advances once per file via
   * [outroSkipTriggered].
   */
  private fun startOutroSkipMonitor() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.RESUMED) {
        combine(
          MPVLib.propInt["time-pos"],
          MPVLib.propInt["duration"],
        ) { pos, duration -> Pair(pos, duration) }
          .collect { (pos, duration) -> checkOutroSkip(pos, duration) }
      }
    }
  }

  /**
   * Advances to the next episode when the remaining time falls inside the configured outro
   * window. Called from the per-second monitor; [outroSkipTriggered] makes it fire once per file.
   */
  private fun checkOutroSkip(pos: Int?, duration: Int?) {
    if (outroSkipTriggered) return
    if (!playerPreferences.skipIntroOutroEnabled.get()) return
    // Don't auto-advance while paused (e.g. user parked at the end of an episode).
    if (MPVLib.getPropertyBoolean("pause") == true) return

    val current = pos ?: return
    val total = duration ?: return
    if (total <= 0 || current <= 0) return

    val outroSeconds = playerPreferences.skipOutroSeconds.get()
    val remaining = total - current
    // Only advance once the remaining time drops to (or below) the configured outro
    // window. Near the very start remaining is the full duration (> outroSeconds), so
    // this naturally only fires in the final seconds of the episode.
    if (remaining > outroSeconds) return

    // Mark handled first so we never double-advance while the next file loads.
    outroSkipTriggered = true

    if (isCurrentMediaInSourceRoot()) {
      Log.d(TAG, "Skip outro: disabled, media sits directly in the source root")
      return
    }
    if (!hasNext()) {
      Log.d(TAG, "Skip outro: no next episode in playlist, letting playback end normally")
      return
    }

    Log.d(TAG, "Skip outro: ${remaining}s left (<= ${outroSeconds}s), advancing to next episode")
    playNext()
  }

  /**
   * True when the media sits directly in the root of its source — the storage root for local
   * files, or the share base directory for SMB/WebDAV/FTP. Root-level files are typically
   * standalone movies, so auto skipping is turned off for them.
   */
  private fun isCurrentMediaInSourceRoot(): Boolean {
    val currentUri = playlist.getOrNull(playlistIndex) ?: intent.data
    val networkFilePath =
      currentUri?.let { playlistNetworkFilePaths[it] }
        ?: intent.getStringExtra("network_file_path")
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

    // Network playback: a file is "in the source root" only when its parent directory
    // equals the configured share/mount root. IMPORTANT: viewModel.networkBaseDir holds the
    // *current file's* parent (the series subfolder), NOT the share root — comparing against
    // it wrongly marked every network episode as root-level and disabled intro/outro skipping
    // for all SMB/WebDAV/FTP playback. The true share root is cached in [networkShareRoot]
    // (populated asynchronously from the connection config); until it's available we treat
    // the file as NOT root-level so skipping stays enabled rather than silently disabled.
    if (!networkFilePath.isNullOrBlank() && networkConnectionId != -1L) {
      val shareRoot = networkShareRoot ?: return false
      val canonical = NetworkMediaIdUtils.canonicalizeNetworkPath(networkFilePath) ?: return false
      val fileParent = NetworkMediaIdUtils.parentPath(canonical)
      return fileParent.trimEnd('/').equals(shareRoot.trimEnd('/'), ignoreCase = true)
    }

    // Local playback: compare the parent directory against the storage roots.
    val path = currentUri?.resolveUri(this) ?: parsePathFromIntent(intent)
    if (path.isNullOrBlank()) return false
    val parent = File(path).parent ?: return false
    return isStorageRoot(parent)
  }

  /** True when [path] is a top-level storage root (internal storage, SD card, USB drive). */
  private fun isStorageRoot(path: String): Boolean {
    val normalized = path.trimEnd('/')
    if (normalized.isBlank()) return false
    if (
      normalized.equals("/storage/emulated/0", ignoreCase = true) ||
      normalized.equals("/sdcard", ignoreCase = true) ||
      normalized.equals("/mnt/sdcard", ignoreCase = true)
    ) {
      return true
    }
    val externalRoot =
      android.os.Environment.getExternalStorageDirectory()?.absolutePath?.trimEnd('/')
    if (externalRoot != null && normalized.equals(externalRoot, ignoreCase = true)) return true
    // /storage/<volume-id> for removable SD cards and USB drives.
    val segments = normalized.trim('/').split('/')
    return segments.size == 2 && segments[0].equals("storage", ignoreCase = true)
  }

  /**
   * Asynchronously resolves and caches the share/mount root of the active network connection
   * (e.g. `smb://host/share`) so [isCurrentMediaInSourceRoot] can compare a file's parent
   * against the REAL share root instead of the current file's parent. Must be called off the
   * synchronous event path because [NetworkRepository.getConnectionById] is a suspend function.
   */
  private fun refreshNetworkShareRoot() {
    val connectionId = intent.getLongExtra("network_connection_id", -1L)
    if (connectionId == -1L) {
      networkShareRoot = null
      return
    }
    lifecycleScope.launch(Dispatchers.IO) {
      val conn = runCatching { networkRepository.getConnectionById(connectionId) }.getOrNull()
      networkShareRoot = conn?.let {
        val rawRoot =
          "${it.protocol.name.lowercase()}://${it.host}" +
            (if (it.port != -1) ":${it.port}" else "") +
            (if (it.path.startsWith("/")) it.path else "/${it.path}")
        NetworkMediaIdUtils.canonicalizeNetworkPath(rawRoot)
      }
    }
  }

  /**
   * Handles the file loaded event from MPV.
   * Initializes playback state, loads saved playback data, restores custom settings,
   * applies user preferences, and sets up metadata and media session.
   */
  private fun handleFileLoaded() {
    // Clear any deferred resume target from the previous file before loading new state.
    pendingResumeSeek = null
    resumeSeekApplied = false

    // Rebase the stall watchdog for this file before anything else can look at it. One Activity
    // plays many files in a row (singleTask onNewIntent, auto-advance) and each of them restarts
    // `time-pos` from 0, so the previous file's baseline must not survive into this one. A
    // recovery reload of the SAME source is not a new file: it must keep its rebuild budget.
    rebaseStallWatchdogForNewFile(recoveryReloadPending)

    // Reset per-file audio/subtitle restore snapshots so a previous file's track ids are
    // not re-applied after a later file (which may have different or no tracks at all).
    audioTrackBeforePause = 0
    subTrackBeforePause = 0

    // vo=mediacodec_embed opens the video output the moment the file starts. If the OSD
    // surface is not there yet the open fails, mpv deselects the video track and audio
    // keeps playing over a black screen. Re-check once the load settled and rebuild the
    // video output if the picture never appeared.
    lifecycleScope.launch(Dispatchers.Main) {
      delay(900)
      runCatching { player.recoverVideoOutputIfNeeded() }
    }

    // Reset per-file auto skip state. The intro decision is made live in the
    // PLAYBACK_RESTART handler (time-based, decoupled from resume), so we only need
    // to clear the "already applied" flag here; the outro flag is also reset so the
    // per-second monitor can evaluate this file from scratch.
    introSkipApplied = false
    outroSkipTriggered = false

    // Extract fileName from intent only if not already set
    // This preserves fileName set in onNewIntent or onCreate
    if (fileName.isBlank()) {
      fileName = getFileName(intent)
      // Ensure fileName is not blank - use a fallback if necessary
      if (fileName.isBlank()) {
        fileName = intent.data?.lastPathSegment ?: "Unknown Video"
      }
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    } else if (mediaIdentifier.isBlank()) {
      // If fileName was already set, but mediaIdentifier is missing, set it for safety
      mediaIdentifier = getMediaIdentifier(intent, fileName)
    }

    // Refresh the cached network share root (async) so intro/outro skip can correctly
    // decide whether this network file sits directly in the share root.
    refreshNetworkShareRoot()

    // Synchronously preload the resume position. event() is dispatched on the main
    // thread (runOnUiThread), so the PLAYBACK_RESTART handler cannot run until this
    // method returns — meaning pendingResumeSeek is always set before the first
    // restart. The disk read happens on an IO dispatcher, only briefly blocking the
    // main thread (one indexed row).
    if (mediaIdentifier.isNotBlank()) {
      pendingResumeSeek = runCatching {
        runBlocking(Dispatchers.IO) {
          val s = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)
          if (s != null && playerPreferences.savePositionOnQuit.get() && s.lastPosition != 0) s.lastPosition else null
        }
      }.getOrNull()
    }

    // Start media notification service (like YouTube - always show notification)
    startBackgroundPlayback()

    // Reset AB loop values when video changes
    viewModel.clearABLoop()

    setIntentExtras(intent.extras)
    logActiveRenderer()

    lifecycleScope.launch(Dispatchers.IO) {
      // Load playback state (will skip track restoration if preferred language configured)
      val hasState = loadVideoPlaybackState(fileName)

      // Auto-advance: ensure the freshly loaded next file is not left paused
      if (shouldResumeAfterLoad) {
        shouldResumeAfterLoad = false
        withContext(Dispatchers.Main) { viewModel.unpause() }
      }

      // Apply track selection logic (defaults only apply when no saved state).
      // Pass the directory/playlist scope so a carried-over subtitle/audio choice only
      // follows within the same folder/playlist (and not into an unrelated file).
      trackSelector.onFileLoaded(hasState, currentTrackScopeKey())

      // Apply default zoom only if there's no saved state
      if (!hasState) {
        withContext(Dispatchers.Main) {
          val zoomPreference = playerPreferences.defaultVideoZoom.get()
          MPVLib.setPropertyDouble("video-zoom", zoomPreference.toDouble())
          viewModel.setVideoZoom(zoomPreference)
        }
      }

      // Apply saved aspect ratio setting
      withContext(Dispatchers.Main) {
        val savedAspect = playerPreferences.defaultVideoAspect.get()
        val savedCustomRatio = playerPreferences.defaultCustomAspectRatio.get()

        if (savedCustomRatio > 0) {
          // Apply custom aspect ratio
          viewModel.setCustomAspectRatio(savedCustomRatio)
        } else {
          // Apply standard aspect mode (Fit, Crop, or Stretch)
          viewModel.changeVideoAspect(savedAspect, showUpdate = false)
        }
      }

      // Autoload sibling subtitle files (local + network), then re-run subtitle
      // selection so a remembered external/track choice can match a sub that only
      // became available after the async autoload completed.
      if (subtitlesPreferences.autoloadMatchingSubtitles.get()) {
        autoloadMatchingSubtitlesForCurrent()
        trackSelector.reselectSubtitlesNow(hasState)
      }
    }

    // Save to recently played when video actually loads and plays
    lifecycleScope.launch(Dispatchers.IO) {
      if (playlist.isNotEmpty()) {
        // For playlist items, save using the current URI
        // All items are loaded, so playlistIndex is the direct index
        if (playlistIndex >= 0 && playlistIndex < playlist.size) {
          saveRecentlyPlayedForUri(playlist[playlistIndex], fileName)
        } else {
          Log.w(TAG, "Cannot save recently played: invalid playlist index $playlistIndex (playlist size: ${playlist.size})")
        }
      } else {
        // For non-playlist videos, use the original saveRecentlyPlayed
        saveRecentlyPlayed()
      }
    }

    // Only set orientation immediately if NOT in Video mode
    // For Video mode, wait for video-params/aspect to become available
    if (playerPreferences.orientation.get() != PlayerOrientation.Video) {
      setOrientation()
    } else {
      // For Video mode, try to set orientation after a short delay to ensure
      // video dimensions are available
      lifecycleScope.launch {
        kotlinx.coroutines.delay(100)
        if (mpvInitialized && !player.isExiting && !isFinishing) {
          val aspect = player.getVideoOutAspect()
          Log.d(TAG, "handleFileLoaded - Video mode, aspect after delay: $aspect")
          if (aspect != null && aspect > 0) {
            setOrientation()
          }
        }
      }
    }

    applySubtitlePreferences()

    // Don't force media-title for m3u/m3u8 streams - let MPV provide it
    if (!isCurrentStreamM3U()) {
      MPVLib.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    viewModel.unpause()

    // Publish network playback context so the "add external subtitle" picker can browse
    // the remote share (instead of local storage) when streaming from SMB/WebDAV/FTP.
    run {
      val currentUri = playlist.getOrNull(playlistIndex) ?: intent.data
      val networkFilePath = currentUri?.let { playlistNetworkFilePaths[it] } ?: intent.getStringExtra("network_file_path")
      val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)
      viewModel.setNetworkContext(networkConnectionId, networkFilePath)
    }

    updateMediaSessionMetadata(
      title = fileName,
      durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L,
    )
    updateMediaSessionPlaybackState(isPlaying = true)

    // Asynchronously fetch better filename from HTTP headers for network streams
    fetchNetworkStreamTitle()
  }

  /**
   * Autoloads sibling subtitle files for the currently loaded media.
   * For SMB/WebDAV/FTP this scans the original network directory via the network client;
   * for local files it scans the parent directory. Suspends until the sub-add commands
   * have been issued so callers can safely re-run subtitle selection afterwards.
   */
  private suspend fun autoloadMatchingSubtitlesForCurrent() {
    // For network files played via proxy (SMB/WebDAV/FTP), use the original network file path
    val currentUri = playlist.getOrNull(playlistIndex) ?: intent.data
    val networkFilePath = currentUri?.let { playlistNetworkFilePaths[it] } ?: intent.getStringExtra("network_file_path")
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

    if (networkFilePath != null && networkConnectionId != -1L) {
      // Pass network file path and connection ID for subtitle discovery
      SubtitleOps.autoloadSubtitles(
        videoFilePath = networkFilePath,
        videoFileName = fileName,
        networkConnectionId = networkConnectionId,
      )
    } else {
      // Regular file or direct network stream
      val filePath = parsePathFromIntent(intent)
      if (filePath != null) {
        SubtitleOps.autoloadSubtitles(
          videoFilePath = filePath,
          videoFileName = fileName,
        )
      }
    }
  }

  private fun logActiveRenderer() {
    val requestedVulkan = runCatching { decoderPreferences.useVulkan.get() }.getOrDefault(false)
    val actualVo = runCatching { MPVLib.getPropertyString("vo") }.getOrNull()
    val actualGpuApi = runCatching { MPVLib.getPropertyString("gpu-api") }.getOrNull()
    val actualGpuContext = runCatching { MPVLib.getPropertyString("gpu-context") }.getOrNull()
    Log.d(
      TAG,
      "Renderer status: requestedVulkan=$requestedVulkan, vo=$actualVo, gpuApi=$actualGpuApi, gpuContext=$actualGpuContext",
    )
  }

  /**
   * Fetches a better title from HTTP headers for network streams asynchronously.
   * Updates the title in UI, MPV, and media session if a better name is found.
   */
  private fun fetchNetworkStreamTitle() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val uri = extractUriFromIntent(intent)
        if (uri == null || !HttpUtils.isNetworkStream(uri)) {
          return@launch
        }

        // Skip fetching for m3u/m3u8 streams - let MPV provide the title
        if (isCurrentStreamM3U()) {
          Log.d(TAG, "Skipping title fetch for m3u/m3u8 stream: $uri")
          return@launch
        }

        // Skip fetching if title was provided in intent extras (e.g. from Jellyfin or other external launchers)
        // This prevents overwriting the correct title with a generic filename from the URL (like "stream")
        if (intent.hasExtra("title") || intent.hasExtra("filename")) {
          Log.d(TAG, "Skipping title fetch because title was explicitly provided in intent: $fileName")
          return@launch
        }

        // Skip fetching for local proxy URLs (SMB/WebDAV/FTP files)
        // These already have correct filename from intent extras
        val host = uri.host?.lowercase()
        if (host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
          Log.d(TAG, "Skipping title fetch for local proxy URL: $uri")
          return@launch
        }

        val url = uri.toString()
        Log.d(TAG, "Fetching title from network stream: $url")

        val betterFilename = HttpUtils.extractFilenameFromUrl(url)
        if (betterFilename != null && betterFilename.isNotBlank() &&
          betterFilename != fileName &&
          betterFilename != uri.host &&
          betterFilename != "Network Stream"
        ) {

          Log.d(TAG, "Found better filename from HTTP headers: $betterFilename")

          // Update fileName
          fileName = betterFilename

          // DO NOT update mediaIdentifier - keep the original identifier for playback state consistency
          // The URI hash in mediaIdentifier ensures position is saved/loaded correctly even if filename changes

          // Update MPV title
          withContext(Dispatchers.Main) {
            MPVLib.setPropertyString("force-media-title", fileName)
            viewModel.setMediaTitle(fileName)

            // Update media session
            val durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
            updateMediaSessionMetadata(
              title = fileName,
              durationMs = durationMs,
            )

            // Update background service if connected
            if (serviceBound && mediaPlaybackService != null) {
              val artist = runCatching { MPVLib.getPropertyString("metadata/artist") }.getOrNull() ?: ""
              val thumbnail = runCatching { MPVLib.grabThumbnail(1080) }.getOrNull()
              mediaPlaybackService?.setMediaInfo(title = fileName, artist = artist, thumbnail = thumbnail)
            }
          }

          // Update recently played with the parsed video title, duration, and file size
          val filePath = when (uri.scheme) {
            "file" -> uri.path ?: uri.toString()
            "content" -> {
              contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else null
              } ?: uri.toString()
            }

            else -> uri.toString()
          }

          // Get duration and file size from MPV
          val updatedDuration = runCatching {
            (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
          }.getOrDefault(0L)

          val updatedFileSize = runCatching {
            // Try multiple properties to get file size
            MPVLib.getPropertyDouble("file-size")?.toLong()
              ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
              ?: 0L
          }.getOrDefault(0L)

          // Get video resolution from MPV
          val updatedWidth = runCatching {
            MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
          }.getOrDefault(0)

          val updatedHeight = runCatching {
            MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
          }.getOrDefault(0)

          // Update metadata without thumbnail
          runCatching {
            RecentlyPlayedOps.updateVideoMetadata(
              filePath,
              fileName,
              updatedDuration,
              updatedFileSize,
              updatedWidth,
              updatedHeight,
            )
            Log.d(
              TAG,
              "Updated recently played metadata: $fileName (duration: ${updatedDuration}ms, size: ${updatedFileSize}B, resolution: ${updatedWidth}x${updatedHeight}) for $filePath",
            )
          }.onFailure { e ->
            Log.e(TAG, "Error updating video metadata in recently played", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error fetching network stream title", e)
      }
    }
  }

  /**
   * Applies all saved subtitle preferences when a file is loaded.
   * This ensures subtitle customizations (font, colors, position, etc.) persist across videos.
   */
  private fun applySubtitlePreferences() {
    // Typography settings
    MPVLib.setPropertyString("sub-font", subtitlesPreferences.font.get())
    MPVLib.setPropertyString("secondary-sub-font", subtitlesPreferences.font.get())
    MPVLib.setPropertyInt("sub-font-size", subtitlesPreferences.fontSize.get())
    MPVLib.setPropertyBoolean("sub-bold", subtitlesPreferences.bold.get())
    MPVLib.setPropertyBoolean("sub-italic", subtitlesPreferences.italic.get())
    MPVLib.setPropertyString("sub-justify", subtitlesPreferences.justification.get().value)
    MPVLib.setPropertyString("sub-border-style", subtitlesPreferences.borderStyle.get().value)
    MPVLib.setPropertyInt("sub-outline-size", subtitlesPreferences.borderSize.get())
    MPVLib.setPropertyInt("sub-shadow-offset", subtitlesPreferences.shadowOffset.get())

    // Color settings
    MPVLib.setPropertyString("sub-color", subtitlesPreferences.textColor.get().toColorHexString())
    MPVLib.setPropertyString("sub-border-color", subtitlesPreferences.borderColor.get().toColorHexString())
    MPVLib.setPropertyString("sub-back-color", subtitlesPreferences.backgroundColor.get().toColorHexString())

    // Miscellaneous settings
    val overrideAssSubs = subtitlesPreferences.overrideAssSubs.get()
    MPVLib.setPropertyString("sub-ass-override", if (overrideAssSubs) "force" else "scale")
    MPVLib.setPropertyString("secondary-sub-ass-override", if (overrideAssSubs) "force" else "scale")

    val scaleByWindow = subtitlesPreferences.scaleByWindow.get()
    val scaleValue = if (scaleByWindow) "yes" else "no"
    MPVLib.setPropertyString("sub-scale-by-window", scaleValue)
    MPVLib.setPropertyString("sub-use-margins", scaleValue)

    MPVLib.setPropertyFloat("sub-scale", subtitlesPreferences.subScale.get())
    MPVLib.setPropertyInt("sub-pos", subtitlesPreferences.subPos.get())

    Log.d(TAG, "Applied subtitle preferences")
  }

  /**
   * Helper extension function to convert Int color to hex string for MPV
   */
  @OptIn(ExperimentalStdlibApi::class)
  private fun Int.toColorHexString() = "#" + this.toHexString().uppercase()

  /**
   * Saves the current playback state to the database.
   *
   * Uses lifecycleScope to save state; cancels previous pending saves.
   *
   * @param mediaTitle The title of the media being played
   */
  /**
   * Saves the current playback state to the database.
   *
   * Uses lifecycleScope to save state; cancels previous pending saves.
   *
   * @param mediaTitle The title of the media being played
   */
  private fun saveVideoPlaybackState(mediaTitle: String) {
    if (mediaIdentifier.isBlank()) return

    val currentPos = MPVLib.getPropertyInt("time-pos") ?: 0
    val currentDuration = MPVLib.getPropertyInt("duration") ?: 0

    // If the media is no longer loaded (both position and duration are 0),
    // do not overwrite any previously saved valid progress.
    if (currentDuration <= 0 && currentPos <= 0) {
      Log.d(TAG, "Skipping saveVideoPlaybackState because duration and position are 0 (media likely unloaded)")
      return
    }

    // Capture current track selection manually in session memory
    trackSelector.saveCurrentTrackSelection()
    val currentNetworkConnectionId = intent.getLongExtra("network_connection_id", -1L)
    val currentNetworkPath = currentCanonicalNetworkPath()
    val currentNetworkDirectory = currentNetworkPath?.let(NetworkMediaIdUtils::parentPath)
    val networkTrackProfile = if (currentNetworkConnectionId != -1L && currentNetworkDirectory != null) {
      trackSelector.buildProfile(
        connectionId = currentNetworkConnectionId,
        directoryPath = currentNetworkDirectory,
      )
    } else {
      null
    }

    // Capture all necessary state variables SYNCHRONOUSLY before launching the coroutine.
    // This prevents a race condition where mediaIdentifier or other values are updated
    // for the NEXT video before the coroutine for the CURRENT video runs.
    val currentIdentifier = mediaIdentifier
    val currentSpeed = MPVLib.getPropertyDouble("speed") ?: DEFAULT_PLAYBACK_SPEED
    val currentZoom = MPVLib.getPropertyDouble("video-zoom")?.toFloat() ?: 0f
    val currentSid = player.sid
    val currentSecondarySid = player.secondarySid
    val currentSubDelay = ((MPVLib.getPropertyDouble("sub-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt()
    val currentSubSpeed = MPVLib.getPropertyDouble("sub-speed") ?: DEFAULT_SUB_SPEED
    val currentAid = player.aid
    val currentAudioDelay = ((MPVLib.getPropertyDouble("audio-delay") ?: 0.0) * MILLISECONDS_TO_SECONDS).toInt()
    val currentExternalSubs = viewModel.externalSubtitles.toList() // Copy the list

    // Only supersede a still-pending save for the SAME media (to keep the latest position).
    // Cancelling a *different* file's save (e.g. during a playlist transition) could drop
    // its write mid-flight, so leave it running to completion.
    if (savePlaybackStateJobIdentifier == currentIdentifier) {
      savePlaybackStateJob?.cancel()
    }
    savePlaybackStateJobIdentifier = currentIdentifier

    // Launch new save job and track it using non-cancelling playerScope
    savePlaybackStateJob = playerScope.launch(Dispatchers.IO) {
      runCatching {
        val oldState = playbackStateRepository.getVideoDataByTitle(currentIdentifier)
        Log.d(TAG, "Saving playback state for: $mediaTitle (identifier: $currentIdentifier)")

        val lastPosition = calculateSavePosition(currentPos, currentDuration, oldState)
        val timeRemaining = if (currentDuration > lastPosition) currentDuration - lastPosition else 0

        playbackStateRepository.upsert(
          PlaybackStateEntity(
            mediaTitle = currentIdentifier,
            lastPosition = lastPosition,
            playbackSpeed = currentSpeed,
            videoZoom = currentZoom,
            sid = currentSid,
            secondarySid = currentSecondarySid,
            subDelay = currentSubDelay,
            subSpeed = currentSubSpeed,
            aid = currentAid,
            audioDelay = currentAudioDelay,
            timeRemaining = timeRemaining,
            externalSubtitles = currentExternalSubs.joinToString("|"),
            hasBeenWatched = run {
              val watchedThreshold = browserPreferences.watchedThreshold.get()
              val durationSeconds = currentDuration.toFloat()
              
              // Check if we are at the end (effectively watched)
              // Using a small buffer (1s) to account for float inaccuracies or near-end stops
              val isFinished = (durationSeconds > 0) && (currentPos.toFloat() >= durationSeconds - 1)

              val progress = if (durationSeconds > 0) currentPos.toFloat() / durationSeconds else 0f
              val isCurrentlyWatched = progress >= (watchedThreshold / 100f)
              
              // Also check lastPosition in case we are saving partway through (though lastPosition might be 0 if finished)
              val oldProgress = if (durationSeconds > 0) lastPosition.toFloat() / durationSeconds else 0f
              val wasWatchedThisSession = oldProgress >= (watchedThreshold / 100f)

              isCurrentlyWatched || isFinished || wasWatchedThisSession || (oldState?.hasBeenWatched == true)
            },
            lastUpdatedAt = System.currentTimeMillis(),
          ),
        )

        networkTrackProfile?.let { profile ->
          networkTrackProfileRepository.upsert(profile)
        }
      }.onFailure { e ->
        Log.e(TAG, "Error saving playback state for $currentIdentifier", e)
      }
    }
  }

  /**
   * Calculates the position to save based on user preferences.
   *
   * If "savePositionOnQuit" is not enabled, returns the previous saved position or 0.
   * If enabled, saves the current playback position unless at end of video.
   *
   * @param pos Current playback position
   * @param duration Current video duration
   * @param oldState Previous playback state if it exists
   * @return Position in seconds to save
   */
  private fun calculateSavePosition(pos: Int, duration: Int, oldState: PlaybackStateEntity?): Int {
    if (!playerPreferences.savePositionOnQuit.get()) {
      return oldState?.lastPosition ?: 0
    }

    return if (pos < duration - 1) pos else 0
  }

  /**
   * Loads and applies saved playback state from the database.
   *
   * @param mediaTitle The title of the media being played
   * @return true if saved state was found and applied, false otherwise
   */
  private suspend fun loadVideoPlaybackState(mediaTitle: String): Boolean {
    if (mediaIdentifier.isBlank()) return false

    return runCatching {
      var state = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)
      if (state == null) {
        val legacyKey = legacyPlaybackIdentifier()
        if (legacyKey != null) {
          val legacyState = playbackStateRepository.getVideoDataByTitle(legacyKey)
          if (legacyState != null) {
            state = legacyState.copy(mediaTitle = mediaIdentifier)
            playbackStateRepository.upsert(state!!)
            playbackStateRepository.deleteByTitle(legacyKey)
            Log.d(TAG, "Migrated legacy playback state key: $legacyKey -> $mediaIdentifier")
          }
        }
      }

      applyPlaybackState(state)
      hydrateDirectoryTrackProfile(state)
      applyDefaultSettings(state)

      state != null
    }.onFailure { e ->
      Log.e(TAG, "Error loading playback state", e)
    }.getOrDefault(false)
  }

  /**
   * Applies saved playback state to MPV.
   *
   * Restores subtitle delay, audio delay, audio and track selections, and playback speed.
   * Also restores saved time position if enabled.
   *
   * @param state The saved playback state entity
   */
  private fun applyPlaybackState(state: PlaybackStateEntity?) {
    if (state == null) return

    val subDelay = state.subDelay / DELAY_DIVISOR
    val audioDelay = state.audioDelay / DELAY_DIVISOR

    // Restore external subtitles first
    if (state.externalSubtitles.isNotBlank()) {
      val externalSubUris = state.externalSubtitles.split("|").filter { it.isNotBlank() }
      Log.d(TAG, "Restoring ${externalSubUris.size} external subtitle(s)")

      for (subUri in externalSubUris) {
        viewModel.addSubtitle(Uri.parse(subUri), select = false, silent = true)
      }
    }

    // Always restore subtitle and audio tracks from saved state
    // User's manual selection has highest priority
    if (state.sid > 0) {
      player.sid = state.sid
      Log.d(TAG, "Restored primary subtitle track: ${state.sid} (user selection)")
    }

    if (state.secondarySid > 0) {
      player.secondarySid = state.secondarySid
      Log.d(TAG, "Restored secondary subtitle track: ${state.secondarySid} (user selection)")
    }

    if (state.aid > 0) {
      player.aid = state.aid
      Log.d(TAG, "Restored audio track: ${state.aid} (user selection)")
    }

    MPVLib.setPropertyDouble("sub-delay", subDelay)
    MPVLib.setPropertyDouble("speed", state.playbackSpeed)
    MPVLib.setPropertyDouble("audio-delay", audioDelay)
    MPVLib.setPropertyDouble("sub-speed", state.subSpeed)

    // Restore video zoom from saved state
    MPVLib.setPropertyDouble("video-zoom", state.videoZoom.toDouble())
    viewModel.setVideoZoom(state.videoZoom)

    if (playerPreferences.savePositionOnQuit.get() && state.lastPosition != 0) {
      // Best-effort immediate seek. The authoritative, race-free apply happens in the
      // MPV_EVENT_PLAYBACK_RESTART handler (pendingResumeSeek is preloaded synchronously
      // in handleFileLoaded), so even if mpv resets the timeline to 0 on start, resume works.
      MPVLib.setPropertyInt("time-pos", state.lastPosition)
    }
  }

  /**
   * Applies default settings when no saved state exists.
   *
   * Sets subtitle speed to user default if not present in saved state.
   *
   * @param state The saved playback state entity (null if no saved state)
   */
  private fun applyDefaultSettings(state: PlaybackStateEntity?) {
    if (state == null) {
      val defaultSubSpeed = subtitlesPreferences.defaultSubSpeed.get().toDouble()
      MPVLib.setPropertyDouble("sub-speed", defaultSubSpeed)
    }
  }

  private fun legacyPlaybackIdentifier(): String? {
    val networkFilePath = intent.getStringExtra("network_file_path") ?: return null
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)
    if (networkConnectionId == -1L) return null
    return "network_${networkConnectionId}_${networkFilePath.hashCode()}"
  }

  private suspend fun hydrateDirectoryTrackProfile(fileState: PlaybackStateEntity?) {
    if (fileState != null) return
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)
    val canonicalPath = NetworkMediaIdUtils.canonicalizeNetworkPath(intent.getStringExtra("network_file_path"))
    if (networkConnectionId == -1L || canonicalPath == null) return

    val profile = networkTrackProfileRepository.get(
      connectionId = networkConnectionId,
      directoryPath = NetworkMediaIdUtils.parentPath(canonicalPath),
    ) ?: run {
      trackSelector.resetManualSelectionMemory()
      return
    }

    trackSelector.hydrateFromProfile(profile)
  }

  /**
   * Saves the currently playing file to recently played history.
   *
   * Handles various URI schemes and infers launch source.
   */
  private suspend fun saveRecentlyPlayed() {
    runCatching {
      val uri = extractUriFromIntent(intent)

      if (uri == null) {
        Log.w(TAG, "Cannot save recently played: URI is null")
        return@runCatching
      }

      if (uri.scheme == null) {
        Log.w(TAG, "Cannot save recently played: URI has null scheme: $uri")
        return@runCatching
      }

      val networkFilePath = intent.getStringExtra("network_file_path")
      val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

      val filePath = if (networkConnectionId != -1L && networkFilePath != null) {
        NetworkMediaIdUtils.canonicalizeNetworkPath(networkFilePath) ?: networkFilePath
      } else {
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }
      }

      val launchSource =
        when {
          intent.getStringExtra("launch_source") != null -> intent.getStringExtra("launch_source")
          intent.action == Intent.ACTION_SEND -> "share"
          else -> "normal"
        }

      // Get parsed video title from MPV, but ignore proxy stream ids for network playback.
      val videoTitle = sanitizedRecentlyPlayedTitle(
        candidate = runCatching { MPVLib.getPropertyString("media-title") }.getOrNull(),
        fallbackName = fileName,
        currentUri = uri,
        networkConnectionId = networkConnectionId,
      )

      // Get duration and file size from MPV
      val duration = runCatching {
        (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      val fileSize = runCatching {
        // Try multiple properties to get file size
        MPVLib.getPropertyDouble("file-size")?.toLong()
          ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = fileName,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = launchSource,
        networkConnectionId = networkConnectionId.takeIf { it != -1L },
      )

      Log.d(TAG, "Saved recently played: $filePath")
      Log.d(TAG, "  - fileName: $fileName")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - source: $launchSource")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played", e)
    }
  }

  // ==================== Intent and Result Management ====================

  /**
   * Sets the result intent with current playback position and duration.
   * Called when activity is finishing to return data to caller.
   */
  private fun setReturnIntent() {
    Log.d(TAG, "Setting return intent")

    val resultIntent =
      Intent(RESULT_INTENT).apply {
        viewModel.pos?.let { putExtra("position", it * MILLISECONDS_TO_SECONDS) }
        viewModel.duration?.let { putExtra("duration", it * MILLISECONDS_TO_SECONDS) }
      }

    setResult(RESULT_OK, resultIntent)
  }

  /**
   * Handles new intents to load a different file without recreating the activity.
   *
   * @param intent The new intent
   */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)

    // Update the intent first so getFileName uses the new intent data
    setIntent(intent)

    // Check if this intent has playlist information
    val hasPlaylistExtras = intent.hasExtra("playlist_id") ||
      intent.hasExtra("playlist")

    // Load playlist from intent extras first (fast path)
    val playlistFromIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableArrayListExtra("playlist", Uri::class.java) ?: emptyList()
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableArrayListExtra("playlist") ?: emptyList()
    }

    // Only update playlist state if we have new playlist information
    // This prevents losing the playlist when coming back from notification/PiP
    if (hasPlaylistExtras || playlistFromIntent.isNotEmpty()) {
      val newPlaylistId = intent.getIntExtra("playlist_id", -1).takeIf { it != -1 }
      playlistId = newPlaylistId
      playlistIndex = intent.getIntExtra("playlist_index", 0)
      playlistWindowOffset = 0
      playlistTotalCount = -1
      playlist = playlistFromIntent
    }

    // If playlist is empty but playlist_id is provided, load from database
    if (playlist.isEmpty() && playlistId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        val pid = playlistId ?: return@launch
        try {
          val totalCount = playlistRepository.getPlaylistItemCount(pid)
          val items = playlistRepository.getPlaylistItemsAsUris(pid)
          withContext(Dispatchers.Main) {
            playlist = items
            playlistTotalCount = totalCount
            Log.d(TAG, "onNewIntent: Loaded ${items.size} items from playlist $pid")
          }
        } catch (e: Exception) {
          Log.e(TAG, "onNewIntent: Failed to load playlist from database", e)
        }
      }
    }

    val dataUri = intent.data ?: intent.getStringExtra("uri")?.toUri()
    val isMpvnas = dataUri?.scheme == "mpvnas"

    if (isMpvnas) {
      intent.data = dataUri
      lifecycleScope.launch(Dispatchers.Main) {
        val traceId = "newIntent_${System.currentTimeMillis()}_${dataUri?.hashCode()}"
        val launchStartedAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "SMB trace[$traceId] onNewIntent mpvnas start uri=$dataUri")
        val resolvedUri = withContext(Dispatchers.IO) {
          dataUri?.let { resolveMpvnasUri(it) }
        }
        if (resolvedUri != null) {
          intent.data = resolvedUri
          
          fileName = getFileName(intent)
          if (fileName.isBlank()) {
            fileName = intent.data?.lastPathSegment ?: "Unknown Video"
          }
          mediaIdentifier = getMediaIdentifier(intent, fileName)

          if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
            val networkFilePath = intent.getStringExtra("network_file_path")
            val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)
            if (networkFilePath != null && networkConnectionId != -1L) {
              generatePlaylistFromNetworkFolder(networkConnectionId, networkFilePath)
            }
          }

          setHttpHeadersFromExtras(intent.extras)

          getPlayableUri(intent)?.let { uri ->
            Log.d(
              TAG,
              "SMB trace[$traceId] onNewIntent resolved playableUri=$uri totalBeforeLoad=${SystemClock.elapsedRealtime() - launchStartedAt}ms",
            )
            lifecycleScope.launch(Dispatchers.Default) {
              Log.d(TAG, "MPV dispatch[loadfile:onNewIntent:mpvnas] uri=$uri")
              MPVLib.command("loadfile", uri)
            }
          }
        } else {
          Log.e(TAG, "Failed to resolve mpvnas URI in onNewIntent: ${intent.data}")
        }
      }
    } else {
      // Auto-generate playlist from folder if playlist mode is enabled and no playlist_id
      if (playlist.isEmpty() && playlistId == null && playerPreferences.playlistMode.get()) {
        val path = parsePathFromIntent(intent)
        if (path != null) {
          generatePlaylistFromFolder(path)
        }
      }

      // Extract the new fileName before loading the file
      fileName = getFileName(intent)
      if (fileName.isBlank()) {
        fileName = intent.data?.lastPathSegment ?: "Unknown Video"
      }
      mediaIdentifier = getMediaIdentifier(intent, fileName)

      // Set HTTP headers (including referer) BEFORE loading the new file
      setHttpHeadersFromExtras(intent.extras)

      // Load the new file
      getPlayableUri(intent)?.let { uri ->
        // Avoid blocking UI thread while mpv opens network streams (e.g., HLS).
        lifecycleScope.launch(Dispatchers.Default) {
          Log.d(TAG, "MPV dispatch[loadfile:onNewIntent:regular] uri=$uri")
          MPVLib.command("loadfile", uri)
        }
      }
    }
  }

  // ==================== Picture-in-Picture Management ====================

  /**
   * Called when Picture-in-Picture mode changes.
   * Updates UI visibility and window configuration.
   *
   * @param isInPictureInPictureMode true if entering PiP, false if exiting
   * @param newConfig The new configuration
   */
  @RequiresApi(Build.VERSION_CODES.P)
  override fun onPictureInPictureModeChanged(
    isInPictureInPictureMode: Boolean,
    newConfig: Configuration,
  ) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

    pipHelper.onPictureInPictureModeChanged(isInPictureInPictureMode)

    binding.controls.alpha = if (isInPictureInPictureMode) 0f else 1f

    runCatching {
      if (isInPictureInPictureMode) {
        enterPipUIMode()
      } else {
        exitPipUIMode()
      }
    }.onFailure { e ->
      Log.e(TAG, "Error handling PiP mode change", e)
    }
  }

  /**
   * Configures window for Picture-in-Picture mode.
   * Shows system UI and navigation bars.
   */
  private fun enterPipUIMode() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    WindowCompat.setDecorFitsSystemWindows(window, true)
    try {
      windowInsetsController.apply {
        show(WindowInsetsCompat.Type.systemBars())
        show(WindowInsetsCompat.Type.navigationBars())
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to show system bars for PiP mode", e)
    }
  }

  /**
   * Restores window configuration when exiting Picture-in-Picture mode.
   * Hides system UI for immersive playback.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  private fun exitPipUIMode() {
    setupWindowFlags()
    setupSystemUI()
  }

  /**
   * Enters Picture-in-Picture mode and hides all overlay controls.
   */
  fun enterPipModeHidingOverlay() {
    runCatching {
      enterPipUIMode()
    }.onFailure { e ->
      Log.e(TAG, "Error entering PiP mode with hidden overlay", e)
    }

    binding.controls.alpha = 0f

    pipHelper.enterPipMode()
  }

  // ==================== Orientation Management ====================

  /**
   * Sets the screen orientation based on user preferences.
   *
   * IMPORTANT: Preferences are the single source of truth for orientation.
   * This method applies the preference value when videos load.
   * The rotation button temporarily overrides this without changing preferences.
   *
   * For "Video" orientation mode, this will wait for video-params/aspect to update
   * to the correct orientation, starting with landscape as fallback.
   */
  private fun setOrientation() {
    val orientationPref = playerPreferences.orientation.get()

    requestedOrientation =
      when (orientationPref) {
        PlayerOrientation.Free -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        PlayerOrientation.Video -> {
          // For video orientation, check if aspect is available
          val aspect = runCatching { player.getVideoOutAspect() }.getOrNull()
          Log.d(TAG, "setOrientation - Video mode: aspect=$aspect")
          if (aspect == null || aspect <= 0.0) {
            // Aspect not available yet - wait for video-params/aspect update
            Log.d(TAG, "setOrientation - Aspect not available, defaulting to landscape")
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
          } else {
            // Aspect available - set correct orientation now
            val orientation = if (aspect > 1.0) {
              Log.d(TAG, "setOrientation - Aspect $aspect > 1.0, setting landscape")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
              Log.d(TAG, "setOrientation - Aspect $aspect <= 1.0, setting portrait")
              ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            orientation
          }
        }
        PlayerOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        PlayerOrientation.ReversePortrait -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        PlayerOrientation.SensorPortrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        PlayerOrientation.ReverseLandscape -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        PlayerOrientation.SensorLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      }
  }

  // ==================== Key Event Handling ====================

  /**
   * Handles hardware key down events for player control.
   * Supports D-pad navigation, media keys, and volume controls.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
  override fun onKeyDown(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    val isTrackSheetOpen =
      viewModel.sheetShown.value == Sheets.SubtitleTracks ||
        viewModel.sheetShown.value == Sheets.AudioTracks
    val isNoSheetOpen = viewModel.sheetShown.value == Sheets.None

    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_UP -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }
        // Playing: speed +0.1x; Paused: default navigation
        if (viewModel.paused != true) {
          val currentSpeed = MPVLib.getPropertyDouble("speed") ?: 1.0
          val newSpeed = (currentSpeed + 0.1).coerceAtMost(4.0)
          MPVLib.setPropertyDouble("speed", newSpeed)
          android.widget.Toast.makeText(this, String.format("%.1fx", newSpeed), android.widget.Toast.LENGTH_SHORT).show()
          return true
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_LEFT,
        -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }

        if (isNoSheetOpen) {
          // Playing: LEFT/RIGHT = seek, DOWN = speed -0.1x
          // Paused: all default navigation
          if (viewModel.paused != true) {
            when (keyCode) {
              KeyEvent.KEYCODE_DPAD_RIGHT -> {
                viewModel.handleRightDoubleTap()
                return true
              }

              KeyEvent.KEYCODE_DPAD_LEFT -> {
                viewModel.handleLeftDoubleTap()
                return true
              }

              KeyEvent.KEYCODE_DPAD_DOWN -> {
                val currentSpeed = MPVLib.getPropertyDouble("speed") ?: 1.0
                val newSpeed = (currentSpeed - 0.1).coerceAtLeast(0.1)
                MPVLib.setPropertyDouble("speed", newSpeed)
                android.widget.Toast.makeText(this, String.format("%.1fx", newSpeed), android.widget.Toast.LENGTH_SHORT).show()
                return true
              }
            }
          }
        }
        return super.onKeyDown(keyCode, event)
      }

      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
        if (isTrackSheetOpen) {
          return super.onKeyDown(keyCode, event)
        }
        // OK: toggle play/pause + show controls
        viewModel.pauseUnpause()
        viewModel.showControls()
        return true
      }

      KeyEvent.KEYCODE_SPACE -> {
        viewModel.pauseUnpause()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_UP -> {
        viewModel.changeVolumeBy(1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_VOLUME_DOWN -> {
        viewModel.changeVolumeBy(-1)
        viewModel.displayVolumeSlider()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_STOP -> {
        finishAndRemoveTask()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_REWIND -> {
        viewModel.handleLeftDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
        viewModel.handleRightDoubleTap()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
        // Route through the app so UserPauseState is set; otherwise mpv toggles pause
        // directly (KeyMapping binds this key to PLAYPAUSE) and the stall watchdog
        // mistakes a manual pause for a stall, then auto-resumes the video.
        viewModel.pauseUnpause()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PLAY -> {
        viewModel.unpause()
        return true
      }

      KeyEvent.KEYCODE_MEDIA_PAUSE -> {
        viewModel.pause()
        return true
      }

      KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0,
      KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> {
        // These digits are bound in input.conf to `cycle pause`, which toggles mpv pause
        // directly and bypasses UserPauseState; route them through the app instead so a
        // manual pause is never mis-read as a stall by the watchdog.
        viewModel.pauseUnpause()
        return true
      }

      else -> {
        event?.let { player.onKey(it) }
        return super.onKeyDown(keyCode, event)
      }
    }
  }

  /**
   * Handles hardware key up events for player control.
   *
   * @param keyCode The key code
   * @param event The key event
   * @return true if event was handled, false otherwise
   */
  override fun onKeyUp(
    keyCode: Int,
    event: KeyEvent?,
  ): Boolean {
    // Consume media transport keys here too: mpv's KeyMapping would otherwise toggle
    // pause on key-up and defeat the UserPauseState bookkeeping done in onKeyDown.
    if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
    ) {
      return true
    }
    event?.let {
      if (player.onKey(it)) return true
    }
    return super.onKeyUp(keyCode, event)
  }

  // ==================== System UI Management ====================

  /**
   * Restores system UI to normal state (shows status and navigation bars).
   * Called when finishing the activity to return to normal Android UI.
   */

  // ==================== MediaSession ====================

  /**
   * Initializes MediaSession for integration with system media controls.
   * Supports Android Auto, Wear OS, Bluetooth controls, and notification controls.
   */
  private fun setupMediaSession() {
    runCatching {
      mediaSession =
        MediaSession(this, TAG).apply {
          setCallback(
            object : MediaSession.Callback() {
              override fun onPlay() {
                viewModel.unpause()
                updateMediaSessionPlaybackState(isPlaying = true)
              }

              override fun onPause() {
                viewModel.pause()
                updateMediaSessionPlaybackState(isPlaying = false)
              }

              override fun onSeekTo(pos: Long) {
                viewModel.seekTo((pos / 1000).toInt())
                updateMediaSessionPlaybackState(isPlaying = viewModel.paused == false)
              }
            },
          )
          isActive = true
        }
      playbackStateBuilder =
        PlaybackState
          .Builder()
          .setActions(
            PlaybackState.ACTION_PLAY or
              PlaybackState.ACTION_PAUSE or
              PlaybackState.ACTION_PLAY_PAUSE or
              PlaybackState.ACTION_SEEK_TO,
          )
      mediaSessionInitialized = true
    }.onFailure { e ->
      Log.e(TAG, "Failed to initialize MediaSession", e)
      mediaSessionInitialized = false
    }
  }

  /**
   * Updates MediaSession playback state (playing/paused).
   *
   * @param isPlaying true if currently playing, false if paused
   */
  private fun updateMediaSessionPlaybackState(isPlaying: Boolean) {
    if (!mediaSessionInitialized) return
    runCatching {
      val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
      val positionMs = (viewModel.pos ?: 0) * 1000L
      mediaSession.setPlaybackState(
        playbackStateBuilder
          .setState(state, positionMs, if (isPlaying) 1.0f else 0f)
          .build(),
      )
    }.onFailure { e -> Log.e(TAG, "Error updating playback state", e) }
  }

  /**
   * Updates MediaSession metadata (title, duration, etc.).
   *
   * @param title The media title
   * @param durationMs The media duration in milliseconds
   */
  private fun updateMediaSessionMetadata(
    title: String,
    durationMs: Long,
  ) {
    if (!mediaSessionInitialized) return
    runCatching {
      val metadata =
        MediaMetadata
          .Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, title)
          .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
          .build()
      mediaSession.setMetadata(metadata)
    }.onFailure { e -> Log.e(TAG, "Error updating metadata", e) }
  }

  /**
   * Releases MediaSession resources.
   * Called during activity cleanup.
   */
  private fun releaseMediaSession() {
    if (!mediaSessionInitialized) return
    runCatching {
      mediaSession.isActive = false
      mediaSession.release()
    }.onFailure { e -> Log.e(TAG, "Error releasing MediaSession", e) }
    mediaSessionInitialized = false
  }

  // ==================== Background Playback Service ====================

  /**
   * Service connection for binding to background playback service.
   */
  private val serviceConnection =
    object : ServiceConnection {
      override fun onServiceConnected(
        name: ComponentName?,
        service: IBinder?,
      ) {
        val binder = service as? MediaPlaybackService.MediaPlaybackBinder ?: return
        mediaPlaybackService = binder.getService()
        serviceBound = true
        Log.d(TAG, "Service connected")
      }

      override fun onServiceDisconnected(name: ComponentName?) {
        Log.d(TAG, "Service disconnected")
        mediaPlaybackService = null
        serviceBound = false
      }
    }

  /**
   * Starts the background playback service and binds to it.
   *
   * This should only be called if a video is loaded and playback is initialized.
   * Responsible for starting and binding to the MediaPlaybackService, which
   * handles background playback.
   */
  private fun startBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot start background playback: video not ready")
      return
    }

    // Prevent starting service multiple times
    if (serviceBound) {
      Log.d(TAG, "Service already bound, skipping start")
      return
    }

    Log.d(TAG, "Starting background playback for: $fileName")
    
    // Ensure notification channel exists
    MediaPlaybackService.createNotificationChannel(this)
    
    // Get media info before starting service
    val artist = runCatching { MPVLib.getPropertyString("metadata/artist") }.getOrNull() ?: ""
    val thumbnail = runCatching { MPVLib.grabThumbnail(1080) }.getOrNull()
    
    // Pass media info via intent extras
    val intent = Intent(this, MediaPlaybackService::class.java).apply {
      putExtra("media_title", fileName)
      putExtra("media_artist", artist)
    }
    
    // Store thumbnail in companion object for service to access
    MediaPlaybackService.thumbnail = thumbnail
    
    try {
      startForegroundService(intent)
      bindService(intent, serviceConnection, BIND_AUTO_CREATE)
      Log.d(TAG, "Service start and bind initiated")
    } catch (e: Exception) {
      Log.e(TAG, "Error starting/binding service", e)
    }
  }

  /**
   * Stops the background playback service and unbinds from it.
   *
   * Called when the activity is destroyed to remove the notification.
   */
  private fun endBackgroundPlayback() {
    Log.d(TAG, "Ending background playback service")
    
    if (serviceBound) {
      try {
        unbindService(serviceConnection)
        Log.d(TAG, "Service unbound successfully")
      } catch (e: Exception) {
        Log.e(TAG, "Error unbinding service", e)
      }
      serviceBound = false
    }
    
    // Stop the service which will trigger its onDestroy and cleanup
    try {
      stopService(Intent(this, MediaPlaybackService::class.java))
      Log.d(TAG, "Stop service command sent")
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping service", e)
    }
    
    mediaPlaybackService = null
  }

  /**
   * Manually triggers background playback when the user clicks the background playback button.
   * This works independently of the automaticBackgroundPlayback preference.
   */
  @RequiresApi(Build.VERSION_CODES.P)
  fun triggerBackgroundPlayback() {
    if (fileName.isBlank() || !isReady) {
      Log.w(TAG, "Cannot trigger background playback: video not ready")
      return
    }

    Log.d(TAG, "User triggered background playback")
    
    // Set flag to enable background playback (same logic as automatic)
    isManualBackgroundPlayback = true
    
    // Restore system UI before going to background
    restoreSystemUI()
    
    // Move to background by going to home screen (same behavior as automatic)
    val intent = Intent(Intent.ACTION_MAIN).apply {
      addCategory(Intent.CATEGORY_HOME)
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    startActivity(intent)
  }

  // ==================== PlayerHost ====================
  override val context: Context
    get() = this
  override val windowInsetsController: WindowInsetsControllerCompat
    get() = WindowCompat.getInsetsController(window, window.decorView)
  override val hostWindow: android.view.Window
    get() = window
  override val hostWindowManager: WindowManager
    get() = windowManager
  override val hostContentResolver: android.content.ContentResolver
    get() = contentResolver
  override val audioManager: AudioManager
    get() = getSystemService(AUDIO_SERVICE) as AudioManager
  override var hostRequestedOrientation: Int
    get() = requestedOrientation
    set(value) {
      requestedOrientation = value
    }

  // ==================== Playlist Management ====================

  /**
   * Check if there's a next video in the playlist
   */
  fun hasNext(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "next" (loops back to beginning)
    if (viewModel.shouldRepeatPlaylist()) return true

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition < shuffledIndices.size - 1
    } else {
      playlistIndex < effectiveSize - 1
    }
  }

  /**
   * Check if there's a previous video in the playlist
   */
  fun hasPrevious(): Boolean {
    if (playlist.isEmpty()) return false

    // With repeat ALL, there's always a "previous" (loops back to end)
    if (viewModel.shouldRepeatPlaylist()) return true

    return if (viewModel.shuffleEnabled.value) {
      shuffledPosition > 0
    } else {
      playlistIndex > 0
    }
  }

  /**
   * Generate shuffled indices for the playlist
   */
  private fun generateShuffledIndices() {
    if (playlist.isEmpty()) return

    // Create a list of all indices except the current one
    val indices = playlist.indices.filter { it != playlistIndex }.toMutableList()
    indices.shuffle()

    // Put current index at the beginning
    shuffledIndices = listOf(playlistIndex) + indices
    shuffledPosition = 0
  }

  /**
   * Called when shuffle is toggled on/off
   */
  fun onShuffleToggled(enabled: Boolean) {
    if (enabled && playlist.isNotEmpty()) {
      generateShuffledIndices()
    } else {
      shuffledIndices = emptyList()
      shuffledPosition = 0
    }
  }

  /**
   * Play the next video in the playlist
   */
  fun playNext() {
    if (playlist.isEmpty()) return

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to next position
      if (shuffledPosition < shuffledIndices.size - 1) {
        shuffledPosition++
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of shuffled playlist with repeat ALL: regenerate and restart
        generateShuffledIndices()
        shuffledPosition = 0
        playlistIndex = shuffledIndices[0]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex < effectiveSize - 1) {
        playlistIndex++
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At end of playlist with repeat ALL: restart from beginning
        playlistIndex = 0
        loadPlaylistItem(0)
      }
    }
  }

  /**
   * Play the previous video in the playlist
   */
  fun playPrevious() {
    if (playlist.isEmpty()) return

    // Use total count if we're doing windowed loading, otherwise use playlist size
    val effectiveSize = if (playlistTotalCount > 0) playlistTotalCount else playlist.size

    if (viewModel.shuffleEnabled.value) {
      // Initialize shuffle if not done yet
      if (shuffledIndices.isEmpty()) {
        generateShuffledIndices()
      }

      // Move to previous position
      if (shuffledPosition > 0) {
        shuffledPosition--
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of shuffled playlist with repeat ALL: go to end
        shuffledPosition = shuffledIndices.size - 1
        playlistIndex = shuffledIndices[shuffledPosition]
        loadPlaylistItem(playlistIndex)
      }
    } else {
      // Normal sequential playback
      if (playlistIndex > 0) {
        playlistIndex--
        loadPlaylistItem(playlistIndex)
      } else if (viewModel.shouldRepeatPlaylist()) {
        // At beginning of playlist with repeat ALL: go to last item
        playlistIndex = effectiveSize - 1
        loadPlaylistItem(playlistIndex)
      }
    }
  }

  /**
   * Load a playlist item by index
   */
  private fun loadPlaylistItem(index: Int) {
    // All items are loaded - just validate index and load directly
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }
    loadPlaylistItemInternal(index)
  }

  /**
   * Internal method to load a playlist item
   */
  private fun loadPlaylistItemInternal(index: Int) {
    if (index < 0 || index >= playlist.size) {
      Log.e(TAG, "Invalid playlist index: $index (playlist size: ${playlist.size})")
      return
    }

    // Save current video's playback state before switching
    if (fileName.isNotBlank()) {
      saveVideoPlaybackState(fileName)
    }

    val uri = playlist[index]
    val playableUri = uri.openContentFd(this) ?: uri.toString()

    // Update playlist index
    playlistIndex = index

    // Extract and set the new file name
    fileName = getFileNameFromUri(uri)
    playlistNetworkFilePaths[uri]?.let { networkPath ->
      intent.putExtra("network_file_path", networkPath)
      intent.putExtra("network_connection_id", intent.getLongExtra("network_connection_id", -1L))
      intent.putExtra("title", fileName)
      intent.putExtra("filename", fileName)
    }
    // Generate new media identifier for playback state
    mediaIdentifier = getMediaIdentifierFromUri(uri, fileName)

    // Set HTTP headers (including referer) for network streams
    setHttpHeadersForUri(uri)

    // Update playlist play history if this is a custom playlist
    playlistId?.let { id ->
      lifecycleScope.launch(Dispatchers.IO) {
        val filePath = when (uri.scheme) {
          "file" -> uri.path ?: uri.toString()
          "content" -> {
            contentResolver.query(
              uri,
              arrayOf(MediaStore.MediaColumns.DATA),
              null,
              null,
              null,
            )?.use { cursor ->
              if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (columnIndex != -1) cursor.getString(columnIndex) else null
              } else null
            } ?: uri.toString()
          }

          else -> uri.toString()
        }

        runCatching {
          playlistRepository.updatePlayHistory(id, filePath)
          Log.d(TAG, "Updated playlist history for: $filePath in playlist $id")
        }.onFailure { e ->
          Log.e(TAG, "Error updating playlist history", e)
        }
      }
    }

    // Auto-advance: make sure the next file actually starts playing. Network streams
    // (SMB/WebDAV) can otherwise be left paused after loadfile completes.
    shouldResumeAfterLoad = true

    // Load the new video
    // Avoid blocking UI thread while mpv opens network streams (e.g., HLS).
    lifecycleScope.launch(Dispatchers.Default) {
      Log.d(TAG, "MPV dispatch[loadfile:playlistItem] uri=$playableUri")
      MPVLib.command("loadfile", playableUri)
    }

    // Update media title (this will trigger UI update)
    // Don't force media-title for m3u/m3u8 streams - let MPV provide it
    val isM3U = uri.toString().lowercase().contains(".m3u8") || uri.toString().lowercase().contains(".m3u")
    if (!isM3U) {
      MPVLib.setPropertyString("force-media-title", fileName)
      viewModel.setMediaTitle(fileName)
    }

    // Update media session metadata
    lifecycleScope.launch {
      kotlinx.coroutines.delay(100) // Wait for MPV to load the file
      val durationMs = (MPVLib.getPropertyDouble("duration")?.times(1000))?.toLong() ?: 0L
      updateMediaSessionMetadata(
        title = fileName,
        durationMs = durationMs,
      )
      // Refresh playlist items to update the currently playing indicator
      viewModel.refreshPlaylistItems()
    }
  }

  /**
   * Get file name from URI (used for playlist items)
   */
  private fun getFileNameFromUri(uri: Uri): String {
    playlistTitles[uri]?.let { return it }
    getDisplayNameFromUri(uri)?.let { return it }
    return extractFileNameFromUri(uri)
  }

  /**
   * Get the current video title for controls display.
   * Used as a fallback when MPV hasn't set the media-title property yet.
   * For m3u/m3u8 streams, returns the raw media-title from MPV instead of parsing.
   */
  fun getTitleForControls(): String {
    // For m3u/m3u8 streams, use MPV's raw media-title directly
    if (isCurrentStreamM3U()) {
      val rawTitle = MPVLib.getPropertyString("media-title")
      if (!rawTitle.isNullOrBlank()) {
        return rawTitle
      }
    }
    return fileName
  }

  /**
   * Check if the currently playing media is an m3u or m3u8 stream.
   * Checks both the intent URI and the current playlist item if playing from a playlist.
   */
  private fun isCurrentStreamM3U(): Boolean {
    // First check the intent URI
    val uri = extractUriFromIntent(intent)
    if (uri != null && isUriM3U(uri)) {
      return true
    }

    // Also check the current playlist item if playing from a playlist
    if (playlist.isNotEmpty() && playlistIndex >= 0 && playlistIndex < playlist.size) {
      return isUriM3U(playlist[playlistIndex])
    }

    return false
  }

  /**
   * Check if a specific URI is an m3u or m3u8 file/stream.
   */
  private fun isUriM3U(uri: Uri): Boolean {
    val lowerUrl = uri.toString().lowercase()
    return lowerUrl.contains(".m3u8") || lowerUrl.contains(".m3u") ||
      lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".m3u")
  }

  /**
   * Save recently played for a specific URI
   */
  private suspend fun saveRecentlyPlayedForUri(
    uri: Uri,
    name: String,
  ) {
    runCatching {
      val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)
      val networkFilePath = playlistNetworkFilePaths[uri] ?: intent.getStringExtra("network_file_path")

      val filePath = if (networkConnectionId != -1L && networkFilePath != null) {
        NetworkMediaIdUtils.canonicalizeNetworkPath(networkFilePath) ?: networkFilePath
      } else {
        playlistNetworkFilePaths[uri] ?:
        when (uri.scheme) {
          "file" -> {
            uri.path ?: uri.toString()
          }

          "content" -> {
            contentResolver
              .query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
              )?.use { cursor ->
                if (cursor.moveToFirst()) {
                  val columnIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                  if (columnIndex != -1) cursor.getString(columnIndex) else null
                } else {
                  null
                }
              } ?: uri.toString()
          }

          else -> {
            uri.toString()
          }
        }
      }

      // Get parsed video title from MPV, but ignore proxy stream ids for network playback.
      val videoTitle = sanitizedRecentlyPlayedTitle(
        candidate = runCatching { MPVLib.getPropertyString("media-title") }.getOrNull(),
        fallbackName = name,
        currentUri = uri,
        networkConnectionId = networkConnectionId,
      )

      // Get duration and file size from MPV
      val duration = runCatching {
        (MPVLib.getPropertyDouble("duration") ?: 0.0).times(1000).toLong()
      }.getOrDefault(0L)

      val fileSize = runCatching {
        // Try multiple properties to get file size
        MPVLib.getPropertyDouble("file-size")?.toLong()
          ?: MPVLib.getPropertyDouble("stream-end")?.toLong()
          ?: 0L
      }.getOrDefault(0L)

      // Get video resolution from MPV
      val width = runCatching {
        MPVLib.getPropertyInt("width") ?: MPVLib.getPropertyInt("video-params/w") ?: 0
      }.getOrDefault(0)

      val height = runCatching {
        MPVLib.getPropertyInt("height") ?: MPVLib.getPropertyInt("video-params/h") ?: 0
      }.getOrDefault(0)

      RecentlyPlayedOps.addRecentlyPlayed(
        filePath = filePath,
        fileName = name,
        videoTitle = videoTitle,
        duration = duration,
        fileSize = fileSize,
        width = width,
        height = height,
        launchSource = "playlist",
        networkConnectionId = networkConnectionId.takeIf { it != -1L },
        playlistId = playlistId,
      )

      Log.d(TAG, "Saved recently played (playlist): $filePath")
      Log.d(TAG, "  - fileName: $name")
      Log.d(TAG, "  - videoTitle: $videoTitle")
      Log.d(TAG, "  - duration: ${duration}ms")
      Log.d(TAG, "  - size: ${fileSize}B")
      Log.d(TAG, "  - resolution: ${width}x${height}")
      Log.d(TAG, "  - playlistId: $playlistId")
    }.onFailure { e ->
      Log.e(TAG, "Error saving recently played for playlist item", e)
    }
  }

  private fun sanitizedRecentlyPlayedTitle(
    candidate: String?,
    fallbackName: String,
    currentUri: Uri,
    networkConnectionId: Long,
  ): String? {
    val title = candidate?.trim().orEmpty()
    if (title.isBlank() || title == fallbackName) return null

    val looksLikeProxyStreamId = Regex("""^\d+_\d{10,}$""").matches(title) ||
      Regex("""^_?\d{10,}$""").matches(title)
    val isProxyUri = currentUri.host?.lowercase() in setOf("127.0.0.1", "localhost", "0.0.0.0")

    return if ((networkConnectionId != -1L || isProxyUri) && looksLikeProxyStreamId) {
      null
    } else {
      title
    }
  }

  /**
   * Generate a unique identifier for this media for playback state/history.
   *
   * For local/offline files, uses fileName (display name or path).
   * For network streams via proxy (SMB/WebDAV/FTP), uses the stable network file path from intent extras.
   * For other network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifier(intent: Intent, fileName: String): String {
    // Check if this is a network file played via proxy (SMB/WebDAV/FTP)
    // Use the stable network file path instead of the temporary proxy URL
    val networkFilePath = intent.getStringExtra("network_file_path")
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)

    if (networkFilePath != null && networkConnectionId != -1L) {
      val canonicalPath = NetworkMediaIdUtils.canonicalizeNetworkPath(networkFilePath)
      if (canonicalPath != null) {
        Log.d(
          TAG,
          "Using canonical network identifier: $canonicalPath (connection: $networkConnectionId, path: $networkFilePath)",
        )
        return NetworkMediaIdUtils.buildPlaybackKey(canonicalPath)
      }
      Log.d(
        TAG,
        "Falling back to raw network identifier: $networkFilePath (connection: $networkConnectionId)",
      )
      return networkFilePath
    }

    val uri = extractUriFromIntent(intent)
    return if (uri != null && (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms")) {
      // For remote protocols: hash the URI so position is per-episode or per-stream.
      "${fileName}_${uri.toString().hashCode()}"
    } else {
      // For local/file uris and unknown: just use fileName.
      fileName
    }
  }

  /**
   * Generate a unique identifier for this media from a URI and name.
   *
   * For local/offline files, uses fileName (display name or path).
   * For network URIs (http/https/rtmp/etc.), uses a hash of the URI string to distinguish different streams.
   */
  private fun getMediaIdentifierFromUri(uri: Uri, fileName: String): String {
    val networkConnectionId = intent.getLongExtra("network_connection_id", -1L)
    val networkFilePath = playlistNetworkFilePaths[uri]
    if (networkFilePath != null && networkConnectionId != -1L) {
      val canonicalPath = NetworkMediaIdUtils.canonicalizeNetworkPath(networkFilePath)
      if (canonicalPath != null) {
        Log.d(TAG, "Using canonical network playlist item identifier: $canonicalPath")
        return NetworkMediaIdUtils.buildPlaybackKey(canonicalPath)
      }
      return networkFilePath
    }
    return if (uri.scheme?.startsWith("http") == true || uri.scheme == "rtmp" || uri.scheme == "ftp" || uri.scheme == "rtsp" || uri.scheme == "mms") {
      "${fileName}_${uri.toString().hashCode()}"
    } else {
      fileName
    }
  }

  /**
   * A stable key identifying the directory / playlist context of the currently playing
   * media. Items in the same folder (local) or same network directory share a key, while
   * an unrelated standalone file gets a different key. Used to scope session track memory.
   */
  private fun currentTrackScopeKey(): String {
    val connId = intent.getLongExtra("network_connection_id", -1L)
    val netPath = currentCanonicalNetworkPath()
    if (connId != -1L && netPath != null) {
      return "net:$connId:${NetworkMediaIdUtils.parentPath(netPath)}"
    }
    val localPath = parsePathFromIntent(intent)
    val parent = localPath?.substringBeforeLast('/', "")?.takeIf { it.isNotBlank() }
    return "local:${parent ?: mediaIdentifier}"
  }

  private fun currentCanonicalNetworkPath(): String? {
    val currentUri = playlist.getOrNull(playlistIndex)
    val playlistPath = currentUri?.let { playlistNetworkFilePaths[it] }
    return NetworkMediaIdUtils.canonicalizeNetworkPath(
      playlistPath ?: intent.getStringExtra("network_file_path"),
    )
  }

  private inline fun <T> traceStep(
    traceId: String,
    step: String,
    block: () -> T,
  ): T {
    val start = SystemClock.elapsedRealtime()
    return try {
      block()
    } finally {
      val elapsed = SystemClock.elapsedRealtime() - start
      Log.d(TAG, "SMB trace[$traceId] $step took ${elapsed}ms")
    }
  }

  private fun generatePlaylistFromFolder(currentPath: String) {
    lifecycleScope.launch(Dispatchers.IO) {
      runCatching {
        val currentFile = File(currentPath)
        if (!currentFile.exists()) return@runCatching

        val parentFolder = currentFile.parentFile ?: return@runCatching

        val videoExtensions = FileTypeUtils.VIDEO_EXTENSIONS

        val files = parentFolder.listFiles { file ->
          file.isFile &&
            FileTypeUtils.isVideoFile(file) &&
            !FileFilterUtils.shouldSkipFile(file)
        } ?: return@runCatching

        val launchSource = intent.getStringExtra("launch_source") ?: ""
        val siblingFiles = if (launchSource == "video_list" || launchSource == "recently_played_button" || launchSource == "first_video_button") {
          val videoSortType = browserPreferences.videoSortType.get()
          val videoSortOrder = browserPreferences.videoSortOrder.get()
          val bucketId = parentFolder.absolutePath.replace("\\", "/")
          val videosInFolder =
            app.marlboroadvance.mpvex.repository.MediaFileRepository.getVideosForBuckets(
              context,
              setOf(bucketId)
            )
          val sortedVideos = app.marlboroadvance.mpvex.utils.sort.SortUtils.sortVideos(videosInFolder, videoSortType, videoSortOrder)
          sortedVideos.mapNotNull { video -> files.find { it.absolutePath == video.path } }
        } else {
          files.sortedWith { f1, f2 -> app.marlboroadvance.mpvex.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT.compare(f1.name, f2.name) }
        }

        if (siblingFiles.size <= 1) return@runCatching

        val newPlaylist = siblingFiles.map { it.toUri() }

        val newIndex = siblingFiles.indexOfFirst { it.absolutePath == currentFile.absolutePath }

        if (newIndex != -1) {
          withContext(Dispatchers.Main) {
            playlist = newPlaylist
            playlistIndex = newIndex
            Log.d(TAG, "Auto-playlist generated: ${playlist.size} videos")
            // Re-initialize shuffle now that playlist is available
            if (viewModel.shuffleEnabled.value) {
              onShuffleToggled(true)
            }
          }
        }
      }.onFailure { e ->
        Log.e(TAG, "Failed to auto-generate playlist", e)
      }
    }
  }

  private suspend fun resolveMpvnasUri(uri: Uri): Uri? {
    val ref = NetworkMediaIdUtils.parseMpvnasUri(uri) ?: return null
    val connectionId = ref.connectionId
    val networkFilePath = ref.canonicalPath
    val traceId = "${connectionId}_${networkFilePath.hashCode()}"
    val startedAt = SystemClock.elapsedRealtime()

    try {
      Log.d(TAG, "SMB trace[$traceId] resolveMpvnasUri start path=$networkFilePath")
      val connection = traceStep(traceId, "getConnectionById") {
        networkRepository.getConnectionById(connectionId)
      } ?: run {
        Log.w(TAG, "SMB trace[$traceId] connection not found for id=$connectionId")
        return null
      }
      val file = runCatching {
        val parentPath = NetworkMediaIdUtils.parentPath(networkFilePath)
        val filesResult = traceStep(traceId, "listFiles parent=$parentPath") {
          networkRepository.listFiles(connection, parentPath)
        }
        val files = filesResult.getOrNull()
        Log.d(TAG, "SMB trace[$traceId] parent listing returned ${files?.size ?: -1} entries")
        traceStep(traceId, "matchTargetFile") {
          files?.find { candidate ->
            NetworkMediaIdUtils.canonicalizeNetworkPath(candidate.path) == networkFilePath
          }
        }
      }.getOrElse { error ->
        Log.w(TAG, "resolveMpvnasUri: failed to enumerate parent directory for $networkFilePath", error)
        null
      }

      val fileName = ref.displayName ?: file?.name ?: NetworkMediaIdUtils.fileNameFromPath(networkFilePath)
      val fileSize = file?.size ?: -1L
      val mimeType = file?.mimeType ?: guessMimeTypeFromPath(networkFilePath)

      val useProxy = connection.protocol in setOf(
        app.marlboroadvance.mpvex.domain.network.NetworkProtocol.SMB,
        app.marlboroadvance.mpvex.domain.network.NetworkProtocol.FTP,
        app.marlboroadvance.mpvex.domain.network.NetworkProtocol.WEBDAV
      )

      val resolvedUri = if (useProxy) {
        traceStep(traceId, "registerStream") {
          val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance()
          val streamId = "${connectionId}_${System.currentTimeMillis()}_${networkFilePath.hashCode()}"
          val proxyUrl = proxy.registerStream(
            streamId = streamId,
            connection = connection,
            filePath = networkFilePath,
            fileSize = fileSize,
            mimeType = mimeType,
            title = fileName
          )
          registeredStreamIds.add(streamId)
          Uri.parse(proxyUrl)
        }
      } else {
        traceStep(traceId, "providerUri") {
          app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkStreamingProvider.setConnection(connectionId, connection)
          app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkStreamingProvider.getUri(this, connectionId, networkFilePath)
        }
      }

      intent.putExtra("network_file_path", networkFilePath)
      intent.putExtra("network_connection_id", connectionId)
      intent.putExtra("title", fileName)
      intent.putExtra("filename", fileName)
      Log.d(
        TAG,
        "SMB trace[$traceId] resolveMpvnasUri done total=${SystemClock.elapsedRealtime() - startedAt}ms fileMatched=${file != null} useProxy=$useProxy uri=$resolvedUri",
      )

      return resolvedUri
    } catch (e: Exception) {
      Log.e(
        TAG,
        "SMB trace[$traceId] resolveMpvnasUri failed after ${SystemClock.elapsedRealtime() - startedAt}ms: $uri",
        e,
      )
      return null
    }
  }

  private fun guessMimeTypeFromPath(path: String): String {
    return when (path.substringAfterLast('.', "").lowercase()) {
      "mp4" -> "video/mp4"
      "mkv" -> "video/x-matroska"
      "webm" -> "video/webm"
      "avi" -> "video/x-msvideo"
      "mov" -> "video/quicktime"
      "flv" -> "video/x-flv"
      "wmv" -> "video/x-ms-wmv"
      "m4v" -> "video/x-m4v"
      "3gp" -> "video/3gpp"
      "ts" -> "video/mp2t"
      else -> "video/*"
    }
  }

  private fun generatePlaylistFromNetworkFolder(connectionId: Long, networkFilePath: String) {
    lifecycleScope.launch(Dispatchers.IO) {
      val traceId = "playlist_${connectionId}_${networkFilePath.hashCode()}"
      val startedAt = SystemClock.elapsedRealtime()
      runCatching {
        Log.d(TAG, "SMB trace[$traceId] generatePlaylistFromNetworkFolder start path=$networkFilePath")
        val connection = traceStep(traceId, "getConnectionById") {
          networkRepository.getConnectionById(connectionId)
        } ?: return@runCatching

        val parentPath = if (networkFilePath.contains("/")) {
          networkFilePath.substringBeforeLast("/")
        } else {
          ""
        }

        val listResult = traceStep(traceId, "listFiles parent=$parentPath") {
          networkRepository.listFiles(connection, parentPath)
        }
        val files = listResult.getOrNull() ?: return@runCatching
        Log.d(TAG, "SMB trace[$traceId] parent listing returned ${files.size} entries")

        val videoFiles = files.filter { file ->
          !file.isDirectory && app.marlboroadvance.mpvex.utils.storage.FileTypeUtils.VIDEO_EXTENSIONS.contains(
            file.name.substringAfterLast(".", "").lowercase()
          )
        }
        Log.d(TAG, "SMB trace[$traceId] filtered ${videoFiles.size} video entries")

        if (videoFiles.size <= 1) return@runCatching

        // Sort files naturally
        val siblingFiles = videoFiles.sortedWith { f1, f2 ->
          app.marlboroadvance.mpvex.utils.sort.SortUtils.NaturalOrderComparator.DEFAULT.compare(f1.name, f2.name)
        }

        val useProxy = connection.protocol in setOf(
          app.marlboroadvance.mpvex.domain.network.NetworkProtocol.SMB,
          app.marlboroadvance.mpvex.domain.network.NetworkProtocol.FTP,
          app.marlboroadvance.mpvex.domain.network.NetworkProtocol.WEBDAV
        )

        val initialPlayableUri = getPlayableUri(intent)?.toUri()

        val newPlaylist = mutableListOf<Uri>()
        var newIndex = 0

        traceStep(traceId, "buildPlaylistUris count=${siblingFiles.size}") {
          siblingFiles.forEachIndexed { idx, file ->
          val itemUri = if (file.path == networkFilePath && initialPlayableUri != null) {
            newIndex = idx
            initialPlayableUri
          } else {
            if (useProxy) {
              val proxy = app.marlboroadvance.mpvex.ui.browser.networkstreaming.proxy.NetworkStreamingProxy.getInstance()
              val streamId = "${connectionId}_${System.currentTimeMillis()}_${file.path.hashCode()}"
              val proxyUrl = proxy.registerStream(
                streamId = streamId,
                connection = connection,
                filePath = file.path,
                fileSize = file.size,
                mimeType = file.mimeType ?: "video/mp4",
                title = file.name
              )
              registeredStreamIds.add(streamId)
              Uri.parse(proxyUrl)
            } else {
              app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkStreamingProvider.setConnection(connectionId, connection)
              app.marlboroadvance.mpvex.ui.browser.networkstreaming.NetworkStreamingProvider.getUri(this@PlayerActivity, connectionId, file.path)
            }
          }
          newPlaylist.add(itemUri)
          playlistTitles[itemUri] = file.name
          playlistNetworkFilePaths[itemUri] = NetworkMediaIdUtils.canonicalizeNetworkPath(file.path) ?: file.path
        }
        }

        withContext(Dispatchers.Main) {
          playlist = newPlaylist
          playlistIndex = newIndex
          Log.d(
            TAG,
            "SMB trace[$traceId] Network playlist generated: ${playlist.size} videos total=${SystemClock.elapsedRealtime() - startedAt}ms",
          )
          // Re-initialize shuffle if enabled
          if (viewModel.shuffleEnabled.value) {
            onShuffleToggled(true)
          }
        }
      }.onFailure { e ->
        Log.e(
          TAG,
          "SMB trace[$traceId] Failed to generate network playlist after ${SystemClock.elapsedRealtime() - startedAt}ms",
          e,
        )
      }
    }
  }


  /**
   * Check if the current playlist is an M3U playlist (sourced from database).
   */
  fun isCurrentPlaylistM3U(): Boolean = isM3uPlaylist


  companion object {
    /**
     * Intent action used to return playback result data to the calling activity.
     */
    private const val RESULT_INTENT = "app.marlboroadvance.mpvex.ui.player.PlayerActivity.result"

    /**
     * Process-wide count of how many hard wedges (mpv stops answering JNI probes) we have seen
     * this process. `recreate()` is banned as recovery, so a wedge cannot be auto-fixed in place;
     * we only use this to (a) log the wedge count and (b) avoid spamming the restart toast.
     * Refreshed to 0 whenever playback is observed healthy again (see tickStallWatchdog), so the
     * cap is "consecutive wedges", not "ever".
     */
    @Volatile
    private var mpvRebuildCount = 0
    private const val MAX_MPV_REBUILDS = 2

    /**
     * Constant for "brightness not set".
     */
    private const val BRIGHTNESS_NOT_SET = -1f

    /**
     * Constant used when playback position is not set.
     */
    private const val POSITION_NOT_SET = 0

    /**
     * Maximum volume for MPV in percent.
     */
    private const val MAX_MPV_VOLUME = 100

    /**
     * Milliseconds-to-seconds conversion factor.
     */
    private const val MILLISECONDS_TO_SECONDS = 1000

    /**
     * Factor to divide subtitle and audio delays to convert from ms to seconds.
     */
    private const val DELAY_DIVISOR = 1000.0

    /**
     * Default playback speed (1.0 = normal).
     */
    private const val DEFAULT_PLAYBACK_SPEED = 1.0

    /**
     * Default subtitle speed (1.0 = normal).
     */
    private const val DEFAULT_SUB_SPEED = 1.0

    /**
     * General tag for logging from PlayerActivity.
     */
    const val TAG = "mpvex"
  }
}
