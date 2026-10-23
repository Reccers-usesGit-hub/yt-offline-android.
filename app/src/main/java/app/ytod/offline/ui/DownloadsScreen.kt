package app.ytod.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ytod.offline.Fmt
import app.ytod.offline.data.PlaylistRef
import app.ytod.offline.data.Store
import app.ytod.offline.data.Video
import app.ytod.offline.dl.DlJob
import app.ytod.offline.dl.DownloadManager
import app.ytod.offline.dl.JobState
import app.ytod.offline.dl.ShareFile
import androidx.compose.ui.platform.LocalContext
import java.io.File

/** A playlist shown as one stacked card in "All". */
class PlGroup(val id: String, val title: String) {
    val items = mutableListOf<Video>()
    val jobs = mutableListOf<DlJob>()
    var latest = 0L
}

fun playlistIndex(v: Video, listId: String) = v.playlists.firstOrNull { it.id == listId }?.index ?: Int.MAX_VALUE

class Confirm(val title: String, val lines: List<String>, val ok: String, val action: () -> Unit)

@Composable
fun DownloadsScreen(onOpen: (String, String?) -> Unit, onSettings: () -> Unit) {
    val c = LocalYc.current
    val ctx = LocalContext.current
    val videos by Store.videos.collectAsState()
    val jobs by DownloadManager.jobs.collectAsState()
    val positions by Store.positions.collectAsState()
    var filter by remember { mutableStateOf(Store.listFilter) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    val groups = remember(videos, jobs) {
        val map = LinkedHashMap<String, PlGroup>()
        fun group(ref: PlaylistRef) = map.getOrPut(ref.id) { PlGroup(ref.id, ref.title) }
        for (v in videos) for (ref in v.playlists) group(ref).apply { items += v; latest = maxOf(latest, v.savedAt) }
        for (j in jobs) j.playlist?.let { ref -> group(ref).apply { this.jobs += j; latest = Long.MAX_VALUE } }
        for (g in map.values) {
            g.items.sortBy { playlistIndex(it, g.id) }
            g.jobs.sortBy { it.playlist?.index ?: 0 }
        }
        map
    }
    if (filter != "all" && !groups.containsKey(filter)) filter = "all"
    val setFilter = { f: String -> filter = f; Store.listFilter = f }

    val watched = { v: Video -> val p = positions[v.id] ?: 0L; if (p > 0 && v.duration > 0) p.toFloat() / v.duration else 0f }
    val removeVideo = { v: Video ->
        confirm = Confirm("Remove from downloads?", listOf(v.title), "Remove") { Store.remove(listOf(v.id)) }
    }
    val removePlaylist = { g: PlGroup ->
        val own = g.items.filter { it.playlists.size <= 1 }
        val shared = g.items.size - own.size
        val lines = mutableListOf(g.title, "${own.size} downloaded video${if (own.size == 1) "" else "s"} will be removed from this phone.")
        if (shared > 0) lines += "$shared also in another playlist will be kept there."
        if (g.jobs.isNotEmpty()) lines += "${g.jobs.size} unfinished download${if (g.jobs.size == 1) "" else "s"} will be cancelled."
        confirm = Confirm("Remove playlist from downloads?", lines, "Remove") {
            g.jobs.forEach { DownloadManager.cancel(it.id) }
            g.items.filter { it.playlists.size > 1 }.forEach { Store.removeFromPlaylist(it.id, g.id) }
            Store.remove(own.map { it.id })
            if (filter == g.id) setFilter("all")
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Ic.settings, "Settings", tint = c.text) }
        }

        val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(330.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = navPad + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    val total = videos.sumOf { it.size }
                    Text(
                        listOf(
                            if (videos.isNotEmpty()) "${videos.size} video${if (videos.size == 1) "" else "s"}" else "",
                            if (videos.isNotEmpty()) Fmt.bytes(total) else "",
                            Fmt.bytes(Store.freeSpace()) + " free",
                        ).filter { it.isNotEmpty() }.joinToString(" • "),
                        color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                    )
                    if (groups.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                            item { Chip("All", filter == "all") { setFilter("all") } }
                            items(groups.values.toList(), key = { it.id }) { g -> Chip(g.title.ifEmpty { "Playlist" }, filter == g.id) { setFilter(g.id) } }
                        }
                    }
                    if (jobs.size > 1) {
                        Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Downloading ${jobs.size} videos", color = c.text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { DownloadManager.cancelAll() }) { Text("Cancel all", color = c.blue) }
                        }
                    }
                }
            }

            if (filter == "all") {
                val loneJobs = jobs.filter { it.playlist == null }
                items(loneJobs, key = { "job-" + it.id }) { j -> JobCard(j) }
                val cards: List<Any> = (groups.values.map { it as Any } + videos.filter { it.playlists.isEmpty() })
                    .sortedByDescending { if (it is PlGroup) it.latest else (it as Video).savedAt }
                items(cards, key = { if (it is PlGroup) "pl-" + it.id else "v-" + (it as Video).id }) { item ->
                    if (item is PlGroup) {
                        PlaylistCard(
                            g = item,
                            onPlay = {
                                val start = item.items.firstOrNull { (positions[it.id] ?: 0L) > 0 } ?: item.items.firstOrNull()
                                if (start != null) onOpen(start.id, item.id) else setFilter(item.id)
                            },
                            onOpenList = { setFilter(item.id) },
                            onRemove = { removePlaylist(item) },
                        )
                    } else {
                        val v = item as Video
                        VideoCard(v, watched(v), onClick = { onOpen(v.id, null) }, onRemove = { removeVideo(v) })
                    }
                }
            } else {
                val g = groups[filter]
                if (g != null) {
                    items(g.jobs, key = { "jrow-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { j -> JobRow(j) }
                    items(g.items, key = { "row-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { v ->
                        VideoRow(
                            v, watched(v), index = playlistIndex(v, g.id) + 1,
                            onClick = { onOpen(v.id, g.id) },
                            menu = listOf(
                                MenuItem("Share video file", Ic.share) { ShareFile.share(ctx, v) },
                                MenuItem("Remove from this playlist", Ic.playlists) { Store.removeFromPlaylist(v.id, g.id) },
                                MenuItem("Remove from downloads", Ic.trash) { removeVideo(v) },
                            ),
                        )
                    }
                }
            }

            if (videos.isEmpty() && jobs.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") { EmptyState() }
            }
        }
    }

    confirm?.let { cf ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = c.raised,
            title = { Text(cf.title, color = c.text, fontSize = 18.sp) },
            text = {
                Column {
                    cf.lines.forEachIndexed { i, l ->
                        Text(l, color = if (i == 0) c.text else c.text2, fontWeight = if (i == 0) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { cf.action(); confirm = null }) { Text(cf.ok, color = c.blue) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel", color = c.text) } },
        )
    }
}

class MenuItem(val label: String, val icon: ImageVector, val action: () -> Unit)

@Composable
fun DotsMenu(items: List<MenuItem>) {
    val c = LocalYc.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Ic.dots, "More", tint = c.text, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (m in items) {
                DropdownMenuItem(
                    text = { Text(m.label, color = c.text, fontSize = 14.sp) },
                    leadingIcon = { Icon(m.icon, null, tint = c.text, modifier = Modifier.size(22.dp)) },
                    onClick = { open = false; m.action() },
                )
            }
        }
    }
}

@Composable
fun VideoCard(v: Video, watched: Float, onClick: () -> Unit, onRemove: () -> Unit) {
    val c = LocalYc.current
    val dir = Store.videoDir(v.id)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(bottom = 20.dp)) {
        Thumb(thumbFile(dir), Modifier.fillMaxWidth(), duration = v.duration, watched = watched)
        Row(Modifier.padding(top = 10.dp)) {
            Avatar(File(dir, "avatar.jpg"), v.channel)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                Text(listOf(v.channel, Fmt.views(v.views), Fmt.ago(v.published).ifEmpty { v.publishedText })
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                    color = c.text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            val ctx = LocalContext.current
            DotsMenu(listOf(
                MenuItem("Share video file", Ic.share) { ShareFile.share(ctx, v) },
                MenuItem("Remove from downloads", Ic.trash, onRemove),
            ))
        }
    }
}

@Composable
fun PlaylistCard(g: PlGroup, onPlay: () -> Unit, onOpenList: () -> Unit, onRemove: () -> Unit) {
    val c = LocalYc.current
    val first = g.items.firstOrNull()
    val model: Any? = first?.let { thumbFile(Store.videoDir(it.id)) } ?: g.jobs.firstOrNull()?.thumbUrl
    val total = g.items.size + g.jobs.size
    val channels = (g.items.map { it.channel } + g.jobs.map { it.channel }).filter { it.isNotEmpty() }.distinct()
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpenList).padding(bottom = 20.dp)) {
        // The stacked edges behind the thumbnail.
        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(4.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(c.stack2))
        Spacer(Modifier.height(1.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(4.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(c.stack1))
        Spacer(Modifier.height(1.dp))
        Box(Modifier.fillMaxWidth().clickable(onClick = onPlay)) {
            Thumb(model, Modifier.fillMaxWidth()) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0x99000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Ic.playlists, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$total video${if (total == 1) "" else "s"}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Row(Modifier.padding(top = 10.dp)) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(g.title.ifEmpty { "Playlist" }, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text((if (channels.size == 1) channels[0] + " · " else "") + "Playlist", color = c.text2, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (g.jobs.isNotEmpty()) {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            progress = { g.items.size.toFloat() / total.coerceAtLeast(1) },
                            modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = c.text2, trackColor = c.divider,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Downloading ${g.jobs.size} more · ${g.items.size} of $total ready", color = c.text2, fontSize = 12.sp)
                    }
                }
                Text("View full playlist", color = c.text2, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp).clickable(onClick = onOpenList))
            }
            DotsMenu(listOf(
                MenuItem("View full playlist", Ic.playlists, onOpenList),
                MenuItem("Remove playlist from downloads", Ic.trash, onRemove),
            ))
        }
    }
}

