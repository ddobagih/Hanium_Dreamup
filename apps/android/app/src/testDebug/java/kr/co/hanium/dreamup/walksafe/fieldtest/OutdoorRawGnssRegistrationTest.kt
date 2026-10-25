package kr.co.hanium.dreamup.walksafe.fieldtest

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OutdoorRawGnssRegistrationTest {
    @Test fun unregisterFailureRetainsItsExactHandleAndAllowsAnExplicitRetry() {
        val registration = OutdoorRawGnssRegistration()
        var calls = 0
        var fail = true
        registration.prepare { calls++; if (fail) throw IllegalStateException("unregister") }
        registration.confirmRegistration()
        assertTrue(registration.registered)
        assertFalse(registration.release())
        assertFalse(registration.registered)
        assertTrue(registration.cleanupPending)
        assertNotNull(registration.failure)
        assertTrue(runCatching { registration.prepare {} }.isFailure)
        fail = false
        assertTrue(registration.release())
        assertFalse(registration.cleanupPending)
        assertNull(registration.failure)
        assertTrue(registration.release())
        assertEquals(2, calls)
    }

    @Test fun partiallyRegisteredCallbackRemainsOwnedEvenWithoutRegistrationSuccess() {
        val registration = OutdoorRawGnssRegistration()
        var fail = true
        registration.prepare { if (fail) throw SecurityException("permission changed") }
        assertFalse(registration.release())
        assertTrue(registration.cleanupPending)
        fail = false
        assertTrue(registration.release())
        assertFalse(registration.cleanupPending)
    }

    @Test fun failedNativeUnregisterCannotKeepRawWriterAdmissionAliveOrBlockFileClosure() {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var activeGeneration = -1L
        val writer = BoundedRawGnssWriter({ ByteArrayOutputStream() }, byteArrayOf(), 4096,
            onClosed = { closed.countDown() })
        val registration = OutdoorRawGnssRegistration()
        registration.prepare { throw IllegalStateException("native callback retained") }
        registration.confirmRegistration()
        writer.start { success, generation ->
            assertTrue(success)
            activeGeneration = generation
            opened.countDown()
        }
        assertTrue(opened.await(5, TimeUnit.SECONDS))
        assertTrue(writer.accepts(activeGeneration))
        writer.stop()
        assertFalse(registration.release())
        assertFalse(writer.accepts(activeGeneration))
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(writer.snapshot.closed)
        assertEquals(BoundedRawGnssWriter.State.STOPPED, writer.snapshot.state)
        assertTrue(registration.cleanupPending)
    }

    @Test fun actualRawRecorderSeparatesNativeAcknowledgementFromFileCompletionAndFencesLateCallbacks() {
        val source = File("src/debug/java/kr/co/hanium/dreamup/walksafe/fieldtest/OutdoorRawGnssRecorder.kt").readText()
        val stop = source.substringAfter("fun stop(onComplete:").substringBefore("private fun completeStart")
        assertTrue(stop.indexOf("writer.stop()") < stop.indexOf("unregister()"))
        assertTrue(source.contains("registration.prepare { manager.unregisterGnssMeasurementsCallback(listener) }"))
        assertTrue(source.contains("private fun unregister(): Boolean = registration.release()"))
        assertTrue(source.contains("registration.cleanupPending, registration.failure?.javaClass?.simpleName"))
        val record = source.substringAfter("private fun record(").substringBefore("private fun unregister()")
        assertTrue(record.indexOf("if (!writer.accepts(activeGeneration)) return") < record.indexOf("event.clock"))
    }
}
