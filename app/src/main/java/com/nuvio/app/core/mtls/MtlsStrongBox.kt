package com.nuvio.app.core.mtls

/**
 * Which keystore security levels to attempt, in order — the pure half of P2.1's
 * "StrongBox, falling back to TEE".
 *
 * ⚠️ **Why this is its own file with no Android import.** Everything else in P2.1 touches
 * `KeyGenParameterSpec` and the `AndroidKeyStore` provider, neither of which exists off a device —
 * so the generation path can only be *compiled*, never unit-tested. The order of attempts is the
 * one part that is plain arithmetic, and keeping it here means that part is assertable in the host
 * suite rather than resting on a reading of the code.
 *
 * The two rules it encodes:
 *
 * - **Never attempt StrongBox below API 28.** `KeyGenParameterSpec.Builder.setIsStrongBoxBacked`
 *   does not exist before 28, so an unguarded call is a `NoSuchMethodError` on a 7.x device — and
 *   `minSdk` is 24. The guard is a compile-time API requirement, not a preference.
 * - **Always end at the TEE, and never be empty.** The caller loops over this list and throws the
 *   last failure if every attempt fails, so an empty plan would turn "StrongBox is unusual" into
 *   "no key at all". The fallback is the point of the row; it is not a retry of the same thing.
 *
 * ⚠️ **There is deliberately no `hasSystemFeature(FEATURE_STRONGBOX_KEYSTORE)` check.** It would
 * cost a `Context` dependency to skip one millisecond of a failed key generation, and it is not
 * reliable anyway: a device can carry StrongBox without declaring the feature, and some OEMs
 * declare it without the keymaster HAL behind it agreeing. The attempt-and-catch in `MtlsIdentity`
 * is the mechanism; a feature probe would only be an optimisation that can be wrong.
 */
internal object MtlsStrongBox {

    /** `setIsStrongBoxBacked` arrived in API 28. Below it, the call is a `NoSuchMethodError`. */
    const val MIN_SDK: Int = 28

    /**
     * The `strongBox` flag for each generation attempt, in order.
     *
     * `true` means "ask for StrongBox backing"; `false` means "TEE". The result is never empty and
     * always ends with `false`.
     */
    fun attempts(sdkInt: Int): List<Boolean> =
        if (sdkInt >= MIN_SDK) listOf(true, false) else listOf(false)
}
