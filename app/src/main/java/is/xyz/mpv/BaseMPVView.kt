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
        // mediacodec_embed only paints once a frame is decoded. When playback is paused,
        // re-selecting the track alone does not decode anything, so the screen stays black
        // even though the track is back. Nudge a re-decode by seeking to the current
        // position: a (no-op) seek re-reads the frame and paints it. On resume this is not
        // needed because new frames decode naturally.
        if (MPVLib.getPropertyBoolean("pause") == true) {
            val pos = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            Log.w(TAG, "paused: nudging frame decode at $pos to restore picture")
            runCatching { MPVLib.command("seek", pos.toString(), "absolute") }
        }
    }

    /**
     * Public safety net: bring the picture back if it never came up.
     *
     * vo=mediacodec_embed needs the OSD ANativeWindow at the moment the VO is
     * opened. When the surface is not there yet mpv aborts the open and deselects
     * the video track, leaving audio playing over a black screen until another file
     * is loaded. Call this a moment after a file is loaded (or after playback
     * resumes); it is a no-op whenever video is already running.
     *
     * On background/lock-screen resume the OSD SurfaceView reports
     * surfaceCreated (and sets [osdSurfaceReady]) before its underlying
     * ANativeWindow is actually usable, so the first VO reopen may still hit
     * "No Android OSD Surface is attached" and deselect the video track. This
     * method therefore retries on a short delay until the picture appears (or we
     * give up after [RECOVER_MAX] attempts). The top guard stops the instant video
     * is present, so a working VO is never torn down by a later attempt.
     */
    private val recoverHandler = Handler(Looper.getMainLooper())
    private var recoverAttempts = 0
    private val RECOVER_MAX = 12
    private val RECOVER_DELAY_MS = 250L

    fun recoverVideoOutputIfNeeded() {
        // Picture is already up: cancel any pending retry chain and bail out.
        if (MPVLib.getPropertyInt("video-params/w") != null) {
            recoverAttempts = 0
            recoverHandler.removeCallbacksAndMessages(null)
            return
        }
        // gpu/gpu-next have no separate OSD surface; a single attempt suffices.
        if (voInUse != "mediacodec_embed") {
            reopenVo()
            recoverVideoTrackIfMissing()
            return
        }
        // Nothing can be done until the OSD surface exists; the OSD callback invokes
        // us again as soon as it shows up.
        if (osdSurface == null || !osdSurfaceReady) return
        Log.w(TAG, "video output missing, re-opening vo=mediacodec_embed (attempt ${recoverAttempts + 1}/$RECOVER_MAX)")
        reopenVo()
        recoverVideoTrackIfMissing()
        if (MPVLib.getPropertyInt("video-params/w") != null) {
            recoverAttempts = 0
            recoverHandler.removeCallbacksAndMessages(null)
        } else if (recoverAttempts < RECOVER_MAX) {
            recoverAttempts++
            // Only ever keep a single pending retry in flight.
            recoverHandler.removeCallbacksAndMessages(null)
            recoverHandler.postDelayed({ recoverVideoOutputIfNeeded() }, RECOVER_DELAY_MS)
        } else {
            Log.w(TAG, "video output still missing after $RECOVER_MAX attempts; giving up (audio continues)")
            recoverAttempts = 0
        }
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
                MPVLib.attachOsdSurface(holder.surface)
                osdSurfaceReady = true
                // Do NOT reopen the VO synchronously here: the SurfaceView's surface may
                // not be a fully usable ANativeWindow yet right after surfaceCreated
                // (race on background/foreground resume), so a direct reopen still hits
                // "No Android OSD Surface is attached". Let recoverVideoOutputIfNeeded()
                // retry until the picture actually appears.
                recoverVideoOutputIfNeeded()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // surfaceChanged is the reliable signal that the surface now has real
                // dimensions and is a usable ANativeWindow. Re-attach (idempotent) and
                // (re)try opening the VO through the retry-aware recovery path.
                MPVLib.attachOsdSurface(holder.surface)
                osdSurfaceReady = true
                recoverVideoOutputIfNeeded()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.w(TAG, "detaching osd surface")
                osdSurfaceReady = false
                recoverAttempts = 0
                recoverHandler.removeCallbacksAndMessages(null)
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

        // Reset any stale VO-recovery retry state from a previous session.
        recoverAttempts = 0
        recoverHandler.removeCallbacksAndMessages(null)
    }

    /**
     * Deinitialize libmpv.
     *
     * Call this once before the view is destroyed.
     */
    fun destroy() {
        // Cancel any in-flight VO recovery retry so it can't touch mpv after destroy.
        recoverAttempts = 0
        recoverHandler.removeCallbacksAndMessages(null)
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
        // Attach the OSD surface early only when it is already valid. A SurfaceView
        // returns a non-null Surface before its surface exists; attaching that made
        // mediacodec_embed open the VO without an OSD window and fail fatally.
        if (osdSurfaceReady) {
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
        // surface is not ready yet, skip the reopen and let the OSD surfaceCreated
        // callback perform it the moment it becomes available.
        val osdNotReady = voInUse == "mediacodec_embed" && osdSurface != null && !osdSurfaceReady
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
        // Cancel pending VO recovery: with the surface gone a retry would reopen the
        // VO against a missing surface and re-trigger the black screen.
        recoverAttempts = 0
        recoverHandler.removeCallbacksAndMessages(null)
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
    }
}
