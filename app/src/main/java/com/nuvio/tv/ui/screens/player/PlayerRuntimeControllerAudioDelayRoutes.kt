package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.nuvio.tv.data.local.AudioOutputChannels
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debounce window so flapping add/remove events coalesce into one route decision. */
private const val AUDIO_ROUTE_CHANGE_DEBOUNCE_MS = 700L

private const val TAG = "AudioRoute"

internal enum class BluetoothRoutePlaybackAction {
    NONE,
    UPDATE_SINK_IN_PLACE,
    UPDATE_MPV_IN_PLACE,
}

/**
 * Bluetooth connect/disconnect must never rebuild the player: that restarts video from a
 * seek point and shows the loading overlay. Update PCM/passthrough (Exo) or stereo/delay
 * (MPV) on the live engine instead.
 */
internal fun decideBluetoothRoutePlaybackAction(
    wasBluetooth: Boolean,
    isBluetooth: Boolean,
    usingMpv: Boolean,
    oldRouteKey: String? = null,
    newRouteKey: String? = null
): BluetoothRoutePlaybackAction {
    val bluetoothStateChanged = wasBluetooth != isBluetooth
    val bluetoothDeviceChanged = isBluetooth &&
        !oldRouteKey.isNullOrBlank() &&
        !newRouteKey.isNullOrBlank() &&
        oldRouteKey != newRouteKey
    if (!bluetoothStateChanged && !bluetoothDeviceChanged) {
        return BluetoothRoutePlaybackAction.NONE
    }
    return if (usingMpv) {
        BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE
    } else {
        BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE
    }
}

internal object MpvBluetoothAudioPolicy {
    const val STEREO_CHANNELS = "stereo"
    const val AUTO_CHANNELS = "auto"

    fun audioChannels(isBluetooth: Boolean): String {
        return if (isBluetooth) STEREO_CHANNELS else AUTO_CHANNELS
    }

    fun shouldClearAudioSpdif(isBluetooth: Boolean): Boolean = isBluetooth
}

internal fun audioDelayMsToSeconds(delayMs: Int): Double {
    return delayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS) / 1000.0
}

internal suspend fun PlayerRuntimeController.applyStoredAudioDelayForCurrentRouteIfEnabled() {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: return
    currentAudioOutputRoute = route

    val storedDelayMs = audioDelayRouteDataStore.loadDelayMs(route.key) ?: 0
    applyAudioDelay(storedDelayMs, persistForCurrentRoute = false)
    Log.d(
        PlayerRuntimeController.TAG,
        "Applied audio delay ${storedDelayMs}ms for route=${route.key}"
    )
}

internal fun PlayerRuntimeController.persistAudioDelayForCurrentRoute(delayMs: Int) {
    if (!rememberAudioDelayPerDeviceEnabled) return

    val route = AudioOutputRouteDetector.detect(context) ?: currentAudioOutputRoute ?: return
    currentAudioOutputRoute = route
    scope.launch {
        audioDelayRouteDataStore.saveDelayMs(route.key, delayMs)
    }
}

internal fun PlayerRuntimeController.registerAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || audioOutputRouteCallback != null) return

    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged("added", addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onAudioOutputRouteMaybeChanged("removed", removedDevices)
        }
    }

    runCatching {
        audioManager.registerAudioDeviceCallback(callback, null)
        audioOutputRouteCallback = callback
        Log.d(PlayerRuntimeController.TAG, "Registered audio output route callback")
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to register audio route callback", it)
    }
}

internal fun PlayerRuntimeController.unregisterAudioDelayRouteCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val callback = audioOutputRouteCallback ?: return
    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
    runCatching {
        audioManager.unregisterAudioDeviceCallback(callback)
    }.onFailure {
        Log.w(PlayerRuntimeController.TAG, "Failed to unregister audio route callback", it)
    }
    audioOutputRouteCallback = null
}

