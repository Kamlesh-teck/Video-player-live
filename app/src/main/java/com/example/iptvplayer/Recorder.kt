package com.example.iptvplayer

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class Recorder {
    @Volatile
    private var running = false

    fun start(url: String, outFile: File, onDone: (String?) -> Unit) {
        if (running) return
        running = true
        Thread {
            var error: String? = null
            try {
                FileOutputStream(outFile).use { out ->
                    if (url.contains(".m3u8", ignoreCase = true)) {
                        recordHls(url, out)
                    } else {
                        recordDirect(url, out)
                    }
                }
            } catch (e: Exception) {
                if (running) error = e.message ?: "Unknown error"
            } finally {
                running = false
                onDone(error)
            }
        }.start()
    }

    fun stop() {
        running = false
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return c
    }

    private fun recordDirect(url: String, out: FileOutputStream) {
        val c = open(url)
        c.inputStream.use { input ->
            val buf = ByteArray(16384)
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        c.disconnect()
    }

    private fun lowestVariant(lines: List<String>): String? {
        var best: String? = null
        var bestBw = Long.MAX_VALUE
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])
                    ?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE
                val u = lines.drop(i + 1)
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (u != null && (best == null || bw < bestBw)) {
                    best = u
                    bestBw = bw
                }
            }
        }
        return best
    }

    private fun recordHls(startUrl: String, out: FileOutputStream) {
        var playlistUrl = startUrl
        val seen = HashSet<String>()
        while (running) {
            val text = open(playlistUrl).inputStream.bufferedReader().use { it.readText() }
            val lines = text.lines().map { it.trim() }

            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                val next = lowestVariant(lines) ?: throw Exception("Stream variant nahi mila")
                playlistUrl = URI(playlistUrl).resolve(next).toString()
                continue
            }

            if (lines.any { it.startsWith("#EXT-X-KEY") && it.contains("METHOD=AES") }) {
                throw Exception("Encrypted stream record nahi hoga")
            }

            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 4

            for (l in lines) {
                if (!running) break
                if (l.isEmpty() || l.startsWith("#")) continue
                val segUrl = URI(playlistUrl).resolve(l).toString()
                if (seen.add(segUrl)) {
                    val c = open(segUrl)
                    c.inputStream.use { it.copyTo(out) }
                    c.disconnect()
                }
            }

            if (lines.any { it.startsWith("#EXT-X-ENDLIST") }) break

            var waited = 0
            while (running && waited < target * 500) {
                Thread.sleep(100)
                waited += 100
            }
        }
    }
}
