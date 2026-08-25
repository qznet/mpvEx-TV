package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode

class PlayerObserver(
  private val activity: PlayerActivity,
) : MPVLib.EventObserver {
  override fun eventProperty(property: String) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property) }
  }

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property, value) }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property, value) }
  }

  @Suppress("EmptyFunctionBlock")
  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.onObserverEvent(property, value) }
  }

  override fun event(eventId: Int, data: MPVNode) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.event(eventId) }
  }

  // mpv dispatches data-less events (MPV_EVENT_FILE_LOADED, MPV_EVENT_PLAYBACK_RESTART,
  // MPV_EVENT_START_FILE, MPV_EVENT_END_FILE, …) via the single-arg MPVLib.event(eventId)
  // path. Without this override they hit the default empty implementation and never reach
  // PlayerActivity.event(), leaving handleFileLoaded() (resume seek + autoplay unpause) dead.
  override fun event(eventId: Int) {
    if (activity.player.isExiting) return
    activity.runOnUiThread { activity.event(eventId) }
  }
}
