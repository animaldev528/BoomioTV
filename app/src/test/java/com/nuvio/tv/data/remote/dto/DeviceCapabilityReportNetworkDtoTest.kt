package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape checks for the network section of the device-caps/1 payload (bandwidth-report plan §5/§6.1):
 * the measured value must serialize under the documented snake_case key (`estimated_bandwidth_mbps`,
 * `is_metered`, …) — the key the bsm fleet / operators read — and no network bandwidth key may appear
 * before the meter has a value.
 */
class DeviceCapabilityReportNetworkDtoTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(DeviceCapabilityReportDto::class.java)

    @Test
    fun `network section serializes measured bandwidth under the snake_case key`() {
        val report = DeviceCapabilityReportDto(
            device = DeviceInfoDto(installId = "nuvio-tv-test", manufacturer = "Acme", model = "Box"),
            network = NetworkCapabilitiesDto(
                type = "wifi",
                estimatedBandwidthMbps = 90.4,
                frequencyGhz = 5.0,
                signalStrengthDbm = -55,
                isMetered = false
            )
        )
        val json = adapter.toJson(report)
        assertTrue("measured Mbps under documented key", json.contains("\"estimated_bandwidth_mbps\":90.4"))
        assertTrue("transport type present", json.contains("\"type\":\"wifi\""))
        assertTrue("metered flag under documented key", json.contains("\"is_metered\":false"))
        assertTrue("frequency under documented key", json.contains("\"frequency_ghz\":5.0"))
        assertFalse("no camelCase leak", json.contains("estimatedBandwidthMbps"))
        assertFalse("no camelCase metered leak", json.contains("isMetered"))
    }

    @Test
    fun `no bandwidth value leaks when network absent entirely`() {
        val json = adapter.toJson(DeviceCapabilityReportDto())
        assertFalse("no estimated_bandwidth_mbps before a measurement", json.contains("estimated_bandwidth_mbps"))
    }
}
