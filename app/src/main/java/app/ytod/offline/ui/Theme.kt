package app.ytod.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ytod.offline.Fmt
import coil.compose.AsyncImage
import java.io.File

@Immutable
data class Yc(
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val chip: Color,
    val chipOn: Color,
    val chipOnText: Color,
    val text: Color,
    val text2: Color,
    val divider: Color,
    val thumb: Color,
    val stack1: Color,
    val stack2: Color,
    val blue: Color,
    val red: Color = Color(0xFFFF0033),
)

val DarkYc = Yc(
    bg = Color(0xFF0F0F0F), surface = Color(0xFF212121), raised = Color(0xFF282828),
    chip = Color(0x1AFFFFFF), chipOn = Color(0xFFF1F1F1), chipOnText = Color(0xFF0F0F0F),
    text = Color(0xFFF1F1F1), text2 = Color(0xFFAAAAAA), divider = Color(0x33FFFFFF),
    thumb = Color(0xFF272727), stack1 = Color(0xFF5E5E5E), stack2 = Color(0xFF3A3A3A), blue = Color(0xFF3EA6FF),
)
val LightYc = Yc(
    bg = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), raised = Color(0xFFF2F2F2),
    chip = Color(0x0D000000), chipOn = Color(0xFF0F0F0F), chipOnText = Color(0xFFFFFFFF),
    text = Color(0xFF0F0F0F), text2 = Color(0xFF606060), divider = Color(0x1A000000),
    thumb = Color(0xFFE5E5E5), stack1 = Color(0xFFB4B4B4), stack2 = Color(0xFFD6D6D6), blue = Color(0xFF065FD4),
)

val LocalYc = staticCompositionLocalOf { DarkYc }

@Composable
fun YtodTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkYc else LightYc
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.text, onPrimary = c.bg, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceContainer = c.raised, surfaceContainerHigh = c.raised,
            surfaceContainerLow = c.surface, onSurfaceVariant = c.text2, outline = c.divider,
        )
    } else {
        lightColorScheme(
            primary = c.text, onPrimary = c.bg, background = c.bg, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
            surfaceContainerLow = c.surface, onSurfaceVariant = c.text2, outline = c.divider,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalYc provides c, content = content)
    }
}

