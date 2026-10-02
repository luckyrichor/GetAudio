package com.getaudio.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.getaudio.app.player.PlayerHolder
import com.getaudio.app.data.local.AppDatabase
import com.getaudio.app.data.local.SyncStateEntity
import com.getaudio.app.data.local.TrackEntity
import com.getaudio.app.data.remote.TrackDto
import com.getaudio.app.data.remote.ExtractRequest
import com.getaudio.app.data.localextract.DirectExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    fun allWithTimeRange(q: String, startMs: Long?, endMs: Long?) =
        trackDao.allWithTimeRange(q, startMs, endMs)

    fun inCategory(categoryId: Long) = trackDao.inCategory(categoryId)

    suspend fun allNow(): List<TrackEntity> = trackDao.allNow()

    suspend fun byId(id: Long): TrackEntity? = trackDao.byId(id)

    suspend fun rename(id: Long, title: String) {
        require(title.isNotBlank()) { "音频名称不能为空" }
        trackDao.rename(id, title.trim(), System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            com.getaudio.app.player.PlayerHolder.rename(context, id, title.trim())
        }
    }

    suspend fun remove(id: Long) {
        val track = trackDao.byId(id) ?: return
        fileMutex.withLock {
            db.withTransaction {
                if (id > 0) trackDao.suppress(id)
                db.playlistDao().removeTrack(id)
                trackDao.delete(id)
            }
        }
        withContext(NonCancellable + Dispatchers.Main) { PlayerHolder.removeTracks(context, setOf(id)) }
        track.localPath?.let { File(it).delete() }
    }

    suspend fun setFavorite(id: Long, fav: Boolean) = trackDao.setFavorite(id, fav)

    // ---------- 提取 ----------

    /** 分享文本由手机直接提取，保存到本地库，不依赖 GetAudio 服务器。 */
    suspend fun extract(text: String, onDiagnostic: (String) -> Unit = {}): Result<TrackEntity> = runCatching {
        withContext(Dispatchers.IO) {
            val entity = DirectExtractor(context) { line ->
                withContext(Dispatchers.Main) { onDiagnostic(line) }
            }.extract(text)
            fileMutex.withLock { trackDao.upsertLocal(entity) }
            withContext(Dispatchers.Main) { onDiagnostic("已加入本地音频库") }
            entity
        }
    }

    /** Explicit fallback: ask the configured GetAudio server to extract, then cache its audio on this phone. */
    suspend fun extractViaServer(
        text: String, onDiagnostic: (String) -> Unit = {},
    ): Result<TrackEntity> = runCatching {
        val base = settings.serverUrlNow()
        require(base.isNotBlank()) { "请先到「我的 → 服务器设置」填写服务器地址" }
        onDiagnostic("服务器提取：已提交，等待处理")
        val response = ApiProvider.api(base).extract(
            ExtractRequest(text = text, userId = settings.userId()),
        )
        val entity = response.track.toEntity()
        onDiagnostic("服务器提取：成功，开始下载音频到手机")
        fileMutex.withLock { trackDao.restoreExtracted(entity) }
        download(entity).getOrThrow()
        onDiagnostic("服务器音频：下载完成，已加入本地音频库")
        trackDao.byId(entity.id) ?: entity
    }

    // ---------- 同步 ----------

    /** 增量拉取，返回本次新增/更新的条数。 */
    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        val deletedIds = mutableSetOf<Long>()
        try {
            fileMutex.withLock {
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
                    deletedIds.addAll(deleted.map { it.id }.filter { trackDao.byId(it) == null })

                    total += page.items.size
                    cursor = page.nextCursor
                    syncDao.put(SyncStateEntity(cursor = cursor, lastSyncAt = System.currentTimeMillis()))
                    if (!page.hasMore) break
                }
                total
            }
        } finally {
            // Release the download mutex before waiting for a queue operation.
            if (deletedIds.isNotEmpty()) {
                withContext(NonCancellable + Dispatchers.Main) { PlayerHolder.removeTracks(context, deletedIds) }
                deletedIds.forEach { localFile(it).delete() }
            }
        }
    }

    // ---------- 下载 ----------

    fun localFile(id: Long): File = File(audioDir, "$id.audio")

    /** 下载单条音频到本地。已经有了就直接返回。 */
    suspend fun download(track: TrackEntity): Result<File> = runCatching {
        withContext(Dispatchers.IO) { fileMutex.withLock {
            val current = trackDao.byId(track.id) ?: error("音频已被移除，请重新同步")
            val dest = current.localPath?.let(::File) ?: localFile(track.id)
            if (current.localPath == dest.absolutePath && dest.exists() && dest.length() > 0) {
                if (track.localPath == null) trackDao.setLocalPath(track.id, dest.absolutePath)
                return@withLock dest
            }
            if (current.locallyEdited) error("本地剪辑文件已丢失，请恢复原版或重新剪辑")
            if (track.id < 0) error("本机音频文件已丢失，请重新提取")

            val base = settings.serverUrlNow().trimEnd('/')
            val url = base + current.downloadUrl
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

    /** A stable snapshot prevents background server downloads from changing an open edit. */
    suspend fun editSource(id: Long): File = withContext(Dispatchers.IO) {
        val track = trackDao.byId(id) ?: error("音频已被删除")
        download(track).getOrThrow()
        fileMutex.withLock {
            val current = trackDao.byId(id) ?: error("音频已被删除")
            val source = current.localPath?.let(::File)?.takeIf { it.exists() } ?: error("音频已更新，请重新打开剪辑")
            val snapshot = File.createTempFile("edit-source-", ".audio", context.cacheDir)
            try { source.copyTo(snapshot, overwrite = true); snapshot }
            catch (e: Exception) { snapshot.delete(); throw e }
        }
    }

    /** Commit a complete export under a unique path before touching the library record. */
    suspend fun saveEdit(id: Long, output: File, title: String, overwrite: Boolean): TrackEntity = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "名称不能为空" }
        withContext(NonCancellable) {
            var previousPath: String? = null
            val saved = fileMutex.withLock {
                val original = trackDao.byId(id) ?: error("音频已被删除")
                val newId = if (overwrite) id else generateSequence { -java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE) }
                    .first { trackDao.byId(it) == null }
                val target = File(audioDir, "$newId-edit-${java.util.UUID.randomUUID()}.m4a")
                val duration = com.getaudio.app.audioedit.AudioEditor.duration(output)
                require(duration > 0 && output.length() > 0) { "剪辑结果无效" }
                val digest = MessageDigest.getInstance("SHA-256")
                output.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                    val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                try { output.copyTo(target) } catch (e: Exception) { target.delete(); throw e }
                if (overwrite) previousPath = original.localPath
                val now = System.currentTimeMillis()
                val record = original.copy(id = newId, title = title.trim(), localTitleOverride = title.trim(),
                    videoId = if (overwrite) original.videoId else "edit-${java.util.UUID.randomUUID()}",
                    localPath = target.absolutePath, locallyEdited = true,
                    sourceTrackId = original.sourceTrackId ?: original.id,
                    durationSec = duration / 1000.0, audioBytes = target.length(), audioFormat = "m4a", audioSha256 = hash,
                    downloadUrl = if (overwrite) original.downloadUrl else "",
                    updatedAtMs = now, addedAtMs = if (overwrite) original.addedAtMs else now,
                    updatedSeq = if (overwrite) original.updatedSeq else now,
                    favorite = if (overwrite) original.favorite else false)
                try { trackDao.upsertAll(listOf(record)) } catch (e: Exception) { target.delete(); throw e }
                record
            }
            withContext(Dispatchers.Main) { PlayerHolder.edited(context, saved) }
            previousPath?.takeIf { it != saved.localPath }?.let { File(it).delete() }
            saved
        }
    }

    /** Explicitly restore the latest server file; never download over an edited file implicitly. */
    suspend fun restoreOriginal(id: Long): TrackEntity = withContext(Dispatchers.IO) {
        require(id > 0) { "此音频没有可恢复的服务器原版" }
        val api = ApiProvider.api(settings.serverUrlNow())
        var cursor = 0L
        var original: TrackEntity? = null
        while (original == null) {
            val page = api.sync(cursor)
            original = page.items.firstOrNull { it.id == id && !it.deleted }?.toEntity()
            if (!page.hasMore || page.items.isEmpty()) break
            check(page.nextCursor > cursor) { "同步游标无效" }
            cursor = page.nextCursor
        }
        val fresh = original ?: error("服务器原版已不存在")
        val staged = File.createTempFile("restore-", ".audio", audioDir)
        try {
            ApiProvider.okHttp().newCall(Request.Builder().url(settings.serverUrlNow().trimEnd('/') + fresh.downloadUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "恢复失败：HTTP ${response.code}" }
                val body = response.body ?: error("响应为空")
                staged.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            staged.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(staged.length() > 0 && (fresh.audioBytes == null || staged.length() == fresh.audioBytes) &&
                (fresh.audioSha256 == null || hash == fresh.audioSha256)) { "原版下载不完整，请重试" }
            withContext(NonCancellable) {
                var previousPath: String? = null
                val saved = fileMutex.withLock {
                    val current = trackDao.byId(id) ?: error("音频已被删除")
                    previousPath = current.localPath
                    val record = fresh.copy(localPath = staged.absolutePath, favorite = current.favorite,
                        localCategoryId = current.localCategoryId, addedAtMs = current.addedAtMs,
                        title = current.localTitleOverride ?: fresh.title, localTitleOverride = current.localTitleOverride)
                    trackDao.upsertAll(listOf(record)); record
                }
                withContext(Dispatchers.Main) { PlayerHolder.edited(context, saved) }
                previousPath?.takeIf { it != saved.localPath }?.let { File(it).delete() }
                saved
            }
        } catch (e: Exception) { if (trackDao.byId(id)?.localPath != staged.absolutePath) staged.delete(); throw e }
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
    updatedAtMs = updatedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
    audioSha256 = audioSha256,
    categories = categories.joinToString(" · "),
    hasVocal = hasVocal,
    classifyStage = classifyStage,
)
