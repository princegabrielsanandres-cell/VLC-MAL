package org.videolan.vlc

import java.util.regex.Pattern

data class ParsedEpisode(
    val title: String,
    val season: Int?,
    val episode: Int
)

object MalSync {

    private val patterns = listOf(
        Pattern.compile(
            "(?i)^(.*?)[ ._-]*S(\\d{1,2})E(\\d{1,4}).*\\.(mkv|mp4|webm)$"
        ),
        Pattern.compile(
            "(?i)^(.*?)[ ._-]+(?:EP?|Episode)[ ._-]?(\\d{1,4}).*\\.(mkv|mp4|webm)$"
        ),
        Pattern.compile(
            "(?i)^(.*?)[ ._-]+(\\d{1,4}).*\\.(mkv|mp4|webm)$"
        )
    )

    fun parse(fileName: String): ParsedEpisode? {
        for ((index, pattern) in patterns.withIndex()) {
            val match = pattern.matcher(fileName.trim())

            if (match.matches()) {
                return if (index == 0) {
                    ParsedEpisode(
                        match.group(1).trim(),
                        match.group(2).toInt(),
                        match.group(3).toInt()
                    )
                } else {
                    ParsedEpisode(
                        match.group(1).trim(),
                        null,
                        match.group(2).toInt()
                    )
                }
            }
        }

        return null
    }

        fun onMediaStarted(fileName: String) {
    val parsed = parse(fileName) ?: return

    android.util.Log.d(
        "MalSync",
        "MAL SYNC DETECTED: title=${parsed.title}, season=${parsed.season}, episode=${parsed.episode}"
    )
        }