fun jobLabel(j: DlJob): String = j.stage.ifEmpty { "Downloading… ${(j.progress * 100).toInt()}%" }

@Composable
private fun JobStatus(j: DlJob) {
    val c = LocalYc.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        if (j.state == JobState.RUNNING && j.stage.isEmpty()) {
            CircularProgressIndicator(progress = { j.progress }, modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                color = c.text2, trackColor = c.divider)
        } else {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = c.text2)
        }
        Spacer(Modifier.width(6.dp))
        Text(jobLabel(j), color = c.text2, fontSize = 12.sp)
    }
}

@Composable
private fun ProgressOverlay(j: DlJob) {
    val c = LocalYc.current
    Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x99AAAAAA))) {
        Box(Modifier.fillMaxWidth(j.progress.coerceIn(0f, 1f)).height(3.dp).background(c.red))
    }
}

@Composable
fun JobCard(j: DlJob) {
    val c = LocalYc.current
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Thumb(j.thumbUrl, Modifier.fillMaxWidth(), duration = j.duration) {
            Box(Modifier.align(Alignment.BottomStart)) { ProgressOverlay(j) }
        }
        Row(Modifier.padding(top = 10.dp)) {
            Avatar(null, j.channel)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(j.title.ifEmpty { "Getting video details…" }, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (j.channel.isNotEmpty()) Text(j.channel, color = c.text2, fontSize = 12.sp, maxLines = 1)
                JobStatus(j)
            }
            DotsMenu(listOf(MenuItem("Cancel download", Ic.close) { DownloadManager.cancel(j.id) }))
        }
    }
}

