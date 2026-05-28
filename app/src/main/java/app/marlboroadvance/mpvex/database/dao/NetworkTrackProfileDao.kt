package app.marlboroadvance.mpvex.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.marlboroadvance.mpvex.database.entities.NetworkTrackProfileEntity

@Dao
interface NetworkTrackProfileDao {
  @Upsert
  suspend fun upsert(profile: NetworkTrackProfileEntity)

  @Query(
    """
    SELECT * FROM NetworkTrackProfileEntity
    WHERE connectionId = :connectionId AND directoryPath = :directoryPath
    LIMIT 1
    """,
  )
  suspend fun get(connectionId: Long, directoryPath: String): NetworkTrackProfileEntity?
}
