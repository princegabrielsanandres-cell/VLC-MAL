package org.videolan.vlc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.videolan.tools.Settings
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.LocalDate

object MalApi {

    private const val API_BASE =
        "https://api.myanimelist.net/v2"

    /*
     * -------------------------------------------------------------
     * Update the MAL entry for the anime/episode being played.
     *
     * season is optional so existing PlaybackService code will
     * continue to compile.
     * -------------------------------------------------------------
     */

    suspend fun updateEpisode(
        context: Context,
        title: String,
        episode: Int,
        season: Int? = null
    ): String = withContext(Dispatchers.IO) {

        val settings = Settings.getInstance(context)

        val accessToken =
            settings.getString(
                "mal_access_token",
                null
            ) ?: ""

        if (accessToken.isEmpty()) {
            return@withContext "MAL: Not logged in"
        }

        /*
         * ---------------------------------------------------------
         * 1. Search MAL
         * ---------------------------------------------------------
         */

        val encodedTitle =
            URLEncoder.encode(
                title,
                "UTF-8"
            )

        val searchUrl =
            "$API_BASE/anime?q=$encodedTitle&limit=20"

        val searchResult =
            request(
                url = searchUrl,
                method = "GET",
                accessToken = accessToken
            )

        if (!searchResult.success) {
            return@withContext(
                "MAL: Anime search failed (${searchResult.code})"
            )
        }

        val searchJson =
            JSONObject(searchResult.body)

        val data =
            searchJson.optJSONArray("data")

        if (data == null || data.length() == 0) {
            return@withContext(
                "MAL: Anime not found: $title"
            )
        }

        /*
         * ---------------------------------------------------------
         * 2. Find the correct MAL season
         * ---------------------------------------------------------
         *
         * MAL normally stores different seasons as different
         * anime entries.
         *
         * Example:
         *
         * Re:Zero Season 1 -> one MAL ID
         * Re:Zero Season 2 -> another MAL ID
         * Re:Zero Season 3 -> another MAL ID
         *
         * Therefore we cannot simply take the first search result.
         * ---------------------------------------------------------
         */

        val normalizedTitle =
            normalizeTitle(title)

        var animeId = 0
        var matchedTitle = title

        var bestScore = Int.MIN_VALUE

        for (i in 0 until data.length()) {

            val node =
                data.optJSONObject(i)
                    ?.optJSONObject("node")
                    ?: continue

            val malTitle =
                node.optString(
                    "title",
                    ""
                )

            val id =
                node.optInt(
                    "id",
                    0
                )

            if (malTitle.isEmpty() || id == 0) {
                continue
            }

            /*
             * Calculate how well this MAL result matches
             * our filename.
             */
            val score =
                calculateSeasonMatchScore(
                    filenameTitle = normalizedTitle,
                    malTitle = malTitle,
                    requestedSeason = season
                )

            if (score > bestScore) {

                bestScore = score
                animeId = id
                matchedTitle = malTitle
            }
        }

        if (animeId == 0) {
            return@withContext(
                "MAL: Could not find matching anime"
            )
        }

        /*
         * ---------------------------------------------------------
         * 3. Check the user's MAL list
         * ---------------------------------------------------------
         */

        val encodedMatchedTitle =
            URLEncoder.encode(
                matchedTitle,
                "UTF-8"
            )

        val userListUrl =
            "$API_BASE/users/@me/animelist" +
                "?q=$encodedMatchedTitle" +
                "&limit=100" +
                "&fields=list_status"

        val userListResult =
            request(
                url = userListUrl,
                method = "GET",
                accessToken = accessToken
            )

        if (!userListResult.success) {
            return@withContext(
                "MAL: List check failed (${userListResult.code})"
            )
        }

        val userListJson =
            JSONObject(userListResult.body)

        val userData =
            userListJson.optJSONArray("data")

        var foundInList = false

        var currentEpisode = 0

        var currentStatus = ""

        var currentStartDate = ""

        /*
         * Find the exact MAL anime ID in the user's list.
         */

        if (userData != null) {

            for (i in 0 until userData.length()) {

                val item =
                    userData.optJSONObject(i)
                        ?: continue

                val node =
                    item.optJSONObject("node")
                        ?: continue

                val listAnimeId =
                    node.optInt(
                        "id",
                        0
                    )

                if (listAnimeId != animeId) {
                    continue
                }

                foundInList = true

                val listStatus =
                    item.optJSONObject(
                        "list_status"
                    )

                if (listStatus != null) {

                    currentEpisode =
                        listStatus.optInt(
                            "num_episodes_watched",
                            0
                        )

                    currentStatus =
                        listStatus.optString(
                            "status",
                            ""
                        )

                    currentStartDate =
                        listStatus.optString(
                            "start_date",
                            ""
                        )
                }

                break
            }
        }

        /*
         * ---------------------------------------------------------
         * 4. Determine the episode progress
         * ---------------------------------------------------------
         *
         * Never move MAL backwards.
         *
         * Example:
         *
         * MAL = E06
         * VLC = E05
         *
         * Keep MAL at E06.
         * ---------------------------------------------------------
         */

        val targetEpisode =
            if (foundInList) {
                maxOf(
                    currentEpisode,
                    episode
                )
            } else {
                episode
            }

        /*
         * ---------------------------------------------------------
         * 5. Determine status
         * ---------------------------------------------------------
         *
         * Starting playback means the anime should be Watching.
         *
         * We intentionally do not mark it Completed yet.
         * Completion will be added later when we implement
         * final-episode detection.
         * ---------------------------------------------------------
         */

        val targetStatus =
            "watching"

        /*
         * ---------------------------------------------------------
         * 6. Determine start date
         * ---------------------------------------------------------
         *
         * If MAL already has a start date:
         *
         *     KEEP IT
         *
         * Otherwise:
         *
         *     Set today's date.
         * ---------------------------------------------------------
         */

        val startDate =
            if (currentStartDate.isNotEmpty()) {
                currentStartDate
            } else {
                LocalDate.now().toString()
            }

        /*
         * ---------------------------------------------------------
         * 7. Build update body
         * ---------------------------------------------------------
         */

        val body =
            buildUpdateBody(
                status = targetStatus,
                episode = targetEpisode,
                startDate = startDate
            )

        /*
         * ---------------------------------------------------------
         * 8. Update MAL
         * ---------------------------------------------------------
         */

        val updateUrl =
            "$API_BASE/anime/$animeId/my_list_status"

        val updateResult =
            request(
                url = updateUrl,
                method = "PUT",
                accessToken = accessToken,
                body = body
            )

        if (!updateResult.success) {

            return@withContext(
                "MAL: Update failed (${updateResult.code})"
            )
        }

        /*
         * ---------------------------------------------------------
         * 9. Return useful debug information
         * ---------------------------------------------------------
         */

        return@withContext(
            if (foundInList) {

                "MAL: $matchedTitle → " +
                    "S${season ?: "?"}E$targetEpisode " +
                    "($targetStatus)"

            } else {

                "MAL: Started watching " +
                    "$matchedTitle → " +
                    "S${season ?: "?"}E$targetEpisode"
            }
        )
    }

