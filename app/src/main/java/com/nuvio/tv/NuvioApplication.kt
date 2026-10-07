package com.nuvio.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.request.allowRgb565
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.nuvio.app.core.overlay.OverlayEndpointDiscovery
import com.nuvio.app.core.overlay.OverlayEnrollment
import com.nuvio.app.core.overlay.OverlayLocalDiscovery
import com.nuvio.app.core.overlay.OverlayProvisioning
import com.nuvio.app.core.overlay.OverlayRelay
import com.nuvio.app.core.overlay.OverlaySession
import com.nuvio.app.core.overlay.OverlayTunnel
import com.nuvio.app.core.overlay.withOverlayProxy
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.features.addons.AddonRef
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.boomio.BoomioSessionRepository
import com.nuvio.tv.core.diagnostics.SentryInitializer
import com.nuvio.tv.core.image.StaleWhileRevalidateCacheStrategy
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.androidtv.AndroidTvChannelSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.local.ImagePerformancePreferences
import com.nuvio.tv.data.local.SentrySettingsDataStore
import com.nuvio.tv.data.local.ServerConfigurationStore
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var androidTvChannelSyncService: AndroidTvChannelSyncService
    @Inject lateinit var sentrySettingsDataStore: SentrySettingsDataStore
    @Inject lateinit var imagePerformancePreferences: ImagePerformancePreferences
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder
    @Inject lateinit var companionManager: com.nuvio.tv.core.boomio.BoomioCompanionManager
    @Inject lateinit var deviceCapabilityReporter: com.nuvio.tv.core.device.DeviceCapabilityReporter
    @Inject lateinit var serverConfigurationStore: ServerConfigurationStore
    @Inject lateinit var tvAddonRepository: com.nuvio.tv.domain.repository.AddonRepository

    /**
     * Application-lifetime scope for the one publisher this host owns: the addon set that
     * feeds the overlay's relay allow-set. Never cancelled -- the process is the lifetime.
     */
    private val overlayPublishScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SentryInitializer.start(this, sentrySettingsDataStore)
        PluginRuntimeHooks.onApplicationCreate(this)
        androidTvChannelSyncService.start()
        // Companion hub (bsc) connection; inert when BOOMIO_COMPANION_URL is blank.
        companionManager.start()
        // Reports decode + sink capabilities to the bsm fleet view; inert when BSM_BASE_URL is blank.
        deviceCapabilityReporter.start()

        // ---- VPN overlay -------------------------------------------------------------
        // Order is load-bearing, and mirrors mobile's MainActivity.onCreate:
        //  * the discovery ladder's first rung BORROWS OverlayLocalDiscovery's browse, so the
        //    browse has to exist before the ladder is built;
        //  * OverlayRelay.start() binds its diallers ONCE per process and runs before the ladder
        //    has walked anything, so it is handed a DeferredTunnelDialer that reads the tunnel
        //    state PER DIAL. Starting the relay before OverlaySession is what makes that safe --
        //    a tunnel reference captured here would sit at Down for the process's life.
        // None of these opens a socket. The browse is foreground-triggered, and the relay binds
        // no listener at all until BOOMIO_OVERLAY_ADDR is set, so an unconfigured build stays
        // inert even with this block present.
        //
        // The session store initialises FIRST, and it is the one part of this that is not inert:
        // it hydrates a persisted `bs_ses_` token, and OverlayEnrollment -- further down -- binds
        // to that flow. Publishing before enrollment starts collecting is what makes a TV that
        // was linked in a previous life enroll on THIS cold start instead of waiting for a link
        // that will never come again.
        // The overlay derives the hosts the relay is allowed to CARRY from the configured
        // server, and it reads that through the compat `ServerConfigurationRepository` -- which
        // ships with a blank default and no other writer. Until this line existed nothing in
        // the host ever fed it, so the allow-set was permanently empty and `relayRouteFor`
        // answered DIRECT for every host: the pin never matched, the mDNS browse had nothing to
        // look for, and off the home network the relay dialled the public edge directly.
        //
        // Measured on the Shield, 2026-10-07, off-LAN: `OverlayRelay: Relay could not reach
        // nuvioserver.tracemonkey.org:443` -- a SocketTimeoutException after exactly 10000ms,
        // which is DirectDialer's own CONNECT_TIMEOUT_MS, from `/10.151.14.131`, the device's
        // own hotspot address. The relay then answered the engine's CONNECT with 502.
        //
        // ⚠️ Published BEFORE the overlay initialisers start their collectors, so the first
        // read is already populated. The TV's configuration is a plain singleton whose changes
        // are applied by an app restart rather than an emission, so one publish per process is
        // the whole of it -- there is nothing to observe.
        ServerConfigurationRepository.publish(serverConfigurationStore.loadActive())

        // The OTHER half of the same allow-set, and the half that decides whether the home
        // screen works. The relay's route decision is an exact set-membership test over
        // `localServerHosts()`, which is the server config hosts PLUS every installed addon's
        // manifest host on the server's own domain -- on this deployment 17 row addons on
        // `tmdb.tracemonkey.org`, plus `bsf.` and `usn.`. The overlay reads those through the
        // compat `AddonRepository`, which shipped with the same defect as the config repo above:
        // a `publish()` with no call site anywhere, so `addonManifestUrls()` was always empty and
        // the entire catalogue plane was answered DIRECT by `relayRouteFor`.
        //
        // Measured on the Shield, 2026-10-07, off-LAN, device egress `/10.151.14.131`:
        //   OverlayRelay: Relay could not reach tmdb.tracemonkey.org:443
        //     SocketTimeoutException ... tmdb.tracemonkey.org/209.107.100.169:443 after 10000ms
        //   AddonRepository: Failed to fetch addon manifest for
        //     url=https://tmdb.tracemonkey.org/74c28b.../qZaWf26Qbk/row/foryoudirectormovie/manifest.json
        //     code=null message=Unexpected response code for CONNECT: 502
        // x17 row addons, at 16:53:15 and again at 16:53:25. Auth and `bsf.` were fine in the same
        // session -- they are the hosts the server config already named -- so the user signed in
        // and then saw "no catalog addons installed". 443 is not forwarded off-LAN, so DIRECT is
        // not a fallback here, it is the failure.
        //
        // ⚠️ Collected, never seeded once: the addon set is a DataStore-backed Flow whose real
        // contents arrive seconds after launch, and the overlay rebinds its pin on every change
        // (`OverlayLocalDiscovery.observeAddonChanges`). The relay re-reads the set on every
        // CONNECT, so a widened set takes effect on the next dial with no restart.
        //
        // The TV model carries the manifest base as `baseUrl`, not `manifestUrl`; the overlay only
        // takes the *host*, which the base and the built manifest URL share.
        overlayPublishScope.launch {
            tvAddonRepository.getInstalledAddons().collect { addons ->
                AddonRepository.publish(addons.map { AddonRef(it.baseUrl) })
            }
        }
        BoomioSessionRepository.initialize(this)
        OverlayLocalDiscovery.initialize(this)
        OverlayTunnel.initialize(this)
        OverlayEndpointDiscovery.initialize(this)
        OverlayRelay.initialize()
        OverlaySession.initialize(this)
        OverlayEnrollment.initialize(this)
        OverlayProvisioning.initialize(this)
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        val imageOkHttpClient by lazy {
            val imageDispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            OkHttpClient.Builder().withOverlayProxy()
                .dispatcher(imageDispatcher)
                .dns(IPv4FirstDns())
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(12, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    try {
                        chain.proceed(chain.request())
                    } catch (e: java.net.SocketTimeoutException) {
                        chain.withConnectTimeout(3, TimeUnit.SECONDS)
                            .withReadTimeout(4, TimeUnit.SECONDS)
                            .proceed(chain.request())
                    }
                }
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        val imageLoaderRef: () -> ImageLoader = { SingletonImageLoader.get(this) }

        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = { imageOkHttpClient },
                        cacheStrategy = {
                            StaleWhileRevalidateCacheStrategy(
                                revalidationClient = { imageOkHttpClient },
                                imageLoaderProvider = imageLoaderRef,
                            )
                        },
                    )
                )
            }
            .memoryCache {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
                // Low-RAM devices (≤2GB): use 0.15 — larger cache reduces GC pressure
                // from rapid bitmap eviction during scrolling.
                // Mid-range devices (≤3GB): use 0.20 for decent image caching.
                // Normal devices (>3GB): use 0.25 for snappy image loading.
                // - allowHardware(false) keeps bitmaps on heap instead of GPU memory
                val cachePercent = when {
                    totalRamMb <= 2048 -> 0.15
                    totalRamMb <= 3072 -> 0.20
                    else -> 0.25
                }
                MemoryCache.Builder()
                    .maxSizePercent(context, cachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(200L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(false)
            .allowRgb565(imagePerformancePreferences.rgb565Enabled)
            .bitmapFactoryMaxParallelism(4)
            .build()
    }
}
