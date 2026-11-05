package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.inference.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import kr.co.hanium.dreamup.walksafe.inference.unknown.tuning.UnknownRuntimeTuning
import kr.co.hanium.dreamup.walksafe.runtime.vision.remote.VisionPurpose
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal data class JointReferenceMask(val anchor: Int, val width: Int, val height: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int, val bits: ByteArray)
internal data class JointReferenceFrame(val fixture: RuntimeCalibrationFixture, val argbDigest: String,
    val primaryInputDigest: String, val primaryRaw: FloatArray, val primaryDetections: List<DetectionCandidate>,
    val auxiliaryRaw: Array<FloatArray>, val masks: List<JointReferenceMask>)

/** One CPU1 reference generation and one final consumer. Owned arrays never leave this object. */
class JointRuntimeReferenceSet private constructor(val bindingHash: String,
    private val model: ModelRuntimeConfig, private var frames: Map<String, JointReferenceFrame>?,
    private val release: () -> Unit) : Closeable {
    private val lock = Any()
    private val claimed = AtomicBoolean()
    private var readers = 0
    private var closing = false
    internal class Verifier(private val owner: JointRuntimeReferenceSet, private val diagnostic: (String) -> Unit) : Closeable {
        private fun report(fixture: JointRuntimeSampleFixture, role: String, predicate: String, count: Int = -1, maxAbs: Double = -1.0) {
            val id = fixture.definition.id.takeIf { it in JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS } ?: "NA"
            JointRuntimeFinalDiagnostic.emit(diagnostic, "event=JOINT_STRICT role=$role fixture=$id predicate=$predicate mismatch_count=${if(count < 0) "NA" else count.coerceIn(0, 10000000).toString()} max_abs=${if(maxAbs < 0.0) "NA" else if(maxAbs.isFinite()) maxAbs.coerceIn(0.0, 1e9).toString() else "NA"}")
        }
        fun primary(fixture: JointRuntimeSampleFixture, sample: JointPairPrimarySample): Boolean = owner.withFrame(fixture.definition.id) { reference ->
            if (reference.fixture != fixture.definition) { report(fixture,"PRIMARY","FIXTURE_IDENTITY"); false }
            else if (reference.argbDigest != fixture.argbDigest) { report(fixture,"PRIMARY","ARGB_IDENTITY"); false }
            else if (reference.primaryInputDigest != sample.inputDigest) { report(fixture,"PRIMARY","MODEL_INPUT_IDENTITY"); false }
            else {
                val output = RuntimeCalibrationOutputGate.compare(reference.primaryRaw, sample.output.rawOutput,
                    reference.primaryDetections, sample.output.result.detections, owner.model,
                    diagnostic = { report(fixture,"PRIMARY",it) })
                if (!output) {
                    val actual = sample.output.rawOutput
                    var different = 0; var nonfinite = 0; var maximum = 0.0
                    if(actual != null) for(i in 0 until minOf(reference.primaryRaw.size, actual.size)) {
                        val a = reference.primaryRaw[i]; val v = actual[i]
                        if(!a.isFinite() || !v.isFinite()) nonfinite++
                        else { if(a != v) different++; maximum = maxOf(maximum,kotlin.math.abs(a.toDouble()-v)) }
                    }
                    val id = fixture.definition.id.takeIf { it in JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS } ?: "NA"
                    JointRuntimeFinalDiagnostic.emit(diagnostic,"event=JOINT_RAW_AGGREGATE role=PRIMARY fixture=$id different_values=$different nonfinite_values=$nonfinite max_abs_by_index=${maximum.coerceAtMost(1e9)}")
                }
                val rolesMatch = roles(reference.fixture, reference.primaryDetections, sample.output.result.detections,
                    reference.primaryRaw, sample.output.rawOutput, owner.model)
                if (!rolesMatch) {
                    val roleGate = RuntimeCalibrationOutputGate.matchesFixtureRoles(reference.primaryDetections, sample.output.result.detections,
                        RuntimeCalibrationFixtureRole.POSITIVE in reference.fixture.roles, RuntimeCalibrationFixtureRole.EMPTY in reference.fixture.roles,
                        reference.fixture.groundTruth.filter { !it.isCrowd }.map { it.className to it.bboxNorm })
                    if (!roleGate) report(fixture,"PRIMARY","FIXTURE_GROUND_TRUTH_ROLE_MATCHING")
                    if(RuntimeCalibrationFixtureRole.NEAR_THRESHOLD in reference.fixture.roles) {
                        if(!RuntimeCalibrationOutputGate.hasNearThresholdCandidate(reference.primaryRaw,owner.model)) report(fixture,"PRIMARY","NEAR_THRESHOLD_REFERENCE")
                        if(!RuntimeCalibrationOutputGate.hasNearThresholdCandidate(sample.output.rawOutput,owner.model)) report(fixture,"PRIMARY","NEAR_THRESHOLD_ACTUAL")
                    }
                }
                output && rolesMatch
            }
        }
        fun auxiliary(fixture: JointRuntimeSampleFixture, sample: FastSamResult<*>): Pair<Boolean, Boolean> = owner.withFrame(fixture.definition.id) { reference ->
            if (reference.fixture != fixture.definition || reference.argbDigest != fixture.argbDigest) {
                report(fixture,"AUXILIARY","FIXTURE_OR_ARGB_IDENTITY"); false to false
            } else {
                val raw = sample.rawOutputs ?: run { report(fixture,"AUXILIARY","RAW_MISSING"); return@withFrame false to false }
                val rawMatch = equivalentRaw(reference.auxiliaryRaw, raw)
                if (!rawMatch) {
                    val buffers = arrayOf(raw.detections(),raw.prototypes())
                    var count = 0; var finite = 0; var size = reference.auxiliaryRaw.size != 2; var maxAbs = 0.0
                    for(i in 0 until minOf(2,reference.auxiliaryRaw.size)) {
                        val expected = reference.auxiliaryRaw[i]; val values = buffers[i]
                        if(expected.isEmpty() || expected.size != values.remaining()) size = true
                        for(j in 0 until minOf(expected.size,values.remaining())) {
                            val a = expected[j]; val b = values.get()
                            if(!a.isFinite() || !b.isFinite()) finite++
                            else { maxAbs = maxOf(maxAbs,kotlin.math.abs(a.toDouble()-b)); if(kotlin.math.abs(a-b) > .0001f + .001f*kotlin.math.abs(a)) count++ }
                        }
                    }
                    report(fixture,"AUXILIARY",if(size) "RAW_COUNT" else if(finite>0) "RAW_NONFINITE" else "RAW_TOLERANCE",if(finite>0) finite else count,maxAbs)
                }
                val maskMatch = JointMaskNumericalParity.compare(reference.masks, sample.masks, reference.auxiliaryRaw, raw, rawMatch, diagnostic)
                if(!maskMatch) {
                    val actual = sample.masks.sortedBy { it.anchorIndex() }; val expected = reference.masks
                    if(expected.size != actual.size) report(fixture,"AUXILIARY","MASK_COUNT",kotlin.math.abs(expected.size-actual.size))
                    if(actual.map { it.anchorIndex() }.distinct().size != actual.size) report(fixture,"AUXILIARY","MASK_DUPLICATE_ANCHOR",actual.size-actual.map { it.anchorIndex() }.distinct().size)
                    var anchors=0; var roi=0; var bits=0
                    expected.zip(actual).forEach { (a,b) ->
                        if(a.anchor != b.anchorIndex()) anchors++
                        if(a.width != b.imageWidth() || a.height != b.imageHeight() || a.left != b.maskLeft() || a.top != b.maskTop() || a.right != b.maskRightExclusive() || a.bottom != b.maskBottomExclusive()) roi++
                        val packed=b.copyPackedBits()
                        if(a.bits.size != packed.size) bits += kotlin.math.abs(a.bits.size-packed.size)
                        for(i in 0 until minOf(a.bits.size,packed.size)) bits += Integer.bitCount((a.bits[i].toInt() xor packed[i].toInt()) and 255)
                    }
                    if(anchors>0) report(fixture,"AUXILIARY","MASK_ANCHOR",anchors)
                    if(roi>0) report(fixture,"AUXILIARY","MASK_ROI_OR_DIMENSION",roi)
                    if(bits>0) report(fixture,"AUXILIARY","MASK_BIT_PATTERN",bits)
                }
                rawMatch to maskMatch
            }
        }
        override fun close() = owner.close()
    }
    internal fun claimForFinal(expectedBinding: String, diagnostic: (String) -> Unit = {}): Verifier {
        synchronized(lock) { check(!closing && frames != null && bindingHash == expectedBinding) { "reference_binding_or_lifetime" } }
        check(claimed.compareAndSet(false, true)) { "CPU1 references already have a final consumer" }
        return Verifier(this, diagnostic)
    }
    private fun <V> withFrame(id: String, block: (JointReferenceFrame) -> V): V {
        val frame = synchronized(lock) { check(!closing); requireNotNull(frames?.get(id)).also { readers++ } }
        try { return block(frame) } finally {
            val finish = synchronized(lock) { readers--; if (closing && readers == 0 && frames != null) { frames = null; true } else false }
            if (finish) release()
        }
    }
    override fun close() {
        val finish = synchronized(lock) { closing = true; if (readers == 0 && frames != null) { frames = null; true } else false }
        if (finish) release()
    }
    companion object {
        /** No performance limit is applied to reference frames; their native operation is bounded separately. */
        fun <T> capture(host: JointRuntimeSampleHost<T>, budget: JointRuntimePhaseBudget,
            isCurrent: () -> Boolean): JointRuntimeReferenceSet {
            var references: JointRuntimeReferenceSet? = null
            var transferred = false
            val owned = linkedMapOf<String, JointReferenceFrame>()
            val retained = mutableListOf<JointRuntimeMemoryReservation>()
            try {
                require(budget.phase == JointRuntimeSearchPhase.REFERENCE)
                val cpu1 = RuntimeCandidate(RuntimeBackend.CPU, 1)
                host.open(JointRuntimeCandidate(cpu1, cpu1, 400, 600), budget, isCurrent)
                val cases = host.entries()
                val model = requireNotNull(host.config.unifiedWalksafe)
                for (fixture in cases) {
                    val primary = host.consumePrimary(fixture, VisionPurpose.REFERENCE, true) { sample ->
                        val raw = requireNotNull(sample.output.rawOutput); val detections = sample.output.result.detections
                        if (!RuntimeCalibrationOutputGate.compare(raw, raw, detections, detections, model) ||
                            !roles(fixture.definition, detections, detections, raw, raw, model))
                            accuracy("reference_primary_roles_or_output_${fixture.definition.id}")
                        retainAllocation(host, raw.size.toLong() * 4 + detections.size * 256L + 4_096L, retained) {
                            PrimaryReference(raw.copyOf(), detections.toList(), sample.inputDigest,
                                requireNotNull(sample.output.result.timing.modelRuntime))
                        }
                    }
                    val auxiliary = host.consumeAuxiliary(fixture, VisionPurpose.REFERENCE, true) { sample ->
                        host.snapshot(primary.runtime, sample.runtime)
                        val outputs = requireNotNull(sample.rawOutputs)
                        val bytes = (FastSamModelContract.DETECTION_FLOATS.toLong() + FastSamModelContract.PROTOTYPE_FLOATS) * 4 +
                            packedMaskBytes(sample.masks) + sample.masks.size * 128L + 4_096L
                        retainAllocation(host, bytes, retained) {
                            val raw = arrayOf(copy(outputs.detections()), copy(outputs.prototypes()))
                            if (!UnknownRuntimeTuning.equivalentRaw(raw, raw)) accuracy("reference_auxiliary_raw_${fixture.definition.id}")
                            AuxiliaryReference(raw, masks(sample.masks))
                        }
                    }
                    owned[fixture.definition.id] = JointReferenceFrame(fixture.definition, fixture.argbDigest, primary.inputDigest,
                        primary.raw, primary.detections, auxiliary.raw, auxiliary.masks)
                }
                check(owned.keys == JointRuntimeChoicePolicy.REQUIRED_FIXTURE_IDS)
                host.admit()
                val ledger = host.ledger; val allocations = retained.toList()
                references = JointRuntimeReferenceSet(host.bindingHash, model, owned.toMap()) { allocations.forEach(ledger::releaseAfterDrain) }
                transferred = true
            } finally {
                try { if (!transferred) { owned.clear(); retained.forEach(host.ledger::releaseAfterDrain) } }
                finally { host.finish(false) }
            }
            try { host.awaitRelease(isCurrent); return requireNotNull(references) }
            catch (error: Throwable) { references?.close(); throw error }
        }
        private data class PrimaryReference(val raw: FloatArray, val detections: List<DetectionCandidate>,
            val inputDigest: String, val runtime: AndroidDetectorRuntime)
        private data class AuxiliaryReference(val raw: Array<FloatArray>, val masks: List<JointReferenceMask>)
        private fun <T, V> retainAllocation(host: JointRuntimeSampleHost<T>, bytes: Long,
            retained: MutableList<JointRuntimeMemoryReservation>, allocate: () -> V): V {
            val reservation = admitted(host.ledger.reservePersistent(JointRuntimeMemoryBytes(managedBytes = bytes), host.freshManagedHeap()))
            check(host.ledger.allocationStarted(reservation))
            try {
                val value = allocate()
                // Each completed piece is already reflected in the next fresh heap observation.
                // Leaving it pending until all four fixtures finish would subtract those bytes twice.
                check(host.ledger.allocationCompleted(reservation))
                host.admit(); retained += reservation
                return value
            } catch (error: Throwable) { host.ledger.releaseAfterDrain(reservation); throw error }
        }
        private fun packedMaskBytes(masks: List<InstanceMask>): Long {
            check(masks.size <= 100)
            return masks.sumOf { mask ->
                val width = (mask.maskRightExclusive() - mask.maskLeft()).toLong()
                val height = (mask.maskBottomExclusive() - mask.maskTop()).toLong()
                check(width > 0 && height > 0 && width <= 1024 && height <= 1024)
                (Math.multiplyExact(width, height) + 7) / 8
            }
        }
        internal fun fromOwnedForTest(bindingHash: String, model: ModelRuntimeConfig, frames: Map<String, JointReferenceFrame>,
            release: () -> Unit = {}): JointRuntimeReferenceSet = JointRuntimeReferenceSet(bindingHash, model, frames, release)

        internal fun roles(fixture: RuntimeCalibrationFixture, reference: List<DetectionCandidate>, actual: List<DetectionCandidate>,
            referenceRaw: FloatArray?, actualRaw: FloatArray?, model: ModelRuntimeConfig): Boolean =
            RuntimeCalibrationOutputGate.matchesFixtureRoles(reference, actual,
                RuntimeCalibrationFixtureRole.POSITIVE in fixture.roles, RuntimeCalibrationFixtureRole.EMPTY in fixture.roles,
                fixture.groundTruth.filter { !it.isCrowd }.map { it.className to it.bboxNorm }) &&
                (RuntimeCalibrationFixtureRole.NEAR_THRESHOLD !in fixture.roles ||
                    RuntimeCalibrationOutputGate.hasNearThresholdCandidate(referenceRaw, model) && RuntimeCalibrationOutputGate.hasNearThresholdCandidate(actualRaw, model))
        private fun copy(values: java.nio.FloatBuffer) = FloatArray(values.remaining()).also { values.get(it) }
        /** Same tolerance as UnknownRuntimeTuning.equivalentRaw, streamed without another 6.5MB copy. */
        internal fun equivalentRaw(reference: Array<FloatArray>, actual: FastSamResult.RawOutputs): Boolean {
            val buffers = arrayOf(actual.detections(), actual.prototypes())
            return reference.size == 2 && reference.indices.all { output ->
                val values = buffers[output]; val expected = reference[output]
                expected.isNotEmpty() && values.remaining() == expected.size && expected.all { a ->
                    val b = values.get()
                    a.isFinite() && b.isFinite() && kotlin.math.abs(a - b) <= 0.0001f + .001f * kotlin.math.abs(a)
                }
            }
        }
        private fun masks(values: List<InstanceMask>): List<JointReferenceMask> {
            check(values.size <= 100 && values.map { it.anchorIndex() }.distinct().size == values.size)
            return values.sortedBy { it.anchorIndex() }.map { mask ->
                check(mask.imageWidth() in 1..1024 && mask.imageHeight() in 1..1024 && mask.maskLeft() >= 0 && mask.maskTop() >= 0 &&
                    mask.maskRightExclusive() in mask.maskLeft() + 1..mask.imageWidth() && mask.maskBottomExclusive() in mask.maskTop() + 1..mask.imageHeight())
                JointReferenceMask(mask.anchorIndex(), mask.imageWidth(), mask.imageHeight(), mask.maskLeft(), mask.maskTop(),
                    mask.maskRightExclusive(), mask.maskBottomExclusive(), mask.copyPackedBits())
            }
        }
        internal fun sameMasks(reference: List<JointReferenceMask>, actual: List<InstanceMask>): Boolean {
            if (reference.size != actual.size || actual.map { it.anchorIndex() }.distinct().size != actual.size) return false
            return reference.zip(actual.sortedBy { it.anchorIndex() }).all { (a, b) ->
                a.anchor == b.anchorIndex() && a.width == b.imageWidth() && a.height == b.imageHeight() &&
                    a.left == b.maskLeft() && a.top == b.maskTop() && a.right == b.maskRightExclusive() && a.bottom == b.maskBottomExclusive() &&
                    a.bits.contentEquals(b.copyPackedBits())
            }
        }
    }
}

internal fun accuracy(reason: String): Nothing = throw JointRuntimeSampleFailure(
    JointRuntimeSearchFailure(JointRuntimeSearchFailureKind.ACCURACY_UNCONFIRMED, reason))