/** Small set of outline icons, drawn from SVG paths (no big icon library needed). */
object Ic {
    private fun icon(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black))
        .build()

    val download = icon("download", "M17 18v1H6v-1h11zm-.5-6.6-.7-.7-3.8 3.8V4h-1v10.5l-3.8-3.8-.7.7 5 5 5-5z")
    val check = icon("check", "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
    val dots = icon("dots", "M12 16.5c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5-1.5-.67-1.5-1.5.67-1.5 1.5-1.5zM10.5 12c0 .83.67 1.5 1.5 1.5s1.5-.67 1.5-1.5-.67-1.5-1.5-1.5-1.5.67-1.5 1.5zm0-6c0 .83.67 1.5 1.5 1.5s1.5-.67 1.5-1.5-.67-1.5-1.5-1.5-1.5.67-1.5 1.5z")
    val trash = icon("trash", "M11 17H9V8h2v9zm4-9h-2v9h2V8zm4-4v1h-1v16H6V5H5V4h4V3h6v1h4zm-2 1H7v15h10V5z")
    val close = icon("close", "M12.7 12l6.65 6.65-.7.7L12 12.7l-6.65 6.65-.7-.7L11.3 12 4.65 5.35l.7-.7L12 11.3l6.65-6.65.7.7L12.7 12z")
    val like = icon("like", "M18.77 11h-4.23l1.52-4.94C16.38 5.03 15.54 4 14.38 4c-.58 0-1.14.24-1.52.65L7 11H3v10h4h1h9.43c1.06 0 1.98-.67 2.19-1.61l1.34-6C21.23 12.15 20.18 11 18.77 11zM7 20H4v-8h3v8zm12.98-6.83-1.34 6c-.1.48-.61.83-1.21.83H8v-8.61l5.6-6.06c.19-.21.48-.33.78-.33.26 0 .5.11.63.3.07.1.15.26.09.47l-1.52 4.94-.4 1.29h5.58c.41 0 .8.17 1.03.46.13.15.26.4.19.71z")
    val likeFill = icon("likeFill", "M3 11h3v10H3V11zm15.77 0h-4.23l1.52-4.94C16.38 5.03 15.54 4 14.38 4c-.58 0-1.14.24-1.52.65L7 11v10h10.43c1.06 0 1.98-.67 2.19-1.61l1.34-6c.27-1.24-.78-2.39-2.19-2.39z")
    val share = icon("share", "M15 5.63 20.66 12 15 18.37V14h-1c-3.96 0-7.14 1-9.75 3.09 1.84-4.07 5.11-6.4 9.89-7.1l.86-.13V5.63M14 3v6C6.22 10.13 3.11 15.33 2 21c2.78-3.97 6.44-6 12-6v6l8-9-8-9z")
    val play = icon("play", "M8 5.14v13.72c0 .79.87 1.27 1.54.84l10.79-6.86c.62-.39.62-1.29 0-1.69L9.54 4.3C8.87 3.87 8 4.35 8 5.14z")
    val playlists = icon("playlists", "M22 7H2v1h20V7zm-9 5H2v-1h11v1zm0 4H2v-1h11v1zm2 3v-8l7 4-7 4z")
    val back = icon("back", "M21 11v1H5.64l6.72 6.72-.71.71-7.93-7.93 7.92-7.92.71.71L5.64 11H21z")
    val expand = icon("expand", "M12 15.7 5.6 9.4l.7-.7 5.7 5.6 5.7-5.6.7.7z")
    val collapse = icon("collapse", "M18.4 14.6 12 8.3l-6.4 6.3.8.8L12 9.7l5.7 5.7z")
    val settings = icon("settings", "M12 9.5c1.38 0 2.5 1.12 2.5 2.5s-1.12 2.5-2.5 2.5-2.5-1.12-2.5-2.5 1.12-2.5 2.5-2.5m0-1c-1.93 0-3.5 1.57-3.5 3.5s1.57 3.5 3.5 3.5 3.5-1.57 3.5-3.5-1.57-3.5-3.5-3.5zM13.22 3l.55 2.2.13.51.5.18c.61.23 1.19.56 1.72.98l.4.32.5-.14 2.17-.62 1.22 2.11-1.63 1.59-.37.36.08.51c.05.32.08.64.08.98s-.03.66-.08.98l-.08.51.37.36 1.63 1.59-1.22 2.11-2.17-.62-.5-.14-.4.32c-.53.43-1.11.76-1.72.98l-.5.18-.13.51-.55 2.24h-2.44l-.55-2.2-.13-.51-.5-.18c-.6-.23-1.18-.56-1.72-.99l-.4-.32-.5.14-2.17.62-1.21-2.12 1.63-1.59.37-.36-.08-.51c-.05-.32-.08-.65-.08-.98s.03-.66.08-.98l.08-.51-.37-.36L3.6 8.56l1.22-2.11 2.17.62.5.14.4-.32c.53-.44 1.11-.77 1.72-.99l.5-.18.13-.51.54-2.21h2.44M14 2h-4l-.74 2.96c-.73.27-1.4.66-2 1.14l-2.92-.83-2 3.46 2.19 2.13c-.06.37-.09.75-.09 1.14s.03.77.09 1.14l-2.19 2.13 2 3.46 2.92-.83c.6.48 1.27.87 2 1.14L10 22h4l.74-2.96c.73-.27 1.4-.66 2-1.14l2.92.83 2-3.46-2.19-2.13c.06-.37.09-.75.09-1.14s-.03-.77-.09-1.14l2.19-2.13-2-3.46-2.92.83c-.6-.48-1.27-.87-2-1.14L14 2z")
    val comment = icon("comment", "M8 7h8v2H8zm0 4h5v2H8zm12-8H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 14H5.17L4 18.17V5h16v12z")
}

// ------------------------------------------------------------------ shared pieces

@Composable
fun Thumb(
    model: Any?,
    modifier: Modifier = Modifier,
    radius: Dp = 12.dp,
    duration: Long = 0,
    watched: Float = 0f,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val c = LocalYc.current
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(radius)).background(c.thumb)) {
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (duration > 0) {
            Text(
                Fmt.time(duration), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp, 6.dp, 6.dp, if (watched > 0f) 9.dp else 6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (watched > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x99AAAAAA))) {
                Box(Modifier.fillMaxWidth(watched.coerceIn(0f, 1f)).height(3.dp).background(c.red))
            }
        }
        overlay()
    }
}

fun thumbFile(dir: File): File? = File(dir, "thumb.jpg").takeIf { it.exists() }

@Composable
fun Avatar(file: File?, name: String, size: Dp = 36.dp) {
    if (file != null && file.exists()) {
        AsyncImage(model = file, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape))
    } else {
        val n = name.removePrefix("@").ifEmpty { "?" }
        var hash = 0
        for (ch in n) hash = hash * 31 + ch.code
        val hue = (Math.floorMod(hash, 360)).toFloat()
        Box(
            Modifier.size(size).clip(CircleShape).background(Color.hsl(hue, 0.45f, 0.45f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(n.first().uppercase(), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun Pill(
    text: String?,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalYc.current
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.chip).clickable(onClick = onClick)
            .padding(start = if (icon != null) 12.dp else 16.dp, end = if (text != null) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
        if (icon != null && text != null) Spacer(Modifier.width(6.dp))
        if (text != null) Text(text, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = LocalYc.current
    Box(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) c.chipOn else c.chip)
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) c.chipOnText else c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
