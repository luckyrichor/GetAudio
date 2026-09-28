package com.getaudio.app.data.repo

import android.content.Context
import com.getaudio.app.data.local.AppDatabase
import com.getaudio.app.data.local.SyncStateEntity
import com.getaudio.app.data.local.TrackEntity
import com.getaudio.app.data.remote.TrackDto
import com.getaudio.app.data.localextract.DirectExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import okhttp3.Request
import java.io.File

class TrackRepository(private val context: Context) {

    companion object { private val fileMutex = Mutex() }

    private val db = AppDatabase.get(context)
    private val trackDao = db.trackDao()
    private val syncDao = db.syncStateDao()
    private val settings = Settings(context)

    private val audioDir: File
        get() = File(context.filesDir, "audio").apply { mkdirs() }

    // ---------- 读 ----------

    fun filtered(q: String, platform: String?, favoritesOnly: Boolean) = trackDao.filtered(q, platform, favoritesOnly)

    fun observeAll(): Flow<List<TrackEntity>> = trackDao.observeAll()
    fun search(q: String): Flow<List<TrackEntity>> = trackDao.search(q)
    fun byPlatform(p: String): Flow<List<TrackEntity>> = trackDao.byPlatform(p)
    fun favorites(): Flow<List<TrackEntity>> = trackDao.favorites()

    suspend fun setFavorite(id: Long, fav: Boolean) = trackDao.setFavorite(id, fav)

    // ---------- 提取 ----------

    /** 分享文本由手机直接提取，保存到本地库，不依赖 GetAudio 服务器。 */
    suspend fun extract(text: String): Result<TrackEntity> = runCatching {
        withContext(Dispatchers.IO) {
            val entity = DirectExtractor(context).extract(text)
            fileMutex.withLock { trackDao.upsertLocal(entity) }
            entity
        }
    }

    // ---------- 同步 ----------

    /** 增量拉取，返回本次新增/更新的条数。 */
    suspend fun sync(): Int = withContext(Dispatchers.IO) { fileMutex.withLock {
        val serverUrl = settings.serverUrlNow()
        if (serverUrl.isBlank()) return@withLock 0
        val api = ApiProvider.api(serverUrl)
        var cursor = syncDao.cursor()
        var total = 0

        // has_more 时继续拉，直到追平
        while (true) {
            val page = api.sync(cursor = cursor)
            if (page.items.isEmpty()) break

            val (deleted, alive) = page.items.partition { it.deleted }
            trackDao.applySync(alive.map { it.toEntity() }, deleted.map { it.id })
            // 服务器删了就把本地文件也删掉，不然白占空间
            deleted.forEach { localFile(it.id).delete() }

            total += page.items.size
            cursor = page.nextCursor
            syncDao.put(SyncStateEntity(cursor = cursor, lastSyncAt = System.currentTimeMillis()))
            if (!page.hasMore) break
        }
        total
    } }

    // ---------- 下载 ----------

    fun localFile(id: Long): File = File(audioDir, "$id.audio")

    /** 下载单条音频到本地。已经有了就直接返回。 */
    suspend fun download(track: TrackEntity): Result<File> = runCatching {
        withContext(Dispatchers.IO) { fileMutex.withLock {
            val current = trackDao.byId(track.id) ?: error("音频已被移除，请重新同步")
            val dest = localFile(track.id)
            if (current.localPath == dest.absolutePath && dest.exists() && dest.length() > 0) {
                if (track.localPath == null) trackDao.setLocalPath(track.id, dest.absolutePath)
                return@withLock dest
            }

            val base = settings.serverUrlNow().trimEnd('/')
            val url = base + track.downloadUrl
            val request = Request.Builder().url(url).build()

            // 先写临时文件，完整了再改名 —— 中断的下载不会留下半个能播的文件
            val tmp = File(dest.absolutePath + ".part")
            ApiProvider.okHttp().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
                val body = resp.body ?: error("下载失败：响应为空")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if ((current.audioBytes != null && tmp.length() != current.audioBytes) ||
                (current.audioSha256 != null && actualHash != current.audioSha256)) {
                tmp.delete()
                error("音频内容已变更或下载不完整，请同步后重试")
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                error("保存失败")
            }
            trackDao.setLocalPath(track.id, dest.absolutePath)
            dest
        } }
    }

    /** 批量补齐未下载的音频，返回成功条数。给 WorkManager 用。 */
    suspend fun downloadPending(limit: Int = 20): Int {
        if (settings.serverUrlNow().isBlank()) return 0
        var ok = 0
        trackDao.notDownloaded(limit).forEach { track ->
            if (download(track).isSuccess) ok++
        }
        return ok
    }
}

private fun TrackDto.toEntity() = TrackEntity(
    id = id,
    platform = platform,
    videoId = videoId,
    title = title,
    uploader = uploader,
    durationSec = durationSec,
    audioBytes = audioBytes,
    audioFormat = audioFormat,
    sourceUrl = sourceUrl,
    downloadUrl = downloadUrl,
    updatedSeq = updatedSeq,
    audioSha256 = audioSha256,
    categories = categories.joinToString(" · "),
    hasVocal = hasVocal,
    classifyStage = classifyStage,
)
