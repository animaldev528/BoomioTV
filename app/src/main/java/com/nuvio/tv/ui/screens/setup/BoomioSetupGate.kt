@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.screens.settings.BoomioLinkPanel
import com.nuvio.tv.ui.screens.settings.SettingsActionRow
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.overlayStatusText
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.app.core.overlay.LocalServerState
import com.nuvio.app.core.overlay.OverlayEndpointDiscovery
import com.nuvio.app.core.overlay.OverlayEndpointState
import com.nuvio.app.core.overlay.OverlayLocalDiscovery
import com.nuvio.app.core.overlay.OverlayTunnel
import com.nuvio.app.features.boomio.BoomioSessionRepository

/**
 * The first-run setup step: pair this TV with its server, **before** the sign-in screen.
 *
 * **Why this is not the settings row, where the same machine already lives.** Pairing is not a
 * preference -- it is the thing that has to happen before anything else can. A TV that has never
 * paired has no tunnel, and the server it would sign in to is reached *through* that tunnel: this
 * app's Supabase URL is `serverConfiguration.backendUrl`, the self-hosted Nuvio server under the
 * host set the overlay pins, not a public cloud endpoint that would answer anyway. So a wiped TV
 * off the home network cannot sign in at all, and a link row that lives behind the sign-in screen
 * is a row that device can never reach. Mobile hit the identical loop and this mirrors its
 * `DeviceSetupGate`, which is mounted ahead of `Auth` in `AppGate` for the same reason.
 *
 * ⚠️ **It renders the same machine, not a copy of it.** [BoomioLinkPanel] owns the states -- idle,
 * starting, awaiting approval, failed -- and this is the second of its two call sites. One state
 * added there appears here for free.
 *
 * ⚠️ **Skipping is deliberately allowed, and it is not a nicety.** Pairing is a property of *this
 * deployment*, not of Nuvio, and a gate with no way past it would lock someone out of the app
 * entirely if the server it wants is not there to pair with. Skip is the escape hatch, and back
 * maps to it so the first screen a fresh install shows cannot become a trap for a TV remote.
 *
 * Success needs no callback: the link finishes by writing a session, the gate is mounted on that
 * session being absent, and it moves on by itself. `onSkip` exists only for the case where there
 * is nothing to wait for.
 *
 * ⚠️ **The status row is on this screen because pairing is not the first thing that has to work --
 * reaching the server is.** A TV that cannot find the overlay endpoint has nothing to pair
 * *against*, and until now the only place that said so was a diagnostics row buried in Settings,
 * behind the sign-in screen this device cannot get past.
 */
@Composable
internal fun BoomioSetupGate(
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkState by BoomioSessionRepository.linkState.collectAsStateWithLifecycle()
    val linkError by BoomioSessionRepository.error.collectAsStateWithLifecycle()
    val localServerStatus by LocalServerState.status.collectAsStateWithLifecycle()
    val overlayEndpointStatus by OverlayEndpointState.status.collectAsStateWithLifecycle()

    val primaryFocus = remember { FocusRequester() }
    // After a few frames, not immediately: the discovery walk is already running and can recompose
    // this screen out from under a request made during the first composition.
    LaunchedEffect(Unit) { primaryFocus.requestFocusAfterFrames(frames = 3) }

    BackHandler { onSkip() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                // Scrollable because the awaiting-approval state grows -- a code and a URI -- and
                // this is the first screen a fresh install shows, so it is the one most likely to
                // meet a small screen and a large font scale.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.overlay_setup_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(R.string.overlay_setup_description),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center
            )

            BoomioLinkPanel(
                linkState = linkState,
                linkError = linkError,
                // Never `true`: this screen stops being composed the moment a session appears, so
                // the linked branch is unreachable here by construction rather than by intent.
                linked = false,
                focusRequester = primaryFocus
            )

            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = stringResource(R.string.overlay_status_title),
                    subtitle = overlayStatusText(localServerStatus, overlayEndpointStatus),
                    value = stringResource(R.string.overlay_status_recheck),
                    onClick = {
                        // The same three seams the ladder walks, re-run on demand. Each is
                        // fire-and-forget and takes its own mutex, so a press during a walk that
                        // is already in flight is a no-op rather than a second walk.
                        OverlayLocalDiscovery.refreshAsync()
                        OverlayEndpointDiscovery.refreshAsync()
                        OverlayTunnel.refreshAsync()
                    }
                )
            }

            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = stringResource(R.string.overlay_setup_skip),
                    subtitle = stringResource(R.string.overlay_setup_skip_subtitle),
                    onClick = onSkip
                )
            }
        }
    }
}
