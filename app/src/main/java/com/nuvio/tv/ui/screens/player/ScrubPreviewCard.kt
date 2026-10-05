package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.nuvio.tv.core.boomio.TrickplayCue
import com.nuvio.tv.core.boomio.TrickplaySet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * How often the card repaints, in milliseconds.
 *
 * This is a FIXED CLOCK, and that is the whole point: it is deliberately
 * decoupled from both the D-pad repeat rate and the speed of the scrub.
 *
 * A held D-pad repeats every ~50 ms (20/s) and `PlayerScrubRates` steps up to
 * 60 s of film per repeat, so a sustained hold crosses ~1200 s of film a
 * second. One sheet covers `cols * rows * interval` — measured on a real reel,
 * 5 x 5 x 5.316 s = 132.9 s, i.e. ~2.5% of an 87-minute film — so that top rate
 * demands ~9 sheets a second. Nothing serves 9 sheets a second off a LAN one
 * round trip at a time, and it does not need to: the reel only HAS ~40 distinct
 * images, so past a handful of frames a second the extra updates would be
 * invisible anyway.
 *
 * 150 ms (~6.7/s) is chosen against that 9/s demand as the point where the
 * preview still tracks a full-speed hold closely — at most ~1.3 sheets behind —
 * while leaving the loader time to keep the sheet in hand ahead of the target.
 * A slower clock would visibly lag a fast hold; a faster one buys accuracy the
 * tile grid cannot express, because a single tile is already 5.3 s of film.
 */
private const val DISPLAY_MS = 150L

private const val TAG = "TpCard"

/**
 * How many sheets of the reel are pulled at once.
 *
 * The reel is small — 40 sheets, ~6.4 MB for a feature — so the whole thing is
 * worth having locally the moment the card appears. Doing that serially is what
 * broke the preview: 40 sheets at any per-sheet gap takes longer than the ~5 s
 * it takes to scrub the entire film, so the fill could never get ahead of a
 * hold. Four at a time puts the whole reel on disk in well under a second on
 * the LAN, after which a scrub costs only a decode.
 */
private const val PREFETCH_CONCURRENCY = 4

/**
 * Sheet keys already pulled into Coil's disk cache, process-wide.
 *
 * The card is only composed while the seek bar is being dragged, so it is torn
 * down and rebuilt on EVERY scrub — a `LaunchedEffect` alone would re-run the
 * whole reel on each one and burn a few seconds of decoding for nothing. The
 * reel is immutable per title, so once is enough for the life of the process.
 */
private val prefetchedSheets: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

/**
 * DECODED sheets, most-recently-used first, process-wide.
 *
 * The card used to hold a Coil `ImagePainter` built from a freshly executed
 * request. That is what made it blink: `ImagePainter` is a STATEFUL painter —
 * it owns a loading/pending state and only starts drawing once its own internal
 * crossfade has settled — so a painter swapped in every 150 ms is, for most of
 * the time it is on screen, a painter that draws nothing. Measured on the
 * Shield, the card was on the glass and correctly positioned and *empty* on
 * roughly half of all scrubbed frames. Holding a plain `ImageBitmap` and asking
 * Compose for a `BitmapPainter` removes the state machine entirely: it is a
 * value, and drawing a value cannot be deferred.
 *
 * It is also why the reel is kept here rather than re-fetched per tick. A sheet
 * is 1600x900 — ~5.7 MB decoded — so a whole 40-sheet reel would be a couple of
 * hundred megabytes of bitmaps; eight is ~46 MB and covers every direction a
 * scrub actually moves, because scrubbing back over ground just covered is the
 * common case and now costs a map lookup rather than a decode.
 *
 * Keyed by the reel's cache NAMESPACE — the token-free base URL plus the index
 * digest — for exactly the reason `sheetCacheKey` is: sheet filenames are stable
 * across titles and across re-renders, so seeding on a bare name would happily
 * paint the previous film's frame, or a stale render of this one.
 */
private const val BANK_MAX = 8

/**
 * How far from the target a banked sheet may be and still be drawn in its place,
 * in sheet spans (a span is ~133 s of film — see the reel geometry note above).
 *
 * The fallback frame is a NEIGHBOUR of the requested one, never a substitute for
 * it: three spans is far enough that a scrub almost always has something moving
 * in the right direction to show, and near enough that what it shows is still
 * recognisably where the viewer is dragging to.
 */
