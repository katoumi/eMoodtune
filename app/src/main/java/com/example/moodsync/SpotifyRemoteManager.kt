package com.example.moodsync

import android.content.Context
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState
import android.graphics.Bitmap
import com.spotify.protocol.types.ImageUri
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object SpotifyRemoteManager {

    private const val TAG = "SpotifyRemoteManager"

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var playerStateSubscription: Subscription<PlayerState>? = null
    private var isConnecting = false
    
    private var uiPlayerStateCallback: ((PlayerState) -> Unit)? = null
    private var backgroundPlayerStateCallback: ((PlayerState) -> Unit)? = null
    
    // Queue to hold callbacks while connection is in progress
    private val pendingConnectionCallbacks = mutableListOf<() -> Unit>()

    fun isConnected(): Boolean {
        return spotifyAppRemote?.isConnected == true
    }

    fun hardReset(context: Context, onConnected: (() -> Unit)? = null) {
        Log.d(TAG, "Hard Reset initiated - clearing existing bridge")
        disconnect()
        connect(context, showAuth = true, onConnected = onConnected)
    }

    fun connect(
        context: Context,
        showAuth: Boolean = true,
        onConnected: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        retryCount: Int = 0
    ) {
        if (isConnected()) {
            onConnected?.invoke()
            return
        }
        
        if (isConnecting) {
            onConnected?.let { 
                synchronized(pendingConnectionCallbacks) {
                    pendingConnectionCallbacks.add(it)
                }
            }
            return
        }
        
        isConnecting = true
        onConnected?.let { 
            synchronized(pendingConnectionCallbacks) {
                pendingConnectionCallbacks.add(it)
            }
        }

        // LONGER TIMEOUT: 15 seconds for cold starts
        if (retryCount == 0) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isConnecting) {
                    isConnecting = false
                    Log.e(TAG, "Spotify connection timed out after 15s")
                    executeFailureAndClear(IllegalStateException("Connection timed out"))
                    onFailure?.invoke(IllegalStateException("Connection timed out"))
                }
            }, 15000)
        }

        val connectionParams = ConnectionParams.Builder(SpotifyConfig.CLIENT_ID)
            .setRedirectUri(SpotifyConfig.REDIRECT_URI)
            .showAuthView(showAuth)
            .build()

        SpotifyAppRemote.connect(
            context,
            connectionParams,
            object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    spotifyAppRemote = appRemote
                    isConnecting = false
                    Log.d(TAG, "Spotify connected on attempt ${retryCount + 1}")
                    
                    // PING TEST: Ensure bridge is actually responsive
                    appRemote.userApi.capabilities.setResultCallback {
                        Log.d(TAG, "Bridge handshake: Capabilities verified (Ping OK)")
                    }

                    synchronized(pendingConnectionCallbacks) {
                        pendingConnectionCallbacks.forEach { it.invoke() }
                        pendingConnectionCallbacks.clear()
                    }
                }

                override fun onFailure(throwable: Throwable) {
                    Log.e(TAG, "Spotify connection failed (Attempt ${retryCount + 1}): ${throwable.message}")
                    
                    // Diagnostic UI feedback
                    Handler(Looper.getMainLooper()).post {
                        val errorMsg = throwable.message ?: "Unknown Spotify Error"
                        if (errorMsg.contains("broker", ignoreCase = true)) {
                            Toast.makeText(context, "GMS Broker Error: Clearing Cache might help", Toast.LENGTH_LONG).show()
                        }
                    }

                    if (retryCount < 4) { // Increased retries
                        val nextDelay = when(retryCount) {
                            0 -> 1000L
                            1 -> 3000L
                            2 -> 7000L
                            else -> 10000L
                        }
                        
                        SpotifyWakeUpHelper.aggressiveWakeUp(context)
                        
                        Handler(Looper.getMainLooper()).postDelayed({
                            isConnecting = false 
                            connect(context, showAuth, null, onFailure, retryCount + 1)
                        }, nextDelay)
                    } else {
                        spotifyAppRemote = null
                        isConnecting = false
                        synchronized(pendingConnectionCallbacks) {
                            pendingConnectionCallbacks.clear()
                        }
                        onFailure?.invoke(throwable)
                    }
                }
            }
        )
    }
    
    private fun executeFailureAndClear(throwable: Throwable) {
        synchronized(pendingConnectionCallbacks) {
            // Log the error that caused the queue to clear
            Log.e(TAG, "Connection failed, clearing pending callbacks: ${throwable.message}")
            pendingConnectionCallbacks.clear()
        }
    }

    fun disconnect() {
        try {
            playerStateSubscription?.cancel()
            playerStateSubscription = null
        } catch (_: Exception) {
        }

        spotifyAppRemote?.let {
            SpotifyAppRemote.disconnect(it)
        }

        spotifyAppRemote = null
        isConnecting = false
        synchronized(pendingConnectionCallbacks) {
            pendingConnectionCallbacks.clear()
        }
    }

    fun reconnectIfNeeded(context: Context) {
        if (!isConnected() && !isConnecting) {
            connect(context)
        }
    }

    fun play(
        context: Context,
        uri: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        val remote = spotifyAppRemote

        if (remote == null || !remote.isConnected) {
            connect(context, onConnected = {
                play(context, uri, onSuccess, onFailure)
            }, onFailure = onFailure)
            return
        }

        if (uri.isBlank()) {
            onFailure?.invoke(IllegalArgumentException("Spotify URI is blank"))
            return
        }

        remote.playerApi.play(uri)
            .setResultCallback {
                Log.d(TAG, "Play requested successfully: $uri")
                onSuccess?.invoke()
            }
            .setErrorCallback { throwable ->
                Log.e(TAG, "Play failed: $uri", throwable)
                onFailure?.invoke(throwable)
            }
    }

    fun pause(
        context: Context,
        onResult: ((Boolean) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        val remote = spotifyAppRemote

        if (remote == null || !remote.isConnected) {
            connect(context, onConnected = {
                pause(context, onResult, onFailure)
            }, onFailure = onFailure)
            return
        }

        remote.playerApi.pause()
            .setResultCallback { onResult?.invoke(true) }
            .setErrorCallback { throwable -> 
                onFailure?.invoke(throwable)
                onResult?.invoke(false)
            }
    }

    fun resume(context: Context, onFailure: ((Throwable) -> Unit)? = null) {
        val remote = spotifyAppRemote

        if (remote == null || !remote.isConnected) {
            connect(context, onConnected = {
                resume(context, onFailure)
            }, onFailure = onFailure)
            return
        }

        remote.playerApi.resume()
            .setErrorCallback { throwable -> onFailure?.invoke(throwable) }
    }

    fun getImage(
        imageUri: ImageUri,
        onSuccess: (Bitmap) -> Unit,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        val remote = spotifyAppRemote

        if (remote == null || !remote.isConnected) {
            onFailure?.invoke(IllegalStateException("Spotify App Remote is not connected"))
            return
        }

        remote.imagesApi.getImage(imageUri)
            .setResultCallback { bitmap ->
                onSuccess(bitmap)
            }
            .setErrorCallback { throwable ->
                onFailure?.invoke(throwable)
            }
    }

    fun skipNext(context: Context, onFailure: ((Throwable) -> Unit)? = null) {
        val remote = spotifyAppRemote

        if (remote == null || !remote.isConnected) {
            connect(context, onConnected = {
                skipNext(context, onFailure)
            }, onFailure = onFailure)
            return
        }

        remote.playerApi.skipNext()
            .setErrorCallback { throwable -> onFailure?.invoke(throwable) }
    }

    fun skipPrevious(context: Context, onFailure: ((Throwable) -> Unit)? = null) {
        val remote = spotifyAppRemote

        if (remote == null || !remote.isConnected) {
            connect(context, onConnected = {
                skipPrevious(context, onFailure)
            }, onFailure = onFailure)
            return
        }

        remote.playerApi.skipPrevious()
            .setErrorCallback { throwable -> onFailure?.invoke(throwable) }
    }

    fun seekTo(
        context: Context,
        positionMs: Long,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        val remote = spotifyAppRemote

        if (remote == null || !remote.isConnected) {
            connect(context, onConnected = {
                seekTo(context, positionMs, onFailure)
            }, onFailure = onFailure)
            return
        }

        remote.playerApi.seekTo(positionMs)
            .setErrorCallback { throwable -> onFailure?.invoke(throwable) }
    }

    fun setUiPlayerStateCallback(callback: ((PlayerState) -> Unit)?) {
        uiPlayerStateCallback = callback
        ensureMasterSubscription()
    }

    fun setBackgroundPlayerStateCallback(callback: ((PlayerState) -> Unit)?) {
        backgroundPlayerStateCallback = callback
        ensureMasterSubscription()
    }

    private fun ensureMasterSubscription(onFailure: ((Throwable) -> Unit)? = null) {
        val remote = spotifyAppRemote

        if (remote == null || !remote.isConnected) {
            onFailure?.invoke(IllegalStateException("Spotify App Remote is not connected"))
            return
        }

        if (playerStateSubscription != null) {
            return // Already subscribed
        }

        val subscription = remote.playerApi.subscribeToPlayerState()
        playerStateSubscription = subscription

        subscription.setEventCallback { playerState ->
            uiPlayerStateCallback?.invoke(playerState)
            backgroundPlayerStateCallback?.invoke(playerState)
        }

        subscription.setErrorCallback { throwable ->
            onFailure?.invoke(throwable)
        }
    }

    fun getPlayerState(
        onSuccess: (PlayerState) -> Unit,
        onFailure: ((Throwable) -> Unit)? = null
    ) {
        val remote = spotifyAppRemote

        if (remote == null || !remote.isConnected) {
            onFailure?.invoke(IllegalStateException("Spotify App Remote is not connected"))
            return
        }

        remote.playerApi.playerState
            .setResultCallback { state ->
                onSuccess(state)
            }
            .setErrorCallback { throwable ->
                onFailure?.invoke(throwable)
            }
    }
}
