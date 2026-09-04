package com.nuvio.tv.core.boomio

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.currentDeviceClientMetadata
import com.nuvio.tv.core.sync.SyncClientIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

private const val TAG = "BoomioCompanionManager"

/**
 * The TV's companion receiver for the bsc companion hub.
 *
 * Connects to `{BOOMIO_COMPANION_URL}/ws`, registers the TV, forwards inbound
 * play-control commands to the active player via [CompanionPlaybackBridge], and
 * reports ~1s playback telemetry back to the hub. Inert when
 * `BOOMIO_COMPANION_URL` is blank.
 *
 * Wire the register/command frames to the contract in `bsc/services/device-relay.js`:
 * outbound `register` / `playback_position` / `playback_stopped` / `stealth_playpause`
 * / `party_seek`; inbound `play` / `stealth_playpause` / `party_set_playing`
 * / `party_seek` / `party_ended` / `stop` / `companion_paired` / `companion_unpaired`
 * / `scrub_start` / `scrub_update` / `scrub_commit` / `stealth_volume` {percent}
 * / `stealth_keyevent` {keyCode} / `audio_fork_start` {phoneIp, port} / `audio_fork_stop`.
 *
 * `audio_fork_*` are the phone-remote private-listening surface: the TV tees the audio it is
 * already decoding to the phone over direct LAN UDP. The phone supplies `phoneIp`/`port` — the hub
 * relays them, it never derives them (R3). Explicit stop and the hub's heartbeat-driven stop
 * (`audio_fork_stop { reason: "phone_timeout" }`) both arrive as `audio_fork_stop`.
 */