private const val BANK_REACH_SPANS = 3

private val bank: LinkedHashMap<String, ImageBitmap> =
    object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>): Boolean =
            size > BANK_MAX
    }

private fun bankKey(namespace: String, sheet: String) = "$namespace/$sheet"

private fun bankGet(namespace: String, sheet: String): ImageBitmap? =
    synchronized(bank) { bank[bankKey(namespace, sheet)] }

private fun bankPut(namespace: String, sheet: String, frame: ImageBitmap) {
    synchronized(bank) { bank[bankKey(namespace, sheet)] = frame }
}

/**
 * The banked sheet whose stretch of film is nearest [positionMs], or null.
 *
 * This is what seeds the card on its FIRST composed frame, and what keeps it
 * moving on every frame after that. The card is composed only while a scrub is
 * in flight, so anything it `remember`s dies on every key release; without a
 * seed the very first frame of every scrub has nothing to draw and the viewer
 * sees the card appear empty before the first decode lands. Seeding is also what
 * removes the direction asymmetry: scrubbing back over ground just covered finds
 * the sheets still banked and paints immediately, in either direction.
 *
 * [maxDistanceMs] bounds how far the answer may be from [positionMs]. The seed
 * passes none — any frame of this title beats an empty card for one frame — but
 * the scrub ticker passes a reach, because it re-asks every 150 ms and an
 * unbounded answer there would let a stale bank drag the picture minutes away.
 */
private fun bankNearest(
    namespace: String,
    positionMs: Long,
    tilesBySheet: Map<String, List<TrickplayCue>>,
    maxDistanceMs: Long = Long.MAX_VALUE
): Pair<String, ImageBitmap>? = synchronized(bank) {
    bank.entries
        .filter { it.key.startsWith("$namespace/") }
        .mapNotNull { entry ->
            val sheet = entry.key.substring(namespace.length + 1)
            val first = tilesBySheet[sheet]?.firstOrNull()?.startMs ?: return@mapNotNull null
            val distance = abs(first - positionMs)
            if (distance > maxDistanceMs) return@mapNotNull null
            Triple(distance, sheet, entry.value)
        }
        .minByOrNull { it.first }
        ?.let { it.second to it.third }
}

/** A Coil result as a drawable bitmap, or null if it is not one. */
private fun decoded(image: coil3.Image): ImageBitmap? =
    runCatching { image.toBitmap().asImageBitmap() }.getOrNull()

/** The tile of [sheet] that [cue] should draw — a sub-rectangle of the sheet. */
private fun tileRect(preview: TrickplaySet, frame: ImageBitmap, cue: TrickplayCue): BitmapPainter {
    // Clamped against the DECODED sheet's own size, not the index's claim about
    // it: a reel rendered with different geometry than its index describes would
    // otherwise ask the painter for a rectangle off the edge of the bitmap.
    val w = preview.tileW.coerceAtMost(frame.width)
    val h = preview.tileH.coerceAtMost(frame.height)
    val x = (cue.col * preview.tileW).coerceIn(0, (frame.width - w).coerceAtLeast(0))
    val y = (cue.row * preview.tileH).coerceIn(0, (frame.height - h).coerceAtLeast(0))
    return BitmapPainter(
        image = frame,
        srcOffset = IntOffset(x, y),
        srcSize = IntSize(w, h)
    )
}

