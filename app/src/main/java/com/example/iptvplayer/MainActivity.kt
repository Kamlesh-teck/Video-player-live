package com.example.iptvplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File
import java.net.URL

data class Channel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String
)

val groupRegex = Regex("group-title=\"([^\"]*)\"")
val logoRegex = Regex("tvg-logo=\"([^\"]*)\"")

fun parseM3u(text: String): List<Channel> {
    val list = mutableListOf<Channel>()
    var name: String? = null
    var group = ""
    var logo = ""
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.startsWith("#EXTINF")) {
            name = line.substringAfterLast(",").trim()
            group = groupRegex.find(line)?.groupValues?.get(1)?.trim() ?: ""
            logo = logoRegex.find(line)?.groupValues?.get(1)?.trim() ?: ""
        } else if (line.isNotEmpty() && !line.startsWith("#")) {
            list.add(Channel(if (name.isNullOrBlank()) line else name, line, group, logo))
            name = null
            group = ""
            logo = ""
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
    val recorder = remember { Recorder() }
    val recDir = remember {
        (context.getExternalFilesDir("Recordings") ?: context.filesDir).also { it.mkdirs() }
    }

    var buffering by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf<String?>(null) }
    var retryCount by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    playError = null
                    retryCount = 0
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playError = "Stream error: ${error.errorCodeName}"
                retryCount++
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var link by remember { mutableStateOf(prefs.getString("link", "") ?: "") }
    var channels by remember { mutableStateOf(listOf<Channel>()) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var status by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var fullscreen by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf("All") }
    var tab by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableStateOf(0) }
    var recName by remember { mutableStateOf("") }
    var currentRecFile by remember { mutableStateOf<File?>(null) }
    var recFiles by remember { mutableStateOf(listOf<File>()) }
    var favs by remember {
        mutableStateOf((prefs.getStringSet("favs", emptySet()) ?: emptySet()).toSet())
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri: Uri? ->
        if (uri != null) {
            val list = channels.filter { it.url in favs }
            val sb = StringBuilder("#EXTM3U\n")
            for (c in list) {
                val g = c.group.replace("\"", "'")
                val l = c.logo.replace("\"", "'")
                sb.append("#EXTINF:-1 tvg-logo=\"$l\" group-title=\"$g\",${c.name}\n")
                sb.append(c.url).append("\n")
            }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            Thread {
                try {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    val result = parseM3u(text)
                    File(context.filesDir, "local.m3u").writeText(text)
                    prefs.edit().putBoolean("useFile", true).apply()
                    channels = result
                    status = "File se ${result.size} channels mile"
                } catch (e: Exception) {
                    status = "File error: ${e.message}"
                }
            }.start()
        }
                }
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(sb.toString().toByteArray())
                }
                status = "${list.size} favorites export ho gaye"
            } catch (e: Exception) {
                status = "Export error: ${e.message}"
            }
        }
        }

    fun refreshRecs() {
        recFiles = (recDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "ts" }
            .sortedByDescending { it.lastModified() }
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
        prefs.edit().putBoolean("useFile", false).apply()
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

    fun toggleRecord() {
        val ch = selected ?: return
        if (recording) {
            recorder.stop()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val safe = ch.name.replace(Regex("[^A-Za-z0-9]+"), "_").take(30)
        val file = File(recDir, "${safe}_${System.currentTimeMillis()}.ts")
        recName = ch.name
        currentRecFile = file
        recording = true
        ContextCompat.startForegroundService(
            context, Intent(context, RecordService::class.java)
        )
        recorder.start(ch.url, file) { error ->
            recording = false
            currentRecFile = null
            context.stopService(Intent(context, RecordService::class.java))
            status = if (error == null) {
                "Saved: ${file.name}"
            } else {
                "Recording error: $error"
            }
        }
    }

    fun playFile(f: File) {
        selected = Channel(f.name, Uri.fromFile(f).toString(), "", "")
    }

    fun retryNow() {
        val ch = selected ?: return
        playError = null
        retryCount = 0
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare()
        player.playWhenReady = true
    }

     LaunchedEffect(Unit) {
        val f = File(context.filesDir, "local.m3u")
        if (prefs.getBoolean("useFile", false) && f.exists()) {
            channels = parseM3u(f.readText())
            status = "${channels.size} channels (saved file)"
        } else {
            loadLink()
        }
        }

    LaunchedEffect(selected) {
        selected?.let {
            playError = null
            retryCount = 0
            player.setMediaItem(MediaItem.fromUri(it.url))
            player.prepare()
            player.playWhenReady = true
        }
    }

    LaunchedEffect(retryCount) {
        if (playError != null && retryCount in 1..3) {
            delay(3000)
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

    fun move(delta: Int) {
        if (filtered.isEmpty()) return
        val i = filtered.indexOfFirst { it.url == selected?.url }
        val n = if (i < 0) 0 else (i + delta + filtered.size) % filtered.size
        selected = filtered[n]
    }

    val recLabel = if (recording) {
        "⏹ %02d:%02d".format(recSeconds / 60, recSeconds % 60)
    } else {
        "⏺ Rec"
    }

    val errorText = when {
        playError == null -> null
        retryCount in 1..3 -> "Dobara koshish ($retryCount/3)..."
        else -> "Stream nahi chal raha"
    }
    val showRetryButton = playError != null && retryCount > 3

    if (fullscreen && selected != null) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxSize()
            )
            if (buffering) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { fullscreen = false }) { Text("Exit") }
                Button(onClick = { move(-1) }) { Text("⏮") }
                Button(onClick = { move(1) }) { Text("⏭") }
                Button(onClick = { toggleRecord() }) { Text(recLabel) }
            }
            if (errorText != null) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(errorText)
                    if (showRetryButton) {
                        Button(onClick = { retryNow() }) { Text("Retry") }
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)) {
        if (selected != null) {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                AndroidView(
                    factory = { PlayerView(it).apply { this.player = player } },
                    modifier = Modifier.fillMaxSize()
                )
                if (buffering) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
            }
            Text(selected!!.name, Modifier.padding(vertical = 4.dp))
            if (errorText != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(errorText)
                    if (showRetryButton) {
                        Button(
                            onClick = { retryNow() },
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) { Text("Retry") }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { move(-1) },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("⏮") }
                Button(
                    onClick = { toggleRecord() },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(recLabel) }
                Button(
                    onClick = { move(1) },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("⏭") }
                Button(
                    onClick = { fullscreen = true },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("Full") }
            }
            if (recording) {
                Text("Recording: $recName")
            }
        }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Channels") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Recordings") })
        }

        Text(status, Modifier.padding(vertical = 4.dp))

        if (tab == 0) {
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
                Button(
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.padding(bottom = 8.dp)
            ) { Text("📁 File se load karo (M3U)") }
            if (favs.isNotEmpty()) {
                Button(
                    onClick = { exportLauncher.launch("favorites.m3u") },
                    modifier = Modifier.padding(bottom = 8.dp)
                ) { Text("★ Favorites export (M3U)") }
            }

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
                        if (ch.logo.isNotBlank()) {
                            AsyncImage(
                                model = ch.logo,
                                contentDescription = ch.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(48.dp).padding(end = 8.dp)
                            )
                        } else {
                            Spacer(Modifier.size(48.dp))
                        }
                        Text(ch.name, Modifier.weight(1f).padding(6.dp))
                        Text(
                            if (ch.url in favs) "★" else "☆",
                            Modifier.clickable { toggleFav(ch.url) }.padding(12.dp)
                        )
                    }
                    HorizontalDivider()
                }
            }
        } else {
            if (recFiles.isEmpty()) {
                Text("Abhi koi recording nahi hai", Modifier.padding(16.dp))
            }
            LazyColumn {
                items(recFiles) { f ->
                    val isActive = recording && f == currentRecFile
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { playFile(f) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(6.dp)) {
                            Text(f.name)
                            Text(
                                if (isActive) "Recording chal rahi hai..."
                                else "%.1f MB".format(f.length() / 1048576.0)
                            )
                        }
                        TextButton(
                            onClick = {
                                f.delete()
                                refreshRecs()
                            },
                            enabled = !isActive
                        ) { Text("Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