private fun PlayerRuntimeController.onAudioOutputRouteMaybeChanged(
    reason: String,
    devices: Array<out AudioDeviceInfo>
) {
    if (isReleasingPlayer) return
    if (_exoPlayer == null && !isUsingMpvEngine()) return

    Log.d(
        PlayerRuntimeController.TAG,
        "Audio device $reason (count=${devices.size}); scheduling route re-probe"
    )

    audioRouteChangeJob?.cancel()
    audioRouteChangeJob = scope.launch {
        delay(AUDIO_ROUTE_CHANGE_DEBOUNCE_MS)
        if (isReleasingPlayer) return@launch

        val oldRoute = currentAudioOutputRoute
        val newRoute = AudioOutputRouteDetector.detect(context)
        if (newRoute != null) {
            currentAudioOutputRoute = newRoute
        }

        if (rememberAudioDelayPerDeviceEnabled) {
            applyStoredAudioDelayForCurrentRouteIfEnabled()
        }

        val wasBluetooth = oldRoute?.isBluetooth == true
        val isBluetooth = (newRoute ?: currentAudioOutputRoute)?.isBluetooth == true
        when (
            decideBluetoothRoutePlaybackAction(
                wasBluetooth = wasBluetooth,
                isBluetooth = isBluetooth,
                usingMpv = isUsingMpvEngine(),
                oldRouteKey = oldRoute?.key,
                newRouteKey = (newRoute ?: currentAudioOutputRoute)?.key
            )
        ) {
            BluetoothRoutePlaybackAction.NONE -> {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Audio route after device $reason: bluetooth=$isBluetooth (was=$wasBluetooth); player stays running"
                )
            }
            BluetoothRoutePlaybackAction.UPDATE_SINK_IN_PLACE -> {
                if (_exoPlayer == null) return@launch
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media route changed $wasBluetooth → $isBluetooth after device $reason; " +
                        "updating PCM policy in place (player stays running)"
                )
                applyBluetoothAudioRouteInPlace(isBluetooth)
            }
            BluetoothRoutePlaybackAction.UPDATE_MPV_IN_PLACE -> {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media route changed $wasBluetooth → $isBluetooth after device $reason; " +
                        "updating MPV stereo/delay in place (player stays running)"
                )
                applyMpvBluetoothAudioRouteInPlace(isBluetooth)
            }
        }
    }
}

internal fun PlayerRuntimeController.applyBluetoothAudioRouteInPlace(isBluetooth: Boolean) {
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    val sink = playbackSpeedAwareAudioSink
    if (sink != null && sink.isBluetoothForcePcm() == isBluetooth) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Bluetooth PCM policy already $isBluetooth; " +
                if (wasPlaying) "player keeps running" else "player stays paused"
        )
        if (!wasPlaying || userPausedManually) {
            _exoPlayer?.let { player ->
                player.playWhenReady = false
                player.pause()
            }
        }
        return
    }

    sink?.setBluetoothForcePcm(isBluetooth)
    reapplyRouteDownmixTarget(isBluetooth)

    sink?.notifyAudioProcessingRequirementChanged()
    _exoPlayer?.let { player ->
        if (!wasPlaying || userPausedManually) {
            player.playWhenReady = false
            player.pause()
        }
    }
}

/**
 * Re-apply the fold target for the CURRENT route.
 *
 * Split out of [applyBluetoothAudioRouteInPlace] because a phone private-listening fork changes
 * what counts as a two-channel output WITHOUT changing the audio route, so it reaches none of that
 * function's guards. Arming a fork is a new 2.0 output; disarming one is not.
 *
 * ⚠️ The cap follows the SINK, not "the audio left the TV". Only a Bluetooth sink genuinely needs
 * folding, and only there is the FFmpeg fold reachable. A phone fork is a DIGITAL link: its
 * receiver opens a 5.1/7.1 AudioTrack and lets Android's own mixer fold (phone ForkUdpReceiver,
 * validated clean in commit b53adbbf against a TV-side stereo fold that artifacted).
 *
 * On any other route the stored layout decides, and
 * [com.nuvio.tv.data.local.AudioOutputChannels.AUTO] means no fixed target at all.
 */
internal fun PlayerRuntimeController.reapplyRouteDownmixTarget() {
    reapplyRouteDownmixTarget(currentAudioOutputRoute?.isBluetooth == true)
}

