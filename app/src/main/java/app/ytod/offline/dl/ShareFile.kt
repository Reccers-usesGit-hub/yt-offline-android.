package app.ytod.offline.dl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import app.ytod.offline.data.Store
import app.ytod.offline.data.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer

/**
 * Downloads are stored as a separate picture file and sound file. To hand one to somebody
 * we join them into a single MP4 in the cache (Android clears it when it needs the space),
 * then open the normal share sheet.
 */
object ShareFile {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val busy = HashSet<String>()

    fun share(ctx: Context, v: Video) {
        synchronized(busy) { if (!busy.add(v.id)) return }
        toast(ctx, "Preparing video…")
        scope.launch {
            try {
                val dir = Store.videoDir(v.id)
                val shareDir = File(ctx.cacheDir, "share").apply { mkdirs() }
                shareDir.listFiles()?.forEach { it.delete() }
                val name = v.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(60).ifEmpty { v.id }
                val out = File(shareDir, "$name.mp4")
                val src = File(dir, v.videoFile)
                if (v.audioFile == null && src.extension.equals("mp4", true)) src.copyTo(out, overwrite = true)
                else mux(dir, v, out)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
                main.post {
                    val send = Intent(Intent.ACTION_SEND).setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, v.title)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val chooser = Intent.createChooser(send, "Share video")
                    if (ctx !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(chooser)
                }
            } catch (e: Exception) {
                toast(ctx, "Couldn't prepare this video for sharing")
            } finally {
                synchronized(busy) { busy.remove(v.id) }
            }
        }
    }

    private fun toast(ctx: Context, text: String) = main.post { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }

    private fun mux(dir: File, v: Video, out: File) {
        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = mutableListOf<MediaExtractor>()
        var started = false
        try {
            val tracks = mutableListOf<Pair<MediaExtractor, Int>>()
            for (name in listOfNotNull(v.videoFile, v.audioFile)) {
                val e = MediaExtractor()
                extractors += e
                e.setDataSource(File(dir, name).path)
                for (i in 0 until e.trackCount) {
                    val f = e.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        e.selectTrack(i)
                        tracks += e to muxer.addTrack(f)
                        break
                    }
                }
            }
            check(tracks.isNotEmpty())
            muxer.start()
            started = true
            val buf = ByteBuffer.allocate(8 shl 20)
            val info = MediaCodec.BufferInfo()
            // Interleave by timestamp so the file plays and seeks properly.
            while (true) {
                val next = tracks.filter { it.first.sampleTime >= 0 }.minByOrNull { it.first.sampleTime } ?: break
                val (e, t) = next
                buf.clear()
                val n = e.readSampleData(buf, 0)
                if (n < 0) { e.advance(); continue }
                val key = e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, n, e.sampleTime, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(t, buf, info)
                e.advance()
            }
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }
}
