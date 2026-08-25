package org.videolan.vlc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.videolan.tools.Settings
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.LocalDate

object MalApi {

    private const val API_BASE = "https://api.myanimelist.net/v2"

    private val episodeUpdateMutex = Mutex()

    data class UpdateResult(
        val message: String,
        val completed: Boolean,
        val animeTitle: String
    )

    suspend fun updateEpisode(
        context: Context,
        title: String,
        episode: Int,
        season: Int? = null
    ): UpdateResult {
        return episodeUpdateMutex.withLock {
            updateEpisodeLocked(context, title, episode, season)
        }
    }

    private suspend fun updateEpisodeLocked(
        context: Context,
        title: String,
        episode: Int,
        season: Int?
    ): UpdateResult = withContext(Dispatchers.IO) {

        val settings = Settings.getInstance(context)

        val accessToken = settings.getString(
            "mal_access_token",
            null
        ) ?: ""

        if (accessToken.isEmpty()) {
            return@withContext UpdateResult(
                "MAL: Not logged in",
                false,
                title
            )
        }

        val normalizedTitle = normalizeTitle(title)
        val encodedTitle = URLEncoder.encode(title, "UTF-8")

        /*
         * First search the user's MAL list. This keeps updates tied
         * to the actual MAL entry being tracked.
         */
        val userListUrl =
            "$API_BASE/users/@me/animelist" +
                "?q=$encodedTitle" +
                "&limit=100" +
                "&fields=list_status,num_episodes"

        val userListResult = request(
            userListUrl,
            "GET",
            accessToken
        )

        if (!userListResult.success) {
            return@withContext UpdateResult(
                "MAL: List check failed (${userListResult.code})",
                false,
                title
            )
        }

        val userData = JSONObject(userListResult.body)
            .optJSONArray("data")

        var animeId = 0
        var matchedTitle = title
        var totalEpisodes = 0

        var currentEpisode = 0
        var currentStatus = ""
        var currentStartDate = ""
        var currentFinishDate = ""

        var bestListScore = Int.MIN_VALUE

        if (userData != null) {
            for (i in 0 until userData.length()) {

                val item = userData.optJSONObject(i) ?: continue
                val node = item.optJSONObject("node") ?: continue

                val candidateId = node.optInt("id", 0)
                val candidateTitle = node.optString("title", "")

                if (
                    candidateId == 0 ||
                    candidateTitle.isEmpty()
                ) continue

                val candidateScore = calculateSeasonMatchScore(
                    normalizedTitle,
                    candidateTitle,
                    season
                )

                if (candidateScore <= bestListScore) continue

                bestListScore = candidateScore
                animeId = candidateId
                matchedTitle = candidateTitle
                totalEpisodes = node.optInt("num_episodes", 0)

                val listStatus = item.optJSONObject("list_status")

                if (listStatus != null) {
                    currentEpisode = listStatus.optInt(
                        "num_episodes_watched",
                        0
                    )

                    currentStatus = listStatus.optString(
                        "status",
                        ""
                    )

                    currentStartDate = listStatus.optString(
                        "start_date",
                        ""
                    )

                    currentFinishDate = listStatus.optString(
                        "finish_date",
                        ""
                    )
                }
            }
        }

        val foundInList = animeId != 0

        /*
         * If the anime isn't already in the user's list, search MAL.
         */
        if (!foundInList) {

            val searchUrl =
                "$API_BASE/anime" +
                    "?q=$encodedTitle" +
                    "&limit=20" +
                    "&fields=num_episodes"

            val searchResult = request(
                searchUrl,
                "GET",
                accessToken
            )

            if (!searchResult.success) {
                return@withContext UpdateResult(
                    "MAL: Anime search failed (${searchResult.code})",
                    false,
                    title
                )
            }

            val data = JSONObject(searchResult.body)
                .optJSONArray("data")

            if (
                data == null ||
                data.length() == 0
            ) {
                return@withContext UpdateResult(
                    "MAL: Anime not found: $title",
                    false,
                    title
                )
            }

            var bestScore = Int.MIN_VALUE

            for (i in 0 until data.length()) {

                val node = data.optJSONObject(i)
                    ?.optJSONObject("node")
                    ?: continue

                val candidateTitle = node.optString(
                    "title",
                    ""
                )

                val candidateId = node.optInt(
                    "id",
                    0
                )

                if (
                    candidateTitle.isEmpty() ||
                    candidateId == 0
                ) continue

                val candidateScore = calculateSeasonMatchScore(
                    normalizedTitle,
                    candidateTitle,
                    season
                )

                if (candidateScore > bestScore) {
                    bestScore = candidateScore
                    animeId = candidateId
                    matchedTitle = candidateTitle
                    totalEpisodes = node.optInt(
                        "num_episodes",
                        0
                    )
                }
            }
        }

        if (animeId == 0) {
            return@withContext UpdateResult(
                "MAL: Could not find matching anime",
                false,
                title
            )
        }

        /*
         * HARD PROTECTION AGAINST BACKWARDS PROGRESS.
         */
        if (
            foundInList &&
            episode < currentEpisode
        ) {
            return@withContext UpdateResult(
                "MAL: Kept episode $currentEpisode " +
                    "(VLC played E$episode)",
                false,
                matchedTitle
            )
        }

        /*
         * Already completed.
         */
        if (
            foundInList &&
            currentStatus == "completed" &&
            currentEpisode >= episode
        ) {
            return@withContext UpdateResult(
                "MAL: Already completed $matchedTitle " +
                    "at episode $currentEpisode",
                false,
                matchedTitle
            )
        }

        /*
         * Progress can only stay the same or increase.
         */
        val targetEpisode =
            if (foundInList) {
                maxOf(currentEpisode, episode)
            } else {
                episode
            }

        /*
         * Final episode.
         */
        val isFinalEpisode =
            totalEpisodes > 0 &&
                targetEpisode >= totalEpisodes

        /*
         * Start date:
         * - Preserve an existing date.
         * - Only create one when E1 is played.
         */
        val startDate =
            when {
                currentStartDate.isNotEmpty() ->
                    currentStartDate

                episode == 1 ->
                    LocalDate.now().toString()

                else ->
                    ""
            }

        /*
         * Finish date:
         * - Only create it on the final episode.
         * - Preserve an existing finish date.
         */
        val finishDate =
            if (isFinalEpisode) {
                if (currentFinishDate.isNotEmpty()) {
                    currentFinishDate
                } else {
                    LocalDate.now().toString()
                }
            } else {
                ""
            }

        val status =
            if (isFinalEpisode) {
                "completed"
            } else {
                "watching"
            }

        val body = buildUpdateBody(
            status = status,
            episode = targetEpisode,
            startDate = startDate,
            finishDate = finishDate
        )

        val updateUrl =
            "$API_BASE/anime/$animeId/my_list_status"

        val updateResult = request(
            updateUrl,
            "PUT",
            accessToken,
            body
        )

        if (!updateResult.success) {
            return@withContext UpdateResult(
                "MAL: Update failed (${updateResult.code})",
                false,
                matchedTitle
            )
        }

        /*
         * PlaybackService uses completed=true to show
         * the rating dialog.
         */
        if (isFinalEpisode) {
            return@withContext UpdateResult(
                "MAL: Completed $matchedTitle!",
                true,
                matchedTitle
            )
        }

        return@withContext UpdateResult(
            "MAL: $matchedTitle → " +
                "S${season ?: "?"}E$targetEpisode",
            false,
            matchedTitle
        )
    }

