package org.grapheneos.apps.client.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper
import org.grapheneos.apps.client.utils.network.MetaDataHelper
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideMetaDataHelper(
        @ApplicationContext context: Context
    ): MetaDataHelper = MetaDataHelper(context)

    @Singleton
    @Provides
    fun provideApkDownloadHelper(
        @ApplicationContext context: Context
    ): ApkDownloadHelper = ApkDownloadHelper(context)

}