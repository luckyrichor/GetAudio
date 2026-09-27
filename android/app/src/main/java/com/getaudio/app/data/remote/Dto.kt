package com.getaudio.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 服务器返回的一条音频。字段名与 FastAPI 的 TrackOut 一一对应。 */
@Serializable
data class TrackDto(
    val id: Long,
    val platform: String,
    @SerialName("video_id") val videoId: String,
    val title: String,
    val uploader: String? = null,
    @SerialName("duration_sec") val durationSec: Double? = null,
    @SerialName("audio_bytes") val audioBytes: Long? = null,
    @SerialName("audio_format") val audioFormat: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("updated_seq") val updatedSeq: Long,
    val deleted: Boolean,
    val categories: List<String> = emptyList(),
    @SerialName("has_vocal") val hasVocal: Boolean? = null,
    @SerialName("classify_stage") val classifyStage: String? = null,
    @SerialName("download_url") val downloadUrl: String,
)

@Serializable
data class ExtractRequest(
    val text: String,
    @SerialName("user_id") val userId: String? = null,
    val force: Boolean = false,
)

@Serializable
data class ExtractResponse(
    val track: TrackDto,
    val reused: Boolean,
)

@Serializable
data class SyncResponse(
    val items: List<TrackDto>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class LoginStartRequest(
    @SerialName("user_id") val userId: String,
    val platform: String,
)

@Serializable
data class LoginStartResponse(
    @SerialName("session_id") val sessionId: String,
    val platform: String,
    @SerialName("qr_url") val qrUrl: String,
    @SerialName("qr_image_url") val qrImageUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class LoginPollResponse(
    @SerialName("session_id") val sessionId: String,
    val platform: String,
    /** waiting | scanned | confirmed | expired | failed */
    val state: String,
    val message: String,
)

@Serializable
data class CookieImportRequest(
    @SerialName("user_id") val userId: String,
    val platform: String,
    val cookies: String,
)

@Serializable
data class CredentialDto(
    val platform: String,
    val status: String,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
)

/** FastAPI 的错误体统一是 {"detail": "..."}，解出来直接给用户看。 */
@Serializable
data class ApiError(val detail: String? = null)
