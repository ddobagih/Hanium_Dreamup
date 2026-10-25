package kr.co.hanium.dreamup.walksafe.startup.interaction

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class StartupResourceSafetyAdmissionBehaviorTest {
    @Test fun constructionAndMissingLifecycleDoNotQueryResourceOrCoordinator() {
        val f=ResourceAdmissionFixture(); assertTrue(f.trace.isEmpty()); f.initialized=false
        assertTrue(f.controller.observeWalkRuntimeResourceSafety()); assertEquals(listOf("getLifecycleInitialized"),f.trace)
    }
    @Test fun inactiveEpochAvoidsResourceSnapshotAndClockQueries() {
        val f=ResourceAdmissionFixture(); f.runtime=null
        assertTrue(f.controller.observeWalkRuntimeResourceSafety())
        assertEquals(listOf("getLifecycleInitialized","getProbeInitialized","currentRuntimeEpoch"),f.trace)
    }
    @Test fun suppliedEpochAndResourcesAreUsedWithoutReplacingTheirSourceTime() {
        val f=ResourceAdmissionFixture(); val supplied=f.resources.copy(checkedAtElapsedRealtimeMs=WalkSessionResourceQuery.entries.mapIndexed { i,q->q to (950L+i*10L) }.toMap())
        assertTrue(f.controller.observeWalkRuntimeResourceSafety(f.epoch,supplied))
        assertFalse(f.trace.contains("currentRuntimeEpoch")); assertFalse(f.trace.contains("resourceSnapshot"))
        val next=f.coordinator.observe(WalkRuntimeSafetyObservation(f.epoch,960L,receivedAtElapsedRealtimeMs=1_000L))
        assertNotEquals(WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION,next.disposition)
    }
    @Test fun incompleteOrFutureResourceTimesDoNotReachSafetyOwner() {
        for(times in listOf(emptyMap(),WalkSessionResourceQuery.entries.associateWith { 1_001L },WalkSessionResourceQuery.entries.associateWith { -1L })) {
            val f=ResourceAdmissionFixture(); f.resources=f.resources.copy(checkedAtElapsedRealtimeMs=times)
            assertFalse(f.controller.observeWalkRuntimeResourceSafety()); assertFalse(f.trace.contains("coordinator"))
            assertTrue(f.trace.indexOf("thermal:false")<f.trace.indexOf("clock"))
        }
    }
    @Test fun bypassStillPublishesThermalAndChecksEpochWithoutInventingArbiterEvidence() {
        val f=ResourceAdmissionFixture(); f.bypass=true; f.resources=f.resources.copy(thermalThrottled=true,checkedAtElapsedRealtimeMs=emptyMap())
        assertTrue(f.controller.observeWalkRuntimeResourceSafety()); assertTrue(f.trace.contains("thermal:true")); assertFalse(f.trace.contains("clock")); assertFalse(f.trace.contains("coordinator"))
        assertFalse(f.controller.observeWalkRuntimeResourceSafety(WalkRuntimeEpoch("old",0)))
    }
    @Test fun criticalResourceIsFedToActualCoordinatorAndLatchedStopIsNotAdmitted() {
        val f=ResourceAdmissionFixture(); f.resources=f.resources.copy(thermalBelowCritical=false)
        assertFalse(f.controller.observeWalkRuntimeResourceSafety()); assertEquals(1,f.stops.size)
        assertTrue(WalkRuntimeSafetyStopCause.THERMAL_CRITICAL in f.stops.single().causes)
        f.controller.observeWalkRuntimeResourceSafety(); assertEquals(1,f.stops.size)
    }
    @Test fun olderEpochObservationCannotBeAdmittedByCurrentCoordinator() {
        val f=ResourceAdmissionFixture()
        assertFalse(f.controller.observeWalkRuntimeResourceSafety(WalkRuntimeEpoch("old",0)))
    }
}

private class ResourceAdmissionFixture {
    val trace=mutableListOf<String>(); val epoch=WalkRuntimeEpoch("walk",0); var runtime:WalkRuntimeEpoch?=epoch
    var initialized=true; var bypass=false; val stops=mutableListOf<WalkRuntimeSafetyStop>()
    var resources=WalkSessionDeviceResourceSnapshot(true,true,true,false,checkedAtElapsedRealtimeMs=WalkSessionResourceQuery.entries.associateWith { 950L })
    val coordinator=WalkRuntimeSafetyCoordinator(ApprovedWalkRuntimeSafetyThresholdProfile("test",5_000L,5_000L)) { stops+=it }.also { it.beginEpoch(epoch) }
    val controller=StartupResourceSafetyAdmission(
        startupPort<ResourceSafetyFacts>{name,args->trace+=name;when(name){
            "getLifecycleInitialized"->initialized;"getProbeInitialized"->true;"getBypass"->bypass
            "currentRuntimeEpoch"->runtime;"resourceSnapshot"->resources;"epochCurrent"->args[0]==epoch;else->error(name)
        }},ResourceSafetyThermalObservation { trace+="thermal:$it" },{ trace+="coordinator";coordinator },{ trace+="clock";1_000L },
    )
}
