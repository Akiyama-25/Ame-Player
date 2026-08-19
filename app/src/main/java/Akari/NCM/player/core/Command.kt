package Akari.NCM.player.core

import kotlinx.coroutines.channels.Channel

sealed interface PlayerCommand {
    data class Load(val media: PlayableMedia) : PlayerCommand
    data class LoadPlaylist(val items: List<PlayableMedia>, val startIndex: Int) : PlayerCommand
    data object Prepare : PlayerCommand
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data class SeekTo(val positionMs: Long) : PlayerCommand
    data object Next : PlayerCommand
    data object Previous : PlayerCommand
    data class SkipToIndex(val index: Int) : PlayerCommand
    data class SetRepeatMode(val mode: RepeatMode) : PlayerCommand
    data object ToggleShuffle : PlayerCommand
    data class SetVolume(val volume: Float) : PlayerCommand
    data object Release : PlayerCommand
}

class CommandQueue {
    val channel = Channel<PlayerCommand>(Channel.UNLIMITED)

    fun send(command: PlayerCommand) {
        channel.trySend(command)
    }

    fun close() {
        channel.close()
    }
}
