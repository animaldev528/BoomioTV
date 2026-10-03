package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.domain.model.IptvChannel
import com.nuvio.tv.domain.model.IptvNowNext

/**
 * State of the IPTV section.
 *
 * The section has four distinct things it can be showing, and keeping them as
 * separate fields (rather than one opaque "state" enum) lets the screen decide
 * precedence in one readable place:
 *
 *  - [configured] false  -> this build has no IPTV edge compiled in
 *  - [paired] false      -> show pairing (with [pairing] once a code is issued)
 *  - [unscoped] true     -> paired, but the household follows no channel groups
 *  - otherwise           -> the channel list, with [guide] filled in where the
 *                           panel actually has data
 */
data class IptvUiState(
    val configured: Boolean = true,
    val loading: Boolean = true,
    val paired: Boolean = false,
    val pairing: IptvPairingUi? = null,
    val channels: List<IptvChannel> = emptyList(),
    val guide: Map<String, IptvNowNext> = emptyMap(),
    val unscoped: Boolean = false,
    val error: String? = null
)

/**
 * The code a viewer reads off the screen and approves elsewhere.
 *
 * [expired] is a first-class state rather than an error string: a device code
 * lives 5 minutes, so running out is an ordinary outcome that should offer a
 * fresh code, not read like a failure.
 */
data class IptvPairingUi(
    val userCode: String,
    val verificationUri: String,
    val expired: Boolean = false
)
