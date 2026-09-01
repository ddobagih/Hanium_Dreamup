package kr.co.hanium.dreamup.walksafe.device

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceCheckResultStoreTest {
    @Test
    fun fullRoundTripCommitsOnlyTheCanonicalResultValues() {
        val preferences = FakeSharedPreferences()
        val store = AndroidDeviceCheckResultStore(preferences)

        assertTrue(store.save(snapshot(PostLoginDeviceCheckState.FULL)))

        assertEquals(1, preferences.commitCount)
        assertEquals(0, preferences.applyCount)
        assertEquals(
            mapOf(
                POLICY_VERSION_KEY to POST_LOGIN_DEVICE_CHECK_RESULT_POLICY_VERSION,
                TIER_KEY to "FULL",
                DISABLED_FEATURES_KEY to "",
                CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY to false,
            ),
            preferences.all,
        )
        assertEquals(
            PersistedPostLoginDeviceCheckResult(
                state = PostLoginDeviceCheckState.FULL,
                disabledFeatures = emptySet(),
                cameraDependentChecksDeferred = false,
            ),
            store.restore(),
        )
    }

    @Test
    fun limitedRoundTripStoresAStableSortedFeatureCsv() {
        val preferences = FakeSharedPreferences()
        val store = AndroidDeviceCheckResultStore(preferences)
        val disabledFeatures = linkedSetOf(
            PostLoginDeviceCheckFeature.VOICE_GUIDANCE,
            PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK,
            PostLoginDeviceCheckFeature.HANDS_FREE_VOICE,
        )

        assertTrue(store.save(snapshot(PostLoginDeviceCheckState.LIMITED, disabledFeatures)))

        assertEquals(
            "HANDS_FREE_VOICE,HAPTIC_FEEDBACK,VOICE_GUIDANCE",
            preferences.getString(DISABLED_FEATURES_KEY, null),
        )
        assertEquals(
            PersistedPostLoginDeviceCheckResult(
                state = PostLoginDeviceCheckState.LIMITED,
                disabledFeatures = disabledFeatures,
                cameraDependentChecksDeferred = false,
            ),
            store.restore(),
        )
    }

    @Test
    fun cameraPermissionDeferredResultRoundTripsWithoutClaimingCameraFeatureSupport() {
        val preferences = FakeSharedPreferences()
        val store = AndroidDeviceCheckResultStore(preferences)

        assertTrue(
            store.save(
                snapshot(PostLoginDeviceCheckState.FULL),
                cameraDependentChecksDeferred = true,
            ),
        )

        assertEquals(true, preferences.getBoolean(CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY, false))
        assertEquals(
            PersistedPostLoginDeviceCheckResult(
                state = PostLoginDeviceCheckState.FULL,
                disabledFeatures = emptySet(),
                cameraDependentChecksDeferred = true,
            ),
            store.restore(),
        )
    }

    @Test
    fun saveRejectsIncompleteAndInternallyInconsistentSnapshotsWithoutEditing() {
        val rejected = listOf(
            snapshot(PostLoginDeviceCheckState.NOT_RUN),
            snapshot(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS),
            snapshot(PostLoginDeviceCheckState.RUNNING),
            snapshot(
                PostLoginDeviceCheckState.FAIL,
                failure = PostLoginDeviceCheckFailure.CHECK_TIMEOUT,
            ),
            snapshot(
                PostLoginDeviceCheckState.FULL,
                setOf(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE),
            ),
            snapshot(PostLoginDeviceCheckState.LIMITED),
        )

        rejected.forEach { snapshot ->
            val preferences = FakeSharedPreferences()
            assertFalse(AndroidDeviceCheckResultStore(preferences).save(snapshot))
            assertTrue(preferences.all.isEmpty())
            assertEquals(0, preferences.commitCount)
        }
    }

    @Test
    fun saveReturnsFalseWhenTheSynchronousCommitFails() {
        val preferences = FakeSharedPreferences(commitResult = false)

        assertFalse(
            AndroidDeviceCheckResultStore(preferences)
                .save(snapshot(PostLoginDeviceCheckState.FULL)),
        )

        assertEquals(1, preferences.commitCount)
        assertEquals(0, preferences.applyCount)
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun failedCommitCannotBeRestoredFromMemoryAndSuccessfulRetryClearsTheFence() {
        val preferences = FakeSharedPreferences(
            commitResult = false,
            mutateMemoryOnCommitFailure = true,
        )
        val store = AndroidDeviceCheckResultStore(preferences)

        assertFalse(store.save(snapshot(PostLoginDeviceCheckState.FULL)))
        assertEquals("FULL", preferences.getString(TIER_KEY, null))
        assertNull(store.restore())

        preferences.commitResult = true
        val disabledFeatures = setOf(PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK)
        assertTrue(store.save(snapshot(PostLoginDeviceCheckState.LIMITED, disabledFeatures)))
        assertEquals(
            PersistedPostLoginDeviceCheckResult(
                state = PostLoginDeviceCheckState.LIMITED,
                disabledFeatures = disabledFeatures,
                cameraDependentChecksDeferred = false,
            ),
            store.restore(),
        )
    }

    @Test
    fun restoreRejectsPolicyTierAndPartialRecordCorruption() {
        val invalidRecords = listOf(
            emptyMap(),
            validRecord() - POLICY_VERSION_KEY,
            validRecord() - TIER_KEY,
            validRecord() - DISABLED_FEATURES_KEY,
            validRecord() - CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY,
            validRecord(policyVersion = "1"),
            validRecord(tier = "FAIL"),
            validRecord(tier = "NOT_RUN"),
            validRecord(tier = "full"),
            validRecord(tier = "UNKNOWN"),
            validRecord() + (POLICY_VERSION_KEY to 1),
            validRecord() + (TIER_KEY to true),
            validRecord() + (DISABLED_FEATURES_KEY to 0L),
            validRecord() + (CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY to "false"),
        )

        invalidRecords.forEach { values ->
            val preferences = FakeSharedPreferences(initialValues = values)
            assertNull(AndroidDeviceCheckResultStore(preferences).restore())
        }
    }

    @Test
    fun restoreRejectsResultsSavedByThePermissionPersistencePolicy() {
        val oldLimitedResult = FakeSharedPreferences(
            initialValues = validRecord(
                policyVersion = "1",
                tier = "LIMITED",
                disabledFeaturesCsv =
                    "METRIC_DISTANCE_GUIDANCE,OBSTACLE_DETECTION",
            ),
        )

        assertNull(AndroidDeviceCheckResultStore(oldLimitedResult).restore())
    }

    @Test
    fun restoreRejectsUnknownOrNonCanonicalFeatureCsv() {
        val invalidCsvValues = listOf(
            "UNKNOWN_FEATURE",
            "VOICE_GUIDANCE,UNKNOWN_FEATURE",
            "VOICE_GUIDANCE,VOICE_GUIDANCE",
            "VOICE_GUIDANCE,HAPTIC_FEEDBACK",
            "HAPTIC_FEEDBACK,",
            ",HAPTIC_FEEDBACK",
            "HAPTIC_FEEDBACK,,VOICE_GUIDANCE",
            "HAPTIC_FEEDBACK, VOICE_GUIDANCE",
        )

        invalidCsvValues.forEach { csv ->
            val preferences = FakeSharedPreferences(
                initialValues = validRecord(tier = "LIMITED", disabledFeaturesCsv = csv),
            )
            assertNull(AndroidDeviceCheckResultStore(preferences).restore())
        }
    }

    @Test
    fun restoreRejectsTierAndDisabledFeatureInvariantViolations() {
        val fullWithDisabledFeature = FakeSharedPreferences(
            initialValues = validRecord(
                tier = "FULL",
                disabledFeaturesCsv = "LOCATION_GUIDANCE",
            ),
        )
        val limitedWithoutDisabledFeature = FakeSharedPreferences(
            initialValues = validRecord(tier = "LIMITED", disabledFeaturesCsv = ""),
        )

        assertNull(AndroidDeviceCheckResultStore(fullWithDisabledFeature).restore())
        assertNull(AndroidDeviceCheckResultStore(limitedWithoutDisabledFeature).restore())
    }

    @Test
    fun deferredCameraChecksCannotAlsoBeStoredAsPermanentCameraLimits() {
        val conflicting = FakeSharedPreferences(
            initialValues = validRecord(
                tier = "LIMITED",
                disabledFeaturesCsv =
                    "METRIC_DISTANCE_GUIDANCE,OBSTACLE_DETECTION",
                cameraDependentChecksDeferred = true,
            ),
        )

        assertNull(AndroidDeviceCheckResultStore(conflicting).restore())
        assertFalse(
            AndroidDeviceCheckResultStore(FakeSharedPreferences()).save(
                snapshot(
                    PostLoginDeviceCheckState.LIMITED,
                    setOf(
                        PostLoginDeviceCheckFeature.OBSTACLE_DETECTION,
                        PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE,
                    ),
                ),
                cameraDependentChecksDeferred = true,
            ),
        )

        val permanentlyDistanceLimited = setOf(
            PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE,
        )
        val allowed = AndroidDeviceCheckResultStore(FakeSharedPreferences())
        assertTrue(
            allowed.save(
                snapshot(
                    PostLoginDeviceCheckState.LIMITED,
                    permanentlyDistanceLimited,
                ),
                cameraDependentChecksDeferred = true,
            ),
        )
        assertEquals(true, allowed.restore()?.cameraDependentChecksDeferred)
        assertEquals(permanentlyDistanceLimited, allowed.restore()?.disabledFeatures)
    }

    private fun snapshot(
        state: PostLoginDeviceCheckState,
        disabledFeatures: Set<PostLoginDeviceCheckFeature> = emptySet(),
        failure: PostLoginDeviceCheckFailure? = null,
    ): PostLoginDeviceCheckSnapshot = PostLoginDeviceCheckSnapshot(
        state = state,
        actorId = "actor-must-not-be-persisted",
        sessionGeneration = 37L,
        attemptGeneration = 11L,
        failure = failure,
        disabledFeatures = disabledFeatures,
    )

    private fun validRecord(
        policyVersion: String = POST_LOGIN_DEVICE_CHECK_RESULT_POLICY_VERSION,
        tier: String = "FULL",
        disabledFeaturesCsv: String = "",
        cameraDependentChecksDeferred: Boolean = false,
    ): Map<String, Any> = mapOf(
        POLICY_VERSION_KEY to policyVersion,
        TIER_KEY to tier,
        DISABLED_FEATURES_KEY to disabledFeaturesCsv,
        CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY to cameraDependentChecksDeferred,
    )

    private class FakeSharedPreferences(
        initialValues: Map<String, Any> = emptyMap(),
        var commitResult: Boolean = true,
        private val mutateMemoryOnCommitFailure: Boolean = false,
    ) : SharedPreferences {
        private val values = initialValues.toMutableMap()
        var commitCount = 0
            private set
        var applyCount = 0
            private set

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue
        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply { updates[key] = values?.toSet() }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }
            override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

            override fun commit(): Boolean {
                commitCount += 1
                if (!commitResult && !mutateMemoryOnCommitFailure) return false
                if (clearRequested) values.clear()
                removals.forEach(values::remove)
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return commitResult
            }

            override fun apply() {
                applyCount += 1
                commit()
            }
        }
    }

    private companion object {
        const val POLICY_VERSION_KEY = "device_check_result_policy_v1"
        const val TIER_KEY = "device_check_result_tier_v1"
        const val DISABLED_FEATURES_KEY = "device_check_result_disabled_features_v1"
        const val CAMERA_DEPENDENT_CHECKS_DEFERRED_KEY =
            "device_check_result_camera_dependent_checks_deferred_v2"
    }
}