internal fun PlayerRuntimeController.reapplyRouteDownmixTarget(isBluetooth: Boolean) {
    val settings = currentPlayerSettingsForReport
    val smallOutputRoute = isBluetooth || isPhoneAudioForkActive()
    // ⚠️ The cap keys on the SINK, not on smallOutputRoute: a phone fork is NOT a small output. At
    // the default decoder priority a fork cannot fold anyway, so capping it to 2.0 changes nothing
    // about the fold -- it only changes WHICH TRACK the selector picks, and the channel cap outranks
    // bitrate in media3's comparison chain, so it can prefer a worse 2.0 track over a better 5.1
    // one. Passing native channels is the point of the fork: the phone folds, and folds cleanly.
    // An explicit user layout still applies here.
    val targetChannels = settings.downmixTargetFor(isBluetooth)
    // Same rule the Bluetooth path already applies: force-optical must never win over a two-channel
    // output. A phone cannot play AC3/DTS, and passthrough would starve the tee of decoded PCM.
    val forceOptical = !smallOutputRoute &&
        settings.forceOpticalPassthrough &&
        settings.decoderPriority != 0

    // The fold needs the FFmpeg renderer, and renderer choice is fixed at player build: the factory
    // pins it for a Bluetooth sink, but a phone fork appears mid-session and cannot re-pin it. So a
    // fork folds only when the app decoder was already preferred -- and that is then the user's
    // explicit choice, not something a fork silently forces.
    val foldReachable = isBluetooth || settings.isPreferAppDecoder

    ffmpegAudioRenderer?.setForceOpticalPassthrough(forceOptical)
    if (foldReachable && targetChannels != null) {
        ffmpegAudioRenderer?.setAudioOutputChannels(
            targetChannels.ffmpegLayoutName,
            targetChannels.channelCount
        )
        // Always normalized. The retired "maintain original audio on downmix" toggle switched off
        // the decoder's own rematrix_maxval + limiter path, which is what lets a hot fold clip --
        // and a clipping fold cannot be heard on the owner's speakerless monitor, so it would
        // masquerade as the private-listening crackle instead.
        ffmpegAudioRenderer?.setDownmixNormalizationEnabled(true)
    } else {
        ffmpegAudioRenderer?.setAudioOutputChannels(null, 0)
        ffmpegAudioRenderer?.setDownmixNormalizationEnabled(false)
    }

    // The ASK half of the downmix log. The tee logs what it was actually handed (see
    // PrivateListeningAudioSink), which is the renderer's own output; this line is what we
    // requested. On a phone fork the expected reading is now `target=none cap=2147483647` (no cap)
    // with the tee showing the TRACK's own layout -- 6 channels, ~485 datagrams/s -- because the
    // fold happens on the phone. Nothing here can be heard on a speakerless monitor, so the log IS
    // the verification.
    val forkActive = isPhoneAudioForkActive()
    val targetLabel = targetChannels?.ffmpegLayoutName ?: "none"
    val capChannels = targetChannels?.channelCount ?: Int.MAX_VALUE
    Log.i(
        TAG,
        "downmix ask: smallOutputRoute=$smallOutputRoute bluetooth=$isBluetooth fork=$forkActive " +
            "target=$targetLabel foldReachable=$foldReachable forceOptical=$forceOptical " +
            "cap=$capChannels",
    )

    _exoPlayer?.let { player ->
        // Prefer a track that ALREADY fits the target over folding a bigger one: a studio-authored
        // 5.1/2.0 mix beats any fold we can do, and it costs no DSP, so it cannot clip. The cap is
        // a SOFT bound -- exceedAudioConstraintsIfNecessary defaults to true -- so a file whose
        // only track is 5.1 still plays and folds rather than failing selection. No target (AUTO
        // with no small output) means no cap at all.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxAudioChannelCount(capChannels)
            .build()
    }
}

internal fun PlayerRuntimeController.applyMpvBluetoothAudioRouteInPlace(isBluetooth: Boolean) {
    val view = mpvView ?: return
    val wasPlaying = hasActivePlayIntent() && !userPausedManually
    view.applyBluetoothAudioRoute(isBluetooth, reloadOutput = true)
    // ao-reload can drop live properties; re-pin the current per-route delay.
    view.setAudioDelayMs(_uiState.value.audioDelayMs)
    view.applyAudioAmplificationDb(_uiState.value.audioAmplificationDb)
    if (!wasPlaying || userPausedManually) {
        view.setPaused(true)
    }
}
