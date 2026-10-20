package com.nuvio.app.core.overlay

/**
 * What the Settings screen is allowed to say about the private tunnel.
 *
 * ⚠️ **A projection, not a fifth state machine.** The device has exactly one state
 * ([TunnelState]); this adds exactly one fact the device cannot know — whether a *person* asked
 * for it to stay down — and [of] is the whole of the relation. Anything that needed to remember
 * more would be a second source of truth, and two sources is how "Disconnect" ends up showing
 * "Connected" while the tunnel is down.
 */
internal sealed interface OverlayTunnelUiState {

    /** Carrying traffic: `up()` succeeded and a handshake completed. */
    data object Connected : OverlayTunnelUiState

    /** The user asked for it down. **Wins over [TunnelState.Up]** — see [of]. */
    data object Disconnected : OverlayTunnelUiState

    /** Not up, and nobody asked for that: no endpoint yet, or it is still coming up. */
    data object Down : OverlayTunnelUiState

    /** `up()` refused. [reason] is the AAR's own words, shown verbatim so it can be searched. */
    data class Failed(val reason: String) : OverlayTunnelUiState

    companion object {
        /**
         * Projects the device plus the user's choice onto one displayable state.
         *
         * ⚠️ **The manual flag is checked first, before `Up`.** The teardown is asynchronous —
         * the flag is set on the caller's thread and `down()` runs on a background scope — so
         * between those two moments the device genuinely reports `Up`. Reading that as
         * "Connected" for a frame would make the button flip back to "Disconnect" under the
         * user's finger, which reads as the press having failed. The user's intent is the
         * earlier and stronger fact, so it wins.
         */
        fun of(state: TunnelState, manuallyDisconnected: Boolean): OverlayTunnelUiState = when {
            manuallyDisconnected -> Disconnected
            state is TunnelState.Up -> Connected
            state is TunnelState.Failed -> Failed(state.reason)
            else -> Down
        }
    }
}
