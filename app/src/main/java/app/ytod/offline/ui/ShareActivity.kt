package app.ytod.offline.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ytod.offline.Fmt
import app.ytod.offline.data.Store
import app.ytod.offline.dl.DownloadManager
import app.ytod.offline.dl.Yt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Opens as a sheet on top of RVX when you tap Download there, or share a link to this app. */
class ShareActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val videoId = Yt.videoId(text)
        val listId = Yt.listId(text)
        if (videoId == null && listId == null) {
            Toast.makeText(this, "That isn't a YouTube video or playlist link", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            YtodTheme {
                DownloadSheet(
                    videoId = videoId,
                    listId = listId,
                    onClose = { finish() },
                    onOpenApp = { openId ->
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .apply { if (openId != null) putExtra(MainActivity.EXTRA_OPEN, openId) }
                        )
                        finish()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(videoId: String?, listId: String?, onClose: () -> Unit, onOpenApp: (String?) -> Unit) {
    val c = LocalYc.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<StreamInfo?>(null) }
    var infoFailed by remember { mutableStateOf(false) }
    var playlist by remember { mutableStateOf<Yt.Pl?>(null) }
    var playlistFailed by remember { mutableStateOf(false) }
    var wholePlaylist by remember { mutableStateOf(videoId == null) }
    var quality by remember { mutableStateOf(Store.quality) }
    var busy by remember { mutableStateOf(false) }
    val already = videoId != null && Store.has(videoId)

    LaunchedEffect(videoId) {
        if (videoId != null && !already) {
            info = withContext(Dispatchers.IO) { runCatching { Yt.streamInfo(videoId) }.getOrNull() }
            if (info == null) infoFailed = true
        }
    }
    LaunchedEffect(listId) {
        if (listId != null) {
            playlist = withContext(Dispatchers.IO) { runCatching { Yt.playlist(listId) }.getOrNull() }
            if (playlist == null) playlistFailed = true
        }
    }

    val options: List<Pair<Int, Long>> = info?.let { Yt.qualities(it).filter { q -> q.first <= 2160 } }
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(1080 to 0L, 720 to 0L, 480 to 0L, 360 to 0L)
    if (options.none { it.first == quality }) {
        quality = options.firstOrNull { it.first <= quality }?.first ?: options.last().first
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text("Download", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            if (videoId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb("https://i.ytimg.com/vi/$videoId/hqdefault.jpg", Modifier.width(128.dp), radius = 8.dp,
                        duration = info?.duration ?: 0L)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info?.name ?: if (infoFailed) "Couldn't load the video details" else "Loading…",
                            color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        info?.uploaderName?.let { Text(it, color = c.text2, fontSize = 12.sp, maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (listId != null) {
                val pl = playlist
                val plText = when {
                    pl != null -> {
                        val have = pl.entries.count { Store.has(it.id) }
                        "${pl.title} · ${pl.entries.size} videos" + if (have > 0) " · $have already downloaded" else ""
                    }
                    playlistFailed -> "Couldn't load this playlist"
                    else -> "Loading playlist…"
                }
                if (videoId != null) {
                    Choice("This video", null, !wholePlaylist) { wholePlaylist = false }
                    Choice("Whole playlist", plText, wholePlaylist) { wholePlaylist = true }
                } else {
                    Text(plText, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
            }

            if (!wholePlaylist && already) {
                Text("This video is already in your downloads.", color = c.text2, fontSize = 14.sp)
            } else {
                Text("Quality", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                for ((h, size) in options) {
                    Choice("${h}p", if (!wholePlaylist && size > 0) "about ${Fmt.bytes(size)}" else null, quality == h) { quality = h }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpenApp(if (already && !wholePlaylist) videoId else null) }) {
                    Text(if (already && !wholePlaylist) "Watch" else "View downloads", color = c.blue)
                }
                Spacer(Modifier.width(8.dp))
                val ready = when {
                    wholePlaylist -> playlist != null
                    else -> videoId != null && !already
                }
                Button(
                    enabled = ready && !busy,
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.text, contentColor = c.bg),
                    onClick = {
                        busy = true
                        Store.quality = quality
                        if (wholePlaylist) {
                            val pl = playlist ?: return@Button
                            scope.launch {
                                val (added, had) = withContext(Dispatchers.IO) { DownloadManager.enqueuePlaylist(ctx, pl, quality) }
                                Toast.makeText(ctx,
                                    if (added > 0) "Downloading $added video${if (added == 1) "" else "s"} from \"${pl.title}\""
                                    else "Playlist added to your downloads" + if (had > 0) " ($had already downloaded)" else "",
                                    Toast.LENGTH_LONG).show()
                                onClose()
                            }
                        } else if (videoId != null) {
                            val i = info
                            DownloadManager.enqueue(
                                ctx, videoId,
                                title = i?.name ?: "",
                                channel = i?.uploaderName ?: "",
                                thumb = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                                duration = i?.duration ?: 0L,
                                maxHeight = quality,
                            )
                            Toast.makeText(ctx, "Downloading…", Toast.LENGTH_SHORT).show()
                            onClose()
                        }
                    },
                ) {
                    Text(
                        when {
                            wholePlaylist && playlist == null && !playlistFailed -> "Loading…"
                            else -> "Download"
                        },
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Choice(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    val c = LocalYc.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .background(if (selected) c.chip else androidx.compose.ui.graphics.Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = c.blue))
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(title, color = c.text, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, color = c.text2, fontSize = 12.sp)
        }
    }
}
