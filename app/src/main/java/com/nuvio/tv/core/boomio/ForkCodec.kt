package com.nuvio.tv.core.boomio

/**
 * Which payload a private-listening fork puts on the wire.
 *
 * This lives beside the companion protocol rather than with the sender because it is
 * *negotiation* vocabulary: the phone states it in `audio_fork_start` and the TV answers
 * by carrying it or not. Keeping it here also keeps the dependency pointing one way —
 * `ui.screens.player` depends on `core.boomio`, never the reverse, so
 * [ActiveCompanionPlayer] can name the codec without importing from the UI layer.
 *
 * Public, like the other protocol vocabulary in this package ([CompanionPlayRequest],
 * [CompanionPlaybackSnapshot]): it is a parameter type on the public [ActiveCompanionPlayer]
 * interface, and Kotlin refuses to expose an internal type there.
 *
 * The wire format itself is the transport's business (see `ForkOpusWire` in
 * `ui.screens.player`).
 */
enum class ForkCodec(val wireName: String) {
    /** Raw PCM16 in the decoder's native layout — the original transport, and the default. */
    PCM("pcm"),

    /** Opus at 48 kHz, 20 ms frames. Mono/stereo only; anything else falls back to [PCM]. */
    OPUS("opus");

    companion object {
        /**
         * Parse the codec a phone advertised. Anything unrecognised means [PCM], which is what
         * makes an older phone work unchanged: it says nothing, and nothing means PCM.
         */
        fun fromWire(name: String?): ForkCodec =
            if (name?.trim()?.equals(OPUS.wireName, ignoreCase = true) == true) OPUS else PCM
    }
}
