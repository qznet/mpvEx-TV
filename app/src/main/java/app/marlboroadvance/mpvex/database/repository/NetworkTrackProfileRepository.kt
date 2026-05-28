package app.marlboroadvance.mpvex.database.repository

import app.marlboroadvance.mpvex.database.dao.NetworkTrackProfileDao
import app.marlboroadvance.mpvex.database.entities.NetworkTrackProfileEntity

class NetworkTrackProfileRepository(
  private val dao: NetworkTrackProfileDao,
) {
  suspend fun upsert(profile: NetworkTrackProfileEntity) = dao.upsert(profile)

  suspend fun get(connectionId: Long, directoryPath: String): NetworkTrackProfileEntity? =
    dao.get(connectionId, directoryPath)
}
