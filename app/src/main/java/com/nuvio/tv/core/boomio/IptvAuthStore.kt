package com.nuvio.tv.core.boomio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
 * Persists the IPTV edge session for this install.
 *
 * Install-level, not per-profile: pairing is a property of the *device* (the
 * edge records the device id), so every profile on this TV shares one paired
 * session. The session the edge mints lives 90 days, which is why losing it
 * must be cheap to recover — see [clear], called when the edge rejects a token
 * so the UI falls back to pairing instead of failing forever.
 *
 * The token is a bearer credential; it is never logged.
 */
@Singleton
class IptvAuthStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.iptvAuthDataStore

    private val sessionTokenKey = stringPreferencesKey("session_token")
    private val deviceIdKey = stringPreferencesKey("device_id")

    val sessionToken: Flow<String?> = dataStore.data.map { it[sessionTokenKey] }

    suspend fun currentToken(): String? = sessionToken.first()

    suspend fun setSessionToken(token: String) {
        dataStore.edit { it[sessionTokenKey] = token }
    }

    /** Drop the session but keep [deviceId] — a re-pair stays the same device. */
    suspend fun clearSession() {
        dataStore.edit { it.remove(sessionTokenKey) }
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
}