    /*
     * -------------------------------------------------------------
     * Calculate how well a MAL result matches the requested season.
     * -------------------------------------------------------------
     */

    private fun calculateSeasonMatchScore(
        filenameTitle: String,
        malTitle: String,
        requestedSeason: Int?
    ): Int {

        val normalizedMalTitle =
            normalizeTitle(malTitle)

        var score = 0

        /*
         * Exact title match gets the strongest base score.
         */

        if (normalizedMalTitle == filenameTitle) {
            score += 100
        }

        /*
         * Partial title matching.
         */

        if (
            normalizedMalTitle.contains(
                filenameTitle
            )
        ) {
            score += 40
        }

        if (
            filenameTitle.contains(
                normalizedMalTitle
            )
        ) {
            score += 20
        }

        /*
         * If no season was detected, don't try to guess one.
         */

        if (requestedSeason == null) {
            return score
        }

        /*
         * ---------------------------------------------------------
         * Season matching.
         *
         * Examples:
         *
         * "2nd Season" -> Season 2
         * "Second Season" -> Season 2
         * "Season 2" -> Season 2
         * "S2" -> Season 2
         * ---------------------------------------------------------
         */

        val seasonNumber =
            requestedSeason.toString()

        val seasonMatches =
            listOf(
                "season $seasonNumber",
                "season$seasonNumber",
                "${seasonNumber}nd season",
                "${seasonNumber}ndseason",
                "${seasonNumber}rd season",
                "${seasonNumber}rdseason",
                "${seasonNumber}th season",
                "${seasonNumber}thseason",
                "s$seasonNumber",
                "s0$seasonNumber"
            )

        var hasRequestedSeason = false

        for (pattern in seasonMatches) {

            if (
                normalizedMalTitle.contains(
                    pattern
                )
            ) {
                hasRequestedSeason = true
                break
            }
        }

        if (hasRequestedSeason) {
            score += 100
        }

        /*
         * If MAL explicitly looks like another season,
         * penalize it.
         */

        val otherSeasonRegex =
            Regex(
                "(?:season|s)\\s*([0-9]+)"
            )

        val seasonMatch =
            otherSeasonRegex.find(
                normalizedMalTitle
            )

        if (seasonMatch != null) {

            val detectedSeason =
                seasonMatch.groupValues[1]
                    .toIntOrNull()

            if (
                detectedSeason != null &&
                detectedSeason != requestedSeason
            ) {
                score -= 100
            }
        }

        return score
    }

