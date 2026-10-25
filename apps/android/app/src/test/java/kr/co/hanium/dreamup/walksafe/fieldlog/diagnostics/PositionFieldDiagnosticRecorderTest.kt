package kr.co.hanium.dreamup.walksafe.fieldlog.diagnostics

import java.io.OutputStream
import kr.co.hanium.dreamup.walksafe.fieldlog.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.evaluation.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class PositionFieldDiagnosticRecorderTest {
    @Test fun missingRawOrFilteredNeverQueriesOsClockProfileOrAuthority() {
        val f=Fixture();val snapshot=f.snapshot()
        f.pipeline.appendPositionFieldGnssTrace(snapshot.copy(raw=null), f.location, true)
        f.pipeline.appendPositionFieldGnssTrace(snapshot.copy(filtered=null,filteredAtGnssMeasurement=null), f.location, true)
        assertTrue(f.events.isEmpty());assertTrue(f.records.isEmpty())
    }
    @Test fun oldMeasurementAndReplayedTailRemainTwoDifferentOriginalSources() {
        val f=Fixture();f.start();val old=f.snapshot()
        val measured=checkNotNull(old.filtered)
        val replayed=measured.copy(coordinate=PositionCoordinate(37.001,127.002),elapsedRealtimeMs=2_000L)
        f.pipeline.appendPositionFieldGnssTrace(old.copy(filtered=replayed,filteredAtGnssMeasurement=measured),f.location,true)
        assertEquals(2,f.records.size)
        val gnss=f.records[0];val motion=f.records[1]
        assertEquals(1_000_000_000L,gnss.measurementElapsedRealtimeNs)
        assertEquals(10_000L,gnss.measurementUtcEpochMs)
        assertEquals(PositionTraceSource.GNSS,gnss.source)
        assertEquals(measured.coordinate.latitude,gnss.filteredPosition!!.latitudeDegrees,0.0)
        assertEquals(2_000_000_000L,motion.measurementElapsedRealtimeNs)
        assertEquals(11_000L,motion.measurementUtcEpochMs)
        assertEquals(PositionTraceSource.SENSOR_GNSS_ANCHORED,motion.source)
        assertEquals(replayed.coordinate.latitude,motion.filteredPosition!!.latitudeDegrees,0.0)
        assertFalse(f.events.contains("match:1000"));assertTrue(f.events.contains("match:2000"))
        assertEquals(false,gnss.stationary!!.zuptApplied);assertEquals(false,motion.stepProfile!!.stepDetected)
    }
    @Test fun noReplayFallbackOrAcceptedMatchIsInventedForDifferentInputClock() {
        val f=Fixture();f.start();val old=f.snapshot()
        f.pipeline.appendPositionFieldGnssTrace(old.copy(filtered=old.filtered!!.copy(elapsedRealtimeMs=2_000),filteredAtGnssMeasurement=null),f.location,true)
        assertTrue(f.records.isEmpty());assertTrue(f.events.isEmpty())
        f.pipeline.appendPositionFieldGnssTrace(old,f.location,false)
        assertEquals(1,f.records.size);assertNull(f.records[0].matchedPosition)
        assertFalse(f.events.any { it.startsWith("match:") })
    }
    @Test fun actualRecorderAdmissionIsDeferredAndOldAuthorityCannotWriteTheSample() {
        val f=Fixture();val old=f.snapshot()
        f.pipeline.appendPositionFieldGnssTrace(old,f.location,true)
        assertFalse(f.events.contains("authority"));assertTrue(f.records.isEmpty())
        f.start();f.authority=f.authority.copy(gatewayGeneration=21L)
        f.pipeline.appendPositionFieldGnssTrace(old,f.location,true)
        assertTrue(f.events.contains("authority"));assertTrue(f.records.isEmpty())
        assertFalse(f.controller.controls { f.authority.walkEpoch }.active)
    }
    @Test fun invalidOsFieldsStayAbsentAndStepProfileKeepsLegacyReportedNoise() {
        val f=Fixture();f.start();val old=f.snapshot();f.location.invalid=true
        f.pipeline.appendPositionFieldGnssTrace(old,f.location,true)
        val record=f.records.single()
        assertNull(record.accuracyMeters);assertNull(record.speedMetersPerSecond);assertNull(record.bearingDegrees)
        assertEquals(old.raw!!.effectiveHorizontalAccuracyM,record.gnss!!.measurementNoiseMeters)
        assertEquals(0L,record.stepProfile!!.stepCount)
        assertEquals(Int.MAX_VALUE,record.stepProfile!!.profileSampleCount)
        assertEquals(PositionHeadingSource.NONE,record.heading!!.source)
    }
    @Test fun failedStorageAppendTerminatesTheActualRecorderAndDoesNotClaimLaterAck() {
        val f=Fixture();f.start();f.appendSucceeded=false
        f.pipeline.appendPositionFieldGnssTrace(f.snapshot(),f.location,true)
        assertEquals(1,f.records.size)
        f.pipeline.appendPositionFieldMotionTrace(f.snapshot(),true,true,2_000L)
        assertEquals(1,f.records.size);assertFalse(f.controller.controls { f.authority.walkEpoch }.active)
    }
    private class Fixture : PositionTraceReadings {
        val events=mutableListOf<String>();val records=mutableListOf<PositioningTraceRecord>()
        var appendSucceeded=true;var authority=FieldRecordAuthority(true,true,true,WalkRuntimeEpoch("walk",7L),20L)
        private val coordinator=PositioningCoordinator()
        val location=LocationSample(events)
        private val storage=object : PositionFieldScopeStorage {
            override fun localScopeId()="11111111-1111-1111-1111-111111111111"
            override fun purgePending()=false
            override fun purgeMarkerExists()=false
            override fun persistPurgeMarker()=true
            override fun storePurgePending(pending:Boolean)=true
            override fun storeScope(scope:String,clearPurgePending:Boolean)=true
            override fun clearPurgeMarker()=true
        }
        private val store=object : PositionFieldRecorderStore {
            override fun start(metadata:PositioningTraceStartMetadata,binding:RecorderBinding)=PositionFieldSessionLease("session","hash",1L,binding.walkEpoch)
            override fun stop(lease:PositionFieldSessionLease)=true
            override fun purge()=true
            override fun list(binding:RecorderBinding)=emptyList<PositionFieldSessionSummary>()
            override fun append(lease:PositionFieldSessionLease,record:PositioningTraceRecord):Boolean { records+=record;return appendSucceeded }
            override fun markCheckpoint(lease:PositionFieldSessionLease,checkpoint:PositioningTraceCheckpoint)=true
            override fun export(sessionId:String,binding:RecorderBinding,output:OutputStream)=PositionFieldExportResult(PositionFieldExportStatus.EXPORTED,0L,false)
        }
        val controller=PositionFieldRecorderController(true,storage,{store},{3_000L},{3_000_000_000L})
        val pipeline=PositionFieldDiagnosticRecorder({events+="recorder";controller},{events+="authority";authority},this)
        fun start() { controller.initialize();assertTrue(controller.start(PositionFieldStartRequest("22222222-2222-2222-2222-222222222222",true,true,"Phone",35)){authority}) }
        fun snapshot()=coordinator.observeGnss(GnssPositionObservation(37.0,127.0,3.0,1_000L))
        override fun currentProfile():PedestrianMotionProfileSnapshot { events+="profile";return coordinator.currentProfile().copy(acceptedSampleCount=Long.MAX_VALUE) }
        override fun currentAcceptedRouteMatchFor(atMs:Long):RouteCorridorMatchResult? { events+="match:$atMs";return null }
        override val stepCount get()=-3
        override val stepLengthM get()=0.7f
        override val gpsCourse:HeadingObservation? get()=null
        override val stationaryState get()=PositionStationaryState.STATIONARY
    }
    private class LocationSample(private val events:MutableList<String>):PositionDiagnosticLocation {
        var invalid=false
        override val elapsedRealtimeNanos get()=1_000_000_000L.also {events+="measurement_ns"}
        override val time get()=10_000L.also {events+="measurement_utc"}
        override val accuracy get()=if(invalid) Float.NaN else 3f
        override val speed get()=if(invalid) -1f else 1.2f
        override val bearing get()=if(invalid) 360f else 30f
        override fun hasAccuracy()=true
        override fun hasSpeed()=true
        override fun hasBearing()=true
    }
}
