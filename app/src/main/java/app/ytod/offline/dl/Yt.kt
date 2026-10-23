package app.ytod.offline.dl

import androidx.core.text.HtmlCompat
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

/** Everything that reads from YouTube (through NewPipeExtractor). */
object Yt {
    private val idPatterns = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})"""),
    )
    private val listPattern = Regex("""[?&]list=([A-Za-z0-9_-]{2,64})""")

    fun videoId(text: String?): String? {
        if (text == null) return null
        for (p in idPatterns) p.find(text)?.let { return it.groupValues[1] }
        return null
    }

    fun listId(text: String?): String? = text?.let { listPattern.find(it)?.groupValues?.get(1) }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    fun streamInfo(id: String): StreamInfo = StreamInfo.getInfo(ServiceList.YouTube, watchUrl(id))

    fun bestImage(images: List<Image>?, maxHeight: Int = 720): String? {
        if (images.isNullOrEmpty()) return null
        val known = images.filter { it.height > 0 }
        if (known.isEmpty()) return images.last().url
        return (known.filter { it.height <= maxHeight }.maxByOrNull { it.height } ?: known.minByOrNull { it.height })?.url
    }

    fun plainText(d: Description?): String {
        if (d == null) return ""
        val content = d.content ?: return ""
        return if (d.type == Description.HTML) {
            HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
        } else content
    }

    private fun heightOf(s: VideoStream): Int =
        s.resolution?.substringBefore('p')?.toIntOrNull() ?: 0

    /** How well a phone can play a codec: H.264 everywhere, VP9 almost everywhere, AV1 only newer phones. */
    private fun codecScore(s: VideoStream): Int {
        val codec = (s.codec ?: "").lowercase()
        return when {
            codec.startsWith("avc") -> 3
            codec.startsWith("vp9") || codec.startsWith("vp09") -> 2
            codec.startsWith("av01") -> 0
            s.format == MediaFormat.MPEG_4 -> 2
            else -> 1
        }
    }

    data class Pick(val video: VideoStream, val audio: AudioStream?, val height: Int)

    /** Pick the best video (up to [maxHeight]) plus the best audio track. */
    fun pickStreams(info: StreamInfo, maxHeight: Int): Pick? {
        val separate = info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && heightOf(it) in 1..maxHeight }
            .sortedWith(compareByDescending<VideoStream> { heightOf(it) }.thenByDescending { codecScore(it) })
            .firstOrNull()
        val audio = pickAudio(info)
        if (separate != null && audio != null) return Pick(separate, audio, heightOf(separate))
        val combined = info.videoStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.isVideoOnly }
            .sortedByDescending { heightOf(it) }
        val c = combined.firstOrNull { heightOf(it) <= maxHeight } ?: combined.lastOrNull() ?: return null
        return Pick(c, null, heightOf(c))
    }

    private fun pickAudio(info: StreamInfo): AudioStream? = info.audioStreams
        .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        .sortedWith(
            compareByDescending<AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .thenByDescending { it.format == MediaFormat.M4A }
                .thenByDescending { it.averageBitrate }
        )
        .firstOrNull()

    /** Available qualities with an approximate size, for the download sheet. */
    fun qualities(info: StreamInfo): List<Pair<Int, Long>> {
        val audioSize = pickAudio(info)?.itagItem?.contentLength ?: 0L
        return info.videoOnlyStreams
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && heightOf(it) > 0 }
            .groupBy { heightOf(it) }
            .map { (h, list) ->
                val best = list.maxByOrNull { codecScore(it) }!!
                h to ((best.itagItem?.contentLength ?: 0L).coerceAtLeast(0L) + audioSize.coerceAtLeast(0L))
            }
            .sortedByDescending { it.first }
    }

    fun comments(id: String, max: Int): List<CommentsInfoItem> {
        val info = CommentsInfo.getInfo(ServiceList.YouTube, watchUrl(id))
        return info.relatedItems.take(max)
    }

    data class PlEntry(val id: String, val title: String, val channel: String, val duration: Long, val thumb: String?)
    data class Pl(val id: String, val title: String, val entries: List<PlEntry>)

    fun playlist(listId: String, max: Int = 500): Pl {
        val url = "https://www.youtube.com/playlist?list=$listId"
        val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
        val items = info.relatedItems.toMutableList()
        var page = info.nextPage
        while (page != null && items.size < max) {
            val more = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, page)
            items += more.items
            page = if (more.hasNextPage()) more.nextPage else null
        }
        val entries = items.take(max).mapNotNull { it.toEntry() }.distinctBy { it.id }
        return Pl(listId, info.name ?: "Playlist", entries)
    }

    private fun StreamInfoItem.toEntry(): PlEntry? {
        val id = videoId(url) ?: return null
        return PlEntry(id, name ?: "", uploaderName ?: "", duration, bestImage(thumbnails))
    }
}
