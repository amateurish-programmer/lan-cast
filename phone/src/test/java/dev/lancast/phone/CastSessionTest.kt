package dev.lancast.phone

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class CastSessionTest {
    @Test fun currentOperationRemainsValid() {
        val operation = CastSession.generation.incrementAndGet()
        CastSession.ensureCurrent(operation)
    }
    @Test fun stopInvalidatesQueuedOperation() {
        val operation = CastSession.generation.incrementAndGet()
        CastSession.generation.incrementAndGet()
        try { CastSession.ensureCurrent(operation); fail("Old operation must cancel") }
        catch (_: CancellationException) { }
    }
    @Test fun newerOperationCannotBeOverwrittenByOlderWork() {
        val old = CastSession.generation.incrementAndGet()
        val current = CastSession.generation.incrementAndGet()
        try { CastSession.ensureCurrent(old); fail("Old operation must cancel") }
        catch (_: CancellationException) { }
        CastSession.ensureCurrent(current)
    }
}
