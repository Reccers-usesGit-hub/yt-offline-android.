package app.ytod.offline.dl

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import app.ytod.offline.App
import app.ytod.offline.Fmt
import app.ytod.offline.data.Comment
import app.ytod.offline.data.PlaylistRef
import app.ytod.offline.data.Store
import app.ytod.offline.data.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class JobState { WAITING, RUNNING }

data class DlJob(
    val id: String,
    val title: String,
    val channel: String,
    val thumbUrl: String?,
    val duration: Long,
    val playlist: PlaylistRef?,
    val progress: Float = 0f,
    val stage: String = "Waiting…",
    val state: JobState = JobState.WAITING,
)

object DownloadManager {
    private const val TAG = "OfflineDownloads"
    private const val PARALLEL_VIDEOS = 2
    private const val PIECE = 8L * 1024 * 1024
    private const val PIECES_AT_ONCE = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(PARALLEL_VIDEOS)
    private val tasks = ConcurrentHashMap<String, Job>()
    private val lastTick = ConcurrentHashMap<String, Long>()
    private val main = Handler(Looper.getMainLooper())

    private val _jobs = MutableStateFlow<List<DlJob>>(emptyList())
    val jobs: StateFlow<List<DlJob>> = _jobs

    private class Batch(val title: String, val total: Int) {
        var ok = 0
        var failed = 0
        var cancelled = 0
    }
    private val batches = ConcurrentHashMap<String, Batch>()

    fun toast(msg: String) {
        main.post { Toast.makeText(App.instance, msg, Toast.LENGTH_LONG).show() }
    }

    fun isQueued(id: String) = _jobs.value.any { it.id == id }

    /** Returns true when a new download was started. */
    @Synchronized
    fun enqueue(
        ctx: Context,
        id: String,
        title: String = "",
        channel: String = "",
        thumb: String? = null,
        duration: Long = 0,
        playlist: PlaylistRef? = null,
        maxHeight: Int = Store.quality,
    ): Boolean {
        if (Store.has(id)) {
            if (playlist != null) Store.addToPlaylist(id, playlist)
            return false
        }
        if (isQueued(id)) return false
        _jobs.update { it + DlJob(id, title, channel, thumb, duration, playlist) }
        tasks[id] = scope.launch {
            var outcome: Boolean? = null
            try {
                slots.withPermit { outcome = run(id, maxHeight) }
            } catch (_: CancellationException) {
                outcome = null
            } finally {
                val job = _jobs.value.firstOrNull { it.id == id }
                _jobs.update { list -> list.filter { it.id != id } }
                tasks.remove(id)
                lastTick.remove(id)
                job?.playlist?.let { batchDone(it.id, outcome) }
            }
        }
        DownloadService.start(ctx)
        return true
    }

    /** Download every video of a playlist. Videos you already have just join the playlist. */
    fun enqueuePlaylist(ctx: Context, pl: Yt.Pl, maxHeight: Int = Store.quality): Pair<Int, Int> {
        var added = 0
        var had = 0
        val toStart = mutableListOf<Pair<Yt.PlEntry, PlaylistRef>>()
        pl.entries.forEachIndexed { i, e ->
            val ref = PlaylistRef(pl.id, pl.title, i)
            when {
                Store.has(e.id) -> { Store.addToPlaylist(e.id, ref); had++ }
                isQueued(e.id) -> Unit
                else -> toStart += e to ref
            }
        }
        if (toStart.isNotEmpty()) batches[pl.id] = Batch(pl.title, toStart.size)
        for ((e, ref) in toStart) {
            if (enqueue(ctx, e.id, e.title, e.channel, e.thumb, e.duration, ref, maxHeight)) added++
        }
        return added to had
    }

    fun cancel(id: String) {
        tasks[id]?.cancel()
    }

    fun cancelAll() {
        for (j in tasks.values.toList()) j.cancel()
    }

