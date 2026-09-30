package com.getaudio.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' AND (:startMs IS NULL OR updatedAtMs >= :startMs) AND (:endMs IS NULL OR updatedAtMs <= :endMs) ORDER BY updatedAtMs DESC, id DESC")
    fun allWithTimeRange(q: String, startMs: Long?, endMs: Long?): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE localCategoryId = :categoryId ORDER BY updatedAtMs DESC, id DESC")
    fun inCategory(categoryId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun allNow(): List<TrackEntity>

    @Query("UPDATE tracks SET title = :title, updatedAtMs = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE tracks SET localCategoryId = :categoryId, updatedAtMs = :now WHERE id = :id")
    suspend fun move(id: Long, categoryId: Long, now: Long)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("INSERT OR IGNORE INTO deleted_tracks (id) VALUES (:id)")
    suspend fun suppress(id: Long)

    @Query("SELECT id FROM deleted_tracks")
    suspend fun suppressedIds(): List<Long>

    @Query("SELECT * FROM tracks WHERE (:platform IS NULL OR platform = :platform) AND (:favoritesOnly = 0 OR favorite = 1) AND (title LIKE '%' || :q || '%' OR uploader LIKE '%' || :q || '%' OR categories LIKE '%' || :q || '%') ORDER BY updatedSeq DESC")
    fun filtered(q: String, platform: String?, favoritesOnly: Boolean): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY updatedSeq DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR uploader LIKE '%' || :q || '%' ORDER BY updatedSeq DESC")
    fun search(q: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE platform = :platform ORDER BY updatedSeq DESC")
    fun byPlatform(platform: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE favorite = 1 ORDER BY updatedSeq DESC")
    fun favorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE localPath IS NULL ORDER BY updatedSeq DESC LIMIT :limit")
    suspend fun notDownloaded(limit: Int): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE tracks SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: Long, path: String?)

    @Query("UPDATE tracks SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    /**
     * 同步落库。
     *
     * 服务器发来的记录不带本地状态，直接 REPLACE 会把已下载的路径和
     * 收藏标记抹掉 —— 所以先读出本地状态，合并后再写。
     */
    @Transaction
    suspend fun applySync(incoming: List<TrackEntity>, deletedIds: List<Long>) {
        if (deletedIds.isNotEmpty()) deleteByIds(deletedIds)
        if (incoming.isEmpty()) return
        val suppressed = suppressedIds().toSet()
        val merged = incoming.filter { it.id !in suppressed }.map { fresh ->
            val existing = byId(fresh.id)
            if (existing == null) fresh
            else fresh.copy(
                localPath = existing.localPath.takeIf { existing.audioSha256 == fresh.audioSha256 },
                favorite = existing.favorite,
                localCategoryId = existing.localCategoryId,
                updatedAtMs = if (existing.updatedSeq == fresh.updatedSeq && existing.updatedAtMs > 0) existing.updatedAtMs else fresh.updatedAtMs,
            )
        }
        upsertAll(merged)
    }

    @Transaction
    suspend fun upsertLocal(track: TrackEntity) {
        val existing = byId(track.id)
        upsertAll(listOf(track.copy(favorite = existing?.favorite ?: false,
            localCategoryId = existing?.localCategoryId ?: track.localCategoryId,
            updatedAtMs = System.currentTimeMillis())))
    }
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)

    suspend fun cursor(): Long = get()?.cursor ?: 0
}
