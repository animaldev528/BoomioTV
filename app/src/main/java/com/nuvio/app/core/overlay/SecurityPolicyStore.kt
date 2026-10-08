package com.nuvio.app.core.overlay

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonObject

private const val TAG = "SecurityPolicy"

/**
 * The last policy this device was told, on disk.
 *
 * ⚠️ **Plain `SharedPreferences`, and there is nothing here to protect.** The four booleans are not
 * secrets — they describe a routing preference the server publishes to every client — so an
 * `AndroidKeyStore` would buy nothing, exactly as `OverlayEnrollment`'s PREFS note argues for the
 * assignment beside it.
 *
 * ⚠️ **The cache is what makes the asymmetry rule hold.** The owner's rule is that loosening the
 * policy must reach an already-deployed client without a wipe, and a client is *running* when the
 * server loosens it. A cache that lived only in memory would be forgotten on the next process start,
 * and a still-offline device would silently revert to the strict defaults until it could reach the
 * server again — which is the "stranded client" the rule exists to prevent.
 */
internal object SecurityPolicyStore {

    private const val PREFS = "boomio_security_policy"
    private const val KEY = "policy"

    /** The cached policy, or null when this device has never been told one. */
    fun load(context: Context): SecurityPolicy? = try {
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        parseSecurityPolicy(store.getString(KEY, null))
    } catch (t: Throwable) {
        // A corrupt preferences file must not crash the app on start-up; "no policy" is a policy.
        Log.w(TAG, "Could not read the cached security policy", t)
        null
    }

    /** Persists [policy]. Best effort: failing to write must not fail the fetch that produced it. */
    fun save(context: Context, policy: SecurityPolicy) {
        try {
            val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val text: JsonObject = policy.toJsonObject()
            store.edit().putString(KEY, text.toString()).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not cache the security policy", t)
        }
    }
}