/**
 * The frame the viewer is about to land on, drawn while they drag the seek bar.
 *
 * The server renders sprite SHEETS — a grid of tiles in one JPEG — and an index
 * that maps a time range to one rectangle of one sheet. There is no per-frame
 * endpoint to call: showing tile (col, row) means drawing that rectangle of the
 * sheet. The sheets live in Coil's disk cache (200 MB; the whole reel for a 2 h
 * film is ~6 MB), warmed by the prefetch below, and their DECODED forms live in
 * [bank], so a scrub after the first second touches neither the network nor the
 * decoder.
 *
 * [positionMs] is the scrub target, not the playhead: the player publishes the
 * pending preview position as the timeline's current position while a drag is
 * in flight, so this card and the fill under it always agree.
 *
 * THE FRAME IS A VALUE, NOT A PAINTER WITH A LIFECYCLE. Both `AsyncImage` and
 * `rememberAsyncImagePainter` RESET to their loading state whenever the model
 * changes — a blank frame on screen, once per sheet, no matter that the bitmap
 * was already decoded and in the memory cache — and a Coil `ImagePainter`
 * fetched by hand has the same problem for the same reason: it is stateful, and
 * a painter replaced every tick is a painter that is still settling for most of
 * the time it is drawn. A `BitmapPainter` over an `ImageBitmap` has no state to
 * settle, so there is no state in which the card has nothing to draw: it cannot
 * blank.
 *
 * IT ALSO NEVER FREEZES. While the sheet the target wants is still decoding, the
 * card draws the tile of the last sheet IN HAND that is nearest that target, so
 * the picture keeps moving in the right direction instead of sitting on whatever
 * it last managed to load. That fallback is what makes a fast hold usable: the
 * viewer sees the film sweep past at the reel's own resolution, and each frame
 * snaps to exact as its sheet lands.
 */
