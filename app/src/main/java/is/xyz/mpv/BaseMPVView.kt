package `is`.xyz.mpv

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewTreeObserver
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout

// Contains only the essential code needed to get a picture on the screen

abstract class BaseMPVView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    /**
     * Optional OSD surface for vo=mediacodec_embed.
     * MediaCodec renders video directly to the main (video) surface, while mpv's
     * gpu VO renders subtitles/OSC to this separate OSD surface. Both must be
     * attached or mediacodec_embed fails with
     * "No Android OSD Surface is attached for direct MediaCodec output."
     */
    protected var osdSurface: SurfaceView? = null

    /**
     * True once the OSD SurfaceView has reported a *real* surface.
     *
     * A SurfaceView hands out a non-null [SurfaceHolder.surface] long before the
     * surface actually exists. Attaching that to mpv makes vo=mediacodec_embed
     * believe no OSD surface is present, and it then fails to open with
     * "No Android OSD Surface is attached for direct MediaCodec output."
     */
    private var osdSurfaceReady = false

    /**
     * Guards the verify-and-retry VO-open chain so the video-surface callback,
     * the OSD-surface callback and [recoverVideoOutputIfNeeded] don't start
     * overlapping retry loops (which would thrash vo=null / vo=mediacodec_embed).
     */
    private var voEnsureActive = false

    /**
     * Re-open the video output.
     *
     * Setting `vo` to the value it already has is a no-op, so mediacodec_embed is
     * first switched to "null" to tear the old (broken) VO down. This is what
     * [surfaceDestroyed] already does when the surface goes away.
     */
    private fun reopenVo() {
        if (voInUse == "mediacodec_embed") MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("vo", voInUse)
    }

    /**
     * Re-select the video track after a failed VO open.
     *
     * When mediacodec_embed cannot open, mpv deselects the video track entirely
     * ("Video: no video") and never retries, which leaves audio playing over a
     * black screen until the next file is loaded. Re-selecting the track makes mpv
     * rebuild the decoder and the video output.
     */
    private fun recoverVideoTrackIfMissing() {
        if (MPVLib.getPropertyInt("video-params/w") != null) return
        Log.w(TAG, "no video params after vo (re)open, re-selecting video track")
        MPVLib.setPropertyString("vid", "auto")
    }

    /**
     * Open the video output and keep retrying until a picture is actually up.
     *
     * vo=mediacodec_embed needs the OSD ANativeWindow (android-osd-wid) at the moment
     * the VO opens. [MPVLib.attachOsdSurface] queues that property *asynchronously*
     * inside libmpv, while setPropertyString("vo", ...) applies *synchronously* and
     * therefore races ahead of it — so the first open often fails with
     * "No Android OSD Surface is attached for direct MediaCodec output." We re-attach
     * the OSD surface and re-open the VO on a short back-off, verifying with
     * video-params/w each time, until the picture comes up or we hit the retry cap.
     */
    private fun ensureVideoOutput(retry: Int = 0) {
        if (retry == 0) {
            if (voEnsureActive) return
            voEnsureActive = true
        }
        if (voInUse != "mediacodec_embed") {
            reopenVo()
            voEnsureActive = false
            return
        }
        // (Re-)attach the OSD surface so android-osd-wid reflects the live window.
        // attachOsdSurface queues the android-osd-wid assignment *asynchronously* inside
        // libmpv, so it does NOT take effect immediately — reopening the VO in the same
        // tick would race ahead of it and fail with
        // "No Android OSD Surface is attached". We therefore attach first, then wait for
        // the wid to actually land (verified below) before the synchronous vo reopen.
        osdSurface?.holder?.surface?.let { s ->
            if (s.isValid) {
                MPVLib.attachOsdSurface(s)
                osdSurfaceReady = true
            }
        }
        val delay = if (retry == 0) VO_RECOVER_FIRST_DELAY_MS else VO_RECOVER_DELAY_MS
        postDelayed({
            if (!isAttachedToWindow) {
                voEnsureActive = false
                return@postDelayed
            }
            if (voInUse != "mediacodec_embed") {
                reopenVo()
                voEnsureActive = false
                return@postDelayed
            }
            // Gate the VO reopen on the OSD window actually being attached. Reading the
            // property back confirms the async attachOsdSurface has landed; if it hasn't
            // (still blank/"0") we must NOT reopen yet or the race repeats and the first
            // video stays black until the next file is loaded.
            val wid = runCatching { MPVLib.getPropertyString("android-osd-wid") }.getOrNull()
            if (wid.isNullOrBlank() || wid == "0") {
                if (retry < VO_RECOVER_MAX) {
                    ensureVideoOutput(retry + 1)
                } else {
                    Log.w(TAG, "OSD surface never attached; cannot open video output (vo=$voInUse)")
                    voEnsureActive = false
                }
                return@postDelayed
            }
            // The OSD window is live now, so it is safe to let mpv own a VO window.
            // force-window must only be enabled here (not in surfaceCreated) so that the
            // VO is never opened before android-osd-wid is attached.
            MPVLib.setOptionString("force-window", "yes")
            reopenVo()
            recoverVideoTrackIfMissing()
            if (MPVLib.getPropertyInt("video-params/w") != null) {
                Log.i(TAG, "video output up after ${retry + 1} attempt(s)")
                voEnsureActive = false
            } else if (retry < VO_RECOVER_MAX) {
                ensureVideoOutput(retry + 1)
            } else {
                Log.w(TAG, "video output failed to start after ${retry + 1} attempts (vo=$voInUse)")
                voEnsureActive = false
            }
        }, delay)
    }

    /**
     * Public safety net: bring the picture back if it never came up.
     *
     * No-op once the video is already running; otherwise start the verify-and-retry
     * open. Call this a moment after a file is loaded (or after playback resumes).
     */
    fun recoverVideoOutputIfNeeded() {
        if (MPVLib.getPropertyInt("video-params/w") != null) return
        ensureVideoOutput(0)
    }

    /**
     * Wire up a separate OSD SurfaceView. Its surface is attached/detached via
     * MPVLib.attachOsdSurface / detachOsdSurface (sets the android-osd-wid property).
     */
    fun setOsdSurfaceView(surfaceView: SurfaceView) {
        osdSurface = surfaceView
        // Make the OSD surface transparent and stack it as a media overlay ABOVE
        // the video surface. Without a transparent, media-overlay Z-order the
        // OSD SurfaceView defaults to an opaque black layer that covers the
        // video (audio keeps playing, no picture) under vo=mediacodec_embed.
        surfaceView.holder.setFormat(PixelFormat.TRANSPARENT)
        surfaceView.setZOrderMediaOverlay(true)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.w(TAG, "attaching osd surface")
                // Re-run the verify-and-retry VO open now that the OSD window exists.
                // ensureVideoOutput() re-attaches the OSD surface itself and is safe to
                // call repeatedly (guarded by voEnsureActive).
                ensureVideoOutput(0)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // OSD surface size is taken from the ANativeWindow; nothing to do.
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.w(TAG, "detaching osd surface")
                osdSurfaceReady = false
                MPVLib.detachOsdSurface()
            }
        })
    }

    /**
     * Initialize libmpv.
     *
     * Call this once before the view is shown.
     */
    fun initialize(configDir: String, cacheDir: String) {
        // The MPVLib property StateFlows are process-level singletons that are lazily
        // observed once and never re-observed. If a previous player session ended and we
        // are starting a new one (new native instance), those flows still hold the last
        // file's values, so reset them here. The next propXxx[...] access re-registers
        // observeProperty against the fresh native instance via getOrPut.
        MPVLib.clearPropertyFlows()

        MPVLib.create(context.applicationContext)

        /* set normal options (user-supplied config can override) */
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
            MPVLib.setOptionString(opt, cacheDir)
        initOptions()

        MPVLib.init()

        // Read vo from mpv.conf (user may have set mediacodec_embed)
        // Must be done AFTER init() which reads the config file
        val configuredVo = MPVLib.getPropertyString("vo")
        if (!configuredVo.isNullOrBlank() && configuredVo != "auto") {
            voInUse = configuredVo
            Log.w(TAG, "vo from mpv.conf: $voInUse")
        } else {
            Log.w(TAG, "vo not set in mpv.conf, using default: $voInUse")
        }

        /* set hardcoded options */
        postInitOptions()
        // could mess up VO init before surfaceCreated() is called
        MPVLib.setOptionString("force-window", "no")
        // need to idle at least once for playFile() logic to work
        MPVLib.setOptionString("idle", "once")

        holder.addCallback(this)
        observeProperties()
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Disable surface callbacks to avoid using uninitialized mpv state
        holder.removeCallback(this)
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalLayoutListener(embedRelayoutListener)
        }

        MPVLib.destroy()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()

    protected abstract fun observeProperties()

    private var filePath: String? = null

    /**
     * Set the first file to be played once the player is ready.
     */
    fun playFile(filePath: String) {
        // Reset aspect so the next video-params/aspect callback re-calculates
        // the letterbox layout from scratch, avoiding stale aspect from the previous file.
        lastEmbedAspect = null
        this.filePath = filePath
    }

    private var voInUse: String = "gpu"

    /**
     * Sets the VO to use.
     * It is automatically disabled/enabled when the surface dis-/appears.
     */
    fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    // Surface callbacks

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.w(TAG, "attaching surface")
        MPVLib.attachSurface(holder.surface)
        // NOTE: do NOT set force-window="yes" here. That makes mpv open the VO window
        // immediately (even with no file loaded), racing ahead of the async OSD-window
        // attach and failing with "No Android OSD Surface is attached" on the first video
        // (black screen). force-window is only needed for the *idle* window, and is set in
        // ensureVideoOutput() AFTER android-osd-wid is confirmed. During playback the VO
        // opens on loadfile() with the OSD window already attached, so no black screen.

        // mediacodec_embed renders video through MediaCodec directly to the main surface, but it
        // REQUIRES the OSD window (android-osd-wid) to be attached at the moment the VO opens.
        // attachOsdSurface() lands that window asynchronously inside libmpv, so if we let mpv open
        // the VO immediately (via loadfile) the first open races ahead and fails with
        // "No Android OSD Surface is attached" -> "Video: no video" (black screen until the next
        // file is loaded). Attach the OSD window now and only start playback once android-osd-wid
        // is actually reported back, so the very first VO open succeeds.
        attachOsdNow()

        // `filePath` is a mutable property, so the compiler cannot smart-cast it to
        // non-null here — use a null-safe let instead of a plain local copy.
        val pending = filePath
        if (pending != null) {
            filePath = null
            loadFileAfterOsdReady(pending)
        } else {
            ensureVideoOutput()
        }

        // mediacodec_embed cannot letterbox inside mpv, so the SurfaceView is sized at the
        // view level. The first aspect callback can arrive before the parent is laid out to
        // its final size; listen for layout changes so the embed rectangle is recomputed
        // against the real container dimensions (and converges once layout settles).
        if (voInUse == "mediacodec_embed" && viewTreeObserver.isAlive) {
            viewTreeObserver.addOnGlobalLayoutListener(embedRelayoutListener)
        }
    }

    /** Attach the OSD window to libmpv right now (idempotent; harmless if already attached). */
    private fun attachOsdNow() {
        osdSurface?.holder?.surface?.let { s ->
            if (s.isValid) {
                MPVLib.attachOsdSurface(s)
                osdSurfaceReady = true
            }
        }
    }

    /**
     * Start the first playback only after android-osd-wid is reported present, so the
     * mediacodec_embed VO open does not race the async OSD attach. Falls back to loading
     * anyway after a short cap (then asks ensureVideoOutput() to retry the VO) so we never hang.
     */
    private fun loadFileAfterOsdReady(path: String) {
        var tries = 0
        val maxTries = 25 // ~ up to 500 ms at a 20 ms poll
        val runnable = object : Runnable {
            override fun run() {
                val wid = runCatching { MPVLib.getPropertyString("android-osd-wid") }.getOrNull()
                if (!wid.isNullOrBlank() && wid != "0") {
                    Log.i(TAG, "OSD window ready ($wid); loading first file")
                    MPVLib.command("loadfile", path)
                } else if (tries++ < maxTries) {
                    postDelayed(this, 20)
                } else {
                    Log.w(TAG, "OSD window never became ready; loading anyway and retrying VO")
                    MPVLib.command("loadfile", path)
                    ensureVideoOutput()
                }
            }
        }
        postDelayed(runnable, 20)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalLayoutListener(embedRelayoutListener)
        }
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because I don't think
        // setting a property will wait for VO deinit.
        MPVLib.detachOsdSurface()
        MPVLib.detachSurface()
    }

    /**
     * For vo=mediacodec_embed the video is rendered straight to this SurfaceView's
     * ANativeWindow by MediaCodec, so libmpv cannot letterbox it (no GL/scaling).
     * To preserve the source DAR we shrink the view to a centered rectangle that
     * matches the DAR and let the (black) parent show through as letterbox/
     * pillarbox. gpu/gpu-next handle aspect internally, so this is a no-op there.
     * Changing the view size rebuilds the Surface (brief re-decode), which is the
     * unavoidable cost of letterboxing a direct MediaCodec surface.
     */
    private var lastEmbedAspect: Double? = null

    /**
     * For vo=mediacodec_embed the video is rendered into this SurfaceView's ANativeWindow
     * by MediaCodec, so libmpv cannot letterbox it. We instead shrink the SurfaceView to a
     * centered rectangle matching the source DAR (see [applyEmbedAspectRatio]).
     *
     * The problem this listener solves: [applyEmbedAspectRatio] is only invoked from the
     * `video-params/aspect` observer. On the FIRST video that event can fire while the parent
     * container is still mid-layout (transient / not-yet-final size), so the SurfaceView gets
     * sized to a small rectangle and then stays stuck there — because no further aspect event
     * ever arrives to recompute it. Switching to the next video (parent already settled) or
     * pressing the "fit screen" button (re-triggers the same call later) both fix it, which is
     * exactly the symptom reported.
     *
     * Solution: whenever the global layout settles or the parent size changes, recompute the
     * embed rectangle against the *current* parent dimensions. The size guard inside
     * [applyEmbedAspectRatio] makes this a no-op unless the rectangle actually needs to change,
     * so it is cheap once things are stable. Active only in mediacodec_embed mode and only when
     * the user has not chosen an explicit Crop/Stretch override (which we must not fight).
     */
    private val embedRelayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (voInUse != "mediacodec_embed" || lastEmbedAspect == null) return@OnGlobalLayoutListener
        // Respect explicit Crop (panscan=1) / Stretch / custom (override>0) choices.
        val override = MPVLib.getPropertyDouble("video-aspect-override") ?: -1.0
        val panscan = MPVLib.getPropertyDouble("panscan") ?: 0.0
        if (override > 0.0 || panscan >= 1.0) return@OnGlobalLayoutListener
        applyEmbedAspectRatio(null)
    }

    fun applyEmbedAspectRatio(aspect: Double?) {
        if (voInUse != "mediacodec_embed") return
        if (aspect != null) lastEmbedAspect = aspect
        val dar = lastEmbedAspect ?: return
        val parent = parent as? ViewGroup ?: return
        val cw = parent.width
        val ch = parent.height
        if (cw <= 0 || ch <= 0) return
        val containerDar = cw.toDouble() / ch
        val (w, h) = if (dar > containerDar) {
            cw to (cw / dar).toInt()
        } else {
            ((ch * dar).toInt()) to ch
        }
        val lp = layoutParams as? ConstraintLayout.LayoutParams ?: return
        Log.w(TAG, "applyEmbed: dar=$dar container=${cw}x${ch} cDar=$containerDar -> ${w}x${h} (cur=${lp.width}x${lp.height})")
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        lp.horizontalBias = 0.5f
        lp.verticalBias = 0.5f
        layoutParams = lp
    }

    companion object {
        private const val TAG = "mpv"
        // Verify-and-retry budget for the mediacodec_embed VO open. The OSD window is
        // attached asynchronously, so the first open(s) may fail; we re-open until a
        // picture appears. 12 * 120ms ≈ 1.4s worst case, well within a playback start.
        private const val VO_RECOVER_MAX = 20
        private const val VO_RECOVER_DELAY_MS = 120L
        // First attempt waits a touch longer so the async android-osd-wid (set by
        // attachOsdSurface) has time to land before we verify-and-reopen the VO.
        private const val VO_RECOVER_FIRST_DELAY_MS = 120L
    }
}
