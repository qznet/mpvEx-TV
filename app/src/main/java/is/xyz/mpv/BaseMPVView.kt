package `is`.xyz.mpv

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
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
    /** True only after the OSD surface has reported surfaceChanged — the reliable
     *  signal that its ANativeWindow is actually usable. Recovery is gated on this,
     *  not on [osdSurfaceReady] (which surfaceCreated sets too early). */
    private var osdAttached = false
    /** Main (video) surface is currently attached. */
    private var videoSurfaceAttached = false
    /** Set once MPVLib.destroy() runs so late surface callbacks / recovery no-op. */
    private var mpvDestroyed = false

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
     * When vo=mediacodec_embed cannot open, mpv deselects the video track entirely
     * ("Video: no video") and never retries. `vid` is also left at "auto", so setting
     * it to "auto" again is a no-op. Force a *real* re-selection with a no->auto cycle;
     * mpv then rebuilds the decoder chain and re-opens vo=mediacodec_embed (with the OSD
     * surface already attached), painting the picture again. time-pos is preserved across
     * the track change, so playback resumes where it left off rather than jumping to 0.
     */
    private fun forceReselectVideoTrack() {
        if (MPVLib.getPropertyInt("video-params/w") != null) return
        Log.w(TAG, "forcing video track re-selection (attempt $recoveryAttempts/$RECOVERY_MAX)")
        MPVLib.setPropertyString("vid", "no")
        MPVLib.setPropertyString("vid", "auto")
        // mediacodec_embed only paints once a frame is decoded. When playback is paused,
        // re-selecting the track alone does not decode anything, so the screen stays black
        // even though the track is back. Nudge a re-decode by seeking to the current
        // position: a (no-op) seek re-reads the frame and paints it.
        if (MPVLib.getPropertyBoolean("pause") == true) {
            val pos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            Log.w(TAG, "paused: nudging frame decode at $pos to restore picture")
            runCatching { MPVLib.command("seek", pos.toString(), "absolute") }
        }
    }

    /**
     * Public safety net: bring the picture back if it was lost (e.g. on
     * background/lock-screen resume, where vo=mediacodec_embed needs the OSD
     * ANativeWindow attached at the moment the VO opens; otherwise mpv aborts the
     * open, deselects the video track and plays audio over a black screen).
     *
     * Design — single-flight, spaced, ONE re-selection per attempt:
     * - Gated on [osdAttached] (set only by the OSD surfaceChanged — the reliable
     *   "window usable" signal), never on surfaceCreated.
     * - A fatal VO open leaves the video *track* deselected and `vid` still "auto", so
     *   `vid="auto"` is a no-op. We force a real re-selection with a no->auto cycle,
     *   which makes mpv rebuild the decoder and re-open vo=mediacodec_embed.
     * - The no->auto cycle is done exactly ONCE per spaced attempt (500 ms apart,
     *   capped). It is never fired in a tight burst — that previously made mpv restart
     *   the playback chain dozens of times per millisecond and freeze the picture on a
     *   single frame. The top guard bails the instant video-params/w appears.
     */
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private var recoveryAttempts = 0
    private var recoveryRunning = false
    private val RECOVERY_MAX = 4
    private val RECOVERY_DELAY_MS = 500L

    fun recoverVideoOutputIfNeeded() {
        if (mpvDestroyed) return
        // Picture already up: cancel any pending recovery and bail.
        if (MPVLib.getPropertyInt("video-params/w") != null) {
            recoveryAttempts = 0
            recoveryRunning = false
            recoveryHandler.removeCallbacksAndMessages(null)
            return
        }
        // gpu/gpu-next have no separate OSD surface; a single reopen suffices.
        if (voInUse != "mediacodec_embed") {
            reopenVo()
            return
        }
        // Can't recover until both surfaces are truly usable; the OSD surfaceChanged
        // callback (or the Activity resume hook) will invoke us again once they are.
        if (osdSurface == null || !osdAttached || !videoSurfaceAttached) return
        // Single-flight: one recovery flow at a time (prevents burst re-entry from
        // multiple surface callbacks firing in the same instant).
        if (recoveryRunning) return
        recoveryRunning = true
        doRecoveryAttempt()
    }

    private fun doRecoveryAttempt() {
        if (mpvDestroyed) { recoveryRunning = false; return }
        if (MPVLib.getPropertyInt("video-params/w") != null) {
            recoveryRunning = false
            recoveryAttempts = 0
            return
        }
        if (recoveryAttempts >= RECOVERY_MAX) {
            Log.w(TAG, "video output still missing after $RECOVERY_MAX attempts; giving up (audio continues)")
            recoveryRunning = false
            recoveryAttempts = 0
            return
        }
        recoveryAttempts++
        Log.w(TAG, "recovering video output (attempt $recoveryAttempts/$RECOVERY_MAX)")
        // 1) Reopen the VO now that the OSD surface is attached.
        reopenVo()
        // 2) Re-select the video track (no->auto cycle). Done once per attempt.
        forceReselectVideoTrack()
        // 3) Re-check after a delay; if still missing, try again (spaced, not burst).
        recoveryHandler.postDelayed({
            if (MPVLib.getPropertyInt("video-params/w") != null) {
                recoveryRunning = false
                recoveryAttempts = 0
            } else {
                doRecoveryAttempt()
            }
        }, RECOVERY_DELAY_MS)
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
                if (mpvDestroyed) return
                MPVLib.attachOsdSurface(holder.surface)
                osdSurfaceReady = true
                // Do NOT trigger recovery here: surfaceCreated is not a reliable "window
                // usable" signal and firing here caused a same-millisecond burst of
                // re-selections on resume. Recovery is driven by surfaceChanged (below)
                // and the Activity resume / file-loaded hooks.
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (mpvDestroyed) return
                // surfaceChanged is the reliable signal that the surface now has real
                // dimensions and is a usable ANativeWindow. Re-attach (idempotent), mark
                // it usable, and (re)try recovery now that the OSD surface is truly present.
                MPVLib.attachOsdSurface(holder.surface)
                osdSurfaceReady = true
                osdAttached = true
                recoverVideoOutputIfNeeded()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.w(TAG, "detaching osd surface")
                osdSurfaceReady = false
                osdAttached = false
                recoveryRunning = false
                recoveryAttempts = 0
                recoveryHandler.removeCallbacksAndMessages(null)
                if (!mpvDestroyed) MPVLib.detachOsdSurface()
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

        // Reset any stale VO-recovery state from a previous session.
        recoverAttempts = 0
        recoveryRunning = false
        videoSurfaceAttached = false
        osdAttached = false
        mpvDestroyed = false
        recoveryHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Cancel any in-flight VO recovery so it can't touch mpv after destroy.
        recoveryRunning = false
        recoverAttempts = 0
        recoveryHandler.removeCallbacksAndMessages(null)
        // Mark destroyed BEFORE MPVLib.destroy() so any late surface callback that still
        // fires no-ops instead of touching an uninitialized native instance.
        mpvDestroyed = true
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
        if (mpvDestroyed) return
        Log.w(TAG, "attaching surface")
        MPVLib.attachSurface(holder.surface)
        videoSurfaceAttached = true
        // Early-attach the OSD surface only when it is truly usable (surfaceChanged has
        // fired). Attaching on surfaceCreated alone made mediacodec_embed open the VO
        // without a real OSD window and fail fatally.
        if (osdAttached) {
            osdSurface?.holder?.surface?.let { osdSurface ->
                Log.w(TAG, "attaching osd surface (early)")
                MPVLib.attachOsdSurface(osdSurface)
            }
        }
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

        // Ensure VO is set before loadfile (critical for mediacodec_embed).
        // Re-opening (instead of just setting) is required because the surface below
        // it was just replaced, and a stale/broken VO would otherwise be kept.
        // IMPORTANT: vo=mediacodec_embed can only open while the OSD ANativeWindow is
        // attached. The OSD surface is created asynchronously and on surface recreation
        // (e.g. Activity re-create on background/foreground, config change, or pause on
        // some TV firmwares) it often lags the main surface by hundreds of ms. If we
        // reopen here before the OSD surface exists, mpv aborts the open with
        // "No Android OSD Surface is attached", deselects the video track and never
        // retries -> permanent black screen until the next file is loaded. When the OSD
        // surface is not ready yet, skip the reopen and let the OSD surfaceChanged
        // callback (or the Activity resume hook) perform the recovery once it is available.
        val osdNotReady = voInUse == "mediacodec_embed" && (osdSurface != null && !osdAttached)
        if (!osdNotReady) {
            reopenVo()
        }

        if (filePath != null) {
            MPVLib.command("loadfile", filePath as String)
            filePath = null
        }

        // mediacodec_embed cannot letterbox inside mpv, so the SurfaceView is sized at the
        // view level. The first aspect callback can arrive before the parent is laid out to
        // its final size; listen for layout changes so the embed rectangle is recomputed
        // against the real container dimensions (and converges once layout settles).
        if (voInUse == "mediacodec_embed" && viewTreeObserver.isAlive) {
            viewTreeObserver.addOnGlobalLayoutListener(embedRelayoutListener)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
        videoSurfaceAttached = false
        // Cancel pending VO recovery: with the surface gone a retry would reopen the
        // VO against a missing surface and re-trigger the black screen.
        recoveryRunning = false
        recoveryAttempts = 0
        recoveryHandler.removeCallbacksAndMessages(null)
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalLayoutListener(embedRelayoutListener)
        }
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        // Note that before calling detachSurface() we need to be sure that libmpv
        // is done using the surface.
        // FIXME: There could be a race condition here, because I don't think
        // setting a property will wait for VO deinit.
        // Surface callbacks can still fire after MPVLib.destroy(); guard the detaches so
        // we never touch an uninitialized native instance.
        if (!mpvDestroyed) {
            MPVLib.detachOsdSurface()
            MPVLib.detachSurface()
        }
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
    }
}
