package com.nuvio.app.core.mtls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The StrongBox→TEE attempt order.
 *
 * ⚠️ **Read this as the boundary of what P2.1 can be tested at all.** Key generation itself cannot
 * be — `AndroidKeyStore` and `KeyGenParameterSpec` do not exist off a device and Robolectric does
 * not emulate a real keystore provider — so this file covers the one piece of P2.1 that is arithmetic
 * rather than a platform call: which security levels are attempted, in what order, on which API
 * levels. The device half is the plan's P2 verify line, and it is honest to say so.
 *
 * No Robolectric: `MtlsStrongBox` has no Android import at all, which is exactly why it was split
 * out of `MtlsIdentity`.
 */
class MtlsStrongBoxTest {

    @Test
    fun `StrongBox arrives in API 28`() {
        // The constant is the guard `MtlsIdentity.spec` uses to decide whether the method exists.
        assertEquals(28, MtlsStrongBox.MIN_SDK)
    }

    @Test
    fun `below API 28 only the TEE is attempted`() {
        // ⚠️ An unguarded StrongBox request on a 7.x device is a `NoSuchMethodError` at the moment
        // a user enrols. minSdk is 24, so 24..27 are real devices, not a hypothetical.
        assertEquals(listOf(false), MtlsStrongBox.attempts(24))
        assertEquals(listOf(false), MtlsStrongBox.attempts(27))
    }

    @Test
    fun `at API 28 and above StrongBox is tried before the TEE`() {
        assertEquals(listOf(true, false), MtlsStrongBox.attempts(28))
        assertEquals(listOf(true, false), MtlsStrongBox.attempts(35))
    }

    @Test
    fun `every plan ends at the TEE`() {
        // ⚠️ The property that keeps a StrongBox-only device quirk from becoming "no identity at
        // all": the caller throws only after the last attempt, so a plan that did not end at the
        // TEE would turn an unusual device into an unenrollable one.
        for (sdk in 21..40) {
            assertFalse(MtlsStrongBox.attempts(sdk).last(), "plan for SDK $sdk does not end at the TEE")
        }
    }

    @Test
    fun `no plan is empty and none attempts StrongBox twice`() {
        for (sdk in 21..40) {
            val plan = MtlsStrongBox.attempts(sdk)
            assertTrue(plan.isNotEmpty(), "empty plan for SDK $sdk would throw with no attempt made")
            assertTrue(plan.count { it } <= 1, "SDK $sdk attempts StrongBox more than once")
            assertTrue(plan.size <= 2, "SDK $sdk grows the attempt list unexpectedly: $plan")
        }
    }
}