    private fun batchDone(playlistId: String, outcome: Boolean?) {
        val b = batches[playlistId] ?: return
        synchronized(b) {
            when (outcome) {
                true -> b.ok++
                false -> b.failed++
                null -> b.cancelled++
            }
            if (b.ok + b.failed + b.cancelled < b.total) return
        }
        batches.remove(playlistId)
        if (b.ok == 0 && b.failed == 0) return
        toast("\"${b.title}\": ${b.ok} of ${b.total} downloaded" + if (b.failed > 0) " (${b.failed} failed)" else "")
    }

    private fun update(id: String, change: (DlJob) -> DlJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    // ------------------------------------------------------------------ one video

    private suspend fun run(id: String, maxHeight: Int): Boolean {
        val dir = Store.videoDir(id)
        val job0 = _jobs.value.firstOrNull { it.id == id }
        try {
            update(id) { it.copy(state = JobState.RUNNING, stage = "Getting ready…") }
            val info = Yt.streamInfo(id)
            val pick = Yt.pickStreams(info, maxHeight) ?: throw IOException("no downloadable stream")
            update(id) {
                it.copy(
                    title = info.name ?: it.title,
                    channel = info.uploaderName ?: it.channel,
                    thumbUrl = it.thumbUrl ?: Yt.bestImage(info.thumbnails),
                    duration = if (info.duration > 0) info.duration else it.duration,
                )
            }
            dir.deleteRecursively()
            dir.mkdirs()

            Yt.bestImage(info.thumbnails)?.let { saveUrl(it, File(dir, "thumb.jpg")) }
            Yt.bestImage(info.uploaderAvatars, 176)?.let { saveUrl(it, File(dir, "avatar.jpg")) }

            update(id) { it.copy(stage = "Saving comments…") }
            val comments = try {
                Yt.comments(id, 40).mapIndexed { i, c ->
                    val avatar = Yt.bestImage(c.uploaderAvatars, 88)?.let { url ->
                        val name = "c$i.jpg"
                        if (saveUrl(url, File(dir, name))) name else null
                    }
                    Comment(
                        author = c.uploaderName ?: "",
                        text = Yt.plainText(c.commentText),
                        time = c.textualUploadDate ?: "",
                        likes = if (c.likeCount > 0) Fmt.compact(c.likeCount.toLong()) else "",
                        avatar = avatar,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "comments for $id", e)
                emptyList()
            }
            coroutineContext.ensureActive()

            val video = pick.video
            val audio = pick.audio
            val vName = "video." + (video.format?.suffix ?: "mp4")
            val aName = audio?.let { "audio." + (it.format?.suffix ?: "m4a") }
            val vUrl = video.content
            val aUrl = audio?.content
            val vLen = sizeOf(vUrl, video.itagItem?.contentLength)
            val aLen = if (aUrl != null) sizeOf(aUrl, audio?.itagItem?.contentLength) else 0L
            val total = vLen + aLen
            val got = AtomicLong(0)
            update(id) { it.copy(stage = "", progress = 0f) }
            val onBytes: (Long) -> Unit = { n ->
                val g = got.addAndGet(n)
                val now = System.currentTimeMillis()
                if (now - (lastTick[id] ?: 0L) > 300) {
                    lastTick[id] = now
                    update(id) { it.copy(progress = (g.toFloat() / total).coerceIn(0f, 1f)) }
                }
            }
            coroutineScope {
                val parts = mutableListOf(launch { fetch(vUrl, File(dir, vName), vLen, onBytes) })
                if (aUrl != null && aName != null) parts += launch { fetch(aUrl, File(dir, aName), aLen, onBytes) }
                parts.joinAll()
            }

            update(id) { it.copy(stage = "Finishing…", progress = 1f) }
            Store.put(
                Video(
                    id = id,
                    title = info.name ?: job0?.title ?: "",
                    channel = info.uploaderName ?: "",
                    subs = info.uploaderSubscriberCount.takeIf { it >= 0 }?.let { Fmt.compact(it) + " subscribers" } ?: "",
                    duration = info.duration,
                    views = info.viewCount,
                    published = info.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() ?: 0L,
                    publishedText = info.textualUploadDate ?: "",
                    description = Yt.plainText(info.description),
                    likes = info.likeCount.takeIf { it >= 0 }?.let { Fmt.compact(it) } ?: "",
                    commentCount = "",
                    comments = comments,
                    height = pick.height,
                    size = total,
                    savedAt = System.currentTimeMillis(),
                    playlists = listOfNotNull(job0?.playlist),
                    videoFile = vName,
                    audioFile = aName,
                    rating = null,
                )
            )
            if (job0?.playlist == null) toast("Downloaded: ${info.name ?: "video"}")
            return true
        } catch (e: CancellationException) {
            dir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download $id failed", e)
            dir.deleteRecursively()
            if (job0?.playlist == null) toast("Download failed: ${e.message ?: e.javaClass.simpleName}")
            return false
        }
    }

    // ------------------------------------------------------------------ network

    private val http get() = NpDownloader.http

    private fun userAgentFor(url: String): String {
        val client = Regex("""[?&]c=([A-Za-z_]+)""").find(url)?.groupValues?.get(1)?.uppercase() ?: ""
        return when {
            client.startsWith("ANDROID") -> "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip"
            client.startsWith("IOS") -> "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
            else -> NpDownloader.USER_AGENT
        }
    }

    private fun sizeOf(url: String, known: Long?): Long {
        if (known != null && known > 0) return known
        Regex("""[?&]clen=(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { if (it > 0) return it }
        val req = Request.Builder().url(url).head().header("User-Agent", userAgentFor(url)).build()
        http.newCall(req).execute().use { r ->
            val len = r.header("Content-Length")?.toLongOrNull() ?: -1L
            if (r.isSuccessful && len > 0) return len
        }
        throw IOException("couldn't find the file size")
    }

    private fun saveUrl(url: String, file: File): Boolean = try {
        val req = Request.Builder().url(url).header("User-Agent", NpDownloader.USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            val body = r.body
            if (!r.isSuccessful || body == null) false
            else { file.outputStream().use { out -> body.byteStream().copyTo(out) }; true }
        }
    } catch (_: Exception) {
        false
    }

    /** Download a whole file in pieces, several at a time, at full speed. */
    private suspend fun fetch(url: String, file: File, total: Long, onBytes: (Long) -> Unit) {
        RandomAccessFile(file, "rw").use { it.setLength(total) }
        val ranges = (0 until total step PIECE).map { start -> start to (minOf(start + PIECE, total) - 1) }
        val next = AtomicInteger(0)
        coroutineScope {
            List(PIECES_AT_ONCE) {
                launch {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= ranges.size) break
                        val (a, b) = ranges[i]
                        var attempt = 0
                        while (true) {
                            ensureActive()
                            try {
                                fetchRange(url, file, a, b, onBytes)
                                break
                            } catch (e: IOException) {
                                if (++attempt >= 4) throw e
                                delay(1000L * attempt)
                            }
                        }
                    }
                }
            }.joinAll()
        }
    }

    private fun fetchRange(url: String, file: File, a: Long, b: Long, onBytes: (Long) -> Unit) {
        val sep = if (url.contains('?')) '&' else '?'
        val req = Request.Builder().url("$url${sep}range=$a-$b").header("User-Agent", userAgentFor(url)).build()
        var written = 0L
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body ?: throw IOException("empty response")
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(a)
                    val buf = ByteArray(64 * 1024)
                    val input = body.byteStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (written + n > b - a + 1) throw IOException("server sent too much")
                        raf.write(buf, 0, n)
                        written += n
                        onBytes(n.toLong())
                    }
                }
                if (written != b - a + 1) throw IOException("download was cut short")
            }
        } catch (e: IOException) {
            onBytes(-written)
            throw e
        }
    }
}
