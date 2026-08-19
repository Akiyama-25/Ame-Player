package Akari.NCM.player.di

import Akari.NCM.player.api.NcmApi
import Akari.NCM.player.data.UserSessionManager
import Akari.NCM.player.player.AmePlayerEngine
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

import Akari.NCM.player.data.UsbAudioManager

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideNcmApi(sessionManager: UserSessionManager): NcmApi = NcmApi(sessionManager)

    @Provides
    @Singleton
    fun providePlayerEngine(
        @ApplicationContext context: Context,
        ncmApi: NcmApi,
        usbAudioManager: UsbAudioManager,
        sessionManager: UserSessionManager
    ): AmePlayerEngine = AmePlayerEngine(context, ncmApi, usbAudioManager, sessionManager)
}

