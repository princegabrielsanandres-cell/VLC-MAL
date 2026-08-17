package org.videolan.vlc

object MalSync {

    fun onMediaStarted(fileName: String) {
        val name = fileName.substringBeforeLast(".")

        println("MAL SYNC: Media started: $name")
    }
}
