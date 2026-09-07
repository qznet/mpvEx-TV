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
     * Public safety net: bring the picture back if it never came up.
     *
     * vo=mediacodec_embed needs the OSD ANativeWindow at the moment the VO is
     * opened. When the surface is not there yet mpv aborts the open and deselects
     * the video track, leaving audio playing over a black screen until another file
     * is loaded. Call this a moment after a file is loaded (or after playback
     * resumes); it is a no-op whenever video is already running.
     */
    fun recoverVideoOutputIfNeeded() {
        if (MPVLib.getPropertyInt("video-params/w") != null) return
        // Nothing can be done until the OSD surface exists; the OSD callback retries
        // as soon as it shows up.
        if (voInUse == "mediacodec_embed" && osdSurface != null && !osdSurfaceReady) return
        Log.w(TAG, "video output missing, re-opening vo=$voInUse")
        reopenVo()
        recoverVideoTrackIfMissing()
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
                // Re-open the VO so mediacodec_embed picks up the now-available OSD
                // surface in case it was attached after the video surface / vo was set.
                reopenVo()
                recoverVideoTrackIfMissing()
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
        reopenVo()

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
