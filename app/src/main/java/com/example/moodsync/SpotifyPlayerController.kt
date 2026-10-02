package com.example.moodsync

import android.content.Context
import com.spotify.protocol.types.PlayerState

class SpotifyPlayerController(
    private val context: Context,
    private val onStateChanged: (PlayerState) -> Unit,
    private val onError: (Throwable) -> Unit
) {

    fun connect(showAuth: Boolean = true, onConnected: (() -> Unit)? = null) {
        SpotifyRemoteManager.connect(
            context = context,
            showAuth = showAuth,
            onConnected = {
                SpotifyRemoteManager.setUiPlayerStateCallback(onStateChanged)

                SpotifyRemoteManager.getPlayerState(
                    onSuccess = onStateChanged,
                    onFailure = {}
                )

                onConnected?.invoke()
            },
            onFailure = onError
        )
    }



    fun play(
        uri: String,
        onSuccess: (() -> Unit)? = null
    ) {
        SpotifyRemoteManager.play(
            context = context,
            uri = uri,
            onSuccess = {
                onSuccess?.invoke()

                SpotifyRemoteManager.getPlayerState(
                    onSuccess = onStateChanged,
                    onFailure = {}
                )
            },
            onFailure = onError
        )
    }

    fun getImage(
        imageUri: com.spotify.protocol.types.ImageUri,
        onSuccess: (android.graphics.Bitmap) -> Unit
    ) {
        SpotifyRemoteManager.getImage(
            imageUri = imageUri,
            onSuccess = onSuccess,
            onFailure = onError
        )
    }

    fun pause() {
        SpotifyRemoteManager.pause(context, onFailure = onError)
    }

    fun resume() {
        SpotifyRemoteManager.resume(context, onFailure = onError)
    }

    fun next() {
        SpotifyRemoteManager.skipNext(context, onFailure = onError)
    }

    fun previous() {
        SpotifyRemoteManager.skipPrevious(context, onFailure = onError)
    }

    fun seekTo(positionMs: Long) {
        SpotifyRemoteManager.seekTo(context, positionMs, onFailure = onError)
    }

    fun isConnected(): Boolean {
        return SpotifyRemoteManager.isConnected()
    }

    fun subscribeToPlayerState() {
        SpotifyRemoteManager.setUiPlayerStateCallback(onStateChanged)

        SpotifyRemoteManager.getPlayerState(
            onSuccess = onStateChanged,
            onFailure = {}
        )
    }

    fun getPlayerState(onSuccess: (PlayerState) -> Unit) {
        SpotifyRemoteManager.getPlayerState(
            onSuccess = onSuccess,
            onFailure = {}
        )
    }

    fun disconnect() {
        SpotifyRemoteManager.setUiPlayerStateCallback(null)
    }
}