package com.boomio.tv.di

import com.boomio.tv.core.auth.AuthManager
import com.boomio.tv.core.plugin.PluginManager
import com.boomio.tv.core.plugin.PluginRuntime
import com.boomio.tv.core.plugin.cloudstream.ExternalExtensionLoader
import com.boomio.tv.core.plugin.cloudstream.ExternalExtensionRunner
import com.boomio.tv.core.plugin.cloudstream.ExternalRepoParser
import com.boomio.tv.core.sync.PluginSyncService
import com.boomio.tv.data.local.PluginDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    @Provides
    @Singleton
    fun providePluginRuntime(): PluginRuntime {
        return PluginRuntime()
    }

    @Provides
    @Singleton
    fun providePluginManager(
        dataStore: PluginDataStore,
        runtime: PluginRuntime,
        pluginSyncService: PluginSyncService,
        authManager: AuthManager,
        externalRepoParser: ExternalRepoParser,
        externalExtensionLoader: ExternalExtensionLoader,
        externalExtensionRunner: ExternalExtensionRunner
    ): PluginManager {
        return PluginManager(
            dataStore, runtime, pluginSyncService, authManager,
            externalRepoParser, externalExtensionLoader, externalExtensionRunner
        )
    }
}
