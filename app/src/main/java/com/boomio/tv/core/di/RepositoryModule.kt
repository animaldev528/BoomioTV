package com.boomio.tv.core.di

import com.boomio.tv.data.repository.AddonRepositoryImpl
import com.boomio.tv.data.repository.CatalogRepositoryImpl
import com.boomio.tv.data.repository.LibraryRepositoryImpl
import com.boomio.tv.data.repository.MetaRepositoryImpl
import com.boomio.tv.data.repository.StreamRepositoryImpl
import com.boomio.tv.data.repository.SubtitleRepositoryImpl
import com.boomio.tv.data.repository.SyncRepositoryImpl
import com.boomio.tv.data.repository.WatchProgressRepositoryImpl
import com.boomio.tv.domain.repository.AddonRepository
import com.boomio.tv.domain.repository.CatalogRepository
import com.boomio.tv.domain.repository.LibraryRepository
import com.boomio.tv.domain.repository.MetaRepository
import com.boomio.tv.domain.repository.StreamRepository
import com.boomio.tv.domain.repository.SubtitleRepository
import com.boomio.tv.domain.repository.SyncRepository
import com.boomio.tv.domain.repository.WatchProgressRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAddonRepository(impl: AddonRepositoryImpl): AddonRepository

    @Binds
    @Singleton
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    @Singleton
    abstract fun bindMetaRepository(impl: MetaRepositoryImpl): MetaRepository

    @Binds
    @Singleton
    abstract fun bindStreamRepository(impl: StreamRepositoryImpl): StreamRepository

    @Binds
    @Singleton
    abstract fun bindSubtitleRepository(impl: SubtitleRepositoryImpl): SubtitleRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindWatchProgressRepository(impl: WatchProgressRepositoryImpl): WatchProgressRepository
}
