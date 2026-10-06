package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 07.txt #40: only the current walk lease may replace another walk's leftover snapshot. */
class MainActivityRouteSnapshotEndedWalkStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun firstRouteSaveReplacesOnlyAfterTheCurrentWalkLeaseIsRechecked() {
        val request = functionBlock("private fun requestRoute(")
        val save = request.indexOf("routeSnapshotStore.saveFirstRoute(")
        val call = request.substring(save, request.indexOf(")", save) + 1)
        val uiCommit = request.lastIndexOf("runOnUiThreadForCommand(speechOwner) {", save)
        val lease = request.indexOf(
            "routeResponseInstallLeaseCurrent(requestId, ::isCurrentRouteRequest)", uiCommit,
        )

        assertTrue(call.contains("scope = scope"))
        assertTrue(request.contains("RouteSnapshotScope(owner, expectedWalkEpoch.walkSessionId)"))
        assertTrue(request.contains("if (routeSnapshotWalkScope != scope) return@runCatching null"))
        assertTrue(uiCommit >= 0 && lease in uiCommit until save)
        assertTrue(request.indexOf("isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)") in 0 until save)
    }

    private fun functionBlock(marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "missing function marker: $marker" }
        val openingBrace = source.indexOf('{', markerIndex + marker.length)
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
        }
        error("missing closing brace: $marker")
    }
}
