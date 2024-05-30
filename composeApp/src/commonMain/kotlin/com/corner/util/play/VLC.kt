package com.corner.util.play

import cn.hutool.http.Header
import com.corner.catvodcore.bean.Result
import com.corner.catvodcore.bean.v

object VLC : PlayerCommand {
    override fun title(title: String): String {
        return String.format("--video-title=%s", title)
    }

    override fun start(time: String): String {
        return String.format("--start-time=%s", time)
    }

    override fun subtitle(s: String): String {
        return String.format("--sub-file=%s", s)
    }

    override fun getProcessBuilder(result: Result, title: String, playerPath: String): ProcessBuilder {
        return ProcessBuilder(
            playerPath, title(title), /*"--playlist-tree",*/
            buildHeaderStr(result.header), url(result.url.v())
        )
    }

    override fun buildHeaderStr(headers: Map<String, String>?): String {
        if (headers != null) {
            return String.format(
                "--http-referrer=%s   --http-user-agent=%s",
                headers[Header.USER_AGENT.value],
                headers[Header.REFERER.value]
            )
        };
        return "";
    }
}