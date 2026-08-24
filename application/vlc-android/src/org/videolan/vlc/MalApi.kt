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

    suspend fun updateEpisode(
        context: Context,
        title: String,
        episode: Int
    ): String = withContext(Dispatchers.IO) {

        val settings = Settings.getInstance(context)

        val accessToken =
            settings.getString("mal_access_token", null) ?: ""

        if (accessToken.isEmpty()) {
            return@withContext "MAL: Not logged in"
        }

        /*
         * ---------------------------------------------------------
         * 1. Search MAL for the anime
         * ---------------------------------------------------------
         */

        val encodedTitle =
            URLEncoder.encode(title, "UTF-8")

        val searchUrl =
            "$API_BASE/anime?q=$encodedTitle&limit=10"

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
         * 2. Find the best matching MAL result
         * ---------------------------------------------------------
         */

        val normalizedTitle =
            normalizeTitle(title)

        var animeId: Int? = null
        var matchedTitle = ""

        for (i in 0 until data.length()) {

            val node =
                data.optJSONObject(i)
                    ?.optJSONObject("node")
                    ?: continue

            val malTitle =
                node.optString("title", "")

            if (malTitle.isEmpty()) continue

            if (normalizeTitle(malTitle) == normalizedTitle) {

                animeId =
                    node.optInt("id", 0)

                matchedTitle =
                    malTitle

                break
            }
        }

        /*
         * If there is no exact title match, temporarily use
         * MAL's first search result.
         *
         * We will improve sequel/alternate-title matching later.
         */

        if (animeId == null) {

            val firstNode =
                data.optJSONObject(0)
                    ?.optJSONObject("node")

            if (firstNode == null) {
                return@withContext(
                    "MAL: Could not read search result"
                )
            }

            animeId =
                firstNode.optInt("id", 0)

            matchedTitle =
                firstNode.optString("title", title)
        }

        if (animeId == null || animeId == 0) {
            return@withContext "MAL: Invalid anime ID"
        }

        /*
         * ---------------------------------------------------------
         * 3. Check the user's existing MAL list entry
         * ---------------------------------------------------------
         *
         * This is important because we must NEVER move progress
         * backwards.
         *
         * Example:
         *
         * MAL = episode 6
         * VLC plays episode 5
         *
         * Result:
         * MAL remains episode 6.
         */

        val statusUrl =
            "$API_BASE/anime/$animeId/my_list_status"

        val statusResult =
            request(
                url = statusUrl,
                method = "GET",
                accessToken = accessToken
            )

        /*
         * ---------------------------------------------------------
         * 4. Anime is already in the user's MAL list
         * ---------------------------------------------------------
         */

        if (statusResult.success) {

            val statusJson =
                JSONObject(statusResult.body)

            val currentEpisode =
                statusJson.optInt(
                    "num_watched_episodes",
                    0
                )

            /*
             * Do not move MAL backwards.
             */

            if (episode <= currentEpisode) {

                return@withContext(
                    "MAL: Already at episode $currentEpisode"
                )
            }

            /*
             * The new episode is higher, so update progress.
             *
             * We don't send start_date here because the anime
             * already has a MAL list entry. This preserves the
             * original start date.
             */

            val body = buildString {

                append(
                    "status=" +
                        URLEncoder.encode(
                            "watching",
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
            }

            val updateResult =
                request(
                    url = statusUrl,
                    method = "PUT",
                    accessToken = accessToken,
                    body = body
                )

            if (!updateResult.success) {

                return@withContext(
                    "MAL: Update failed (${updateResult.code})"
                )
            }

            return@withContext(
                "MAL: Updated $matchedTitle → episode $episode"
            )
        }

        /*
         * ---------------------------------------------------------
         * 5. Anime is NOT currently in the user's MAL list
         * ---------------------------------------------------------
         *
         * A 404 here means MAL does not have a list entry for
         * this anime yet.
         *
         * We therefore create the entry as Watching and set
         * today's date as the start date.
         */

        if (statusResult.code == 404) {

            val startDate =
                LocalDate.now().toString()

            val body = buildString {

                append(
                    "status=" +
                        URLEncoder.encode(
                            "watching",
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

            val updateResult =
                request(
                    url = statusUrl,
                    method = "PUT",
                    accessToken = accessToken,
                    body = body
                )

            if (!updateResult.success) {

                return@withContext(
                    "MAL: First update failed (${updateResult.code})"
                )
            }

            return@withContext(
                "MAL: Started watching $matchedTitle → episode $episode"
            )
        }

        /*
         * ---------------------------------------------------------
         * 6. Some other error occurred while checking the list
         * ---------------------------------------------------------
         */

        return@withContext(
            "MAL: List check failed (${statusResult.code})"
        )
    }

    /*
     * -------------------------------------------------------------
     * Normalize titles for comparison.
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
     * HTTP request result.
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
