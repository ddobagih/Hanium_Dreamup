package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.inference.composite.JointRuntimeChoiceCodec
import kr.co.hanium.dreamup.walksafe.inference.composite.ValidatedJointRuntimeChoice
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal data class DeviceCheckAttemptRecord(val id: String, val binding: DeviceCheckAuthorityBinding,
    val started: Long, val deadline: Long, val phase: String)
internal data class DeviceCheckBundleRecord(val attemptId: String, val binding: DeviceCheckAuthorityBinding,
    val revision: Long, val acceptedAt: Long, val result: PersistedPostLoginDeviceCheckResult,
    val choice: ValidatedJointRuntimeChoice, val pairLoadIds: List<String>)
internal data class DeviceCheckLoadRecord(val id: String, val spec: DeviceCheckNativeSpec,
    val modelId: String? = null, val actualHash: String? = null, val closed: Boolean = false,
    val verifiedAt: Long? = null)
internal data class DeviceCheckCohortRecord(val lease: DeviceCheckNativeLease,
    val sourceAttemptId: String, val sourceMeasurementId: String?, val binding: DeviceCheckAuthorityBinding,
    val processNonce: String, val process: DeviceCheckNativeProcess, val loads: List<DeviceCheckLoadRecord>,
    val release: String? = null)
internal data class DeviceCheckAuthorityState(val revision: Long = 0, val latest: DeviceCheckAttemptRecord? = null,
    val bundle: DeviceCheckBundleRecord? = null, val cohorts: List<DeviceCheckCohortRecord> = emptyList(),
    val commands: Map<String, String> = emptyMap())

