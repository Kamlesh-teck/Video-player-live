package com.example.iptvplayer

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.URL

data class Channel(val name: String, val url: String, val group: String)

val groupRegex = Regex("group-title=\"([^\"]*)\"")

fun parseM3u(text: String): List<Channel> {
    val list = mutableListOf<Channel>()
    var name: String? = null
    var group = ""
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF")) {
            name = line.substringAfterLast(",").trim()
            group = groupRegex.find(line)?.groupValues?.get(1)?.trim() ?: ""
        } else if (line.isNotEmpty() && !line.startsWith("#")) {
            list.add(Channel(if (name.isNullOrBlank()) line else name, line, group))
            name = null
            group = ""
        }
    }
    return list
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { IptvApp() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvApp() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val prefs = remember { context.getSharedPreferences("iptv", Context.MODE_PRIVATE) }
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var link by remember { mutableStateOf(prefs.getString("link", "") ?: "") }
    var channels by remember { mutableStateOf(listOf<Channel>()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var status by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf("All") }
    var favs by remember {
        mutableStateOf((prefs.getStringSet("favs", emptySet()) ?: emptySet()).toSet())
    }

    fun toggleFav(url: String) {
        val updated = if (url in favs) favs - url else favs + url
        favs = updated
        prefs.edit().putStringSet("favs", updated.toSet()).apply()
    }

    fun loadLink() {
        val url = link.trim()
        if (url.isEmpty()) return
        prefs.edit().putString("link", url).apply()
        status = "Load ho raha hai..."
        Thread {
            try {
                val result = parseM3u(URL(url).readText())
                channels = result
                status = "${result.size} channels mile"
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            }
        }.start()
    }

    LaunchedEffect(Unit) { loadLink() }

    LaunchedEffect(selected) {
        selected?.let {
            player.setMediaItem(MediaItem.fromUri(it.url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    LaunchedEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = fullscreen) { fullscreen = false }

    if (fullscreen && selected != null) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxSize()
            )
            Button(
                onClick = { fullscreen = false },
                modifier = Modifier.padding(16.dp)
            ) { Text("Exit fullscreen") }
        }
        return
    }

    val groups = listOf("All", "Favorites") +
        channels.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()

    val filtered = channels.filter { ch ->
        val inGroup = when (group) {
            "All" -> true
            "Favorites" -> ch.url in favs
            else -> ch.group == group
        }
        inGroup && ch.name.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        if (selected != null) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(selected!!.name, Modifier.weight(1f).padding(top = 12.dp))
                Button(onClick = { fullscreen = true }) { Text("Fullscreen") }
            }
        }

        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("M3U link yahan daalo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { loadLink() },
            modifier = Modifier.padding(vertical = 8.dp)
        ) { Text("Load karo") }

        Text(status)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Channel search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        )

        LazyRow(Modifier.padding(bottom = 8.dp)) {
            items(groups) { g ->
                FilterChip(
                    selected = group == g,
                    onClick = { group = g },
                    label = { Text(if (g == "Favorites") "★ Favorites" else g) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        LazyColumn {
            items(filtered) { ch ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = ch }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(ch.name, Modifier.weight(1f).padding(6.dp))
                    Text(
                        if (ch.url in favs) "★" else "☆",
                        Modifier.clickable { toggleFav(ch.url) }.padding(12.dp)
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
