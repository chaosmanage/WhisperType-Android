package com.whispertype.android.audio

import kotlinx.coroutines.channels.Channel

class BoundedAudioQueue<T>(capacity: Int) {
    /** Underlying channel; the receive side is handed to consumers. */
    internal val channel = Channel<T>(capacity)

    suspend fun send(item: T): Boolean = channel.send(item).let { true }

    fun trySend(item: T): Boolean = channel.trySend(item).isSuccess

    suspend fun receive(): T = channel.receive()

    fun close() = channel.close()
}
