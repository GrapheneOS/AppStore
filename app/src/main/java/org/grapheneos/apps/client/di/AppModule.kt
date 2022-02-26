package org.grapheneos.apps.client.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper
import org.grapheneos.apps.client.utils.network.MetadataHelper
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Singleton
    @Provides
    fun provideMetadataHelper(
        @ApplicationContext context: Context
    ): MetadataHelper = MetadataHelper(context)

    @Singleton
    @Provides
    fun provideApkDownloadHelper(
        @ApplicationContext context: Context
    ): ApkDownloadHelper = ApkDownloadHelper(context)

}
