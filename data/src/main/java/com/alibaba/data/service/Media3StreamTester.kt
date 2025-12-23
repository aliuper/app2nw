package com.alibaba.data.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.alibaba.domain.service.StreamTester
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

class Media3StreamTester @Inject constructor(
    @ApplicationContext private val context: Context
) : StreamTester {

    override suspend fun isPlayable(url: String, timeoutMs: Long): Boolean {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                val player = ExoPlayer.Builder(context).build()
                try {
                    val deferred = CompletableDeferred<Boolean>()
                    val listener = object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY && !deferred.isCompleted) {
                                deferred.complete(true)
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            if (!deferred.isCompleted) deferred.complete(false)
                        }
                    }
                    player.addListener(listener)
                    player.setMediaItem(MediaItem.fromUri(url))
                    player.prepare()
                    player.playWhenReady = true
                    deferred.await()
                } finally {
                    player.release()
                }
            } ?: false
        }
    }
}