@Composable
fun JobRow(j: DlJob) {
    val c = LocalYc.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Box(Modifier.width(168.dp)) {
            Thumb(j.thumbUrl, Modifier.fillMaxWidth(), radius = 8.dp, duration = j.duration) {
                Box(Modifier.align(Alignment.BottomStart)) { ProgressOverlay(j) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(j.title.ifEmpty { "Getting video details…" }, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (j.channel.isNotEmpty()) Text(j.channel, color = c.text2, fontSize = 12.sp, maxLines = 1)
            JobStatus(j)
        }
        DotsMenu(listOf(MenuItem("Cancel download", Ic.close) { DownloadManager.cancel(j.id) }))
    }
}

@Composable
fun VideoRow(v: Video, watched: Float, index: Int?, onClick: () -> Unit, menu: List<MenuItem>, current: Boolean = false) {
    val c = LocalYc.current
    val dir = Store.videoDir(v.id)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (current) c.chip else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        if (index != null) {
            Box(Modifier.width(22.dp).padding(top = 30.dp), contentAlignment = Alignment.Center) {
                if (current) Icon(Ic.play, null, tint = c.text2, modifier = Modifier.size(12.dp))
                else Text(index.toString(), color = c.text2, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
        Box(Modifier.width(if (index != null) 150.dp else 168.dp)) {
            Thumb(thumbFile(dir), Modifier.fillMaxWidth(), radius = 8.dp, duration = v.duration, watched = watched)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Text(v.channel, color = c.text2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            Text(listOf(if (v.height > 0) "${v.height}p" else "", Fmt.bytes(v.size)).filter { it.isNotEmpty() }.joinToString(" · "),
                color = c.text2, fontSize = 12.sp, maxLines = 1)
        }
        DotsMenu(menu)
    }
}

@Composable
private fun EmptyState() {
    val c = LocalYc.current
    Column(Modifier.fillMaxWidth().padding(top = 72.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(c.chip), contentAlignment = Alignment.Center) {
            Icon(Ic.download, null, tint = c.text, modifier = Modifier.size(56.dp))
        }
        Text("Videos you download will appear here", color = c.text, fontSize = 18.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp))
        Text("In RVX, tap Download under any video, or share a video or playlist to this app. Downloads play here, even with no internet.",
            color = c.text2, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}
