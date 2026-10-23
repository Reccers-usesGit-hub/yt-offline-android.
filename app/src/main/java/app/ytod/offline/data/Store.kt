package app.ytod.offline.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PlaylistRef(val id: String, val title: String, val index: Int)

data class Comment(
    val author: String,
    val text: String,
    val time: String,
    val likes: String,
    /** File name inside the video's folder, or null. */
    val avatar: String?,
)

data class Video(
    val id: String,
    val title: String,
    val channel: String,
    val subs: String,
    val duration: Long,
    val views: Long,
    /** Upload time in ms since 1970, or 0 when unknown. */
    val published: Long,
    val publishedText: String,
    val description: String,
    val likes: String,
    val commentCount: String,
    val comments: List<Comment>,
    val height: Int,
    val size: Long,
    val savedAt: Long,
    val playlists: List<PlaylistRef>,
    val videoFile: String,
    val audioFile: String?,
    /** "like", "dislike" or null. */
    val rating: String?,
)

/**
 * Everything lives in the app's private storage: it never shows up in your gallery
 * or Downloads folder, and it's removed if you uninstall the app.
 */
object Store {
    private lateinit var root: File
    private lateinit var prefs: SharedPreferences

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos

    private val _positions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val positions: StateFlow<Map<String, Long>> = _positions

    fun init(ctx: Context) {
        root = File(ctx.filesDir, "offline").apply { mkdirs() }
        File(root, "videos").mkdirs()
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        load()
    }

    fun videoDir(id: String) = File(File(root, "videos"), id)
    fun get(id: String): Video? = _videos.value.firstOrNull { it.id == id }
    fun has(id: String) = get(id) != null

    var quality: Int
        get() = prefs.getInt("quality", 1080)
        set(v) { prefs.edit().putInt("quality", v).apply() }

    var listFilter: String
        get() = prefs.getString("filter", "all") ?: "all"
        set(v) { prefs.edit().putString("filter", v).apply() }

    @Synchronized
    fun put(v: Video) {
        _videos.value = _videos.value.filter { it.id != v.id } + v
        saveVideos()
    }

    @Synchronized
    fun update(id: String, change: (Video) -> Video) {
        _videos.value = _videos.value.map { if (it.id == id) change(it) else it }
        saveVideos()
    }

    @Synchronized
    fun remove(ids: Collection<String>) {
        for (id in ids) videoDir(id).deleteRecursively()
        _videos.value = _videos.value.filter { it.id !in ids }
        _positions.value = _positions.value - ids.toSet()
        saveVideos()
        savePositions()
    }

    fun addToPlaylist(id: String, ref: PlaylistRef) = update(id) { v ->
        v.copy(playlists = v.playlists.filter { it.id != ref.id } + ref)
    }

    fun removeFromPlaylist(id: String, playlistId: String) = update(id) { v ->
        v.copy(playlists = v.playlists.filter { it.id != playlistId })
    }

    fun position(id: String): Long = _positions.value[id] ?: 0L

    @Synchronized
    fun setPosition(id: String, seconds: Long, duration: Long) {
        val finished = duration > 0 && seconds >= duration - 8
        _positions.value = if (seconds <= 3 || finished) _positions.value - id else _positions.value + (id to seconds)
        savePositions()
    }

    fun freeSpace(): Long = root.usableSpace

    // ---------------------------------------------------------------- files

    private fun videosFile() = File(root, "downloads.json")
    private fun positionsFile() = File(root, "positions.json")

    private fun load() {
        _videos.value = try {
            val arr = JSONArray(videosFile().readText())
            (0 until arr.length()).map { videoFromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
        _positions.value = try {
            val o = JSONObject(positionsFile().readText())
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveVideos() = writeAtomic(videosFile(), JSONArray(_videos.value.map { it.toJson() }).toString())
    private fun savePositions() = writeAtomic(positionsFile(), JSONObject(_positions.value as Map<*, *>).toString())

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun Video.toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("channel", channel); put("subs", subs)
        put("duration", duration); put("views", views); put("published", published)
        put("publishedText", publishedText); put("description", description); put("likes", likes)
        put("commentCount", commentCount); put("height", height); put("size", size); put("savedAt", savedAt)
        put("videoFile", videoFile); put("audioFile", audioFile ?: ""); put("rating", rating ?: "")
        put("comments", JSONArray(comments.map {
            JSONObject().apply {
                put("author", it.author); put("text", it.text); put("time", it.time)
                put("likes", it.likes); put("avatar", it.avatar ?: "")
            }
        }))
        put("playlists", JSONArray(playlists.map {
            JSONObject().apply { put("id", it.id); put("title", it.title); put("index", it.index) }
        }))
    }

    private fun videoFromJson(o: JSONObject): Video {
        val cs = o.optJSONArray("comments") ?: JSONArray()
        val ps = o.optJSONArray("playlists") ?: JSONArray()
        return Video(
            id = o.getString("id"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            subs = o.optString("subs"),
            duration = o.optLong("duration"),
            views = o.optLong("views", -1),
            published = o.optLong("published"),
            publishedText = o.optString("publishedText"),
            description = o.optString("description"),
            likes = o.optString("likes"),
            commentCount = o.optString("commentCount"),
            comments = (0 until cs.length()).map { i ->
                val c = cs.getJSONObject(i)
                Comment(c.optString("author"), c.optString("text"), c.optString("time"), c.optString("likes"),
                    c.optString("avatar").ifEmpty { null })
            },
            height = o.optInt("height"),
            size = o.optLong("size"),
            savedAt = o.optLong("savedAt"),
            playlists = (0 until ps.length()).map { i ->
                val p = ps.getJSONObject(i)
                PlaylistRef(p.getString("id"), p.optString("title"), p.optInt("index"))
            },
            videoFile = o.optString("videoFile"),
            audioFile = o.optString("audioFile").ifEmpty { null },
            rating = o.optString("rating").ifEmpty { null },
        )
    }
}
