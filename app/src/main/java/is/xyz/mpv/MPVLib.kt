package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// Wrapper for native library

@Suppress("unused")
object MPVLib {
    init {
        val libs = arrayOf("mpv", "player")
        for (lib in libs) {
            System.loadLibrary(lib)
        }
    }

    external fun create(appctx: Context)
    external fun init()
    external fun destroy(): Int
    external fun attachSurface(surface: Surface)
    external fun replaceSurface(surface: Surface)
    external fun detachSurface()
    external fun attachOsdSurface(surface: Surface)
    external fun replaceOsdSurface(surface: Surface)
    external fun detachOsdSurface()

    // Vararg overload so qznet UI can call command("seek", "10", mode).
    // JVM signature stays command([Ljava/lang/String;)I, which links to the
    // FongMi native JNI symbol Java_is_xyz_mpv_MPVLib_command.
    external fun command(vararg cmd: String): Int

    // Completion is delivered through EventObserver.eventCommandReply.
    external fun enqueueCommand(requestId: Long, cmd: Array<out String>): Int

    external fun setOptionString(name: String, value: String): Int

    external fun grabThumbnail(dimension: Int): Bitmap?

    external fun getPropertyInt(property: String): Int?
    external fun setPropertyInt(property: String, value: Int): Int
    external fun getPropertyDouble(property: String): Double?
    external fun setPropertyDouble(property: String, value: Double): Int
    external fun getPropertyBoolean(property: String): Boolean?
    external fun setPropertyBoolean(property: String, value: Boolean): Int
    external fun getPropertyString(property: String): String?
    external fun setPropertyString(property: String, value: String): Int
    external fun getPropertyByteArray(property: String): ByteArray?

    // mpv native has no float; delegate through Double.
    fun getPropertyFloat(property: String): Float? = getPropertyDouble(property)?.toFloat()
    fun setPropertyFloat(property: String, value: Float): Int = setPropertyDouble(property, value.toDouble())

    external fun observeProperty(property: String, format: Int): Int

    // ---- Property flow registry (Kotlin-side StateFlows driven by native events) ----

    private val propertyFlows = mutableMapOf<String, MutableStateFlow<Any?>>()

    private fun getOrCreateFlow(property: String, format: Int): MutableStateFlow<Any?> {
        synchronized(propertyFlows) {
            propertyFlows[property]?.let { return it }
            val flow = MutableStateFlow<Any?>(null)
            propertyFlows[property] = flow
            observeProperty(property, format)
            return flow
        }
    }

    private fun emit(property: String, value: Any?) {
        synchronized(propertyFlows) {
            propertyFlows[property]?.value = value
        }
    }

    // propInt: Flow<Int?> (native fires INT64 -> Long, read as Int)
    object propInt {
        private val cache = mutableMapOf<String, Flow<Int?>>()
        operator fun get(property: String): Flow<Int?> = synchronized(cache) {
            cache.getOrPut(property) {
                getOrCreateFlow(property, MpvFormat.MPV_FORMAT_INT64).map { (it as? Number)?.toInt() }
            }
        }
    }

    // propDouble: Flow<Double?>
    object propDouble {
        private val cache = mutableMapOf<String, Flow<Double?>>()
        operator fun get(property: String): Flow<Double?> = synchronized(cache) {
            cache.getOrPut(property) {
                getOrCreateFlow(property, MpvFormat.MPV_FORMAT_DOUBLE).map { it as? Double }
            }
        }
    }

    // propBoolean: Flow<Boolean?>
    object propBoolean {
        private val cache = mutableMapOf<String, Flow<Boolean?>>()
        operator fun get(property: String): Flow<Boolean?> = synchronized(cache) {
            cache.getOrPut(property) {
                getOrCreateFlow(property, MpvFormat.MPV_FORMAT_FLAG).map { it as? Boolean }
            }
        }
    }

    // propString: Flow<String?>
    object propString {
        private val cache = mutableMapOf<String, Flow<String?>>()
        operator fun get(property: String): Flow<String?> = synchronized(cache) {
            cache.getOrPut(property) {
                getOrCreateFlow(property, MpvFormat.MPV_FORMAT_STRING).map { it as? String }
            }
        }
    }

