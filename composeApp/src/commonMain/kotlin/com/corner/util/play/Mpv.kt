package com.corner.util.play

import com.corner.catvodcore.bean.Result
import com.corner.catvodcore.bean.v
import com.corner.catvodcore.util.Paths

/**
 * Mpv player command implementation.
 * Launches the MPV executable with the provided URL.
 */
object Mpv : PlayerCommand {

    // Allow optional extra mpv args via playerPath field suffix.
    // Examples:
    //  - "C:\\Program Files\\mpv\\mpv.exe" --no-ytdl
    //  - mpv.exe
    private fun splitExeAndArgs(rawPlayerPath: String): Pair<String, List<String>> {
        val s = rawPlayerPath.trim()
        if (s.isBlank()) return "" to emptyList()

        // If executable path is quoted, split by the closing quote.
        if (s.startsWith("\"")) {
            val end = s.indexOf('"', startIndex = 1)
            if (end > 1) {
                val exe = s.substring(1, end).trim()
                val rest = s.substring(end + 1).trim()
                val args = if (rest.isBlank()) emptyList() else rest.split(Regex("\\s+")).filter { it.isNotBlank() }
                return exe to args
            }
        }

        // Otherwise, split by first whitespace.
        val firstWs = s.indexOfFirst { it.isWhitespace() }
        return if (firstWs < 0) {
            s to emptyList()
        } else {
            val exe = s.substring(0, firstWs).trim()
            val rest = s.substring(firstWs).trim()
            val args = if (rest.isBlank()) emptyList() else rest.split(Regex("\\s+")).filter { it.isNotBlank() }
            exe to args
        }
    }

    private fun buildCommand(rawPlayerPath: String, urlArg: String): List<String> {
        val (exe, args) = splitExeAndArgs(rawPlayerPath)
        return listOf(exe) + args + urlArg
    }

    // MPV does not use a title argument in the same way as other players, but we keep the method for interface compatibility.
    override fun title(title: String): String = title
    override fun start(time: String): String = time
    override fun subtitle(s: String): String = s

    override fun getProcessBuilder(result: Result, title: String, playerPath: String): ProcessBuilder {
        val urlArg = url(result.url.v())
        return ProcessBuilder(buildCommand(playerPath, urlArg)).redirectOutput(Paths.playerLog())
    }

    override fun getProcessBuilder(url: String, title: String, playerPath: String): ProcessBuilder {
        val urlArg = url(url)
        return ProcessBuilder(buildCommand(playerPath, urlArg)).redirectOutput(Paths.playerLog())
    }
}
