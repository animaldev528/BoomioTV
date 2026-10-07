package com.nuvio.app.features.boomio

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the linked session token lives between launches.
 *
 * ⚠️ **Plain Android, not mobile's `expect object`.** Mobile needs `expect`/`actual` because it is
 * KMP and iOS keeps the same token in `NSUserDefaults`; the TV module is Android-only, so an
 * `expect` here would declare exactly one actual and buy nothing.
 *
 * ⚠️ **Persistence is load-bearing, not a convenience.** `OverlayEnrollment` fires off
 * [BoomioSessionRepository.session], and the TV has no device-code poll to re-mint a token on some
 * later launch — so a token that does not survive a reboot means a TV that never enrolls again,
 * silently, with the pairing still live on the server.
 *
 * Deliberately **not** carrying mobile's `pairedDeviceId`. Nothing on this app reads it: mobile
 * stores it to relate a session to a server-side device record, while here the stable identity is
 * the WireGuard public key, which the tunnel controller already owns and persists.
 */
internal object BoomioSessionStorage {

    private const val PREFS = "boomio_session"
    private const val KEY_SESSION_TOKEN = "session_token"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Binds the store to a context.
     *
     * Idempotent, like the overlay's own singletons — `onCreate` can run again after a
     * configuration-forced process restart.
     */
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** The persisted token, or null when this install has never linked. */
    fun loadSessionToken(): String? =
        prefs?.getString(KEY_SESSION_TOKEN, null)?.takeIf { it.isNotBlank() }

    /**
     * Persists a freshly linked token.
     *
     * ⚠️ **Fails loudly when uninitialized rather than writing nowhere.** A silent no-op here would
     * let the session publish, the enrollment succeed, and the TV come back from its next reboot
     * with no credential and no explanation. [BoomioSessionStorage.initialize] is called from
     * `NuvioApplication.onCreate` before anything can link, so this cannot fire in practice — it is
     * here so that if that ever stops being true, it says so.
     */
    fun saveSessionToken(token: String) {
        edit().putString(KEY_SESSION_TOKEN, token).apply()
    }

    /** Forgets the token. */
    fun clearSessionToken() {
        edit().remove(KEY_SESSION_TOKEN).apply()
    }

    private fun edit(): SharedPreferences.Editor = checkNotNull(prefs) {
        "BoomioSessionStorage.initialize() must run before a session is written"
    }.edit()
}
