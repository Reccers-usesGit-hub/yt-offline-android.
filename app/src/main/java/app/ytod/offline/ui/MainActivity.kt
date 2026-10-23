package app.ytod.offline.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ytod.offline.data.Store
import kotlinx.coroutines.flow.MutableStateFlow

sealed interface Screen {
    data object Home : Screen
    data class Watch(val id: String, val list: String?) : Screen
}

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        setContent { YtodTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN)?.let { pendingOpen.value = it }
    }

    companion object {
        const val EXTRA_OPEN = "open_video"
        val pendingOpen = MutableStateFlow<String?>(null)
    }
}

@Composable
private fun Root() {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home)) }
    var settings by remember { mutableStateOf(false) }
    val open by MainActivity.pendingOpen.collectAsState()
    LaunchedEffect(open) {
        open?.let { id ->
            if (Store.has(id)) stack = listOf(Screen.Home, Screen.Watch(id, null))
            MainActivity.pendingOpen.value = null
        }
    }
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    when (val top = stack.last()) {
        Screen.Home -> DownloadsScreen(
            onOpen = { id, list -> stack = stack + Screen.Watch(id, list) },
            onSettings = { settings = true },
        )
        is Screen.Watch -> WatchScreen(
            id = top.id,
            listId = top.list,
            onBack = { stack = stack.dropLast(1) },
            onOpen = { id, list -> stack = stack.dropLast(1) + Screen.Watch(id, list) },
        )
    }
    if (settings) SettingsDialog { settings = false }
}

@Composable
private fun SettingsDialog(onClose: () -> Unit) {
    val c = LocalYc.current
    val ctx = LocalContext.current
    var quality by remember { mutableStateOf(Store.quality) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.raised,
        title = { Text("Settings", color = c.text, fontSize = 20.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Download quality", color = c.text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text("Videos download at this quality or the closest lower one.", color = c.text2, fontSize = 13.sp)
                for (q in listOf(2160, 1440, 1080, 720, 480, 360)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { quality = q; Store.quality = q },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = quality == q, onClick = { quality = q; Store.quality = q },
                            colors = RadioButtonDefaults.colors(selectedColor = c.blue))
                        Text("${q}p" + if (q == 1080) " (recommended)" else "", color = c.text, fontSize = 14.sp)
                    }
                }
                Text("Connect to RVX", color = c.text, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 16.dp))
                Text(
                    "In RVX, open Settings and search for \"External downloader\". Turn it on, and set the package name to:",
                    color = c.text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    ctx.packageName, color = c.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.chip)
                        .clickable {
                            val cm = ctx.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(ClipData.newPlainText("package", ctx.packageName))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Text(
                    "Tap it to copy. RVX's Download button will then send videos here. To download a whole playlist, " +
                        "open the playlist in RVX, tap Share, and choose \"Download offline\".",
                    color = c.text2, fontSize = 13.sp,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done", color = c.blue) } },
    )
}
