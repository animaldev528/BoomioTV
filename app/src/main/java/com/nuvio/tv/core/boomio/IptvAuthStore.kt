package com.nuvio.tv.core.boomio

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.app.features.boomio.BoomioSession
import com.nuvio.app.features.boomio.BoomioSessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.iptvAuthDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "iptv_auth",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * The device's Boomio session, as this app's older call sites reach it.
 *
 * ⚠️ **This is a view now, and it used to be a store — which was the whole bug.**
 *
 * The session belongs to the *device*: linking mints one `bs_ses_…` that every
 * device-scoped service accepts, and [BoomioSessionRepository] is what holds and
 * persists it (surviving reboot; the same token `MtlsRegistration` and
 * `SecurityPolicyRefresh` already present). This class used to keep a **second,
 * private copy** in its own `iptv_auth` DataStore, written from exactly one
 * place — the Pair control on the IPTV screen. Once IPTV stopped being a
 * surface, nothing wrote that copy any more, so every reader of it saw "not
 * linked" on a TV that was in fact linked and enrolled: music search told the
 * viewer to *"Pair the TV from the Boomio dashboard"* on a device holding a
 * perfectly valid session. Reading the one real session is the fix, and there is
 * now no second copy here to drift out of step.
 *
 * Install-level, not per-profile: the session is a property of the device, so
 * every profile on this TV shares it. It lives 90 days.
 *
 * The token is a bearer credential; it is never logged.
 */
@Singleton
class IptvAuthStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.iptvAuthDataStore

    private val deviceIdKey = stringPreferencesKey("device_id")

    /** The device session's token, or null while this TV is not linked. */
    val sessionToken: Flow<String?> = BoomioSessionRepository.session.map { it?.token }

    suspend fun currentToken(): String? = BoomioSessionRepository.bearerToken()

    /**
     * Adopts a session that a pairing flow has just minted.
     *
     * Publishing to the shared repository is deliberate: an approved pairing
     * *is* the device's session, so whichever control obtained it — the link
     * panel or a device-code poll — everything device-scoped should see it.
     */
    suspend fun setSessionToken(token: String) {
        BoomioSessionRepository.publish(BoomioSession(token))
    }

    /**
     * A service rejected this device's token. **The session is deliberately kept.**
     *
     * This used to delete the token outright, and that is the wrong shape for a
     * credential the whole device shares: one IPTV 401 unlinked the device for
     * cert registration, policy refresh and music search alike, silently, with
     * nothing logged to say it had happened. Dropping a session is a decision a
     * person makes — Unlink on the This TV row ([BoomioSessionRepository.unlink])
     * — not a side effect of one feature's request failing.
     */
    suspend fun clearSession() {
        Log.w(
            TAG,
            "a Boomio service rejected this device's session; keeping it. " +
                "Unlink from the This TV row in Settings to drop it deliberately."
        )
    }

    /**
     * A stable id for this install, created once. The edge keys the pairing
     * code and the resulting session to this, and bsm's device registry
     * enriches the session from it, so it must not change between launches.
     */
    suspend fun deviceId(): String {
        dataStore.data.first()[deviceIdKey]?.let { return it }
        val fresh = UUID.randomUUID().toString()
        dataStore.edit { it[deviceIdKey] = fresh }
        return fresh
    }

    private companion object {
        const val TAG = "IptvAuthStore"
    }
}
