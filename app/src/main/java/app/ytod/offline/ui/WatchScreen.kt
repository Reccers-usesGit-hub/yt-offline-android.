package app.ytod.offline.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import app.ytod.offline.Fmt
import app.ytod.offline.data.Store
import app.ytod.offline.data.Video
import app.ytod.offline.dl.ShareFile
import kotlinx.coroutines.delay
import java.io.File

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@androidx.annotation.OptIn(UnstableApi::class)
fun mediaSourceFor(ctx: Context, v: Video): MediaSource {
    val dir = Store.videoDir(v.id)
    val factory = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx))
    val video = factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(File(dir, v.videoFile))))
    val audioName = v.audioFile ?: return video
    val audio = factory.createMediaSource(MediaItem.fromUri(Uri.fromFile(File(dir, audioName))))
    // Video and audio are saved as two files; play them together.
    return MergingMediaSource(false, true, video, audio)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerHost(player: ExoPlayer, modifier: Modifier, onFullscreen: () -> Unit) {
    val cb = rememberUpdatedState(onFullscreen)
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                setShowSubtitleButton(false)
                setShowShuffleButton(false)
                setFullscreenButtonClickListener { _ -> cb.value() }
                keepScreenOn = true
                findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.let { bar ->
                    bar.setPlayedColor(0xFFFF0033.toInt())
                    bar.setScrubberColor(0xFFFF0033.toInt())
                    bar.setBufferedColor(0x66FFFFFF)
                }
            }
        },
        update = { it.player = player },
    )
}