    /*
     * Save MAL score.
     *
     * First find the anime in the user's list so the score goes
     * to the same MAL entry being tracked.
     */
    suspend fun updateScore(
        context: Context,
        title: String,
        score: Int
    ): String = withContext(Dispatchers.IO) {

        val settings = Settings.getInstance(context)

        val accessToken = settings.getString(
            "mal_access_token",
            null
        ) ?: ""

        if (accessToken.isEmpty()) {
            return@withContext "MAL: Not logged in"
        }

        val encodedTitle = URLEncoder.encode(
            title,
            "UTF-8"
        )

        val userListUrl =
            "$API_BASE/users/@me/animelist" +
                "?q=$encodedTitle" +
                "&limit=100" +
                "&fields=list_status,num_episodes"

        val userListResult = request(
            userListUrl,
            "GET",
            accessToken
        )

        if (!userListResult.success) {
            return@withContext(
                "MAL: Score list search failed " +
                    "(${userListResult.code})"
            )
        }

        val userData = JSONObject(userListResult.body)
            .optJSONArray("data")

        var animeId = 0
        var bestScore = Int.MIN_VALUE

        if (userData != null) {

            for (i in 0 until userData.length()) {

                val item = userData.optJSONObject(i)
                    ?: continue

                val node = item.optJSONObject("node")
                    ?: continue

                val candidateId = node.optInt(
                    "id",
                    0
                )

                val candidateTitle = node.optString(
                    "title",
                    ""
                )

                if (
                    candidateId == 0 ||
                    candidateTitle.isEmpty()
                ) continue

                val candidateScore =
                    calculateSeasonMatchScore(
                        normalizeTitle(title),
                        candidateTitle,
                        null
                    )

                if (candidateScore > bestScore) {
                    bestScore = candidateScore
                    animeId = candidateId
                }
            }
        }

        /*
         * Fallback to MAL search.
         */
        if (animeId == 0) {

            val searchUrl =
                "$API_BASE/anime" +
                    "?q=$encodedTitle" +
                    "&limit=20" +
                    "&fields=num_episodes"

            val searchResult = request(
                searchUrl,
                "GET",
                accessToken
            )

            if (!searchResult.success) {
                return@withContext(
                    "MAL: Score search failed " +
                        "(${searchResult.code})"
                )
            }

            val data = JSONObject(searchResult.body)
                .optJSONArray("data")

            if (
                data == null ||
                data.length() == 0
            ) {
                return@withContext(
                    "MAL: Anime not found"
                )
            }

            var bestSearchScore = Int.MIN_VALUE

            for (i in 0 until data.length()) {

                val node = data.optJSONObject(i)
                    ?.optJSONObject("node")
                    ?: continue

                val candidateTitle = node.optString(
                    "title",
                    ""
                )

                val candidateId = node.optInt(
                    "id",
                    0
                )

                if (
                    candidateTitle.isEmpty() ||
                    candidateId == 0
                ) continue

                val candidateScore =
                    calculateSeasonMatchScore(
                        normalizeTitle(title),
                        candidateTitle,
                        null
                    )

                if (
                    candidateScore >
                    bestSearchScore
                ) {
                    bestSearchScore = candidateScore
                    animeId = candidateId
                }
            }
        }

        if (animeId == 0) {
            return@withContext(
                "MAL: Invalid anime ID"
            )
        }

        val body =
            "score=" +
                URLEncoder.encode(
                    score.coerceIn(1, 10).toString(),
                    "UTF-8"
                )

        val updateUrl =
            "$API_BASE/anime/$animeId/my_list_status"

        val result = request(
            updateUrl,
            "PUT",
            accessToken,
            body
        )

        if (!result.success) {
            return@withContext(
                "MAL: Score update failed " +
                    "(${result.code})"
            )
        }

        return@withContext(
            "MAL: Score saved: " +
                "${score.coerceIn(1, 10)}/10"
        )
    }

