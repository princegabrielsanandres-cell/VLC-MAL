package org.videolan.vlc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.videolan.tools.Settings
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

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
            return@withContext "MAL: Anime search failed (${searchResult.code})"
        }

        val searchJson =
            JSONObject(searchResult.body)

        val data =
            searchJson.optJSONArray("data")

        if (data == null || data.length() == 0) {
            return@withContext "MAL: Anime not found: $title"
        }

        /*
         * ---------------------------------------------------------
         * 2. Find the best matching MAL result
         * ---------------------------------------------------------
         *
         * Prefer a result whose title matches the filename.
         *
         * If there is no exact match, fall back to the first
         * result returned by MAL.
         *
         * We'll make this matching smarter later so English
         * filenames can reliably match Japanese MAL titles
         * and different seasons.
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
                animeId = node.optInt("id", 0)
                matchedTitle = malTitle
                break
            }
        }

        /*
         * No exact title match.
         *
         * For now use the first MAL result as a fallback.
         */

        if (animeId == null) {

            val firstNode =
                data.optJSONObject(0)
                    ?.optJSONObject("node")

            if (firstNode == null) {
                return@withContext "MAL: Could not read search result"
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
         * 3. Update the user's MAL list
         * ---------------------------------------------------------
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

        val updateUrl =
            "$API_BASE/users/@me/animelist/$animeId"

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

        return@withContext(
            "MAL: Updated $matchedTitle → episode $episode"
        )
    }

    /*
     * -------------------------------------------------------------
     * Normalize titles for comparison.
     * -------------------------------------------------------------
     */

    private fun normalizeTitle(title: String): String {
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
     * HTTP request helper
     * -------------------------------------------------------------
     *
     * We keep the HTTP status code and response body so that
     * debugging MAL API failures is much easier.
     */

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
