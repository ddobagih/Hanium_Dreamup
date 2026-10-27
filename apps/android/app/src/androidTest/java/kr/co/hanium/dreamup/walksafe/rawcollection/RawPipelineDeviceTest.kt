package kr.co.hanium.dreamup.walksafe.rawcollection

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real encrypted files and native HTTP; synthetic authority/state/clock only in an isolated fixture.
 * Never starts the actual app walk or changes actual account consent. No camera is used. */
class RawPipelineDeviceTest {
    private class Fixture(base: String, val id: String = UUID.randomUUID().toString()) : AutoCloseable {
        var retainForRestart = false
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir.canonicalFile, "raw-pipeline-$id").apply { check(isDirectory || mkdir()) }
        val policy = AeadKeyPolicy("walksafe.instrumentation.pipeline.$id.v",1,setOf(1))
        val cipher = AndroidKeyStoreAead(policy)
        var wall = 1_800_000_000_000L
        var elapsed = 50_000L
        val store = RawCollectionStore(root,cipher,{wall})
        var submittedPlaintexts:List<ByteArray> = emptyList()
        private val client=object:RawCollectionNetworkClient {
            private val native=AndroidRawCollectionClient()
            override fun uploadCall(session:GatewayFieldSession,consent:IntegratedConsentConfirmation,
                networkBinding:IntegratedConsentNetworkBinding,localManifest:RawCollectionManifest,
                backendManifest:BackendRawManifest,chunks:List<RawPlaintextChunk>,isCurrent:()->Boolean):CancellableNetworkCall<RawCollectionReceipt> {
                // Keep references for post-execution inspection; do not replace transport or copy plaintext.
                submittedPlaintexts=chunks.map{it.plaintext}
                return native.uploadCall(session,consent,networkBinding,localManifest,backendManifest,chunks,isCurrent)
            }
        }
        fun assertSubmittedPlaintextCleared(){
            assertEquals(2,submittedPlaintexts.size)
            submittedPlaintexts.forEach{assertTrue(it.isNotEmpty());assertTrue("Submitted plaintext retained",it.all{b->b==0.toByte()})}
        }
        val coordinator = RawCollectionRuntimeCoordinator(store,client,UUID::randomUUID,{elapsed},30_000L,
            testOnly=Unit, allocatableBytes={root.usableSpace})
        val consent = IntegratedConsentSession().apply { check(accept(confirmation())) }
        val session = GatewayFieldSession.backendAccountDeviceSession(base,BackendAccountDeviceCookieBinding(
            ACTOR,1L,1L,DEVICE,"s".repeat(32),System.currentTimeMillis()+3_600_000),
            "${GatewayFieldSession.COOKIE_NAME}=isolated-test-cookie",System.currentTimeMillis()+3_600_000)
        val owner = RawCollectionOwner(ACTOR,1L,DEVICE)
        private val epoch = WalkRuntimeEpoch(WALK,0L)
        var networkBinding=IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI){it.openConnection()}
        fun context(state: WalkSessionState=WalkSessionState.ENDED) = RawCollectionRuntimeContext(
            session,7L,PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,ACTOR,7L,1L),consent,
            when(state) { WalkSessionState.ACTIVE -> activeWalkSnapshot(epoch);WalkSessionState.PAUSED -> pausedWalkSnapshot(epoch);else -> endedWalkSnapshot(epoch) },
            if(state==WalkSessionState.ENDED) null else GatewayWalkLease("ACQUIRED",ACTOR,DEVICE,WALK,"lease-id",1L,wall,wall+90_000,wall,elapsed+90_000),
            ActiveNetworkTransport.WIFI,networkBinding,true)
        fun sample(thermal: Boolean?=false): RawRuntimeWindowSnapshot {
            val a=RawRuntimeWindowAccumulator()
            listOf(20L,40L,900L).forEachIndexed { i,d -> a.recordCompletedInference(WALK,i*1_000L,1,d,i,"model-v1",IDENTITY) }
            return a.snapshot(wall-30_000,wall,true,thermal)
        }
        fun capture(s: RawRuntimeWindowSnapshot=sample()) = coordinator.captureMetadataBatch(context(WalkSessionState.ACTIVE),s.detection,s.performance)
        fun seed() { assertEquals(RawMetadataCaptureResult.CAPTURED,capture());assertEquals(RawSegmentSealResult.SEALED,coordinator.sealActiveSegment(context())) }
        fun execute(): RawCollectionUploadOutcome = checkNotNull(coordinator.startNextUpload {context()}).execute()
        fun files()=root.walkTopDown().filter{it.isFile}.associate {it.relativeTo(root).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()}
        override fun close() {
            coordinator.cancelActiveUpload()
            if (retainForRestart) return
            assertTrue(cipher.destroyKnownVersions())
            val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            ks.deleteEntry("${policy.alias(1)}.generation");assertFalse(ks.containsAlias(policy.alias(1)))
            assertTrue(root.deleteRecursively())
        }
    }

    @Test fun independentSnapshotsRejectedAndMergedTailAndUnknownThermalSurviveEncryptedStorage() {
        Fixture("http://127.0.0.1:1").use { f ->
            assertTrue(f.store.alignConsentPolicy(INTEGRATED_CONSENT_POLICY_VERSION))
            val first=f.sample(null);val other=f.sample(false);val before=f.files()
            assertEquals(RawMetadataCaptureResult.INVALID_WINDOW,f.coordinator.captureMetadataBatch(f.context(WalkSessionState.ACTIVE),first.detection,other.performance))
            assertEquals(before,f.files())
            assertEquals(RawMetadataCaptureResult.CAPTURED,f.capture(first))
            f.wall+=30_000;f.elapsed+=30_000
            assertEquals(RawMetadataCaptureResult.CAPTURED,f.capture(f.sample(false)))
            assertEquals(RawSegmentSealResult.GATE_BLOCKED,f.coordinator.sealActiveSegment(f.context(WalkSessionState.PAUSED)))
            assertEquals(RawSegmentSealResult.SEALED,f.coordinator.sealActiveSegment(f.context()))
            val restored=RawCollectionStore(f.root,f.cipher,{f.wall})
            val m=restored.readyManifests(f.owner).single()
            assertEquals(2,m.chunks.size)
            val jsons=m.chunks.map { chunk ->
                val data=checkNotNull(restored.readUploadChunk(m.collectionId,f.owner,chunk.ordinal))
                try { JSONObject(data.plaintext.toString(Charsets.UTF_8)) } finally { data.plaintext.fill(0) }
            }
            val d=jsons.single{it.getString("kind")=="DETECTION"};val p=jsons.single{it.getString("kind")=="PERFORMANCE"}
            assertEquals(6,d.getInt("completed_inference_count"));assertEquals(6,p.getInt("completed_inference_count"))
            assertEquals(900,d.getInt("max_inference_ms"));assertEquals(320,d.getInt("average_inference_ms"))
            assertEquals(2,d.getInt("window_count"));assertTrue(p.isNull("thermal_throttled"))
            assertEquals(0,p.getInt("thermal_throttled_window_count"));assertEquals(6,p.getInt("busy_dropped_frame_count"))
            val disk=f.root.walkTopDown().filter{it.isFile}.joinToString{it.readText()}
            assertFalse(disk.contains("average_inference_ms"));assertFalse(disk.contains("thermal_throttled"))
            report("RAW_PIPELINE aggregate=PASS mixed_snapshot_rejected=true real_encrypted_restore=true synthetic_metrics=true")
        }
    }

    @Test fun changedNetworkBindingCancelsBeforeOpeningAnyHttpRequest() {
        Fixture("http://127.0.0.1:1").use { f ->
            f.seed();val before=f.files()
            val call=checkNotNull(f.coordinator.startNextUpload{f.context()})
            f.networkBinding=IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI){error("Changed network must never be opened")}
            assertEquals(RawCollectionUploadOutcome.CANCELLED,call.execute())
            assertEquals(before,f.files())
            report("RAW_PIPELINE changed_binding=CANCELLED http_not_opened=true synthetic_network_change=true")
        }
    }

    @Test fun nativeDisabledResponsePreservesFilesAndStopsFurtherAttempts() {
        NativeServer(503,"{\"detail\":{\"code\":\"raw_ingest_disabled\"}}").use { server ->
            Fixture(server.base).use { f ->
                f.seed();val before=f.files()
                assertEquals(RawCollectionUploadOutcome.SERVER_DISABLED,f.execute());server.finished();f.assertSubmittedPlaintextCleared()
                assertEquals(before,f.files());assertEquals(1,f.store.readyManifests(f.owner).size)
                assertEquals(RawReadyStatus.GateBlocked(RawCollectionGateReason.SERVER_DISABLED),f.coordinator.uploadStatus(f.context()))
                repeat(10){assertNull(f.coordinator.startNextUpload{f.context()})}
                report("RAW_PIPELINE native_503_disabled=PASS plaintext_zeroed=true encrypted_queue_preserved=true repeated_calls=10 blocked=true")
            }
        }
    }

    @Test fun nativeDisabledResponseIsRecheckedOnlyAfterActualProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("rawDisabledPhase")
        org.junit.Assume.assumeTrue(phase in setOf("write", "read"))
        val run = requireNotNull(args.getString("rawDisabledRun"))
        require(run.matches(Regex("[a-z0-9]{16}")))
        NativeServer(503,"{\"detail\":{\"code\":\"raw_ingest_disabled\"}}").use { server ->
            Fixture(server.base, run).use { f ->
                val marker = File(f.root, "restart-pid")
                if (phase == "write") {
                    assertFalse(marker.exists()); f.seed()
                } else {
                    assertNotEquals(marker.readText().toInt(), android.os.Process.myPid())
                    assertEquals(1, f.store.readyManifests(f.owner).size)
                    assertNotEquals(RawReadyStatus.GateBlocked(RawCollectionGateReason.SERVER_DISABLED), f.coordinator.uploadStatus(f.context()))
                }
                val before = f.files()
                assertEquals(RawCollectionUploadOutcome.SERVER_DISABLED, f.execute())
                server.finished(); f.assertSubmittedPlaintextCleared()
                assertEquals(before, f.files())
                repeat(10) { assertNull(f.coordinator.startNextUpload { f.context() }) }
                report("RAW_DISABLED_RESTART phase=$phase actual_native_http503=true subsequent_requests_blocked=10 encrypted_files_unchanged=true pid=${android.os.Process.myPid()} synthetic_authority=true")
                if (phase == "write") {
                    marker.writeText(android.os.Process.myPid().toString())
                    f.retainForRestart = true
                }
            }
        }
    }

    @Test fun nativeRejectedUploadMustStayBlockedAfterFailedCleanupAndProcessRestart() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("rawRejectedPhase")
        org.junit.Assume.assumeTrue(phase in setOf("write", "read"))
        val run = requireNotNull(args.getString("rawRejectedRun"))
        val reason = requireNotNull(args.getString("rawRejectedReason"))
        require(run.matches(Regex("[a-z0-9]{16}")))
        require(reason in setOf("consent", "walk"))
        val code = if (reason == "consent") "raw_source_collection_consent_required" else "raw_collection_walk_not_uploadable"
        NativeServer(409, JSONObject().put("detail", JSONObject().put("code", code)).toString()).use { server ->
            Fixture(server.base, run).use { f ->
                val marker = File(f.root, "restart-marker.json")
                if (phase == "write") {
                    assertFalse(marker.exists()); f.seed()
                    val collection = f.store.readyManifests(f.owner).single().collectionId
                    val dir = File(f.root, "raw_collections_v2/collections/$collection")
                    try {
                        android.system.Os.chmod(dir.absolutePath, 0b101000000)
                        assertFalse(dir.canWrite())
                        assertEquals(RawCollectionUploadOutcome.LOCAL_READ_FAILED, f.execute())
                        server.finished(); f.assertSubmittedPlaintextCleared()
                        marker.writeText(JSONObject().put("pid", android.os.Process.myPid()).put("collection", collection).toString())
                        f.retainForRestart = true
                        report("RAW_REJECTED_RESTART phase=write reason=$reason actual_http409=true actual_marker_and_delete_denial=true local_failure_reported=true pid=${android.os.Process.myPid()}")
                    } finally {
                        if (!f.retainForRestart) android.system.Os.chmod(dir.absolutePath, 0b111000000)
                    }
                } else {
                    val saved = JSONObject(marker.readText())
                    assertNotEquals(saved.getInt("pid"), android.os.Process.myPid())
                    val collection = saved.getString("collection")
                    android.system.Os.chmod(File(f.root, "raw_collections_v2/collections/$collection").absolutePath, 0b111000000)
                    val selectable = f.store.readyManifests(f.owner).any { it.collectionId == collection }
                    // Exercise the normal coordinator only if the rejected item was selected again.
                    if (selectable) {
                        val outcome = f.execute()
                        server.finished(); f.assertSubmittedPlaintextCleared()
                        assertEquals(if (reason == "consent") RawCollectionUploadOutcome.CONSENT_REJECTED else RawCollectionUploadOutcome.WALK_REJECTED, outcome)
                        assertTrue(f.store.readyManifests(f.owner).isEmpty())
                        report("RAW_REJECTED_RESTART phase=read reason=$reason second_actual_http_request=true same_collection=$collection cleanup_after_second_rejection=true pid=${android.os.Process.myPid()} loopback_only=true synthetic_authority=true")
                    }
                    assertFalse("Permanently rejected data was uploaded again after restart", selectable)
                    repeat(10) { assertNull(f.coordinator.startNextUpload { f.context() }) }
                    assertFalse("No connection may reach the loopback server", server.received.await(500, TimeUnit.MILLISECONDS))
                    assertEquals(RawCapacityAdmission.ALLOWED, f.store.admitNewSegment(WALK, confirmation().backendConsentReceiptSha256))
                    assertFalse(File(f.root, "raw_collections_v2/collections/$collection").exists())
                    report("RAW_REJECTED_RESTART phase=read reason=$reason second_actual_http_request=false blocked_attempts=10 local_admission_cleanup=true pid=${android.os.Process.myPid()} loopback_only=true synthetic_authority=true")
                }
            }
        }
    }

    @Test fun nativeRetryAfterBlocksUntilDeadlineAndMalformedSuccessCannotDeleteQueue() {
        NativeServer(429,"{}","60").use { server ->
            Fixture(server.base).use { f ->
                f.seed();val before=f.files()
                assertEquals(RawCollectionUploadOutcome.RETRYABLE_FAILURE,f.execute());server.finished();f.assertSubmittedPlaintextCleared()
                f.elapsed+=59_999
                assertNull(f.coordinator.startNextUpload{f.context()})
                assertEquals(RawReadyStatus.GateBlocked(RawCollectionGateReason.RETRY_DELAY),f.coordinator.uploadStatus(f.context()))
                assertEquals(before,f.files())
                f.elapsed+=1
                val next=checkNotNull(f.coordinator.startNextUpload{f.context()});next.cancel()
                assertEquals(before,f.files())
                report("RAW_PIPELINE native_429=PASS plaintext_zeroed=true retry_deadline_boundary=true clock=synthetic queue_preserved=true")
            }
        }
        NativeServer(201,"{}").use { server ->
            Fixture(server.base).use { f ->
                f.seed();val before=f.files()
                assertEquals(RawCollectionUploadOutcome.RECEIPT_REJECTED,f.execute());server.finished();f.assertSubmittedPlaintextCleared()
                assertEquals(before,f.files());assertEquals(1,f.store.readyManifests(f.owner).size)
                report("RAW_PIPELINE malformed_native_201=REJECTED plaintext_zeroed=true encrypted_queue_preserved=true")
            }
        }
    }

    @Test fun activeNativeTransferCancellationPreservesQueueAndReleasesSingleFlight() {
        NativeServer(503,"{}",hold=true).use { server ->
            Fixture(server.base).use { f ->
                f.seed();val before=f.files();val call=checkNotNull(f.coordinator.startNextUpload{f.context()})
                val worker=Executors.newSingleThreadExecutor()
                try {
                    val result=worker.submit<RawCollectionUploadOutcome>{call.execute()}
                    assertTrue(server.received.await(10,TimeUnit.SECONDS))
                    assertNull(f.coordinator.startNextUpload{f.context()})
                    f.coordinator.cancelActiveUpload()
                    server.release.countDown()
                    assertEquals(RawCollectionUploadOutcome.CANCELLED,result.get(15,TimeUnit.SECONDS));server.finished();f.assertSubmittedPlaintextCleared()
                    assertEquals(before,f.files())
                    checkNotNull(f.coordinator.startNextUpload{f.context()}).cancel()
                    report("RAW_PIPELINE native_inflight_cancel=PASS plaintext_zeroed=true single_flight_released=true queue_preserved=true")
                } finally {server.release.countDown();worker.shutdownNow()}
            }
        }
    }

    @Test fun rejectedWalkWithUnwritableFilesMustReportLocalFailure() = rejectWithUnwritableFiles("raw_collection_walk_not_uploadable")
    @Test fun rejectedConsentWithUnwritableFilesMustReportLocalFailure() = rejectWithUnwritableFiles("raw_source_collection_consent_required")
    private fun rejectWithUnwritableFiles(code:String) {
        NativeServer(409,JSONObject().put("detail",JSONObject().put("code",code)).toString()).use { server ->
            Fixture(server.base).use { f ->
                f.seed()
                val collection=f.store.readyManifests(f.owner).single().collectionId
                val dir=File(f.root,"raw_collections_v2/collections/$collection")
                assertTrue(dir.isDirectory)
                val before=f.files()
                try {
                    assertTrue(dir.setWritable(false,true));assertFalse("Fault injection must really deny writing",dir.canWrite())
                    val outcome=f.execute();server.finished();f.assertSubmittedPlaintextCleared()
                    assertEquals("Failed deletion must preserve existing ciphertext", before, f.files().filterKeys { it in before })
                    assertTrue("Durable rejection must block immediate reselection", f.store.readyManifests(f.owner).isEmpty())
                    report("RAW_PIPELINE rejected_code=$code actual_unwritable_directory=true outcome=$outcome ciphertext_still_present=true")
                    assertEquals("Rejected segment could not be marked or deleted",RawCollectionUploadOutcome.LOCAL_READ_FAILED,outcome)
                } finally {assertTrue(dir.setWritable(true,true))}
                // Retry the existing local cleanup after the isolated filesystem fault is removed.
                val cleared=if(code=="raw_collection_walk_not_uploadable") f.store.discardWalkRejected(f.owner,collection)
                    else f.store.discardConsentRejected(f.owner,collection)
                assertTrue(cleared);assertTrue(f.store.readyManifests(f.owner).isEmpty());assertFalse(dir.exists())
                report("RAW_PIPELINE rejected_code=$code local_cleanup_after_permission_restore=PASS")
            }
        }
    }

    private class NativeServer(status: Int,body: String,retry: String?=null,hold: Boolean=false):AutoCloseable {
        val socket=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val base="http://127.0.0.1:${socket.localPort}"
        val received=CountDownLatch(1);val release=CountDownLatch(if(hold)1 else 0)
        private val worker=Executors.newSingleThreadExecutor()
        private val response=worker.submit {
            socket.soTimeout=15_000
            socket.accept().use { c ->
                c.soTimeout=15_000
                val input=c.getInputStream();val header=ByteArrayOutputStream()
                while(!header.toString("US-ASCII").endsWith("\r\n\r\n")) {val b=input.read();check(b>=0&&header.size()<16_384);header.write(b)}
                val length=header.toString("US-ASCII").lineSequence().first{it.startsWith("Content-Length:",true)}.substringAfter(':').trim().toInt()
                check(length in 1..1_000_000);repeat(length){check(input.read()>=0)}
                received.countDown();check(release.await(15,TimeUnit.SECONDS))
                val bytes=body.toByteArray()
                try {c.getOutputStream().apply{
                    write(("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n"+(retry?.let{"Retry-After: $it\r\n"}?:"")+"Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray());write(bytes);flush()
                }} catch(e:java.net.SocketException){if(!hold)throw e}
            }
        }
        fun finished(){response.get(20,TimeUnit.SECONDS)}
        override fun close(){release.countDown();socket.close();worker.shutdownNow()}
    }
    private fun report(value:String)=InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("stream",value+"\n")})
    private companion object {
        const val ACTOR="123e4567-e89b-42d3-a456-426614174002"
        const val WALK="123e4567-e89b-42d3-a456-426614174001"
        const val DEVICE="device-installation-00000001"
        val IDENTITY=RawDetectorRuntimeIdentity("1".repeat(64),"2".repeat(64),"3".repeat(64),"cpu",false)
        fun confirmation()=IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,INTEGRATED_CONSENT_POLICY_VERSION,
            DEVICE,"request_id_1234567890",IntegratedConsentItemVersions(),1L,1L,IntegratedConsentSelections(rawSourceCollection=true),
            "2026-08-29T00:00:00.000Z","a".repeat(64),"c".repeat(64),"b".repeat(64))
    }
}

