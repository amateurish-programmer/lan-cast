package dev.lancast.phone

import java.io.IOException
import java.io.InputStream
import java.util.ArrayDeque

/** Bounded, in-memory fan-out. A slow receiver is disconnected, never allowed to stall capture. */
internal class BroadcastStream(
    private val maxClients: Int = 4,
    private val maxQueuedBytes: Int = 2 * 1024 * 1024
) {
    private val clients = mutableSetOf<Client>()
    private var stopped = false

    @Synchronized
    fun open(): InputStream {
        if (stopped) throw IOException("Screen sharing has stopped")
        if (clients.size >= maxClients) throw IOException("Too many mirror receivers")
        return Client().also { clients.add(it) }
    }

    /** Call under the muxer's writer lock immediately before writing keyframe tables. */
    @Synchronized
    fun activateWaitingClients() {
        clients.forEach { it.ready = true }
    }

    @Synchronized
    fun publish(data: ByteArray) {
        if (stopped || data.isEmpty()) return
        // The single immutable copy is shared by the bounded client queues.
        val immutable = data.copyOf()
        val iterator = clients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            if (client.ready && !client.offer(immutable)) {
                client.finish("Receiver is too slow; reconnect to resume")
                iterator.remove()
            }
        }
    }

    @Synchronized
    fun close() {
        stopped = true
        clients.forEach { it.finish(null) }
        clients.clear()
    }

    @Synchronized
    private fun remove(client: Client) {
        clients.remove(client)
    }

    private inner class Client : InputStream() {
        // Only the enclosing BroadcastStream monitor reads/writes ready.
        var ready = false
        private val lock = Object()
        private val chunks = ArrayDeque<ByteArray>()
        private var bytesQueued = 0
        private var current: ByteArray? = null
        private var offset = 0
        private var closed = false
        private var failure: String? = null

        fun offer(bytes: ByteArray): Boolean = synchronized(lock) {
            if (closed || bytesQueued.toLong() + bytes.size > maxQueuedBytes) return@synchronized false
            chunks.addLast(bytes)
            bytesQueued += bytes.size
            lock.notifyAll()
            true
        }

        fun finish(reason: String?) = synchronized(lock) {
            closed = true
            failure = reason
            chunks.clear()
            current = null
            offset = 0
            bytesQueued = 0
            lock.notifyAll()
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(destination: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off > destination.size - len) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            synchronized(lock) {
                while (true) {
                    if (closed) {
                        failure?.let { throw IOException(it) }
                        return -1
                    }
                    if (current == null && chunks.isNotEmpty()) {
                        current = chunks.removeFirst()
                        offset = 0
                    }
                    val chunk = current
                    if (chunk != null) {
                        val count = minOf(len, chunk.size - offset)
                        System.arraycopy(chunk, offset, destination, off, count)
                        offset += count
                        bytesQueued -= count
                        if (offset == chunk.size) current = null
                        return count
                    }
                    try {
                        lock.wait()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Mirror stream interrupted", interrupted)
                    }
                }
            }
        }

        override fun available(): Int = synchronized(lock) { bytesQueued }

        override fun close() {
            // Never acquire the BroadcastStream monitor while holding the client lock.
            finish(null)
            remove(this)
        }
    }
}