fun richText(text: String, link: Color, onSeek: (Long) -> Unit): AnnotatedString = buildAnnotatedString {
    val re = Regex("""(https?://\S+)|\b((?:\d{1,2}:)?\d{1,2}:\d{2})\b""")
    val style = TextLinkStyles(SpanStyle(color = link))
    var last = 0
    for (m in re.findAll(text)) {
        append(text.substring(last, m.range.first))
        val url = m.groups[1]?.value
        if (url != null) {
            withLink(LinkAnnotation.Url(url, style)) { append(url) }
        } else {
            val secs = m.value.split(":").fold(0L) { acc, p -> acc * 60 + (p.toLongOrNull() ?: 0L) }
            withLink(LinkAnnotation.Clickable("t$secs", style) { onSeek(secs) }) { append(m.value) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(id: String, listId: String?, onBack: () -> Unit, onOpen: (String, String?) -> Unit) {
    val c = LocalYc.current
    val ctx = LocalContext.current
    val videos by Store.videos.collectAsState()
    val positions by Store.positions.collectAsState()
    val v = videos.firstOrNull { it.id == id }
    if (v == null) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().padding(24.dp)) {
            Text("This video isn't in your downloads anymore.", color = c.text, fontSize = 16.sp)
            TextButton(onClick = onBack) { Text("Back to downloads", color = c.blue) }
        }
        return
    }
    val dir = remember(id) { Store.videoDir(id) }

    val plItems = remember(videos, listId) {
        if (listId == null) emptyList()
        else videos.filter { x -> x.playlists.any { it.id == listId } }.sortedBy { playlistIndex(it, listId) }
    }
    val pos = plItems.indexOfFirst { it.id == id }
    val inPlaylist = pos >= 0
    val others = videos.filter { it.id != id && !(inPlaylist && plItems.any { p -> p.id == it.id }) }
        .sortedByDescending { it.savedAt }
    val next: Pair<String, String?>? =
        if (inPlaylist) plItems.getOrNull(pos + 1)?.let { it.id to listId } else others.firstOrNull()?.let { it.id to null }
    val nextState = rememberUpdatedState(next)
    val openState = rememberUpdatedState(onOpen)

    val player = remember(id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaSource(mediaSourceFor(ctx, v))
            prepare()
            val start = Store.position(id)
            if (start > 0) seekTo(start * 1000)
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    Store.setPosition(id, 0, v.duration)
                    nextState.value?.let { (nid, nl) -> openState.value(nid, nl) }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            Store.setPosition(id, player.currentPosition / 1000, v.duration)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.isPlaying) Store.setPosition(id, player.currentPosition / 1000, v.duration)
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Fullscreen: landscape, no system bars.
    var full by remember { mutableStateOf(false) }
    val activity = ctx.findActivity()
    LaunchedEffect(full) {
        val a = activity ?: return@LaunchedEffect
        a.requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val ctl = WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (full) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { a ->
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(a.window, a.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    BackHandler(enabled = full) { full = false }

    if (full) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PlayerHost(player, Modifier.fillMaxSize()) { full = false }
        }
        return
    }

    var showDesc by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val seek = { s: Long -> player.seekTo(s * 1000); player.play(); showDesc = false; showComments = false }
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.background(Color.Black).statusBarsPadding()) {
            PlayerHost(player, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { full = true }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = navPad + 16.dp)) {
            item(key = "title") {
                Column(Modifier.fillMaxWidth().clickable { showDesc = true }.padding(12.dp, 12.dp, 12.dp, 4.dp)) {
                    Text(v.title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
                    Text(
                        listOf(Fmt.views(v.views), Fmt.ago(v.published).ifEmpty { v.publishedText }).filter { it.isNotEmpty() }
                            .joinToString("  ") + "  ...more",
                        color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item(key = "channel") {
                Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(File(dir, "avatar.jpg"), v.channel, 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(v.channel, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (v.subs.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(v.subs.removeSuffix(" subscribers"), color = c.text2, fontSize = 12.sp)
                    }
                }
            }
            item(key = "actions") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { LikeDislike(v) }
                    item { Pill("Share file", Ic.share, onClick = { ShareFile.share(ctx, v) }) }
                    item {
                        Pill("Share link", Ic.share, onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${v.id}")
                            ctx.startActivity(Intent.createChooser(send, "Share"))
                        })
                    }
                    item { Pill("Downloaded", Ic.check, onClick = {}) }
                    item { Pill("Remove", Ic.trash, onClick = { confirmRemove = true }) }
                }
            }
            item(key = "comments") {
                Column(
                    Modifier.padding(12.dp, 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.raised)
                        .clickable { showComments = true }.padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Comments", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(if (v.comments.isEmpty()) "" else v.comments.size.toString(), color = c.text2, fontSize = 13.sp)
                    }
                    val first = v.comments.firstOrNull()
                    if (first == null) {
                        Text("No comments were saved with this download.", color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(first.avatar?.let { File(dir, it) }, first.author, 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(first.text, color = c.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (inPlaylist && listId != null) {
                item(key = "playlist") {
                    PlaylistPanel(
                        title = v.playlists.firstOrNull { it.id == listId }?.title ?: "Playlist",
                        pos = pos, items = plItems, currentId = id, positions = positions,
                        onOpen = { onOpen(it, listId) },
                        onClose = { onOpen(id, null) },
                    )
                }
            }
            if (others.isNotEmpty()) {
                item(key = "upnext") {
                    Text(if (inPlaylist) "More downloads" else "Up next", color = c.text, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp, 16.dp, 12.dp, 4.dp))
                }
                items(others, key = { "o-" + it.id }) { o ->
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        VideoRow(
                            o, watched = (positions[o.id] ?: 0L).let { p -> if (p > 0 && o.duration > 0) p.toFloat() / o.duration else 0f },
                            index = null, onClick = { onOpen(o.id, null) },
                            menu = listOf(
                                MenuItem("Share video file", Ic.share) { ShareFile.share(ctx, o) },
                                MenuItem("Remove from downloads", Ic.trash) { Store.remove(listOf(o.id)) },
                            ),
                        )
                    }
                }
            }
        }
    }

    if (showDesc) {
        ModalBottomSheet(onDismissRequest = { showDesc = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface) {
            LazyColumn(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
                item {
                    Text("Description", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(v.title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Stat(v.likes.ifEmpty { "—" }, "Likes")
                        Stat(if (v.views >= 0) "%,d".format(v.views) else "—", "Views")
                        Stat(Fmt.ago(v.published).ifEmpty { v.publishedText.ifEmpty { "—" } }, "Uploaded")
                    }
                    Text(
                        richText(v.description, c.blue, seek), color = c.text, fontSize = 14.sp, lineHeight = 20.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.raised).padding(12.dp).fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showComments) {
        ModalBottomSheet(onDismissRequest = { showComments = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface) {
            Text("Comments", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp))
            LazyColumn(Modifier.navigationBarsPadding()) {
                if (v.comments.isEmpty()) {
                    item { Text("No comments were saved with this download.", color = c.text2, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
                }
                items(v.comments.size) { i ->
                    val cm = v.comments[i]
                    Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
                        Avatar(cm.avatar?.let { File(dir, it) }, cm.author, 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(listOf(cm.author, cm.time).filter { it.isNotEmpty() }.joinToString(" · "), color = c.text2, fontSize = 12.sp)
                            Text(richText(cm.text, c.blue, seek), color = c.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 2.dp))
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Ic.like, null, tint = c.text, modifier = Modifier.size(16.dp))
                                if (cm.likes.isNotEmpty()) Text(cm.likes, color = c.text2, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                                Spacer(Modifier.width(20.dp))
                                Icon(Ic.like, null, tint = c.text, modifier = Modifier.size(16.dp).rotate(180f))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = c.raised,
            title = { Text("Remove from downloads?", color = c.text, fontSize = 18.sp) },
            text = { Text(v.title, color = c.text2, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onBack(); Store.remove(listOf(id)) }) { Text("Remove", color = c.blue) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel", color = c.text) } },
        )
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val c = LocalYc.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = c.text2, fontSize = 12.sp)
    }
}

@Composable
private fun LikeDislike(v: Video) {
    val c = LocalYc.current
    val rate = { r: String -> Store.update(v.id) { it.copy(rating = if (it.rating == r) null else r) } }
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.chip), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clickable { rate("like") }.padding(start = 12.dp, end = 12.dp).height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (v.rating == "like") Ic.likeFill else Ic.like, "Like", tint = c.text, modifier = Modifier.size(20.dp))
            if (v.likes.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(v.likes, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Box(Modifier.width(1.dp).height(20.dp).background(c.divider))
        Box(Modifier.clickable { rate("dislike") }.padding(horizontal = 12.dp).height(36.dp), contentAlignment = Alignment.Center) {
            Icon(if (v.rating == "dislike") Ic.likeFill else Ic.like, "Dislike", tint = c.text, modifier = Modifier.size(20.dp).rotate(180f))
        }
    }
}

@Composable
private fun PlaylistPanel(
    title: String,
    pos: Int,
    items: List<Video>,
    currentId: String,
    positions: Map<String, Long>,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalYc.current
    val pctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(12.dp, 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.raised)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Downloads · ${pos + 1} / ${items.size}", color = c.text2, fontSize = 12.sp)
            }
            Icon(if (open) Ic.collapse else Ic.expand, null, tint = c.text, modifier = Modifier.size(24.dp))
            IconButton(onClick = onClose) { Icon(Ic.close, "Close playlist", tint = c.text, modifier = Modifier.size(20.dp)) }
        }
        if (open) {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) {
                items.forEachIndexed { i, o ->
                    VideoRow(
                        o, watched = (positions[o.id] ?: 0L).let { p -> if (p > 0 && o.duration > 0) p.toFloat() / o.duration else 0f },
                        index = i + 1, current = o.id == currentId,
                        onClick = { if (o.id != currentId) onOpen(o.id) },
                        menu = listOf(
                            MenuItem("Share video file", Ic.share) { ShareFile.share(pctx, o) },
                            MenuItem("Remove from downloads", Ic.trash) { Store.remove(listOf(o.id)) },
                        ),
                    )
                }
            }
        }
    }
}