    private fun buildUpdateBody(
        status: String,
        episode: Int,
        startDate: String,
        finishDate: String
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

            if (startDate.isNotEmpty()) {
                append(
                    "&start_date=" +
                        URLEncoder.encode(
                            startDate,
                            "UTF-8"
                        )
                )
            }

            if (finishDate.isNotEmpty()) {
                append(
                    "&finish_date=" +
                        URLEncoder.encode(
                            finishDate,
                            "UTF-8"
                        )
                )
            }
        }
    }

    private fun calculateSeasonMatchScore(
        filenameTitle: String,
        malTitle: String,
        requestedSeason: Int?
    ): Int {

        val normalizedMalTitle =
            normalizeTitle(malTitle)

        var score = 0

        if (
            normalizedMalTitle ==
            filenameTitle
        ) {
            score += 100
        }

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

        if (requestedSeason == null) {
            return score
        }

        val season =
            requestedSeason.toString()

        val requestedPatterns =
            listOf(
                "season $season",
                "season$season",
                "${season}nd season",
                "${season}rd season",
                "${season}th season",
                "s$season",
                "s0$season"
            )

        for (pattern in requestedPatterns) {

            if (
                normalizedMalTitle.contains(
                    pattern
                )
            ) {
                score += 100
                break
            }
        }

        val seasonRegex =
            Regex("(?:season|s)\\s*([0-9]+)")

        val match =
            seasonRegex.find(normalizedMalTitle)

        if (match != null) {

            val detectedSeason =
                match.groupValues[1]
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

    private data class RequestResult(
        val success: Boolean,
        val code: Int,
        val body: String
    )

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

            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

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
                    ?.use { it.readText() }
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
                body = e.message ?: ""
            )

        } finally {
            connection?.disconnect()
        }
    }
}
