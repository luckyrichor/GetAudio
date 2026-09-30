package com.getaudio.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * 本地音频库。
 *
 * 服务器是单向权威：除了 [localPath] 和 [favorite] 这两个纯本地状态，
 * 其余字段每次同步都会被服务器覆盖。
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: Long,
    val platform: String,
    val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSec: Double?,
    val audioBytes: Long?,
    val audioFormat: String,
    val sourceUrl: String,
    val downloadUrl: String,
    val updatedSeq: Long,
    val audioSha256: String? = null,
    val categories: String = "",
    val hasVocal: Boolean? = null,
    val classifyStage: String? = null,

    /** 本地文件路径；null 表示还没下载 */
    val localPath: String? = null,
    val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "1") val localCategoryId: Long = 1,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    /** null=空类别，CONTAINER=子类别，LEAF=音频。 */
    val kind: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val lastAccessedAt: Long = 0,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "trackId"])
data class PlaylistItemEntity(
    val playlistId: Long,
    val trackId: Long,
    val addedAt: Long = System.currentTimeMillis(),
    val orderIndex: Int,
)

/** 本机删除的云端曲目，避免下次同步又出现。 */
@Entity(tableName = "deleted_tracks")
data class DeletedTrackEntity(@PrimaryKey val id: Long)

/** 同步游标等单例配置。只有一行，id 固定为 0。 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val cursor: Long = 0,
    val lastSyncAt: Long = 0,
)
