package com.nuvio.tv.ui.navigation

import android.os.SystemClock
import com.nuvio.tv.ui.screens.home.DrillTarget
import java.net.URLEncoder

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Movies : Screen("movies")
    data object Tv : Screen("tv")
    data object Anime : Screen("anime")
    // Kids wall presentation (Leo): full approved-content poster walls instead of
    // the genre-row Movies/TV browsers. Reached only from the kids drawer; registered
    // in the graph unconditionally so a back-stack restore can never hit an
    // unregistered route (see MainActivity KIDS_PROFILE_IDS).
    data object KidsMovies : Screen("kids_movies")
    data object KidsTv : Screen("kids_tv")
    // Result wall behind the long-press "More like this" action (all profiles: kids
    // walls + adult AI-search rows). itemId is the pressed title's tt id; the screen
    // re-resolves the ACTIVE profile's curated row addon by media type, so it works
    // for tiles pressed on any wall/library regardless of source addon.
    data object MoreLikeThis : Screen("kids_more_like_this/{itemType}/{itemId}?title={title}&exclude={exclude}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /**
         * [exclude] carries the tt ids of the wall the user drilled FROM, so a
         * recursive "More like this" hides those tiles and stays fresh (3+ deep).
         * Empty for the first entry (from a wall/library); only recursive pushes
         * supply it. ids are [A-Za-z0-9]+ plus commas — safe unencoded, but encode
         * anyway for uniform route building.
         */
        fun createRoute(
            itemType: String,
            itemId: String,
            title: String? = null,
            exclude: List<String> = emptyList()
        ): String {
            val encodedTitle = title?.let { encode(it) } ?: ""
            val encodedExclude = exclude.joinToString(",") { encode(it) }
            return "kids_more_like_this/${encode(itemType)}/${encode(itemId)}?title=$encodedTitle&exclude=$encodedExclude"
        }
    }
    data object Detail : Screen("detail/{itemId}/{itemType}?addonBaseUrl={addonBaseUrl}&returnFocusSeason={returnFocusSeason}&returnFocusEpisode={returnFocusEpisode}&returnToHomeOnBack={returnToHomeOnBack}&heroBackdropUrl={heroBackdropUrl}&playOnLoad={playOnLoad}&manualSelection={manualSelection}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            itemId: String,
            itemType: String,
            addonBaseUrl: String? = null,
            returnFocusSeason: Int? = null,
            returnFocusEpisode: Int? = null,
            returnToHomeOnBack: Boolean = false,
            heroBackdropUrl: String? = null,
            playOnLoad: Boolean = false,
            manualSelection: Boolean = false
        ): String {
            val encodedItemId = encode(itemId)
            val encodedItemType = encode(itemType)
            val encodedAddon = addonBaseUrl?.let { encode(it) } ?: ""
            val encodedHeroBackdrop = heroBackdropUrl?.let { encode(it) } ?: ""
            return "detail/$encodedItemId/$encodedItemType?addonBaseUrl=$encodedAddon&returnFocusSeason=${returnFocusSeason ?: ""}&returnFocusEpisode=${returnFocusEpisode ?: ""}&returnToHomeOnBack=$returnToHomeOnBack&heroBackdropUrl=$encodedHeroBackdrop&playOnLoad=$playOnLoad&manualSelection=$manualSelection"
        }
    }
    data object Stream : Screen("stream/{videoId}/{contentType}/{title}?poster={poster}&backdrop={backdrop}&logo={logo}&season={season}&episode={episode}&episodeName={episodeName}&genres={genres}&year={year}&contentId={contentId}&contentName={contentName}&runtime={runtime}&manualSelection={manualSelection}&returnToDetailOnBack={returnToDetailOnBack}&returnToHomeOnBack={returnToHomeOnBack}&startFromBeginning={startFromBeginning}&contentLanguage={contentLanguage}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            videoId: String,
            contentType: String,
            title: String,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            season: Int? = null,
            episode: Int? = null,
            episodeName: String? = null,
            genres: String? = null,
            year: String? = null,
            contentId: String? = null,
            contentName: String? = null,
            runtime: Int? = null,
            manualSelection: Boolean = false,
            returnToDetailOnBack: Boolean = false,
            returnToHomeOnBack: Boolean = false,
            startFromBeginning: Boolean = false,
            contentLanguage: String? = null
        ): String {
            val encodedVideoId = encode(videoId)
            val encodedContentTypePath = encode(contentType)
            val encodedTitle = encode(title)
            val encodedPoster = poster?.let { encode(it) } ?: ""
            val encodedBackdrop = backdrop?.let { encode(it) } ?: ""
            val encodedLogo = logo?.let { encode(it) } ?: ""
            val encodedEpisodeName = episodeName?.let { encode(it) } ?: ""
            val encodedGenres = genres?.let { encode(it) } ?: ""
            val encodedYear = year?.let { encode(it) } ?: ""
            val encodedContentId = contentId?.let { encode(it) } ?: ""
            val encodedContentName = contentName?.let { encode(it) } ?: ""
            val encodedContentLanguage = contentLanguage?.let { encode(it) } ?: ""
            return "stream/$encodedVideoId/$encodedContentTypePath/$encodedTitle?poster=$encodedPoster&backdrop=$encodedBackdrop&logo=$encodedLogo&season=${season ?: ""}&episode=${episode ?: ""}&episodeName=$encodedEpisodeName&genres=$encodedGenres&year=$encodedYear&contentId=$encodedContentId&contentName=$encodedContentName&runtime=${runtime ?: ""}&manualSelection=$manualSelection&returnToDetailOnBack=$returnToDetailOnBack&returnToHomeOnBack=$returnToHomeOnBack&startFromBeginning=$startFromBeginning&contentLanguage=$encodedContentLanguage"
        }
    }
    data object Player : Screen("player/{streamUrl}/{title}?streamName={streamName}&year={year}&headers={headers}&contentId={contentId}&contentType={contentType}&contentName={contentName}&poster={poster}&backdrop={backdrop}&logo={logo}&videoId={videoId}&season={season}&episode={episode}&episodeTitle={episodeTitle}&bingeGroup={bingeGroup}&autoPlayNav={autoPlayNav}&returnToDetailOnBack={returnToDetailOnBack}&returnToHomeOnBack={returnToHomeOnBack}&filename={filename}&videoHash={videoHash}&videoSize={videoSize}&startFromBeginning={startFromBeginning}&addonName={addonName}&addonLogo={addonLogo}&streamDescription={streamDescription}&infoHash={infoHash}&fileIdx={fileIdx}&sources={sources}&contentLanguage={contentLanguage}&cloudSessionToken={cloudSessionToken}&launchStartedAtMs={launchStartedAtMs}&resumeFromMs={resumeFromMs}&startPaused={startPaused}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            streamUrl: String,
            title: String,
            streamName: String? = null,
            year: String? = null,
            headers: Map<String, String>? = null,
            contentId: String? = null,
            contentType: String? = null,
            contentName: String? = null,
            poster: String? = null,
            backdrop: String? = null,
            logo: String? = null,
            videoId: String? = null,
            season: Int? = null,
            episode: Int? = null,
            episodeTitle: String? = null,
            bingeGroup: String? = null,
            autoPlayNav: Boolean = false,
            returnToDetailOnBack: Boolean = false,
            returnToHomeOnBack: Boolean = false,
            filename: String? = null,
            videoHash: String? = null,
            videoSize: Long? = null,
            startFromBeginning: Boolean = false,
            addonName: String? = null,
            addonLogo: String? = null,
            streamDescription: String? = null,
            infoHash: String? = null,
            fileIdx: Int? = null,
            sources: List<String>? = null,
            contentLanguage: String? = null,
            cloudSessionToken: String? = null,
            launchStartedAtMs: Long = SystemClock.elapsedRealtime(),
            resumeFromMs: Long? = null,
            startPaused: Boolean = false
        ): String {
            val encodedUrl = encode(streamUrl)
            val encodedTitle = encode(title)
            val encodedStreamName = streamName?.let { encode(it) } ?: ""
            val encodedYear = year?.let { encode(it) } ?: ""
            val encodedHeaders = headers?.let {
                encode(org.json.JSONObject(it).toString())
            } ?: ""
            val encodedContentId = contentId?.let { encode(it) } ?: ""
            val encodedContentType = contentType?.let { encode(it) } ?: ""
            val encodedContentName = contentName?.let { encode(it) } ?: ""
            val encodedPoster = poster?.let { encode(it) } ?: ""
            val encodedBackdrop = backdrop?.let { encode(it) } ?: ""
            val encodedLogo = logo?.let { encode(it) } ?: ""
            val encodedVideoId = videoId?.let { encode(it) } ?: ""
            val encodedEpisodeTitle = episodeTitle?.let { encode(it) } ?: ""
            val encodedBingeGroup = bingeGroup?.let { encode(it) } ?: ""
            val encodedFilename = filename?.let { encode(it) } ?: ""
            val encodedVideoHash = videoHash ?: ""
            val encodedAddonName = addonName?.let { encode(it) } ?: ""
            val encodedAddonLogo = addonLogo?.let { encode(it) } ?: ""
            val encodedStreamDescription = streamDescription?.let { encode(it) } ?: ""
            val encodedInfoHash = infoHash ?: ""
            val encodedSources = sources?.let { encode(org.json.JSONArray(it).toString()) } ?: ""
            val encodedContentLanguage = contentLanguage?.let { encode(it) } ?: ""
            val encodedCloudSessionToken = cloudSessionToken?.let { encode(it) } ?: ""
            return "player/$encodedUrl/$encodedTitle?streamName=$encodedStreamName&year=$encodedYear&headers=$encodedHeaders&contentId=$encodedContentId&contentType=$encodedContentType&contentName=$encodedContentName&poster=$encodedPoster&backdrop=$encodedBackdrop&logo=$encodedLogo&videoId=$encodedVideoId&season=${season ?: ""}&episode=${episode ?: ""}&episodeTitle=$encodedEpisodeTitle&bingeGroup=$encodedBingeGroup&autoPlayNav=$autoPlayNav&returnToDetailOnBack=$returnToDetailOnBack&returnToHomeOnBack=$returnToHomeOnBack&filename=$encodedFilename&videoHash=$encodedVideoHash&videoSize=${videoSize ?: ""}&startFromBeginning=$startFromBeginning&addonName=$encodedAddonName&addonLogo=$encodedAddonLogo&streamDescription=$encodedStreamDescription&infoHash=$encodedInfoHash&fileIdx=${fileIdx ?: ""}&sources=$encodedSources&contentLanguage=$encodedContentLanguage&cloudSessionToken=$encodedCloudSessionToken&launchStartedAtMs=$launchStartedAtMs&resumeFromMs=${resumeFromMs ?: ""}&startPaused=$startPaused"
        }
    }
    data object Search : Screen("search")
    data object Discover : Screen("discover")
    data object Library : Screen("library")
    data object Settings : Screen("settings")
    data object Tracking : Screen("trakt")
    data object TmdbSettings : Screen("tmdb_settings")
    data object ThemeSettings : Screen("theme_settings")
    data object PlaybackSettings : Screen("playback_settings")
    data object About : Screen("about")
    data object SupportersContributors : Screen("supporters_contributors")
    data object LicensesAttributions : Screen("licenses_attributions")
    data object AddonManager : Screen("addon_manager")
    data object CatalogOrder : Screen("catalog_order")
    data object Plugins : Screen("plugins")
    data object ExperienceModeSelection : Screen("experience_mode_selection")
    data object LayoutSelection : Screen("layout_selection")
    data object LayoutSettings : Screen("layout_settings")
    data object Account : Screen("account")
    data object ManageProfiles : Screen("manage_profiles")
    data object AuthSignIn : Screen("auth_sign_in")
    data object AuthQrSignIn : Screen("auth_qr_sign_in")
    data object SyncCodeGenerate : Screen("sync_code_generate")
    data object SyncCodeClaim : Screen("sync_code_claim")
    data object CatalogSeeAll : Screen("catalog_see_all/{catalogId}/{addonId}/{type}?fromSearch={fromSearch}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(catalogId: String, addonId: String, type: String, fromSearch: Boolean = false): String {
            return "catalog_see_all/${encode(catalogId)}/${encode(addonId)}/${encode(type)}?fromSearch=$fromSearch"
        }
    }

    data object CategoryRows : Screen(
        "category_rows/{drillCatalogId}/{addonId}/{type}" +
            "?addonBaseUrl={addonBaseUrl}&title={title}" +
            "&secondaryCatalogId={secondaryCatalogId}&secondaryAddonId={secondaryAddonId}" +
            "&secondaryAddonBaseUrl={secondaryAddonBaseUrl}&secondaryType={secondaryType}"
    ) {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            drillCatalogId: String,
            addonId: String,
            type: String,
            addonBaseUrl: String,
            title: String,
            secondaryCatalogId: String = "",
            secondaryAddonId: String = "",
            secondaryAddonBaseUrl: String = "",
            secondaryType: String = ""
        ): String {
            return "category_rows/${encode(drillCatalogId)}/${encode(addonId)}/${encode(type)}" +
                "?addonBaseUrl=${encode(addonBaseUrl)}&title=${encode(title)}" +
                "&secondaryCatalogId=${encode(secondaryCatalogId)}" +
                "&secondaryAddonId=${encode(secondaryAddonId)}" +
                "&secondaryAddonBaseUrl=${encode(secondaryAddonBaseUrl)}" +
                "&secondaryType=${encode(secondaryType)}"
        }

        /** Route for a [DrillTarget], carrying its secondary drill source when present
         *  (a Home hub-group door drills into both the movie and the series drill). */
        fun createRoute(target: DrillTarget): String = createRoute(
            target.drillCatalogId,
            target.addonId,
            target.type,
            target.addonBaseUrl,
            target.title,
            secondaryCatalogId = target.secondary?.drillCatalogId ?: "",
            secondaryAddonId = target.secondary?.addonId ?: "",
            secondaryAddonBaseUrl = target.secondary?.addonBaseUrl ?: "",
            secondaryType = target.secondary?.type ?: ""
        )
    }

    data object Collections : Screen("collections")

    data object CollectionEditor : Screen("collection_editor?collectionId={collectionId}") {
        fun createRoute(collectionId: String? = null): String {
            return "collection_editor?collectionId=${collectionId ?: ""}"
        }
    }

    data object FolderDetail : Screen("folder_detail/{collectionId}/{folderId}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(collectionId: String, folderId: String): String {
            return "folder_detail/${encode(collectionId)}/${encode(folderId)}"
        }
    }

    data object ProfileSelection : Screen("profile_selection")

    data object CastDetail : Screen("cast_detail/{personId}/{personName}?preferCrew={preferCrew}") {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            personId: Int,
            personName: String,
            preferCrew: Boolean = false
        ): String {
            return "cast_detail/$personId/${encode(personName)}?preferCrew=$preferCrew"
        }
    }

    data object TmdbEntityBrowse : Screen(
        "tmdb_entity_browse/{entityKind}/{entityId}/{entityName}?sourceType={sourceType}"
    ) {
        private fun encode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun createRoute(
            entityKind: String,
            entityId: Int,
            entityName: String,
            sourceType: String
        ): String {
            return "tmdb_entity_browse/${encode(entityKind)}/$entityId/${encode(entityName)}?sourceType=${encode(sourceType)}"
        }
    }
}