@Composable
internal fun ScrubPreviewCard(
    preview: TrickplaySet,
    positionMs: Long,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp
) {
    val target = remember(preview, positionMs) { preview.cueAt(positionMs) }
    Log.d(
        TAG,
        "compose posMs=$positionMs target=${target?.sheet}:${target?.col},${target?.row} " +
            "cues=${preview.cues.size} base=${preview.baseUrl} v=${preview.version}"
    )
    if (target == null) return
    val context = LocalContext.current
    val loader = remember(context) { SingletonImageLoader.get(context) }

    // Keyed on the TOKEN-FREE base URL, which identifies the title. Keying the
    // held frame on `preview` itself would drop it back to null every time the
    // stream token is re-minted — the set is a data class, so a new token makes
    // a new value — and the card would blank for the length of the reload even
    // though every cue in hand still describes exactly the same frames.
    val reelKey = preview.baseUrl
    val namespace = preview.cacheNamespace
    // A sheet name -> its tiles, so the nearest-tile fallback below is a lookup
    // rather than a scan of the whole index on every tick.
    val tilesBySheet = remember(preview) { preview.cues.groupBy { it.sheet } }

    // How far the fallback may reach, derived from the reel rather than assumed:
    // a span is `cols * rows * interval`, and the interval is the server's to
    // choose, so it is measured from the index (mean gap between sheet starts).
    val reachMs = remember(tilesBySheet) {
        val starts = tilesBySheet.values.mapNotNull { it.firstOrNull()?.startMs }.sorted()
        if (starts.size < 2) Long.MAX_VALUE
        else ((starts.last() - starts.first()) / (starts.size - 1)) * BANK_REACH_SPANS
    }

    // The film time a sheet starts at — the currency of "which of these two
    // frames is nearer where the viewer is now".
    val startOf: (String?) -> Long? = { name ->
        name?.let { tilesBySheet[it]?.firstOrNull()?.startMs }
    }

    // The frame in hand, seeded from the bank so the card paints on its FIRST
    // composed frame whatever the scrub direction — see `bankNearest`.
    val seed = remember(reelKey) { bankNearest(namespace, positionMs, tilesBySheet) }
    var heldName by remember(reelKey) { mutableStateOf(seed?.first) }
    var heldFrame by remember(reelKey) { mutableStateOf(seed?.second) }
    val latest by rememberUpdatedState(target)

    // Exact when the sheet in hand is the one the target wants, and the nearest
    // tile of that sheet when it is not — see the note above. DERIVED rather than
    // stored: `heldName` is what changes, and holding a second copy of the answer
    // in a ticker is how the two got out of step in the first place.
    val tileFor: (String?, TrickplayCue) -> TrickplayCue = { name, t ->
        if (name == null || t.sheet == name) t
        else tilesBySheet[name]?.minByOrNull { abs(it.startMs - t.startMs) } ?: t
    }
    val shown = tileFor(heldName, target)

    // Put a decoded sheet on screen, logging only when the sheet CHANGES. The
    // freeze is precisely "this stopped changing while the target kept moving",
    // so the transitions are the entire signal — and a per-tick log would be 6.7
    // lines a second, which is why the previous captures could not tell a stuck
    // frame from a healthy one.
    val adopt: (String, ImageBitmap) -> Unit = { name, frame ->
        if (heldName != name) {
            Log.d(TAG, "held $name while target=${latest.sheet}")
        }
        heldName = name
        heldFrame = frame
    }

    // The fixed clock. One ticker rather than a LaunchedEffect keyed on the
    // target: a fast hold re-targets 20x a second, so a keyed effect would
    // cancel and restart forever and never commit anything until the viewer let
    // go. The decode runs in a CHILD coroutine so this loop is never blocked — a
    // tick that had to wait on a decode would make the cadence a function of
    // latency, which is precisely the thing that made the preview give up.
    LaunchedEffect(reelKey, context) {
        // Concurrent: the loop tests a sheet and the child that decodes it
        // clears the same sheet, and both also run on the loader's dispatcher
        // once a request resumes. A plain HashSet is only incidentally safe
        // here, and a lost `remove` would retire that sheet for the session.
        val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
        while (true) {
            val t = latest
            val frame = bankGet(namespace, t.sheet)
            if (frame != null) {
                // A banked sheet is already decoded, so the card changes frame
                // with no async step at all: this assignment and the recomposition
                // it schedules happen in the same frame.
                adopt(t.sheet, frame)
            } else {
                // Nothing banked for the exact target. Move what is on screen to
                // the nearest sheet the bank DOES hold, bounded to a neighbour.
                //
                // THIS MUST RUN EVERY TICK, not only on a cold start. A sustained
                // hold re-targets every 150 ms, so the sheet a tick asks for is
                // almost never the sheet a decode has just landed for — measured
                // on a real hold, 516 distinct target positions produced only 121
                // decodes. A fallback gated on `heldFrame == null` therefore runs
                // at most ONCE per reel and leaves the card pinned to its seed
                // while the target moves on: the picture stops changing while the
                // scrub carries on, which is exactly the freeze the viewer
                // reports. Holding the last frame is not "keeping the card up",
                // it is the bug.
                bankNearest(namespace, t.startMs, tilesBySheet, reachMs)?.let { (name, frame) ->
                    adopt(name, frame)
                }
                if (inFlight.add(t.sheet)) {
                    val wanted = t
                    launch {
                        val started = System.currentTimeMillis()
                        val result = runCatching { loader.execute(request(preview, context, wanted)) }
                        val decodedFrame = (result.getOrNull() as? SuccessResult)?.image?.let { decoded(it) }
                        Log.d(
                            TAG,
                            "decode ${wanted.sheet} ok=${decodedFrame != null} " +
                                "in ${System.currentTimeMillis() - started}ms " +
                                "err=${result.exceptionOrNull()?.message}"
                        )
                        if (decodedFrame != null) {
                            bankPut(namespace, wanted.sheet, decodedFrame)
                            // Adopt the moment it lands, if it is nearer where
                            // the viewer is NOW than what is on screen. Waiting
                            // for a tick whose target equals this sheet is
                            // waiting for a coincidence that a moving scrub does
                            // not provide.
                            val now = latest
                            val mine = startOf(wanted.sheet)
                            val held = startOf(heldName)
                            if (mine != null &&
                                (held == null || abs(mine - now.startMs) < abs(held - now.startMs))
                            ) {
                                adopt(wanted.sheet, decodedFrame)
                            }
                        }
                        inFlight.remove(wanted.sheet)
                    }
                }
            }
            delay(DISPLAY_MS)
        }
    }

    // The whole reel onto DISK, nearest-first and IN PARALLEL. Fire and forget: a
    // failure here just means that sheet is fetched on demand later.
    LaunchedEffect(reelKey, context) {
        // Keyed on the title, not a sheet: a scrub can begin anywhere, and
        // keying on the first sheet seen would re-run the whole reel the next
        // time the viewer started from a different position.
        if (reelKey in prefetchedSheets) return@LaunchedEffect
        val startMs = positionMs
        // Nearest the current position first — a scrub moves outward from where
        // the viewer already is, not from the start of the film.
        val ordered = preview.cues
            .distinctBy { it.sheet }
            .sortedBy { abs(it.startMs - startMs) }
        coroutineScope {
            val gate = Semaphore(PREFETCH_CONCURRENCY)
            ordered
                .map { cue ->
                    async { gate.withPermit { runCatching { loader.execute(request(preview, context, cue)) } } }
                }
                .awaitAll()
        }
        // Marked only once the whole reel is down. The viewer usually stops
        // dragging long before this finishes, and the card dies with the scrub;
        // marking up front would leave the reel permanently half-warm. A
        // restarted pass costs almost nothing, because the sheets already
        // fetched resolve straight from the cache.
        prefetchedSheets.add(reelKey)
        Log.d(TAG, "prefetch done $reelKey sheets=${ordered.size}")
    }

    // dp per source pixel, so a 320x180 tile and a 480x270 tile produce the same
    // card on screen whatever the edge's geometry happened to be.
    val scale = height.value / preview.tileH.toFloat()
    val cardWidth = (preview.tileW * scale).dp

    Box(
        modifier = modifier
            .size(cardWidth, height)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
    ) {
        heldFrame?.let { frame ->
            Image(
                painter = tileRect(preview, frame, shown),
                contentDescription = null,
                // The painter is handed exactly one tile, so it fills the card's
                // own bounds; there is no sheet-sized layout to slide around and
                // nothing for an ancestor to clip away.
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/**
 * The request for one cue's sheet — shared by the prefetch and the ticker so
 * both use the same cache key and the prefetch actually warms what is drawn.
 * Sheets are immutable, so the cache key deliberately drops the stream token:
 * keying on the full URL would discard every decoded sheet each time the token
 * is re-minted, and re-pull megabytes over the LAN for bytes that cannot have
 * changed.
 */
private fun request(preview: TrickplaySet, context: Context, cue: TrickplayCue): ImageRequest =
    ImageRequest.Builder(context)
        .data(preview.sheetUrl(cue))
        .memoryCacheKey(preview.sheetCacheKey(cue))
        .diskCacheKey(preview.sheetCacheKey(cue))
        // Decode at the sheet's own resolution. Letting Coil size itself from the
        // card's dp would UPSCALE a 320x180-per-tile sheet on a 2x-density TV —
        // a slower decode and a bigger bitmap for pixels that do not exist.
        .size(preview.tileW * preview.cols, preview.tileH * preview.rows)
        .build()

/**
 * Put a child [gap] above the parent's top edge, horizontally centred, WITHOUT
 * ever changing the parent's size.
 *
 * A plain `offset` would not do: the child is still measured, so a 132 dp card
 * would make the row it belongs to 132 dp taller and shove the seek bar (and
 * every control under it) down the screen the moment a scrub started. Reporting a
 * 0x0 node and placing the content outside it keeps the bar exactly where it
 * was — the card floats over the title above it instead, which is what a scrub
 * preview should do.
 *
 * WARNING: this only works if the node it hangs off does NOT clip. It did clip:
 * the seek bar carried `clip(RoundedCornerShape(3.dp))`, and since the bar is a
 * few dp tall and the card is placed entirely above it, every scrub preview was
 * erased — silently, on every title, while the sprites fetched fine over the
 * network and nothing was ever logged. Fixed by taking the bar's rounded shape
 * on `background` (which does not clip) instead of `clip`. If a preview ever
 * goes missing again, check for a clip on the bar or any ancestor first.
 */
internal fun Modifier.overlayAboveCenter(gap: Dp): Modifier = this.layout { measurable, constraints ->
    // Unbounded on BOTH ends, not just relaxed minimums. The bar this hangs off
    // is a few dp tall, and `Modifier.size` coerces its request to the incoming
    // constraints — measuring under the bar's own max height would squash the
    // card to the thickness of the track. The card carries its own explicit
    // size, so giving it all the room it asks for is safe and exact.
    val placeable = measurable.measure(
        Constraints(
            minWidth = 0,
            maxWidth = Constraints.Infinity,
            minHeight = 0,
            maxHeight = Constraints.Infinity
        )
    )
    val gapPx = gap.roundToPx()
    val available = constraints.maxWidth
    layout(0, 0) {
        val x = if (available == Constraints.Infinity) 0 else (available - placeable.width) / 2
        placeable.place(x, -placeable.height - gapPx)
    }
}
