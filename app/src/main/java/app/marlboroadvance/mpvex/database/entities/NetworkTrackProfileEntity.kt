package app.marlboroadvance.mpvex.database.entities

import androidx.room.Entity

@Entity(primaryKeys = ["connectionId", "directoryPath"])
data class NetworkTrackProfileEntity(
  val connectionId: Long,
  val directoryPath: String,
  val audioTrackNumber: Int? = null,
  val audioLang: String? = null,
  val audioTitle: String? = null,
  val subtitleMode: String = "unset",
  val subtitleTrackNumber: Int? = null,
  val subtitleLang: String? = null,
  val subtitleTitle: String? = null,
  val subtitleIsExternal: Boolean? = null,
  val updatedAt: Long = System.currentTimeMillis(),
)