internal object DeviceCheckAuthorityValidation {
    private val hash = Regex("[a-f0-9]{64}")
    private val key = Regex("[A-Za-z0-9._:-]{1,200}")
    fun hash(value: String) = hash.matches(value)
    fun key(value: String) = key.matches(value)
    fun command(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    fun binding(value: DeviceCheckAuthorityBinding) = listOf(value.actorBindingHash, value.resultBindingHash,
        value.jointBindingHash, value.runtimeBuildId).all(::hash)
    fun result(value: PersistedPostLoginDeviceCheckResult): Boolean {
        val metricDisabled = PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE in value.disabledFeatures
        val obstacleDisabled = PostLoginDeviceCheckFeature.OBSTACLE_DETECTION in value.disabledFeatures
        if (value.cameraDependentChecksDeferred && obstacleDisabled || obstacleDisabled && !metricDisabled) return false
        if (value.metricDepthState in setOf(PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED,
                PostLoginMetricDepthState.UNKNOWN, PostLoginMetricDepthState.TIMED_OUT) && !metricDisabled) return false
        // PENDING remains compatible with legacy/deferred policy and existing voice recovery.
        // SUPPORTED can still be metric-limited when the camera/object capability is unavailable.
        return when (value.state) {
            PostLoginDeviceCheckState.FULL -> value.disabledFeatures.isEmpty()
            PostLoginDeviceCheckState.LIMITED -> value.disabledFeatures.isNotEmpty()
            else -> false
        }
    }
    fun spec(value: DeviceCheckNativeSpec) = key(value.role) && key(value.modelKey) && hash(value.modelSha256) &&
        (if (value.role == "METRIC_SUPPORT") value.delegate == "support" && value.numThreads == 0
        else value.delegate in setOf("cpu", "gpu") && value.numThreads in 1..4) && hash(value.optionsHash)
    fun process(value: DeviceCheckNativeProcess) = value.pid > 0 && value.uid >= 0 && key(value.processName) &&
        value.startTicks > 0 && key(value.bootIdentity)
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Canonical, bounded and versioned. Unknown/duplicate/coerced fields never become pass evidence. */
internal object DeviceCheckAuthorityCodec {
    const val MAX_BYTES = 524_288
    private const val VERSION = 1
    fun resultText(value: PersistedPostLoginDeviceCheckResult): String = result(value).toString()
    fun encode(state: DeviceCheckAuthorityState): String? = runCatching {
        val text = JSONObject().put("schema", VERSION).put("revision", state.revision)
            .put("latest", state.latest?.let { a -> JSONObject().put("id", a.id).put("binding", binding(a.binding))
                .put("started", a.started).put("deadline", a.deadline).put("phase", a.phase) } ?: JSONObject.NULL)
            .put("bundle", state.bundle?.let { b -> JSONObject().put("attempt", b.attemptId).put("binding", binding(b.binding))
                .put("revision", b.revision).put("acceptedAt", b.acceptedAt).put("result", result(b.result))
                .put("choice", requireNotNull(JointRuntimeChoiceCodec.encode(b.choice)))
                .put("pairLoads", JSONArray(b.pairLoadIds)) } ?: JSONObject.NULL)
            .put("cohorts", JSONArray().also { array -> state.cohorts.forEach { c ->
                array.put(JSONObject().put("cohort", c.lease.cohortId).put("owner", c.lease.ownerId).put("epoch", c.lease.ownerEpoch)
                    .put("attempt", c.sourceAttemptId).put("measurement", c.sourceMeasurementId ?: JSONObject.NULL)
                    .put("binding", binding(c.binding)).put("parentNonce", c.processNonce)
                    .put("pid", c.process.pid).put("uid", c.process.uid).put("process", c.process.processName)
                    .put("ticks", c.process.startTicks).put("boot", c.process.bootIdentity)
                    .put("release", c.release ?: JSONObject.NULL).put("loads", JSONArray().also { loads -> c.loads.forEach { l ->
                        loads.put(JSONObject().put("id", l.id).put("role", l.spec.role).put("model", l.spec.modelKey)
                            .put("sha", l.spec.modelSha256).put("delegate", l.spec.delegate).put("threads", l.spec.numThreads)
                            .put("options", l.spec.optionsHash).put("modelId", l.modelId ?: JSONObject.NULL)
                            .put("actual", l.actualHash ?: JSONObject.NULL).put("closed", l.closed)
                            .put("verifiedAt", l.verifiedAt ?: JSONObject.NULL))
                    } }))
            } })
            .put("commands", JSONArray().also { array -> state.commands.forEach { (id, hash) ->
                array.put(JSONObject().put("id", id).put("hash", hash))
            } }).toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        text
    }.getOrNull()

    fun decode(text: String?): DeviceCheckAuthorityState? = runCatching {
        require(text != null && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val o = JSONObject(text)
        require(o.getInt("schema") == VERSION)
        val latest = if (o.isNull("latest")) null else o.getJSONObject("latest").let { a ->
            DeviceCheckAttemptRecord(a.getString("id"), readBinding(a.getJSONObject("binding")), a.getLong("started"),
                a.getLong("deadline"), a.getString("phase")).also {
                require(DeviceCheckAuthorityValidation.command(it.id) && it.started >= 0 && it.deadline > it.started &&
                    it.phase in setOf("CHECKING", "COMMITTED", "CANCELLED", "INTERRUPTED"))
            }
        }
        val bundle = if (o.isNull("bundle")) null else o.getJSONObject("bundle").let { b ->
            DeviceCheckBundleRecord(b.getString("attempt"), readBinding(b.getJSONObject("binding")), b.getLong("revision"),
                b.getLong("acceptedAt"), readResult(b.getJSONObject("result")),
                requireNotNull(JointRuntimeChoiceCodec.decode(b.getString("choice"))), strings(b.getJSONArray("pairLoads"))).also {
                require(DeviceCheckAuthorityValidation.command(it.attemptId) && it.revision > 0 && it.acceptedAt >= 0 &&
                    it.pairLoadIds.size == 2 && it.pairLoadIds.distinct().size == 2 && it.pairLoadIds.all(DeviceCheckAuthorityValidation::command) &&
                    it.choice.bindingHash == it.binding.jointBindingHash)
            }
        }
        val cohorts = o.getJSONArray("cohorts").let { a ->
            require(a.length() <= 64)
            (0 until a.length()).map { index -> a.getJSONObject(index).let { c ->
                val loads = c.getJSONArray("loads").let { l ->
                    require(l.length() in 1..32)
                    (0 until l.length()).map { j -> l.getJSONObject(j).let { v ->
                        DeviceCheckLoadRecord(v.getString("id"), DeviceCheckNativeSpec(v.getString("role"), v.getString("model"),
                            v.getString("sha"), v.getString("delegate"), v.getInt("threads"), v.getString("options")),
                            nullable(v, "modelId"), nullable(v, "actual"), v.getBoolean("closed"),
                            if (v.isNull("verifiedAt")) null else v.getLong("verifiedAt")).also {
                            require(DeviceCheckAuthorityValidation.command(it.id) && DeviceCheckAuthorityValidation.spec(it.spec))
                            require((it.modelId == null) == (it.actualHash == null))
                            require(it.modelId == null || DeviceCheckAuthorityValidation.key(it.modelId))
                            require(it.actualHash == null || DeviceCheckAuthorityValidation.hash(it.actualHash))
                            require(it.verifiedAt == null || it.verifiedAt >= 0 && it.modelId != null)
                        }
                    } }.also { require(it.map { row -> row.id }.distinct().size == it.size) }
                }
                DeviceCheckCohortRecord(DeviceCheckNativeLease(c.getString("cohort"), c.getString("owner"), c.getLong("epoch")),
                    c.getString("attempt"), nullable(c, "measurement"), readBinding(c.getJSONObject("binding")),
                    c.getString("parentNonce"), DeviceCheckNativeProcess(c.getInt("pid"), c.getInt("uid"), c.getString("process"),
                        c.getLong("ticks"), c.getString("boot")), loads, nullable(c, "release")).also {
                    require(DeviceCheckAuthorityValidation.key(it.lease.cohortId) && DeviceCheckAuthorityValidation.key(it.lease.ownerId) &&
                        it.lease.ownerEpoch > 0 && DeviceCheckAuthorityValidation.command(it.sourceAttemptId) &&
                        DeviceCheckAuthorityValidation.command(it.processNonce) && DeviceCheckAuthorityValidation.process(it.process))
                    require(it.sourceMeasurementId == null || DeviceCheckAuthorityValidation.command(it.sourceMeasurementId))
                    require(it.release == null || it.release in setOf("CHILD_DEATH", "NO_NATIVE_START", "RECOVERED_PROCESS_ABSENT"))
                }
            } }.also { require(it.map { c -> c.lease.cohortId }.distinct().size == it.size) }
        }
        val commands = linkedMapOf<String, String>()
        o.getJSONArray("commands").let { a ->
            require(a.length() <= 256)
            for (i in 0 until a.length()) a.getJSONObject(i).let { c ->
                val id = c.getString("id"); val hash = c.getString("hash")
                require(DeviceCheckAuthorityValidation.command(id) && DeviceCheckAuthorityValidation.hash(hash) && commands.put(id, hash) == null)
            }
        }
        DeviceCheckAuthorityState(o.getLong("revision"), latest, bundle, cohorts, commands).also {
            require(it.revision >= 0 && (it.bundle?.revision ?: 0) <= it.revision && encode(it) == text)
            if (latest?.phase == "COMMITTED") {
                require(bundle != null && latest.id == bundle.attemptId && latest.binding == bundle.binding &&
                    bundle.acceptedAt >= latest.started && bundle.acceptedAt < latest.deadline)
                val located = bundle.pairLoadIds.map { id ->
                    val matching = cohorts.flatMap { c -> c.loads.filter { l -> l.id == id }.map { l -> c to l } }
                    require(matching.size == 1); matching.single()
                }
                require(located[0].first.lease == located[1].first.lease && located.all { (c, l) ->
                    c.sourceAttemptId == bundle.attemptId && c.sourceMeasurementId == bundle.choice.measurementId &&
                        l.modelId != null && l.actualHash != null && l.verifiedAt == bundle.acceptedAt
                })
                val primary = located.single { row -> row.second.spec.role == "PRIMARY" }.second.spec
                val auxiliary = located.single { row -> row.second.spec.role == "FASTSAM" }.second.spec
                require(primary.delegate == bundle.choice.candidate.primary.backend.name.lowercase() &&
                    primary.numThreads == bundle.choice.candidate.primary.numThreads &&
                    auxiliary.delegate == bundle.choice.candidate.auxiliary.backend.name.lowercase() &&
                    auxiliary.numThreads == bundle.choice.candidate.auxiliary.numThreads)
            }
        }
    }.getOrNull()

    private fun binding(b: DeviceCheckAuthorityBinding) = JSONObject().put("actor", b.actorBindingHash)
        .put("result", b.resultBindingHash).put("joint", b.jointBindingHash).put("build", b.runtimeBuildId)
    private fun readBinding(o: JSONObject) = DeviceCheckAuthorityBinding(o.getString("actor"), o.getString("result"),
        o.getString("joint"), o.getString("build")).also { require(DeviceCheckAuthorityValidation.binding(it)) }
    private fun result(r: PersistedPostLoginDeviceCheckResult) = JSONObject().put("state", r.state.name)
        .put("disabled", JSONArray(r.disabledFeatures.map { it.name }.sorted())).put("deferred", r.cameraDependentChecksDeferred)
        .put("metricPolicy", r.metricDepthProbePolicyCurrent).put("metric", r.metricDepthState.name)
    private fun readResult(o: JSONObject) = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.valueOf(o.getString("state")),
        strings(o.getJSONArray("disabled")).map { PostLoginDeviceCheckFeature.valueOf(it) }.toSet(), o.getBoolean("deferred"),
        o.getBoolean("metricPolicy"), PostLoginMetricDepthState.valueOf(o.getString("metric"))).also { require(DeviceCheckAuthorityValidation.result(it)) }
    private fun nullable(o: JSONObject, key: String) = if (o.isNull(key)) null else o.getString(key)
    private fun strings(a: JSONArray): List<String> { require(a.length() <= 32); return (0 until a.length()).map(a::getString) }
}