@Singleton
class BoomioCompanionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient,
    private val syncClientIdentity: SyncClientIdentity,
    private val bridge: CompanionPlaybackBridge
) {
    private val companionUrl: String = BuildConfig.BOOMIO_COMPANION_URL.trim()
    private val wsUrl: String = companionUrl.trimEnd('/') + "/ws"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webSocketClient = okHttpClient.newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var telemetryJob: Job? = null
    private var lastTelemetryWasActive = false

    private val _currentPartyId = MutableStateFlow<String?>(null)
    /** Watch-party id when the active playback belongs to a party, else null. */
    val currentPartyId: StateFlow<String?> = _currentPartyId.asStateFlow()

    /** Playback was playing when a companion scrub started; resume on scrub_commit. */
    private var scrubbingWasPlaying = false

    /** The TV's foreground activity — target for companion-dispatched DPAD/back keys. */
    @Volatile private var resumedActivity: Activity? = null

    /**
     * Last volume percent set from the companion remote. Kept so a newly-started
     * player (next episode, etc.) inherits it when the OS blocks device-stream
     * changes and volume is scaled on the player instead.
     */
    @Volatile private var companionVolumePercent: Int? = null

    init {
        // Track the resumed activity so companion key presses (stealth_keyevent)
        // can be delivered to whatever screen is in front — home rows, player,
        // settings, etc. — exactly as a physical remote key would be.
        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) { resumedActivity = activity }
                override fun onActivityPaused(activity: Activity) {
                    if (resumedActivity === activity) resumedActivity = null
                }
                override fun onActivityStopped(activity: Activity) {
                    if (resumedActivity === activity) resumedActivity = null
                }
                override fun onActivityDestroyed(activity: Activity) {
                    if (resumedActivity === activity) resumedActivity = null
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            }
        )
        // A player that starts after a companion volume was set inherits it, so
        // volume keeps working across episodes when the OS blocks device-stream
        // changes (volume is then scaled on the player, which resets per player).
        scope.launch {
            bridge.activePlayer.collect { player ->
                if (player != null) {
                    val percent = companionVolumePercent
                    if (percent != null) runOnMain { applyCompanionVolume(percent) }
                }
            }
        }
    }

    /**
     * ExoPlayer must only be touched on the main thread. The OkHttp WS callbacks
     * and [scope] (IO) run off-main, so route player interactions through here.
     */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    /** Starts the companion connection. Safe to call repeatedly. */
    @Synchronized
    fun start() {
        if (companionUrl.isBlank()) return
        if (webSocket != null || reconnectJob?.isActive == true) return
        connect()
    }

    /** Reports a local party pause/resume so the hub re-broadcasts to all members. */
    fun reportPartyPlayPause() {
        _currentPartyId.value?.let { partyId ->
            webSocket?.send(JSONObject().apply {
                put("type", "stealth_playpause")
                put("partyId", partyId)
            }.toString())
        }
    }

    /** Reports a local party seek so the hub re-broadcasts to all members. */
    fun reportPartySeek(positionMs: Long) {
        _currentPartyId.value?.let { partyId ->
            webSocket?.send(JSONObject().apply {
                put("type", "party_seek")
                put("partyId", partyId)
                put("positionMs", positionMs)
            }.toString())
        }
    }

    private fun connect() {
        webSocket = webSocketClient.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            listener
        )
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempts = 0
            sendRegister()
            ensureTelemetryLoop()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runOnMain { handleInbound(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@BoomioCompanionManager.webSocket = null
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@BoomioCompanionManager.webSocket = null
            scheduleReconnect()
        }
    }

    private fun sendRegister() {
        val metadata = currentDeviceClientMetadata(context)
        val payload = JSONObject().apply {
            put("type", "register")
            put("deviceId", syncClientIdentity.currentClientId())
            put("name", metadata.deviceName)
            put("platform", "androidtv")
            bestEffortLanIp()?.let { put("ip", it) }
        }
        webSocket?.send(payload.toString())
    }

    private fun handleInbound(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "play" -> handlePlay(msg)
            "stealth_playpause" -> {
                msg.optString("partyId").takeIf { it.isNotBlank() }?.let { _currentPartyId.value = it }
                // A party broadcast (party_command/party_sync) has already reached every
                // member — re-reporting it would echo back through the hub and ping-pong
                // play/pause between devices. Only a fresh controller command (e.g. from
                // the phone, _from="companion") should re-propagate to the rest of the party.
                val isPartyBroadcast = msg.optString("_from") in setOf("party_command", "party_sync")
                bridge.activePlayer.value?.togglePlayPause(reportParty = !isPartyBroadcast)
            }
            // The hub broadcasts the desired state (not a toggle) for party
            // pause/resume commands — apply it directly so a "resume" always
            // resumes, even if the device was already in the target state.
            "party_set_playing" -> {
                msg.optString("partyId").takeIf { it.isNotBlank() }?.let { _currentPartyId.value = it }
                val isPlaying = msg.optBoolean("isPlaying")
                bridge.activePlayer.value?.let { if (isPlaying) it.resume() else it.pause() }
            }
            "party_seek" -> {
                msg.optLong("positionMs", -1L).takeIf { it >= 0L }?.let { positionMs ->
                    bridge.activePlayer.value?.seekTo(positionMs)
                }
            }
            "party_ended" -> {
                _currentPartyId.value = null
                bridge.activePlayer.value?.pause()
                showToast("Watch party ended")
            }
            "scrub_start" -> {
                // Begin a remote seek from the phone's scrubber — pause so the drag
                // preview lands on a stable frame; playback resumes on scrub_commit
                // if it was playing.
                val player = bridge.activePlayer.value
                if (player != null) {
                    scrubbingWasPlaying = player.playbackSnapshot.isPlaying
                    player.pause()
                }
            }
            "scrub_update" -> {
                // Live drag preview — the phone owns the scrub bar, so nothing to
                // apply per-frame; the TV stays paused until scrub_commit.
            }
            "scrub_commit" -> {
                msg.optLong("positionMs", -1L).takeIf { it >= 0L }?.let { positionMs ->
                    val player = bridge.activePlayer.value
                    player?.seekTo(positionMs)
                    if (scrubbingWasPlaying) player?.resume()
                }
                scrubbingWasPlaying = false
            }
            // Device-level media volume, 0–100 — the same stream the physical
            // remote's volume rocker adjusts.
            "stealth_volume" ->
                msg.optInt("percent", -1).takeIf { it in 0..100 }?.let { applyCompanionVolume(it) }
            // Raw DPAD/OK/back key from the phone remote — dispatched to the
            // foreground activity like a physical key press.
            "stealth_keyevent" ->
                msg.optInt("keyCode", 0).takeIf { it > 0 }?.let { dispatchCompanionKey(it) }
            // Open the TV's Search screen so the phone remote's keyboard/speech
            // input can type into the search field.
            "stealth_search" -> bridge.requestSearchScreen()
            // Whole-text replacement for the Search field (phone remote keyboard /
            // voice). Forwarded to whatever Search screen is in front.
            "keyboard_input" ->
                bridge.activeSearchInput.value?.onRemoteText(msg.optString("text"))
            // Enter from the phone remote's keyboard / voice search.
            "keyboard_submit" -> bridge.activeSearchInput.value?.submit()
            "stop" -> bridge.activePlayer.value?.stop()
            "companion_paired" -> showToast("Phone connected")
            "companion_unpaired" -> showToast("Phone disconnected")
            // Roku-style private listening (phone remote, N2): tee this TV's decoded audio to the
            // phone over direct LAN UDP. phoneIp/port are phone-supplied — the hub relays them, it
            // never derives them (R3). Explicit stop and the hub's heartbeat-driven
            // audio_fork_stop { reason: "phone_timeout" } both land here.
            "audio_fork_start" -> handleAudioForkStart(msg)
            "audio_fork_stop" -> {
                val player = bridge.activePlayer.value
                val wasActive = player?.isPhoneAudioForkActive == true
                player?.stopPhoneAudioFork()
                if (msg.optString("reason") == "phone_timeout") {
                    // The hub just declared this phone dead — don't ack into the relay path it closed.
                    Log.i(TAG, "Private listening ended: phone heartbeat timed out")
                } else if (wasActive) {
                    // Ack only when the stop actually changed state (review F2) — an explicit stop
                    // with nothing forked stays silent rather than claiming a state change.
                    sendAudioForkStatus(status = "stopped")
                }
            }
            else -> Unit
        }
    }

    /**
     * Arm private listening to the phone that requested it. The phone must supply its actual LAN
     * IP + the UDP port it is listening on (R3); with no active player, or an address the player
     * cannot fork to (mpv engine, no Exo sink), the request fails with a reason the phone can show.
     */
    private fun handleAudioForkStart(msg: JSONObject) {
        val player = bridge.activePlayer.value
        if (player == null) {
            sendAudioForkStatus(status = "error", reason = "no_active_player")
            return
        }
        val phoneIp = msg.optString("phoneIp")
        val port = msg.optInt("port", -1)
        // Pre-flight before arming (Q6 / review F1): the phone must be a literal IPv4 on the TV's
        // own subnet. Rejecting here avoids acking "started" to a phone on guest Wi-Fi or another
        // VLAN (which would then deliver silence), and avoids the async dead-worker case where a
        // malformed address acks "started" and only then dies inside the sender's runWorker.
        if (port !in 1..65535 || parseIpv4(phoneIp) == null) {
            sendAudioForkStatus(status = "error", reason = "bad_phone_address")
            return
        }
        if (!isPhoneOnTvSubnet(phoneIp)) {
            sendAudioForkStatus(status = "error", reason = "different_network")
            return
        }
        if (player.startPhoneAudioFork(phoneIp, port)) {
            Log.i(TAG, "Private listening started -> $phoneIp:$port")
            sendAudioForkStatus(status = "started")
        } else {
            // Engine not Exo, sink not live, or a fork is already running.
            sendAudioForkStatus(status = "error", reason = "fork_unavailable")
        }
    }

    /**
     * Ack an audio_fork command back to the phone. `bsc/services/device-relay.js` forwards any
     * `audio_fork`-typed frame from a TV to its paired phone, so acking with the same type gives
     * the phone's private-listening UI a reliable started/stopped/error signal.
     */
    private fun sendAudioForkStatus(status: String, reason: String? = null) {
        webSocket?.send(JSONObject().apply {
            put("type", "audio_fork")
            put("status", status)
            reason?.let { put("reason", it) }
        }.toString())
    }

    private fun audioManager(): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Apply a companion volume percent (0–100). First tries the device's media
     * stream (the physical remote's volume). On Android 12+ the OS blocks
     * third-party apps from moving a stream they don't own, so if the change
     * doesn't stick we scale the active player instead — guaranteed audible on
     * the current playback. Runs on the main thread (ExoPlayer rule).
     */
    private fun applyCompanionVolume(percent: Int) {
        val pct = percent.coerceIn(0, 100)
        companionVolumePercent = pct
        val player = bridge.activePlayer.value

        val am = audioManager()
        val max = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        if (am != null && max > 0) {
            val index = (pct * max / 100).coerceIn(0, max)
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
            val applied = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (applied == index) {
                // The OS honored the device-stream change — undo any previous
                // player scaling so loudness isn't applied twice.
                player?.setVolume(1f)
                return
            }
        }
        // Device-stream change blocked (or no audio stream available): scale the
        // active player. No player means nothing to scale — best-effort only.
        player?.setVolume(pct / 100f)
    }

    /** Current device media-stream volume as a 0–100 percent, for telemetry. */
    private fun deviceVolumePercent(): Int {
        val am = audioManager() ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).coerceIn(0, 100)
    }

    /**
     * Deliver a raw Android key (DPAD up/down/left/right, center/OK, back) to the
     * resumed activity exactly as a physical remote press would — dispatchKeyEvent
     * routes through the decor view to Compose's own focus/back handling.
     * handleInbound() already runs on the main thread, which key dispatch requires.
     */
    private fun dispatchCompanionKey(keyCode: Int) {
        val activity = resumedActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0)
        )
        activity.dispatchKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0)
        )
    }

    private fun handlePlay(msg: JSONObject) {
        val url = msg.optString("url")
        if (url.isBlank()) return
        val partyId = msg.optString("partyId").takeIf { it.isNotBlank() }
        _currentPartyId.value = partyId
        bridge.postPlayRequest(
            CompanionPlayRequest(
                streamUrl = url,
                title = msg.optString("title").takeIf { it.isNotBlank() },
                imdbId = msg.optString("imdbId").takeIf { it.isNotBlank() },
                season = msg.optString("season").toIntOrNull(),
                episode = msg.optString("episode").toIntOrNull(),
                resumeFromMs = msg.optLong("resumeFrom", 0L).coerceAtLeast(0L),
                startPaused = !msg.optBoolean("autoPlay", true),
                partyId = partyId,
                source = msg.optString("source").takeIf { it.isNotBlank() }
            )
        )
    }

    private fun ensureTelemetryLoop() {
        if (telemetryJob?.isActive == true) return
        telemetryJob = scope.launch {
            while (isActive) {
                val player = bridge.activePlayer.value
                if (player != null) {
                    withContext(Dispatchers.Main) {
                        sendPlaybackPosition(player.playbackSnapshot)
                    }
                    lastTelemetryWasActive = true
                } else if (lastTelemetryWasActive) {
                    sendPlaybackStopped()
                    lastTelemetryWasActive = false
                }
                delay(1_000L)
            }
        }
    }

    private fun sendPlaybackPosition(snapshot: CompanionPlaybackSnapshot) {
        val payload = JSONObject().apply {
            put("type", "playback_position")
            put("deviceId", syncClientIdentity.currentClientId())
            put("positionMs", snapshot.positionMs)
            put("durationMs", snapshot.durationMs)
            put("isPlaying", snapshot.isPlaying)
            put("streamUrl", snapshot.streamUrl)
            snapshot.imdbId?.let { put("imdbId", it) }
            snapshot.title?.let { put("title", it) }
            snapshot.season?.let { put("season", it) }
            snapshot.episode?.let { put("episode", it) }
            snapshot.posterUrl?.let { put("posterUrl", it) }
            snapshot.logoUrl?.let { put("logoUrl", it) }
            put("volumePercent", companionVolumePercent ?: deviceVolumePercent())
        }
        webSocket?.send(payload.toString())
    }

    private fun sendPlaybackStopped() {
        webSocket?.send(JSONObject().apply {
            put("type", "playback_stopped")
            put("deviceId", syncClientIdentity.currentClientId())
        }.toString())
    }

    private fun scheduleReconnect() {
        if (companionUrl.isBlank()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffMs = (1_000L * 2.0.pow(reconnectAttempts.coerceAtMost(5))).toLong()
                .coerceAtMost(30_000L)
            delay(backoffMs)
            reconnectAttempts++
            webSocket = null
            if (isActive) connect()
        }
    }

    private fun showToast(message: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Best-effort LAN IPv4, used by the hub for party member IP discovery (register) and by the
     * private-listening pre-flight [isPhoneOnTvSubnet] to compare the phone's subnet against the
     * TV's own. Resolved from the up network interfaces — NOT the old connect-to-8.8.8.8
     * default-route trick: that did real socket I/O, which threw NetworkOnMainThreadException when
     * the pre-flight ran from the main-thread message handler (audio_fork_start lands here via
     * runOnMain in [listener]) and was swallowed into null, failing a same-LAN fork as
     * "different_network". Pure local lookup — safe on any thread, no route/DNS dependency.
     * Prefers Wi-Fi/Ethernet site-local IPv4; skips loopback, virtual, tun/ppp/rmnet.
     */
    private fun bestEffortLanIp(): String? {
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (net in interfaces) {
                if (!net.isUp || net.isLoopback || net.isVirtual) continue
                val lower = net.name.lowercase()
                if ("tun" in lower || "ppp" in lower || "rmnet" in lower) continue
                val score = when {
                    lower.startsWith("wlan") -> 0
                    lower.startsWith("eth") || lower.startsWith("en") -> 1
                    else -> 2
                }
                for (addr in net.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (!isPrivateLanIp(ip)) continue
                    if (score < bestScore || (score == bestScore && best == null)) {
                        bestScore = score
                        best = ip
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }
        return best
    }

    /** True for the RFC1918 site-local ranges the companion fleet's LAN lives on. */
    private fun isPrivateLanIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        if (first == 10) return true
        if (first == 192 && parts[1] == "168") return true
        if (first == 172) {
            val second = parts[1].toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /**
     * Parse a dotted-quad IPv4 literal, or null. Private listening requires the phone to send its
     * actual LAN IP as a literal — the fork is a unicast UDP stream and a hostname has no place in
     * that contract (it would also ack "started" and only then fail async in the sender).
     */
    private fun parseIpv4(ip: String): IntArray? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v
        }
        return out
    }

    /**
     * True when [phoneIp] is a literal IPv4 on the same /24 subnet as the TV's own LAN address.
     * The companion fleet sits on flat /24 LANs (the hub itself is 192.168.68.x). A phone on guest
     * Wi-Fi or another VLAN would otherwise ack "started" and stream into the void (Q6 — same-subnet
     * requirement). Fails closed (rejects) when the TV cannot determine its own address or the phone
     * IP is not IPv4.
     */
    private fun isPhoneOnTvSubnet(phoneIp: String): Boolean {
        val phone = parseIpv4(phoneIp) ?: return false
        val tv = parseIpv4(bestEffortLanIp() ?: return false) ?: return false
        return phone[0] == tv[0] && phone[1] == tv[1] && phone[2] == tv[2]
    }
}
