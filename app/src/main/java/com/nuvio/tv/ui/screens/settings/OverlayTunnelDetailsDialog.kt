package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.app.core.overlay.OverlayEndpointStatus
import com.nuvio.app.core.overlay.OverlaySession
import com.nuvio.app.core.overlay.OverlayTunnelUiState
import com.nuvio.app.core.overlay.OverlayWgTunnelController
import com.nuvio.app.features.boomio.BoomioConfig
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The tunnel as a panel rather than a toggle: the state, the one control, and the identities
 * behind them.
 *
 * The settings row used to carry the action itself, which left a person who wanted to *look* at
 * the tunnel with nothing to open, and a person who wanted to act with a label they had to press
 * to decode. Both live here now: the row says "Details", this says what the tunnel is doing and
 * offers the button that changes it.
 *
 * The field set mirrors the mobile VPN screen (device name, tunnel address, address on the
 * overlay, endpoint, server public key) and adds the device's own public key, which the server's
 * peer table is keyed on and which is the first thing worth checking when a tunnel connects but
 * routes nothing.
 *
 * ⚠️ **No private key, ever.** [OverlayWgTunnel.status] is wireguard-go's `IpcGet` dump with
 * `private_key`/`preshared_key` already redacted; this screen reads only that projection and
 * never the keypair itself. A dialog is the most screenshot-able surface in the app.
 */
@Composable
internal fun OverlayTunnelDetailsDialog(
    tunnelState: OverlayTunnelUiState,
    endpointStatus: OverlayEndpointStatus,
    onDismiss: () -> Unit,
) {
    val connected = tunnelState is OverlayTunnelUiState.Connected
    // Opening the panel lands the remote on the button, not on nothing: the details are text,
    // and a TV with no focus ring is a TV where the user presses select and waits for something
    // to happen. `requestFocus` before the first layout throws, hence the guard.
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { actionFocus.requestFocus() }
    }

    // Read once, when the panel opens: `IpcGet` is a synchronous call into wireguard-go and
    // nothing here is worth re-reading every frame. A snapshot that is a second old beats a
    // dump taken on the composition thread of every recomposition.
    val liveStatus = remember {
        runCatching { OverlayWgTunnelController.instance?.status() }.getOrNull()
    }
    val devicePublicKey = remember {
        runCatching { OverlayWgTunnelController.instance?.publicKeyBase64() }.getOrNull()
    }
    // The peer's address as the tunnel actually resolved it, which is the answer while the
    // tunnel is up; the configured endpoint is what it would use next time. `endpoint` is the
    // one UAPI field here that is *not* key material.
    val liveEndpoint = remember(liveStatus) { liveStatus.uapiValue("endpoint") }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.overlay_connection_title),
        subtitle = overlayConnectionText(tunnelState),
        width = 720.dp,
        suppressFirstKeyUp = false,
    ) {
        // ⚠️ The control sits ABOVE the scrolling details, not below them. `NuvioDialog` clips
        // its container and its content column does not scroll, so a scrollable child measured
        // ahead of the buttons takes the whole remaining height and the buttons end up outside
        // the clip -- drawn, focusable, and invisible. The one control this panel exists for is
        // not allowed to be the thing that falls off the bottom.
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.overlay_details_close),
                onClick = onDismiss,
            )
            SettingsDialogActionButton(
                text = if (connected) {
                    stringResource(R.string.overlay_connection_disconnect)
                } else {
                    stringResource(R.string.overlay_connection_reconnect)
                },
                onClick = {
                    // Same two calls the row used to make; the panel stays open so the state
                    // line above can show the result of the press.
                    if (connected) OverlaySession.disconnect() else OverlaySession.reconnect()
                },
                primary = true,
                // Nothing to reconnect to before the ladder has any answer at all; once the
                // user has disconnected by hand it stays live even from `Idle`, because
                // Reconnect re-walks the ladders and is their retry button too.
                enabled = connected ||
                    tunnelState !is OverlayTunnelUiState.Down ||
                    endpointStatus !is OverlayEndpointStatus.Idle,
                modifier = Modifier.focusRequester(actionFocus),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            DetailsSection(stringResource(R.string.overlay_details_section_interface)) {
                DetailsRow(R.string.overlay_details_device_name, BoomioConfig.overlayDeviceName)
                DetailsRow(R.string.overlay_details_tunnel_address, BoomioConfig.overlayLocalCidr)
                DetailsRow(R.string.overlay_details_overlay_address, BoomioConfig.overlayServerAddress)
                DetailsRow(R.string.overlay_details_client_key, devicePublicKey)
            }
            DetailsSection(stringResource(R.string.overlay_details_section_peer)) {
                DetailsRow(R.string.overlay_details_endpoint, liveEndpoint ?: BoomioConfig.overlayEndpoint)
                DetailsRow(R.string.overlay_details_server_key, BoomioConfig.overlayServerPubKey)
            }
            Text(
                text = stringResource(R.string.overlay_details_private_note),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
            )
        }
    }
}

@Composable
private fun DetailsSection(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextTertiary,
        )
        content()
    }
}

/**
 * Label above value, the shape the mobile VPN screen uses: these values are long base64 keys and
 * `host:port` authority strings, and a side-by-side row either wraps them mid-token or clips
 * them at exactly the part worth reading.
 */
@Composable
private fun DetailsRow(@StringRes labelRes: Int, value: String?) {
    val unset = stringResource(R.string.overlay_details_unset)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
        )
        Text(
            text = value?.trim().takeUnless { it.isNullOrEmpty() } ?: unset,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = NuvioTheme.colors.TextPrimary,
        )
    }
}

/** One `key=value` line out of wireguard-go's UAPI dump, or null. */
private fun String?.uapiValue(key: String): String? =
    this?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
