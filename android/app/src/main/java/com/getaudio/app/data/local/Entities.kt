package com.getaudio.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

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

    /** 本地文件路径；null 表示还没下载 */
    val localPath: String? = null,
    val favorite: Boolean = false,
)

/** 同步游标等单例配置。只有一行，id 固定为 0。 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val cursor: Long = 0,
    val lastSyncAt: Long = 0,
)
