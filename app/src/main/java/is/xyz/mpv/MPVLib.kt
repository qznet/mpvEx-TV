package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.properties.ReadOnlyProperty
import kotlin.properties.getValue

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

    // FongMi native signature

    // Vararg overload 鈥?qznet UI calls command("seek", "10", seekMode)
    @JvmStatic
    fun command(vararg cmd: String): Int = command(cmd as Array<out String>)

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

    // Float support 鈥?mpv native has no float; we delegate through Double
    @JvmStatic
    fun getPropertyFloat(property: String): Float? {
        return getPropertyDouble(property)?.toFloat()
    }

    @JvmStatic
    fun setPropertyFloat(property: String, value: Float): Int {
        return setPropertyDouble(property, value.toDouble())
    }

    external fun observeProperty(property: String, format: Int): Int

    // 鈹€鈹€ Internal property flow registry 鈹€鈹€

    // Maps property name 鈫?MutableStateFlow<Any?>
    // Any? = Int? | Long? | Double? | Boolean? | String? | MPVNode? | null
    private val propertyFlows = mutableMapOf<String, MutableStateFlow<Any?>>()

    /**
     * Get or create a MutableStateFlow for a property.
     * Observes the property with the given mpv format.
     */
    private fun getOrCreateFlow(property: String, format: Int): MutableStateFlow<Any?> {
        synchronized(propertyFlows) {
            val existing = propertyFlows[property]
            if (existing != null) return existing
            val flow = MutableStateFlow<Any?>(null)
            propertyFlows[property] = flow
            // Auto-observe in native
            observeProperty(property, format)
            return flow
        }
    }

    // 鈹€鈹€ Kotlin property delegates (by MPVLib.propInt["time-pos"]) 鈹€鈹€

    // propInt: Int? delegate 鈥?mpv returns INT64, native fires eventProperty(L)
    // We cast Long鈫扞nt and store as Int? in the flow
    object propInt {
        private val intFlows = mutableMapOf<String, IntFlow>()

        /** Subscript: MPVLib.propInt["time-pos"] */
        @JvmName("getProperty")
        operator fun get(property: String): IntFlow {
            synchronized(intFlows) {
                return intFlows.getOrPut(property) {
                    IntFlow(property, getOrCreateFlow(property, MpvFormat.MPV_FORMAT_INT64))
                }
            }
        }

        /** Kotlin property delegate: by MPVLib.propInt.timePos */
        fun provideDelegate(thisRef: Any?, property: kotlin.reflect.KProperty<*>): IntFlow {
            return get(property.name)
        }
        fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): IntFlow = get(property.name)
    }

    /**
     * Wraps a MutableStateFlow<Long?> and exposes its value as Int?.
     * Native JNI stores Long; Kotlin side reads Int.
     */
    class IntFlow(private val prop: String, private val source: MutableStateFlow<Any?>) {
        val value: Int?
            get() = (source.value as? Number)?.toInt()

        // StateFlow contract (read-only needed for `by` syntax)
        // Use the source's collection interface
        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): Int? = value

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): ReadOnlyProperty<Any?, Int?> =
            ReadOnlyProperty { _, _ -> value }

        companion object {
            // Convert Long鈫扞nt when native fires
            @JvmStatic
            fun update(prop: String, newValue: Long) {
                val flow: MutableStateFlow<Any?>?
                synchronized(propertyFlows) { flow = propertyFlows[prop] }
                flow?.value = newValue.toInt()
            }
        }
    }

    // propDouble: Double?
    object propDouble {
        private val flows = mutableMapOf<String, DoubleFlow>()

        @JvmName("getProperty")
        operator fun get(property: String): DoubleFlow {
            synchronized(flows) {
                return flows.getOrPut(property) {
                    DoubleFlow(property, getOrCreateFlow(property, MpvFormat.MPV_FORMAT_DOUBLE))
                }
            }
        }

        fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): DoubleFlow = get(prop.name)
        fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): DoubleFlow = get(prop.name)
    }

    class DoubleFlow(private val prop: String, private val source: MutableStateFlow<Any?>) {
        val value: Double?
            get() = source.value as? Double

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): Double? = value

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): ReadOnlyProperty<Any?, Double?> =
            ReadOnlyProperty { _, _ -> value }

        companion object {
            @JvmStatic
            fun update(prop: String, newValue: Double) {
                val flow: MutableStateFlow<Any?>?
                synchronized(propertyFlows) { flow = propertyFlows[prop] }
                flow?.value = newValue
            }
        }
    }

    // propBoolean: Boolean?
    object propBoolean {
        private val flows = mutableMapOf<String, BooleanFlow>()

        @JvmName("getProperty")
        operator fun get(property: String): BooleanFlow {
            synchronized(flows) {
                return flows.getOrPut(property) {
                    BooleanFlow(property, getOrCreateFlow(property, MpvFormat.MPV_FORMAT_FLAG))
                }
            }
        }

        fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): BooleanFlow = get(prop.name)
        fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): BooleanFlow = get(prop.name)
    }

    class BooleanFlow(private val prop: String, private val source: MutableStateFlow<Any?>) {
        val value: Boolean?
            get() = source.value as? Boolean

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): Boolean? = value

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): ReadOnlyProperty<Any?, Boolean?> =
            ReadOnlyProperty { _, _ -> value }

        companion object {
            @JvmStatic
            fun update(prop: String, newValue: Boolean) {
                val flow: MutableStateFlow<Any?>?
                synchronized(propertyFlows) { flow = propertyFlows[prop] }
                flow?.value = newValue
            }
        }
    }

    // propString: String?
    object propString {
        private val flows = mutableMapOf<String, StringFlow>()

        @JvmName("getProperty")
        operator fun get(property: String): StringFlow {
            synchronized(flows) {
                return flows.getOrPut(property) {
                    StringFlow(property, getOrCreateFlow(property, MpvFormat.MPV_FORMAT_STRING))
                }
            }
        }

        fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): StringFlow = get(prop.name)
        fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): StringFlow = get(prop.name)
    }

    class StringFlow(private val prop: String, private val source: MutableStateFlow<Any?>) {
        val value: String?
            get() = source.value as? String

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): String? = value

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): ReadOnlyProperty<Any?, String?> =
            ReadOnlyProperty { _, _ -> value }

        companion object {
            @JvmStatic
            fun update(prop: String, newValue: String) {
                val flow: MutableStateFlow<Any?>?
                synchronized(propertyFlows) { flow = propertyFlows[prop] }
                flow?.value = newValue
            }
        }
    }

    // propFloat: Float? 鈥?observe as Double, convert on read
    object propFloat {
        private val flows = mutableMapOf<String, FloatFlow>()

        @JvmName("getProperty")
        operator fun get(property: String): FloatFlow {
            synchronized(flows) {
                return flows.getOrPut(property) {
                    FloatFlow(property, getOrCreateFlow(property, MpvFormat.MPV_FORMAT_DOUBLE))
                }
            }
        }

        fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): FloatFlow = get(prop.name)
        fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): FloatFlow = get(prop.name)
    }

    class FloatFlow(private val prop: String, private val source: MutableStateFlow<Any?>) {
        val value: Float?
            get() = (source.value as? Number)?.toFloat()

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): Float? = value

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): ReadOnlyProperty<Any?, Float?> =
            ReadOnlyProperty { _, _ -> value }

        companion object {
            @JvmStatic
            fun update(prop: String, newValue: Double) {
                val flow: MutableStateFlow<Any?>?
                synchronized(propertyFlows) { flow = propertyFlows[prop] }
                flow?.value = newValue.toFloat()
            }
        }
    }

    // propNode: MPVNode? 鈥?observe as MPV_FORMAT_NODE (native must support it)
    object propNode {
        private val flows = mutableMapOf<String, NodeFlow>()

        @JvmName("getProperty")
        operator fun get(property: String): NodeFlow {
            synchronized(flows) {
                return flows.getOrPut(property) {
                    NodeFlow(property, getOrCreateFlow(property, MpvFormat.MPV_FORMAT_NODE))
                }
            }
        }

        fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): NodeFlow = get(prop.name)
        fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): NodeFlow = get(prop.name)
    }

    class NodeFlow(private val prop: String, private val source: MutableStateFlow<Any?>) {
        val value: MPVNode?
            get() = source.value as? MPVNode

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun getValue(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): MPVNode? = value

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        operator fun provideDelegate(thisRef: Any?, prop: kotlin.reflect.KProperty<*>): ReadOnlyProperty<Any?, MPVNode?> =
            ReadOnlyProperty { _, _ -> value }

        companion object {
            @JvmStatic
            fun update(prop: String, newValue: MPVNode) {
                val flow: MutableStateFlow<Any?>?
                synchronized(propertyFlows) { flow = propertyFlows[prop] }
                flow?.value = newValue
            }
        }
    }

    // 鈹€鈹€ Native 鈫?Kotlin flow dispatch 鈹€鈹€

    // Called from native JNI (mpv fires these):
    // eventProperty(String, Long)  鈫?mpv INT64 鈫?convert to Int for IntFlow
    // eventProperty(String, Double) 鈫?mpv DOUBLE
    // eventProperty(String, Boolean) 鈫?mpv FLAG
    // eventProperty(String, String) 鈫?mpv STRING
    // eventProperty(String)         鈫?mpv FORMAT_NONE / property gone

    private fun dispatchProperty(property: String, value: Any?) {
        val flow: MutableStateFlow<Any?>?
        synchronized(propertyFlows) { flow = propertyFlows[property] }
        flow?.value = value

        // Also update the typed flows
        synchronized(propertyFlows) { propertyFlows[property] }
        // IntFlow: cast Long鈫扞nt
        val rawFlow: MutableStateFlow<Any?>?
        synchronized(propertyFlows) { rawFlow = propertyFlows[property] }
        if (rawFlow != null) {
            IntFlow.update(property, (value as? Long) ?: (value as? Int)?.toLong() ?: return)
            DoubleFlow.update(property, value as? Double ?: return)
            BooleanFlow.update(property, value as? Boolean ?: return)
            StringFlow.update(property, value as? String ?: return)
            FloatFlow.update(property, (value as? Number)?.toFloat()?.toDouble() ?: return)
            NodeFlow.update(property, value as? MPVNode ?: return)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Long) {
        dispatchProperty(property, value)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value.toInt())
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) {
        dispatchProperty(property, value)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) {
        dispatchProperty(property, value)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) {
        dispatchProperty(property, value)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property, value)
        }
    }

    @JvmStatic
    fun eventProperty(property: String) {
        dispatchProperty(property, null)
        synchronized(observers) {
            for (o in observers)
                o.eventProperty(property)
        }
    }

    // 鈹€鈹€ Observer management 鈹€鈹€

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
    fun event(eventId: Int) {
        synchronized(observers) {
            for (o in observers)
                o.event(eventId)
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

    // 鈹€鈹€ Log observer management 鈹€鈹€

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

    // 鈹€鈹€ Interfaces 鈹€鈹€

    /**
     * Event observer interface.
     * FongMi native delivers: eventProperty(String), eventProperty(String, Long/Bool/Double/String)
     * qznet UI additionally uses: eventProperty(String, MPVNode), event(Int, MPVNode)
     */
    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Int)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun eventProperty(property: String, value: Double)
        // qznet UI callback for MPVNode 鈥?native won't fire it unless mpv sends MPV_FORMAT_NODE
        fun eventProperty(property: String, value: MPVNode)
        fun event(eventId: Int)
        // qznet UI callback for event data 鈥?native won't fire it unless mpv sends data
        fun event(eventId: Int, data: MPVNode)
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
        const val MPV_ERROR: Int = 20
        const val MPV_LOG_LEVEL_WARN: Int = 30
        const val MPV_LOG_LEVEL_INFO: Int = 40
        const val MPV_LOG_LEVEL_V: Int = 50
        const val MPV_LOG_LEVEL_DEBUG: Int = 60
        const val MPV_LOG_LEVEL_TRACE: Int = 70
    }
}
