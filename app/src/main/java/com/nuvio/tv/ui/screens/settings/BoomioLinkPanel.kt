@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.app.features.boomio.BoomioLinkState
import com.nuvio.app.features.boomio.BoomioSessionRepository

/**
 * The link state machine, rendered once.
 *
 * ⚠️ **This exists so its two call sites cannot drift.** The first-run setup gate and the settings
 * row show the same five states and the same verbs; a copy of the `when` below in each of them
 * would disagree the first time a state is added, and the copy that disagreed would be whichever
 * one nobody happened to be looking at. Mobile reached the same conclusion for the same reason --
 * this mirrors the `UnlinkedCard` it renders from both `DeviceSetupGate` and `CompanionScreen`.
 *
 * [linked] is the one thing a caller varies, and it is a parameter rather than a copied branch
 * because the difference is in the *state*, not the wording: the settings row can be reached by a
 * TV that is already paired and then offers Unlink, while the setup gate stops being composed the
 * moment a session appears. A state that cannot be entered is a branch no test can reach, so the
 * gate never passes `true` -- it is not asked to, because it is gone by then.
 *
 * [focusRequester] is for the gate, which is a whole screen and has to put the D-pad somewhere.
 * Every branch renders exactly one row, so it goes on all of them rather than on a chosen one;
 * `.focusRequester` has to precede whatever `SettingsActionRow` applies internally, which is why
 * it is passed as the row's `modifier` rather than requested from outside.
 */
@Composable
internal fun BoomioLinkPanel(
    linkState: BoomioLinkState,
    linkError: String?,
    linked: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    SettingsGroupCard(modifier = modifier.fillMaxWidth()) {
        val rowModifier = if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)

        when (val link = linkState) {
            is BoomioLinkState.AwaitingApproval -> SettingsActionRow(
                title = stringResource(R.string.overlay_link_code_title),
                subtitle = stringResource(
                    R.string.overlay_link_code_subtitle,
                    link.userCode,
                    link.verificationUri
                        ?: stringResource(R.string.overlay_link_code_no_uri)
                ),
                value = stringResource(R.string.overlay_link_cancel),
                onClick = { BoomioSessionRepository.cancelLink() },
                modifier = rowModifier,
                trailingIcon = Icons.Default.Close
            )

            // ⚠️ Cancel, never "try again". A second exchange would mint a second code, and the
            // approver is looking at the first one.
            is BoomioLinkState.Starting -> SettingsActionRow(
                title = stringResource(R.string.overlay_link_title),
                subtitle = stringResource(R.string.overlay_link_working),
                value = stringResource(R.string.overlay_link_cancel),
                onClick = { BoomioSessionRepository.cancelLink() },
                modifier = rowModifier,
                trailingIcon = Icons.Default.Close
            )

            is BoomioLinkState.Failed -> SettingsActionRow(
                title = stringResource(R.string.overlay_link_title),
                subtitle = overlayLinkFailureText(link.reason, linkError),
                value = stringResource(R.string.overlay_link_retry),
                onClick = { BoomioSessionRepository.startLink() },
                modifier = rowModifier,
                trailingIcon = Icons.Default.Refresh
            )

            is BoomioLinkState.Idle -> if (!linked) {
                SettingsActionRow(
                    title = stringResource(R.string.overlay_link_title),
                    subtitle = stringResource(R.string.overlay_link_subtitle),
                    value = stringResource(R.string.overlay_link_action),
                    onClick = { BoomioSessionRepository.startLink() },
                    modifier = rowModifier
                )
            } else {
                SettingsActionRow(
                    title = stringResource(R.string.overlay_link_title),
                    subtitle = stringResource(R.string.overlay_link_linked),
                    value = stringResource(R.string.overlay_link_unlink),
                    onClick = { BoomioSessionRepository.unlink() },
                    modifier = rowModifier,
                    trailingIcon = Icons.Default.Close
                )
            }
        }
    }
}
