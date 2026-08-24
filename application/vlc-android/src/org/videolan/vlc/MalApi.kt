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
         * 2. Find the best matching anime
         * ---------------------------------------------------------
         */

        val normalizedTitle =
            normalizeTitle(title)

        var animeId = 0
        var matchedTitle = title

        for (i in 0 until data.length()) {

            val node =
                data.optJSONObject(i)
                    ?.optJSONObject("node")
                    ?: continue

            val malTitle =
                node.optString("title", "")

            val id =
                node.optInt("id", 0)

            if (malTitle.isEmpty() || id == 0) {
                continue
            }

            if (normalizeTitle(malTitle) == normalizedTitle) {

                animeId = id
                matchedTitle = malTitle

                break
            }
        }

        /*
         * If there wasn't an exact title match,
         * use MAL's first search result for now.
         */

        if (animeId == 0) {

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
                firstNode.optString(
                    "title",
                    title
                )
        }

        if (animeId == 0) {
            return@withContext "MAL: Invalid anime ID"
        }

        /*
         * ---------------------------------------------------------
         * 3. Get the user's MAL list
         * ---------------------------------------------------------
         *
         * We request the user's list and include list_status.
         *
         * The anime ID is what matters here.
         *
         * We DO NOT trust the filename episode number to be
         * higher or lower than the existing MAL progress.
         */

        val userListUrl =
            "$API_BASE/users/@me/animelist" +
                "?limit=1000" +
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

        /*
         * ---------------------------------------------------------
         * 4. Find the exact anime ID in the user's list
         * ---------------------------------------------------------
         */

        var foundInList = false
        var currentEpisode = 0

        if (userData != null) {

            for (i in 0 until userData.length()) {

                val item =
                    userData.optJSONObject(i)
                        ?: continue

                val node =
                    item.optJSONObject("node")
                        ?: continue

                val listAnimeId =
                    node.optInt("id", 0)

                if (listAnimeId != animeId) {
                    continue
                }

                foundInList = true

                val listStatus =
                    item.optJSONObject("list_status")

                currentEpisode =
                    listStatus?.optInt(
                        "num_episodes_watched",
                        0
                    ) ?: 0

                break
            }
        }

        /*
         * ---------------------------------------------------------
         * 5. Anime already exists in MAL
         * ---------------------------------------------------------
         */

        if (foundInList) {

            /*
             * CRITICAL:
             *
             * Never decrease MAL progress.
             *
             * Example:
             *
             * MAL = E06
             * VLC = E05
             *
             * Result:
             *
             * MAL stays at E06.
             */

            if (episode <= currentEpisode) {

                return@withContext(
                    "MAL: Already at episode $currentEpisode"
                )
            }

            /*
             * VLC is ahead of MAL.
             *
             * Update ONLY the episode number.
             *
             * We intentionally do NOT send:
             *
             * start_date
             * status
             *
             * This protects the original MAL start date.
             */

            val body =
                buildEpisodeBody(episode)

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
                    "MAL: Episode update failed " +
                        "(${updateResult.code})"
                )
            }

            return@withContext(
                "MAL: Updated $matchedTitle → episode $episode"
            )
        }

        /*
         * ---------------------------------------------------------
         * 6. Anime is NOT in the user's MAL list
         * ---------------------------------------------------------
         *
         * Add it as Watching.
         *
         * This is the ONLY situation where we send start_date.
         */

        val startDate =
            LocalDate.now().toString()

        val body =
            buildStartBody(
                episode = episode,
                startDate = startDate
            )

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
                "MAL: First update failed " +
                    "(${updateResult.code})"
            )
        }

        return@withContext(
            "MAL: Started watching " +
                "$matchedTitle → episode $episode"
        )
    }

    /*
     * -------------------------------------------------------------
     * Build body for an existing MAL entry.
     * -------------------------------------------------------------
     *
     * Only the episode number is changed.
     */

    private fun buildEpisodeBody(
        episode: Int
    ): String {

        return buildString {

            append(
                "num_watched_episodes=" +
                    URLEncoder.encode(
                        episode.toString(),
                        "UTF-8"
                    )
            )
        }
    }

    /*
     * -------------------------------------------------------------
     * Build body for a brand-new MAL entry.
     * -------------------------------------------------------------
     */

    private fun buildStartBody(
        episode: Int,
        startDate: String
    ): String {

        return buildString {

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