    /*
     * -------------------------------------------------------------
     * Build MAL update body.
     * -------------------------------------------------------------
     */

    private fun buildUpdateBody(
        status: String,
        episode: Int,
        startDate: String
    ): String {

        return buildString {

            append(
                "status=" +
                    URLEncoder.encode(
                        status,
                        "UTF-8"
                    )
            )

            append(
                "&num_watched_episodes=" +
                    URLEncoder.encode(
                        episode.toString(),
                        "UTF-8"
                    )
            )

            append(
                "&start_date=" +
                    URLEncoder.encode(
                        startDate,
                        "UTF-8"
                    )
            )
        }
    }

    /*
     * -------------------------------------------------------------
     * Normalize titles.
     * -------------------------------------------------------------
     */

    private fun normalizeTitle(
        title: String
    ): String {

        return title
            .lowercase()
            .replace(
                Regex("[._:_-]+"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    /*
     * -------------------------------------------------------------
     * HTTP result.
     * -------------------------------------------------------------
     */

    private data class RequestResult(
        val success: Boolean,
        val code: Int,
        val body: String
    )

    /*
     * -------------------------------------------------------------
     * HTTP request helper.
     * -------------------------------------------------------------
     */

    private fun request(
        url: String,
        method: String,
        accessToken: String,
        body: String? = null
    ): RequestResult {

        var connection: HttpURLConnection? = null

        return try {

            connection =
                URL(url).openConnection()
                    as HttpURLConnection

            connection.requestMethod =
                method

            connection.connectTimeout =
                15000

            connection.readTimeout =
                15000

            connection.setRequestProperty(
                "Authorization",
                "Bearer $accessToken"
            )

            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded"
            )

            if (body != null) {

                connection.doOutput = true

                connection.outputStream.use { output ->

                    output.write(
                        body.toByteArray(
                            Charsets.UTF_8
                        )
                    )
                }
            }

            val responseCode =
                connection.responseCode

            val stream =
                if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val responseBody =
                stream
                    ?.bufferedReader()
                    ?.use {
                        it.readText()
                    }
                    ?: ""

            RequestResult(
                success =
                    responseCode in 200..299,
                code = responseCode,
                body = responseBody
            )

        } catch (e: Exception) {

            RequestResult(
                success = false,
                code = -1,
                body =
                    e.message ?: ""
            )

        } finally {

            connection?.disconnect()
        }
    }
}
