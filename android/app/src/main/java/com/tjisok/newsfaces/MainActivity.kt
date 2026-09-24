package com.tjisok.newsfaces

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

val Bg = Color(0xFF0B0B0E); val Panel = Color(0xFF15151B); val Panel2 = Color(0xFF1D1D25); val Line = Color(0xFF2A2A34)
val Fg = Color(0xFFF2F2F5); val Muted = Color(0xFF8E8EA0); val Accent = Color(0xFF0A84FF)

lateinit var app: AppState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!::app.isInitialized) app = AppState(applicationContext)
        app.startServer()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel, onSurface = Fg, onBackground = Fg, surfaceVariant = Panel2, outline = Line)) {
                Root()
            }
        }
    }
}

@Composable
fun Root() {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val mode by app.mode.collectAsStateWithLifecycle()
    val viewer by app.viewer.collectAsStateWithLifecycle()
    val settings by app.settings.collectAsStateWithLifecycle()
    val hud by app.hud.collectAsStateWithLifecycle()
    var zoomRequest by remember { mutableStateOf(0) } // +1 zoom in, -1 zoom out, consumed by the active screen
    val immersive = mode == "face" && !hud
    // Face mode hides every piece of UI, including the system bars; a long-press brings the controls back
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(immersive) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val c = androidx.core.view.WindowCompat.getInsetsController(window, view)
        c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = !immersive, drawerContent = { ModalDrawerSheet(drawerContainerColor = Panel, modifier = Modifier.width(320.dp)) { MenuContent() } }) {
        Column(Modifier.fillMaxSize().background(Bg).then(if (immersive) Modifier else Modifier.statusBarsPadding())) {
            if (!immersive) {
                TopBar(mode = mode, sort = settings.sort, onMenu = { scope.launch { drawer.open() } },
                    onMode = { app.mode.value = it }, onSort = { app.update { s -> s.copy(sort = it) } },
                    onZoom = { zoomRequest = if (it > 0) zoomRequest + 1 else zoomRequest - 1 }, onRefresh = { app.refresh() })
                Spectrum()
            }
            Box(Modifier.weight(1f)) {
                if (mode == "face") FaceScreen(zoomRequest) else GalleryScreen(zoomRequest)
            }
        }
    }
    viewer?.let { PhotoViewer(it) { app.viewer.value = null } }
}

@Composable
fun TopBar(mode: String, sort: String, onMenu: () -> Unit, onMode: (String) -> Unit, onSort: (String) -> Unit, onZoom: (Int) -> Unit, onRefresh: () -> Unit) {
    val fetching by app.fetching.collectAsStateWithLifecycle()
    val analysing by app.analysing.collectAsStateWithLifecycle()
    val photos by app.photos.collectAsStateWithLifecycle()
    val states by app.feedState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onMenu, modifier = Modifier.size(36.dp).background(Panel2, RoundedCornerShape(10.dp))) { Icon(Icons.Default.Menu, "Menu", tint = Fg) }
            Segmented(listOf("gallery" to "Gallery", "face" to "Face"), mode, onMode, onColor = Color.White, onText = Color.Black)
            Spacer(Modifier.weight(1f))
            Row(Modifier.background(Panel2, RoundedCornerShape(10.dp))) {
                IconButton(onClick = { onZoom(1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Add, "Zoom in", tint = Muted) }
                IconButton(onClick = { onZoom(-1) }, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Remove, "Zoom out", tint = Muted) }
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp).background(Panel2, RoundedCornerShape(10.dp))) { Icon(Icons.Default.Refresh, "Refresh", tint = if (fetching) Accent else Fg) }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Segmented(listOf("newest" to "Newest", "hue" to "Colour", "light" to "Light", "sat" to "Vivid", "warm" to "Warm", "source" to "Source"), sort, onSort)
            }
        }
        val loading = states.values.count { it == FeedState.LOADING }; val failed = states.values.count { it == FeedState.FAIL }
        val parts = mutableListOf("${photos.size} photos")
        if (loading > 0) parts += "loading $loading feed${if (loading > 1) "s" else ""}…"
        if (failed > 0) parts += "$failed unavailable"
        if (analysing.first < analysing.second) parts += "analysing colours ${analysing.first}/${analysing.second}"
        Text(parts.joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit, onColor: Color = Accent, onText: Color = Color.White) {
    Row(Modifier.background(Panel2, RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((k, label) in options) {
            val on = k == value
            Text(label, color = if (on) onText else Muted, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.background(if (on) onColor else Color.Transparent, RoundedCornerShape(7.dp)).clickableNoRipple { onChange(k) }.padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}