    // propFloat: Flow<Float?> (observe as DOUBLE, convert on read)
    object propFloat {
        private val cache = mutableMapOf<String, Flow<Float?>>()
        operator fun get(property: String): Flow<Float?> = synchronized(cache) {
            cache.getOrPut(property) {
                getOrCreateFlow(property, MpvFormat.MPV_FORMAT_DOUBLE).map { (it as? Number)?.toFloat() }
            }
        }
    }

    // propNode: Flow<MPVNode?> (observe as STRING; mpv serializes NODE props to JSON)
    object propNode {
        private val cache = mutableMapOf<String, Flow<MPVNode?>>()
        operator fun get(property: String): Flow<MPVNode?> = synchronized(cache) {
            cache.getOrPut(property) {
                getOrCreateFlow(property, MpvFormat.MPV_FORMAT_STRING).map { v ->
                    (v as? String)?.let { MPVNode.fromJsonString(it) }
                }
            }
        }
    }

    // ---- Observer management ----

    private val observers = mutableListOf<EventObserver>()

    @JvmStatic
    fun addObserver(o: EventObserver) {
        synchronized(observers) {
            observers.add(o)
        }
    }

    @JvmStatic
    fun removeObserver(o: EventObserver) {
        synchronized(observers) {
            observers.remove(o)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Long) {
        emit(property, value)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) {
        emit(property, value)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) {
        emit(property, value)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) {
        emit(property, value)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: MPVNode) {
        emit(property, value)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String) {
        emit(property, null)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property)
        }
    }

    @JvmStatic
    fun event(eventId: Int) {
        synchronized(observers) {
            for (o in observers)
                o.event(eventId)
        }
    }

    @JvmStatic
    fun event(eventId: Int, data: MPVNode) {
        synchronized(observers) {
            for (o in observers)
                o.event(eventId, data)
        }
    }

    @JvmStatic
    fun eventCommandReply(requestId: Long, error: Int) {
        synchronized(observers) {
            for (o in observers)
                o.eventCommandReply(requestId, error)
        }
    }

    @JvmStatic
    fun eventEndFile(reason: Int, error: Int, errorString: String?) {
        synchronized(observers) {
            for (o in observers)
                o.eventEndFile(reason, error, errorString)
        }
    }

    // ---- Log observer management ----

    private val log_observers = mutableListOf<LogObserver>()

    @JvmStatic
    fun addLogObserver(o: LogObserver) {
        synchronized(log_observers) {
            log_observers.add(o)
        }
    }

    @JvmStatic
    fun removeLogObserver(o: LogObserver) {
        synchronized(log_observers) {
            log_observers.remove(o)
        }
    }

    @JvmStatic
    fun logMessage(prefix: String, level: Int, text: String) {
        synchronized(log_observers) {
            for (o in log_observers)
                o.logMessage(prefix, level, text)
        }
    }

    // ---- Interfaces ----

    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun eventProperty(property: String, value: Double)
        // qznet UI callback for MPVNode; native (FongMi) does not fire this directly.
        fun eventProperty(property: String, value: MPVNode) {}
        fun event(eventId: Int)
        // qznet UI callback for event data; native does not fire this directly.
        fun event(eventId: Int, data: MPVNode) {}
        fun eventCommandReply(requestId: Long, error: Int) {}
        fun eventEndFile(reason: Int, error: Int, errorString: String?) {
            event(MpvEvent.MPV_EVENT_END_FILE)
        }
    }

    interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object MpvFormat {
        const val MPV_FORMAT_NONE: Int = 0
        const val MPV_FORMAT_STRING: Int = 1
        const val MPV_FORMAT_OSD_STRING: Int = 2
        const val MPV_FORMAT_FLAG: Int = 3
        const val MPV_FORMAT_INT64: Int = 4
        const val MPV_FORMAT_DOUBLE: Int = 5
        const val MPV_FORMAT_NODE: Int = 6
        const val MPV_FORMAT_NODE_ARRAY: Int = 7
        const val MPV_FORMAT_NODE_MAP: Int = 8
        const val MPV_FORMAT_BYTE_ARRAY: Int = 9
    }

    object MpvEvent {
        const val MPV_EVENT_NONE: Int = 0
        const val MPV_EVENT_SHUTDOWN: Int = 1
        const val MPV_EVENT_LOG_MESSAGE: Int = 2
        const val MPV_EVENT_GET_PROPERTY_REPLY: Int = 3
        const val MPV_EVENT_SET_PROPERTY_REPLY: Int = 4
        const val MPV_EVENT_COMMAND_REPLY: Int = 5
        const val MPV_EVENT_START_FILE: Int = 6
        const val MPV_EVENT_END_FILE: Int = 7
        const val MPV_EVENT_FILE_LOADED: Int = 8
        @Deprecated("")
        const val MPV_EVENT_IDLE: Int = 11
        @Deprecated("")
        const val MPV_EVENT_TICK: Int = 14
        const val MPV_EVENT_CLIENT_MESSAGE: Int = 16
        const val MPV_EVENT_VIDEO_RECONFIG: Int = 17
        const val MPV_EVENT_AUDIO_RECONFIG: Int = 18
        const val MPV_EVENT_SEEK: Int = 20
        const val MPV_EVENT_PLAYBACK_RESTART: Int = 21
        const val MPV_EVENT_PROPERTY_CHANGE: Int = 22
        const val MPV_EVENT_QUEUE_OVERFLOW: Int = 24
        const val MPV_EVENT_HOOK: Int = 25
    }

    object MpvEndFileReason {
        const val MPV_END_FILE_REASON_EOF: Int = 0
        const val MPV_END_FILE_REASON_STOP: Int = 2
        const val MPV_END_FILE_REASON_QUIT: Int = 3
        const val MPV_END_FILE_REASON_ERROR: Int = 4
        const val MPV_END_FILE_REASON_REDIRECT: Int = 5
    }

    object MpvError {
        const val MPV_ERROR_SUCCESS: Int = 0
        const val MPV_ERROR_EVENT_QUEUE_FULL: Int = -1
        const val MPV_ERROR_NOMEM: Int = -2
        const val MPV_ERROR_UNINITIALIZED: Int = -3
        const val MPV_ERROR_INVALID_PARAMETER: Int = -4
        const val MPV_ERROR_OPTION_NOT_FOUND: Int = -5
        const val MPV_ERROR_OPTION_FORMAT: Int = -6
        const val MPV_ERROR_OPTION_ERROR: Int = -7
        const val MPV_ERROR_PROPERTY_NOT_FOUND: Int = -8
        const val MPV_ERROR_PROPERTY_FORMAT: Int = -9
        const val MPV_ERROR_PROPERTY_UNAVAILABLE: Int = -10
        const val MPV_ERROR_PROPERTY_ERROR: Int = -11
        const val MPV_ERROR_COMMAND: Int = -12
        const val MPV_ERROR_LOADING_FAILED: Int = -13
        const val MPV_ERROR_AO_INIT_FAILED: Int = -14
        const val MPV_ERROR_VO_INIT_FAILED: Int = -15
        const val MPV_ERROR_NOTHING_TO_PLAY: Int = -16
        const val MPV_ERROR_UNKNOWN_FORMAT: Int = -17
        const val MPV_ERROR_UNSUPPORTED: Int = -18
        const val MPV_ERROR_NOT_IMPLEMENTED: Int = -19
        const val MPV_ERROR_GENERIC: Int = -20
    }

    object MpvLogLevel {
        const val MPV_LOG_LEVEL_NONE: Int = 0
        const val MPV_LOG_LEVEL_FATAL: Int = 10
        const val MPV_LOG_LEVEL_ERROR: Int = 20
        const val MPV_LOG_LEVEL_WARN: Int = 30
        const val MPV_LOG_LEVEL_INFO: Int = 40
        const val MPV_LOG_LEVEL_V: Int = 50
        const val MPV_LOG_LEVEL_DEBUG: Int = 60
        const val MPV_LOG_LEVEL_TRACE: Int = 70
    }
}
