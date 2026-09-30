package com.getaudio.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.getaudio.app.data.local.AppDatabase
import com.getaudio.app.data.local.PlaylistEntity
import com.getaudio.app.data.local.PlaylistItemEntity

class PlaylistRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.playlistDao()
    private val categoryRepo = CategoryRepository(context)

    fun observeAll() = dao.observeAll()
    fun items(id: Long) = dao.items(id)

    suspend fun create(name: String): Long {
        require(name.isNotBlank()) { "列表名称不能为空" }
        return dao.insert(PlaylistEntity(name = name.trim()))
    }

    suspend fun rename(id: Long, name: String) {
        require(name.isNotBlank()) { "列表名称不能为空" }
        require(dao.byId(id) != null) { "播放列表不存在" }
        dao.rename(id, name.trim())
    }

    suspend fun delete(id: Long) = db.withTransaction {
        dao.clear(id)
        dao.delete(id)
    }

    suspend fun addTracks(playlistId: Long, trackIds: List<Long>) = db.withTransaction {
        require(dao.byId(playlistId) != null) { "播放列表不存在" }
        val valid = db.trackDao().allNow().map { it.id }.toSet()
        val start = dao.lastOrder(playlistId) + 1
        dao.add(trackIds.distinct().filter { it in valid }.mapIndexed { index, id ->
            PlaylistItemEntity(playlistId, id, orderIndex = start + index)
        })
    }

    suspend fun addCategory(playlistId: Long, categoryId: Long) =
        addTracks(playlistId, categoryRepo.recursiveTracks(categoryId).map { it.id })
}
