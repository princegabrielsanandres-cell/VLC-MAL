package org.videolan.vlc

import java.util.regex.Pattern

data class ParsedEpisode(
    val title: String,
    val season: Int?,
    val episode: Int
)

object MalSync {

    /*
     * Supported formats:
     *
     * Re Zero S1E01
     * Re Zero S1-E01
     * Re Zero S01E01
     * Re Zero S01-E01
     *
     * All of the above:
     * -> title = Re Zero
     * -> season = 1
     * -> episode = 1
     *
     * Also supports:
     *
     * Re Zero S2E01
     * Re Zero S2-E01
     * Re Zero S02E01
     * Re Zero S02-E01
     *
     * -> season = 2
     * -> episode = 1
     */

    private val seasonEpisodePattern = Pattern.compile(
        "(?i)^(.*?)[ ._-]*S(\\d{1,2})[ ._-]*E(\\d{1,4}).*$"
    )

    /*
     * Examples:
     *
     * Re Zero Episode 01
     * Re Zero Ep 01
     * Re Zero EP01
     * Re Zero E01
     */
    private val episodePattern = Pattern.compile(
        "(?i)^(.*?)[ ._-]+(?:EP?|Episode)[ ._-]?(\\d{1,4}).*$"
    )

    /*
     * Fallback:
     *
     * Re Zero 01
     *
     * No season information is available here.
     */
    private val numberPattern = Pattern.compile(
        "(?i)^(.*?)[ ._-]+(\\d{1,4}).*$"
    )

    fun parse(fileName: String): ParsedEpisode? {

        // Remove the file extension first.
        val cleanName = fileName
            .substringBeforeLast(".")
            .trim()

        /*
         * ---------------------------------------------------------
         * SxxExx / Sxx-Exx
         * ---------------------------------------------------------
         */
        val seasonMatch = seasonEpisodePattern.matcher(cleanName)

        if (seasonMatch.matches()) {
            val title = seasonMatch.group(1)
                ?.trim()
                ?.replace(Regex("[._-]+$"), "")
                ?.trim()
                ?: return null

            val season = seasonMatch.group(2)?.toIntOrNull()
                ?: return null

            val episode = seasonMatch.group(3)?.toIntOrNull()
                ?: return null

            if (title.isEmpty()) return null

            return ParsedEpisode(
                title = cleanTitle(title),
                season = season,
                episode = episode
            )
        }

        /*
         * ---------------------------------------------------------
         * Episode / EP / E
         * ---------------------------------------------------------
         */
        val episodeMatch = episodePattern.matcher(cleanName)

        if (episodeMatch.matches()) {
            val title = episodeMatch.group(1)
                ?.trim()
                ?.replace(Regex("[._-]+$"), "")
                ?.trim()
                ?: return null

            val episode = episodeMatch.group(2)?.toIntOrNull()
                ?: return null

            if (title.isEmpty()) return null

            return ParsedEpisode(
                title = cleanTitle(title),
                season = null,
                episode = episode
            )
        }

        /*
         * ---------------------------------------------------------
         * Plain episode number fallback
         * ---------------------------------------------------------
         */
        val numberMatch = numberPattern.matcher(cleanName)

        if (numberMatch.matches()) {
            val title = numberMatch.group(1)
                ?.trim()
                ?.replace(Regex("[._-]+$"), "")
                ?.trim()
                ?: return null

            val episode = numberMatch.group(2)?.toIntOrNull()
                ?: return null

            if (title.isEmpty()) return null

            return ParsedEpisode(
                title = cleanTitle(title),
                season = null,
                episode = episode
            )
        }

        return null
    }

    /*
     * Cleans common filename separators from the anime title.
     *
     * Example:
     *
     * "Re.Zero" -> "Re Zero"
     * "Re_Zero" -> "Re Zero"
     */
    private fun cleanTitle(title: String): String {
        return title
            .replace(".", " ")
            .replace("_", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /*
     * Used by the current VLC playback debugging code.
     */
    fun onMediaStarted(fileName: String): String {

        val parsed = parse(fileName)
            ?: return "MAL SYNC: Could not parse\n$fileName"

        val seasonText = parsed.season?.toString() ?: "?"

        return "MAL SYNC: ${parsed.title} S${seasonText}E${parsed.episode}"
    }
}