private fun activeWalkSnapshot(epoch: WalkRuntimeEpoch): WalkSessionSnapshot {
    val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
    val observations = WalkSessionReadinessRequirement.entries.associateWith { requirement ->
        WalkSessionReadinessObservation(
            epoch = epoch,
            requirement = requirement,
            status = if (plan.requires(requirement)) {
                WalkSessionReadinessStatus.READY
            } else {
                WalkSessionReadinessStatus.NOT_REQUIRED
            },
        )
    }
    return WalkSessionSnapshot(
        epoch = epoch,
        state = WalkSessionState.ACTIVE,
        mode = WalkSessionMode.FULL,
        recoveryStage = null,
        isForeground = true,
        readiness = WalkSessionReadinessSnapshot(epoch, plan, 1L, observations),
    )
}

private fun pausedWalkSnapshot(epoch: WalkRuntimeEpoch): WalkSessionSnapshot = WalkSessionSnapshot(
    epoch = epoch,
    state = WalkSessionState.PAUSED,
    mode = WalkSessionMode.FULL,
    recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED,
    isForeground = true,
    readiness = null,
)

private fun endedWalkSnapshot(epoch: WalkRuntimeEpoch): WalkSessionSnapshot = WalkSessionSnapshot(
    epoch = epoch,
    state = WalkSessionState.ENDED,
    mode = WalkSessionMode.UNAVAILABLE,
    recoveryStage = null,
    isForeground = true,
    readiness = null,
)
