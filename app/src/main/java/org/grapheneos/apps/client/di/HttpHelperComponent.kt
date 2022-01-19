package org.grapheneos.apps.client.di

import androidx.annotation.Nullable
import dagger.BindsInstance
import dagger.Component
import org.grapheneos.apps.client.item.Progress
import org.grapheneos.apps.client.item.network.Response
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Component
@Singleton
interface HttpHelperComponent {

    @Component.Builder
    interface Builder {

        @BindsInstance
        fun uri(@Named("uri") uri: String): Builder

        @BindsInstance
        fun file(@Named("file") file: File): Builder

        @BindsInstance
        fun setTimeout(@Named("timeout") timeout: Int?): Builder

        @BindsInstance
        fun addETag(@Nullable @Named("eTag") eTag: String? = null): Builder

        @BindsInstance
        fun addOnResponse(@Nullable @Named("responseListener") listener: (response: Response) -> Unit?): Builder

        @BindsInstance
        fun addProgressListener(
            @Nullable @Named("progressListener") progressListener: (progress: Progress) -> Unit?
        ): Builder

        fun build(): HttpHelperComponent

    }

    companion object {
        fun Builder.defaultConfigBuild(): Builder {
            return this
                .addOnResponse { }
                .addProgressListener { }
                .addETag(null)

        }
    }

    fun downloader(): HttpModule

}