package org.videolan.vlc

import android.content.Context
import org.videolan.tools.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object MalApi {

    private const val API_BASE = "https://api.myanimelist.net/v2"

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
            "$API_BASE/anime?q=$encodedTitle&limit=5"

        val searchResponse =
            request(
                url = searchUrl,
                method = "GET",
                accessToken = accessToken
            )

        if (searchResponse == null) {
            return@withContext "MAL: Anime search failed"
        }

        val searchJson =
            JSONObject(searchResponse)

        val data =
            searchJson.optJSONArray("data")

        if (data == null || data.length() == 0) {
            return@withContext "MAL: Anime not found: $title"
        }

        /*
         * For now use MAL's first search result.
         *
         * Later we'll improve this so English filenames can
         * reliably match Japanese MAL titles and sequels.
         */

        val animeId =
            data.getJSONObject(0)
                .getJSONObject("node")
                .getInt("id")

        /*
         * ---------------------------------------------------------
         * 2. Add/update the anime in the user's MAL list
         * ---------------------------------------------------------
         */

        val body = buildString {
            append("status=watching")
            append("&num_watched_episodes=")
            append(episode)
        }

        val updateUrl =
            "$API_BASE/users/@me/animelist/$animeId"

        val updateResponse =
            request(
                url = updateUrl,
                method = "PUT",
                accessToken = accessToken,
                body = body
            )

        if (updateResponse == null) {
            return@withContext "MAL: Failed to update episode"
        }

        return@withContext "MAL: Updated $title → episode $episode"
    }

    private fun request(
        url: String,
        method: String,
        accessToken: String,
        body: String? = null
    ): String? {

        var connection: HttpURLConnection? = null

        return try {

            connection =
                URL(url).openConnection() as HttpURLConnection

            connection.requestMethod = method

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
                        body.toByteArray(Charsets.UTF_8)
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

            val response =
                stream?.bufferedReader()?.use {
                    it.readText()
                }

            if (responseCode !in 200..299) {
                null
            } else {
                response
            }

        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
