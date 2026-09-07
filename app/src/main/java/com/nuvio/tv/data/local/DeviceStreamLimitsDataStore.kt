package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.streams.CodecFamily
import com.nuvio.tv.core.streams.CodecLimitConfig
import com.nuvio.tv.core.streams.DeviceStreamLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile "device stream limits": a master toggle plus, per codec family, an
 * enable flag and a Mbps ceiling. Mirrors the StreamBadge/Tmdb DataStore pattern
 * (ProfileDataStoreFactory, keyed off the active profile). Values are stored as
 * discrete preference keys per field so defaulting and per-row setters stay
 * trivial and there is no serialized blob to migrate.
 */
@Singleton
class DeviceStreamLimitsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    companion object {
        const val FEATURE = "device_stream_limits"
        private const val MASTER_ENABLED_KEY = "device_stream_limits_enabled"

        /** Ceiling stepper bounds, shared with the settings UI (1 Mbps steps). */
        const val MIN_CEILING_MBPS = 1
        const val MAX_CEILING_MBPS = 120
    }

    private val masterEnabledKey = booleanPreferencesKey(MASTER_ENABLED_KEY)

    private fun codecEnabledKey(family: CodecFamily) =
        booleanPreferencesKey("codec_${family.canonical}_enabled")

    private fun codecMbpsKey(family: CodecFamily) =
        intPreferencesKey("codec_${family.canonical}_mbps")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    /** Live settings for the active profile. Defaults: master ON, all families on
     *  at their suggested ceiling (all-on preserves today's no-drops Gate-2
     *  behavior — ceilings carry the real filtering). */
    val settings: Flow<DeviceStreamLimits> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data
    }.map { prefs ->
        DeviceStreamLimits(
            enabled = prefs[masterEnabledKey] ?: true,
            limits = CodecFamily.entries.map { family ->
                CodecLimitConfig(
                    family = family,
                    enabled = prefs[codecEnabledKey(family)] ?: true,
                    ceilingMbps = prefs[codecMbpsKey(family)] ?: family.defaultCeilingMbps,
                )
            },
        )
    }

    suspend fun setMasterEnabled(enabled: Boolean) {
        store().edit { it[masterEnabledKey] = enabled }
    }

    suspend fun setCodecEnabled(family: CodecFamily, enabled: Boolean) {
        store().edit { it[codecEnabledKey(family)] = enabled }
    }

    suspend fun setCodecCeilingMbps(family: CodecFamily, mbps: Int) {
        store().edit { it[codecMbpsKey(family)] = mbps.coerceIn(MIN_CEILING_MBPS, MAX_CEILING_MBPS) }
    }
}
