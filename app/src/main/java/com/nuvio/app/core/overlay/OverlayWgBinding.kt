package com.nuvio.app.core.overlay

import overlaywg.Overlaywg

/**
 * The `overlaywg` AAR as Kotlin sees it.
 *
 * **Why there is an interface here at all.** Everything in this package that touches the
 * tunnel goes through this seam, and the only production implementation is a flat list of
 * delegations to `overlaywg.Overlaywg` with no branches in it. What the indirection buys
 * is the only thing that matters: the AAR is a JNI library that loads a `.so` on first use
 * and needs an Android device, so a test that called it directly could not run on the host
 * JVM at all. With the seam, every piece of logic that can actually be wrong — the
 * short-write loop, the argument-range checks, the `-1` translation, close semantics —
 * is exercised against a fake, and what remains untested is delegation.
 *
 * **The signatures are read back, not assumed.** They were taken from the built AAR with
 * `javap` rather than inferred from the Go source, because `gomobile bind` does not map
 * them the way a first reading suggests:
 *
 * ```
 * public static native long dial(java.lang.String, long) throws java.lang.Exception;
 * public static native long read(long, byte[], long, long);
 * public static native long write(long, byte[], long, long);
 * ```
 *
 * ⚠️ **`read` and `write` carry no `throws`, and that asymmetry is deliberate.** gobind
 * turns a Go `error` return into a thrown Java exception, so `Read`/`Write` were written
 * in Go to return a count and no error — otherwise every ordinary end-of-stream, which
 * happens on every single connection teardown, would arrive here as an exception the relay
 * had to catch on the hot path. `dial`, `close`, `up` and `down` do return errors and so
 * do throw, because those failures are real and the relay wants them.
 *
 * Go `int` becomes Java `long`, which is why every count and handle is `Long` and why the
 * offsets are widened at the call site rather than being `Int` throughout.
 */
internal interface OverlayWgBinding {

    /**
     * Brings the userspace device up.
     *
     * [dns] is the overlay resolver (`10.77.0.1`), and it is not optional in practice:
     * [dial] takes the **hostname from the CONNECT line**, not a resolved address, so the
     * name has to resolve inside the tunnel. An empty [dns] leaves the tunnel able to reach
     * literal addresses only. Throws if the config is rejected or the TUN cannot be built.
     */
    fun up(
        privateKeyHex: String,
        peerPublicKeyHex: String,
        endpoint: String,
        localCidr: String,
        allowedIps: String,
        dns: String,
        mtu: Int,
        keepalive: Int,
    )

    /** Tears the tunnel down and closes every connection through it. Safe when already down. */
    fun down()

    /** wireguard-go's own view of the device: handshake time and tx/rx counters. */
    fun status(): String

    /** Mints a fresh keypair, in both the base64 and hex encodings the two ends want. */
    fun generateKeypair(): OverlayWgKeypair

    /**
     * Opens a TCP connection through the tunnel and returns a handle to it.
     *
     * Throws on failure — which is what lets the relay answer `502 Bad Gateway` rather
     * than `200 Connection Established` followed by silence.
     */
    fun dial(host: String, port: Int): Long

    /**
     * Reads up to [length] bytes into [buffer] at [offset].
     *
     * Returns the count, or **`-1` when the stream is finished**. Never throws.
     *
     * ⚠️ The window is passed through rather than sliced here: gobind copies the Java
     * `byte[]` into a fresh Go slice on every call anyway, so re-packaging it on this side
     * would be a second allocation of the same 32 KB, per read, on the playback path.
     */
    fun read(id: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * Writes [length] bytes from [buffer] at [offset].
     *
     * Returns the number accepted, which **may be fewer than [length] with no error** — a
     * Go `net.Conn` is allowed to accept part of a buffer, and the caller loops. Never throws.
     */
    fun write(id: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Closes one connection. Closing an unknown handle is not an error. */
    fun close(id: Long)

    /**
     * The most recent read or write failure, for diagnostics.
     *
     * This exists because [read] and [write] cannot say *why* they ended — a Java exception
     * on every teardown is the wrong shape — so this is the escape hatch that costs the
     * hot path nothing and still lets a failure be named in a log line.
     */
    fun lastError(): String
}

/**
 * A keypair in both encodings.
 *
 * Two encodings rather than one because the two ends of this system speak different ones:
 * `wg set` on the server and the mDNS/DuckDNS adverts use **base64**, while wireguard-go's
 * `IpcSet` takes **hex**. Converting on this side would mean either a base64 decoder or a
 * hex encoder written by hand, since the module's `minSdk` of 24 predates
 * `java.util.Base64` — so the Go side, which has both in the standard library, emits both.
 */
internal data class OverlayWgKeypair(
    val privateKeyBase64: String,
    val publicKeyBase64: String,
    val privateKeyHex: String,
    val publicKeyHex: String,
)

/**
 * The real binding: every method delegates to a static on the generated class.
 *
 * An `object` rather than a class because the Go side holds the device and the connection
 * table in package-level state — there is exactly one tunnel per process, and giving this a
 * constructor would suggest otherwise.
 */
internal object GomobileWgBinding : OverlayWgBinding {

    override fun up(
        privateKeyHex: String,
        peerPublicKeyHex: String,
        endpoint: String,
        localCidr: String,
        allowedIps: String,
        dns: String,
        mtu: Int,
        keepalive: Int,
    ) {
        Overlaywg.up(
            privateKeyHex,
            peerPublicKeyHex,
            endpoint,
            localCidr,
            allowedIps,
            dns,
            mtu.toLong(),
            keepalive.toLong(),
        )
    }

    override fun down() {
        Overlaywg.down()
    }

    override fun status(): String = Overlaywg.status()

    override fun generateKeypair(): OverlayWgKeypair = Overlaywg.generateKeypair().let {
        OverlayWgKeypair(
            privateKeyBase64 = it.privateKeyB64,
            publicKeyBase64 = it.publicKeyB64,
            privateKeyHex = it.privateKeyHex,
            publicKeyHex = it.publicKeyHex,
        )
    }

    override fun dial(host: String, port: Int): Long = Overlaywg.dial(host, port.toLong())

    override fun read(id: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        Overlaywg.read(id, buffer, offset.toLong(), length.toLong()).toInt()

    override fun write(id: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        Overlaywg.write(id, buffer, offset.toLong(), length.toLong()).toInt()

    override fun close(id: Long) {
        Overlaywg.close(id)
    }

    override fun lastError(): String = Overlaywg.lastError()
}
