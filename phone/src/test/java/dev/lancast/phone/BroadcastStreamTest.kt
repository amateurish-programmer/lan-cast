package dev.lancast.phone

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BroadcastStreamTest {
    @Test fun newClientWaitsUntilKeyframeBoundary() {
        val broadcast = BroadcastStream()
        val client = broadcast.open()
        broadcast.publish(byteArrayOf(1, 2))
        assertEquals(0, client.available())
        broadcast.activateWaitingClients()
        broadcast.publish(byteArrayOf(3, 4))
        assertEquals(3, client.read())
        assertEquals(4, client.read())
        client.close()
    }

    @Test fun laterClientDoesNotStartMidstream() {
        val broadcast = BroadcastStream()
        val first = broadcast.open()
        broadcast.activateWaitingClients()
        broadcast.publish(byteArrayOf(1))
        val second = broadcast.open()
        broadcast.publish(byteArrayOf(2))
        assertEquals(2, first.available())
        assertEquals(0, second.available())
        broadcast.activateWaitingClients()
        broadcast.publish(byteArrayOf(3))
        assertEquals(3, second.read())
        broadcast.close()
    }

    @Test fun slowClientDisconnectsWithoutBlockingHealthyClient() {
        val broadcast = BroadcastStream(maxQueuedBytes = 4)
        val slow = broadcast.open()
        val fast = broadcast.open()
        broadcast.activateWaitingClients()
        broadcast.publish(byteArrayOf(1, 2, 3, 4))
        val buffer = ByteArray(4)
        assertEquals(4, fast.read(buffer))
        broadcast.publish(byteArrayOf(5))
        assertEquals(5, fast.read())
        assertThrows(IOException::class.java) { slow.read() }
        broadcast.close()
    }

    @Test fun closeDropsQueuedPrivateDataAndUnblocksReader() {
        val broadcast = BroadcastStream()
        val waiting = broadcast.open()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            val read = executor.submit<Int> { started.countDown(); waiting.read() }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            broadcast.close()
            assertEquals(-1, read.get(1, TimeUnit.SECONDS).toInt())
            assertThrows(IOException::class.java) { broadcast.open() }
        } finally {
            executor.shutdownNow()
        }
        val other = BroadcastStream()
        val queued = other.open()
        other.activateWaitingClients()
        other.publish(byteArrayOf(99))
        other.close()
        assertEquals(-1, queued.read())
        assertEquals(0, queued.available())
    }

    @Test fun clientLimitRecoversAfterDisconnect() {
        val broadcast = BroadcastStream(maxClients = 1)
        val first = broadcast.open()
        assertThrows(IOException::class.java) { broadcast.open() }
        first.close()
        broadcast.open().close()
        broadcast.close()
    }

    @Test fun partialReadsPreserveOrderAndBoundIncludesCurrentChunk() {
        val broadcast = BroadcastStream(maxQueuedBytes = 6)
        val client = broadcast.open()
        broadcast.activateWaitingClients()
        broadcast.publish(byteArrayOf(1, 2, 3, 4))
        val buffer = ByteArray(4) { 88 }
        assertEquals(2, client.read(buffer, 1, 2))
        assertArrayEquals(byteArrayOf(88, 1, 2, 88), buffer)
        assertEquals(2, client.available())
        broadcast.publish(byteArrayOf(5, 6, 7, 8))
        assertEquals(2, client.read(buffer))
        assertArrayEquals(byteArrayOf(3, 4, 2, 88), buffer)
        assertEquals(4, client.read(buffer))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), buffer)
        assertEquals(0, client.read(buffer, 0, 0))
        assertThrows(IndexOutOfBoundsException::class.java) { client.read(buffer, 3, 2) }
        broadcast.close()
    }

    @Test fun callerCannotMutateAlreadyPublishedBytes() {
        val broadcast = BroadcastStream()
        val client = broadcast.open()
        broadcast.activateWaitingClients()
        val bytes = byteArrayOf(12)
        broadcast.publish(bytes)
        bytes[0] = 42
        assertEquals(12, client.read())
        broadcast.close()
    }
}
