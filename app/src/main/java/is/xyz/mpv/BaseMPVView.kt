package `is`.xyz.mpv

import android.content.Context
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
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
                // Re-apply VO so mediacodec_embed picks up the now-available OSD surface
                // in case it was attached after the video surface / vo was set.
                MPVLib.setPropertyString("vo", voInUse)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // OSD surface size is taken from the ANativeWindow; nothing to do.
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.w(TAG, "detaching osd surface")
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
        // Attach OSD surface early if it is already available (same-layout SurfaceView).
        // If not, the OSD SurfaceView's own callback will attach it and re-apply vo.
        osdSurface?.holder?.surface?.let { osdSurface ->
            Log.w(TAG, "attaching osd surface (early)")
            MPVLib.attachOsdSurface(osdSurface)
        }
        // This forces mpv to render subs/osd/whatever into our surface even if it would ordinarily not
        MPVLib.setOptionString("force-window", "yes")

        // Ensure VO is set before loadfile (critical for mediacodec_embed)
        MPVLib.setPropertyString("vo", voInUse)

        if (filePath != null) {
            MPVLib.command("loadfile", filePath as String)
            filePath = null
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
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
     * For vo=mediacodec_embed, MediaCodec renders decoded frames straight to
     * this SurfaceView's ANativeWindow and libmpv cannot letterbox or scale them
     * (no GL pass). We resize the SurfaceView to a cover-fit rectangle that has
     * the source DAR and covers the parent container: at least one axis runs
     * edge-to-edge, the other may extend past the parent bounds and gets
     * cropped by the layout. The matching buffer DAR keeps MediaCodec from
     * adding its own letterbox inside the SurfaceView.
     *
     * Examples (parent 1920x1080, containerDar=1.78):
     *   16:9 video (dar=1.78) → (1922,1080): barely overflows, full screen
     *   4:3 video  (dar=1.33) → (1920,1444): full width, top/bottom cropped
     *   21:9 video (dar=2.33) → (2518,1080): full height, sides cropped
     *
     * gpu/gpu-next handle aspect internally, so this is a no-op there.
     * Aspect changes trigger a brief re-decode flash because MediaCodec must
     * renegotiate its output format -- unavoidable for any scaling done outside
     * mpv.
     */
    private var lastEmbedAspect: Double? = null

    fun applyEmbedAspectRatio(aspect: Double?) {
        if (voInUse != "mediacodec_embed") return
        if (aspect != null) lastEmbedAspect = aspect
        val dar = lastEmbedAspect ?: return
        val parent = parent as? ViewGroup ?: return
        val cw = parent.width
        val ch = parent.height
        if (cw <= 0 || ch <= 0) return

        // Cover-fit: output rect has ratio dar and covers the container when
        // centered. Whichever axis is already full, the other extends beyond
        // the parent bounds and gets clipped by the layout.
        val w = maxOf(cw, (ch * dar).toInt())
        val h = maxOf(ch, (cw / dar).toInt())

        val lp = layoutParams as? ConstraintLayout.LayoutParams ?: return
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
