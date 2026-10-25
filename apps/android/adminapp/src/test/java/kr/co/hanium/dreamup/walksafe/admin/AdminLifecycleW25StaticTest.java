package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * w25 source wiring guards (S18-18, S18-16). They pin the Activity/controller wiring that the JVM
 * behaviour tests cannot reach; they are not a substitute for device lifecycle tests.
 */
public final class AdminLifecycleW25StaticTest {
    private static final String SECURITY = "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/";
    private final String activity = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java"
    );
    private final String controller = read(SECURITY + "AdminSecurityController.java");

    @Test
    public void s18_18CloseLocksLocallyFirstThenCancelsInFlightRequestsWithoutWaiting() {
        String close = section(controller, "    @Override\n    public void close() {\n",
            "private static void bestEffortCancel(");
        int discard = close.indexOf("discardLocalSession(true);");
        assertTrue(discard >= 0);
        assertTrue(discard < close.indexOf("bestEffortCancel(api::cancelInFlightRequests)"));
        assertTrue(discard < close.indexOf("bestEffortCancel(deliveries::cancelInFlightRequests)"));
        assertTrue(discard < close.indexOf("bestEffortCancel(rawCollections::cancelInFlightRequests)"));
        assertFalse(close.contains("revokeSession("));
        for (String client : new String[] {
            "AdminSecurityHttpClient.java", "AdminOperationsHttpClient.java", "AdminRawCollectionHttpClient.java"
        }) {
            String source = read(SECURITY + client);
            assertTrue(client, source.contains("public void cancelInFlightRequests() {"));
            assertFalse(client, source.contains("synchronized void cancelInFlightRequests("));
        }
        String destroy = section(activity, "protected void onDestroy() {", "protected void onActivityResult(");
        assertTrue(destroy.indexOf("controller.close()") < destroy.indexOf("networkExecutor.shutdown()"));
        assertFalse(activity.contains("networkExecutor.execute(controller::close)"));
    }

    @Test
    public void s18_18FailedLogoutUsesTheSeparateLocalLockAndServerRevokeText() {
        String revoke = section(activity, "private void revokeSession(String sessionId, boolean currentSession) {",
            "private void reportLostDevice(");
        assertTrue(revoke.contains("controller.beginCurrentSessionRevocation(sessionId)"));
        assertTrue(revoke.indexOf("controller.beginCurrentSessionRevocation(sessionId)")
            < revoke.indexOf("runSecurityOperation("));
        assertTrue(revoke.contains("AdminRecoveryMessagePolicy.Phase.CURRENT_SESSION_LOGOUT"));
        assertTrue(revoke.contains("AdminRecoveryMessagePolicy.Phase.GENERAL"));
        assertTrue(revoke.contains("controller.revokeSession(sessionId)"));
        // adm-F6: the failure text is chosen with the controller's retained-session answer.
        String security = section(activity, "private void runSecurityOperation(\n        String pendingMessage,\n"
            + "        String successMessage,\n        AdminRecoveryMessagePolicy.Phase phase,\n"
            + "        SecurityOperation operation,\n        boolean credentialBound", "private void render()");
        assertTrue(security.contains("AdminRecoveryMessagePolicy.failureMessage("));
        assertTrue(security.contains("phase, error, owner.hasRetainedSession()"));
    }

    @Test
    public void s18_16NoUiCallbackThatReenablesWorkIsGuardedOnlyByIsDestroyed() {
        // Every UI callback that ends an operation (operationInFlight = false) must also be bound
        // to the screen being started or to the generation it was dispatched in.
        String[] lines = activity.split("\n", -1);
        int checked = 0;
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].contains("runOnUiThread(() -> {")) continue;
            StringBuilder head = new StringBuilder();
            for (int next = index + 1; next < Math.min(lines.length, index + 4); next++) {
                head.append(lines[next]).append('\n');
            }
            String guard = head.toString();
            int end = Math.min(lines.length, index + 14);
            boolean endsOperation = false;
            for (int next = index + 1; next < end; next++) {
                if (lines[next].contains("operationInFlight = false")) endsOperation = true;
            }
            if (!endsOperation || !guard.contains("return")) continue;
            checked += 1;
            assertFalse("line " + (index + 2) + " is guarded only by isDestroyed()",
                guard.contains("if (isDestroyed()) return;"));
            assertTrue("line " + (index + 2) + " has no screen/generation binding",
                guard.contains("!activityStarted")
                    || guard.contains("isCurrentOperationCallback(")
                    // onStop advances securityOperationGeneration, so this binds screen activity too.
                    || guard.contains("generation != securityOperationGeneration")
                    // adm-F7: reportHistoryGeneration alone is not advanced by onStop, so it no
                    // longer counts; history reads use the screen-bound helper instead.
                    || guard.contains("isCurrentHistoryCallback(")
                    || guard.contains("evidenceWorkflow.isCurrent(request)"));
        }
        assertTrue(checked >= 10);
        String helper = section(activity, "private boolean isCurrentOperationCallback(",
            "private void completeRawMutation(");
        assertTrue(helper.contains("!isDestroyed() && activityStarted"));
        assertTrue(helper.contains("generation == securityOperationGeneration"));
        assertTrue(helper.contains("sessionGeneration == historyState.operationsSessionGeneration()"));
    }

    @Test
    public void s18_16IncidentAndRawMutationResultsAreBoundToTheirDispatchGenerations() {
        String incident = section(activity, "private void runIncidentStatusUpdate(", "private void ");
        int capture = incident.indexOf("long generation = securityOperationGeneration;");
        assertTrue(capture >= 0);
        assertTrue(capture < incident.indexOf("long sessionGeneration = historyState.operationsSessionGeneration();"));
        assertTrue(capture < incident.indexOf("networkExecutor.execute("));
        assertEquals(4, count(incident, "if (!isCurrentOperationCallback(generation, sessionGeneration)) return;"));
        assertFalse(incident.contains("if (isDestroyed()) return;"));
        for (String method : new String[] {"private void runRawPurposeDecision(", "private void runRawLegalHold("}) {
            String raw = section(activity, method, "    private void ");
            assertTrue(method, raw.indexOf("long generation = securityOperationGeneration;")
                < raw.indexOf("networkExecutor.execute("));
            assertEquals(method, 3, count(raw, "completeRawMutation(\n                        generation, sessionGeneration,"));
        }
        String complete = section(activity, "private void completeRawMutation(", "private void loadAudits(");
        assertTrue(complete.startsWith(
            "private void completeRawMutation(long generation, long sessionGeneration, String message) {\n"
                + "        if (!isCurrentOperationCallback(generation, sessionGeneration)) return;"));
        String conflict = section(activity, "private void applyOperationalConflict(", "private String operationalResultMessage(");
        assertTrue(conflict.contains("if (isDestroyed() || !activityStarted) return;"));
        // The authentication path keeps its three-way binding (S18-16 main case).
        String security = section(activity, "private void runSecurityOperation(\n        String pendingMessage,\n"
            + "        String successMessage,\n        AdminRecoveryMessagePolicy.Phase phase,\n"
            + "        SecurityOperation operation,\n        boolean credentialBound", "private void render()");
        assertTrue(security.contains("AdminOperationRunner.execute(networkExecutor, this::runOnUiThread,"));
        assertTrue(security.contains("() -> isCurrentScreenOwner(owner, generation, sessionGeneration)"));
        String owner = section(activity, "private boolean isCurrentScreenOwner(", "private OperationalConflictRefresh refreshOperationalConflict(");
        assertTrue(owner.contains("controller == owner && !isDestroyed() && activityStarted"));
        assertTrue(owner.contains("generation == securityOperationGeneration"));
        assertTrue(owner.contains("owner.isLocalGenerationCurrent(sessionGeneration)"));
    }

    @Test
    public void admF7HistoryReadCallbacksAreBoundToTheStartedScreen() {
        String stop = section(activity, "protected void onStop() {", "protected void onDestroy() {");
        assertTrue(stop.contains("securityOperationGeneration += 1L;"));
        String helper = section(activity, "private boolean isCurrentHistoryCallback(",
            "private void finishHistoryFailure(");
        assertTrue(helper.contains("return !isDestroyed() && activityStarted"));
        assertTrue(helper.contains("securityGeneration == securityOperationGeneration"));
        assertTrue(helper.contains("generation == historyState.reportHistoryGeneration()"));
        String history = section(activity, "private void runReportHistoryOperation(",
            "private void runRecentHistoryOperation(");
        int capture = history.indexOf("long securityGeneration = securityOperationGeneration;");
        assertTrue(capture >= 0 && capture < history.indexOf("networkExecutor.execute("));
        assertEquals(2, count(history, "if (!isCurrentHistoryCallback(generation, securityGeneration)"));
        assertEquals(2, count(history, "finishHistoryFailure(\n                    generation, securityGeneration,"));
        assertFalse(history.contains("if (isDestroyed() || generation != reportHistoryGeneration"));
        String recent = section(activity, "private void runRecentHistoryOperation(",
            "private boolean isCurrentHistoryCallback(");
        assertTrue(recent.indexOf("long securityGeneration = securityOperationGeneration;")
            < recent.indexOf("networkExecutor.execute("));
        assertEquals(3, count(recent,
            "if (!isCurrentHistoryCallback(generation, securityGeneration)"));
        String failure = section(activity, "private void finishHistoryFailure(", "private void renderReportHistory()");
        assertTrue(failure.contains(") {\n        if (!isCurrentHistoryCallback(generation, securityGeneration)\n"));
    }

    @Test
    public void admF7HelperSplitCallbacksThatEndAnOperationStartWithAScreenBoundGuard() {
        // The line-window scan above cannot see callbacks written as runOnUiThread(() -> helper(...)).
        java.util.regex.Matcher calls = java.util.regex.Pattern
            .compile("runOnUiThread\\(\\(\\) -> ([A-Za-z0-9_]+)\\(").matcher(activity);
        java.util.Set<String> helpers = new java.util.TreeSet<>();
        while (calls.find()) helpers.add(calls.group(1));
        int checked = 0;
        for (String name : helpers) {
            int declaration = activity.indexOf("private void " + name + "(");
            assertTrue(name, declaration >= 0);
            int bodyStart = activity.indexOf(") {\n", declaration) + 4;
            int firstStatementEnd = activity.indexOf(";\n", bodyStart);
            String firstStatement = activity.substring(bodyStart, firstStatementEnd + 1);
            int bodyEnd = activity.indexOf("\n    }\n", bodyStart);
            if (!activity.substring(bodyStart, bodyEnd).contains("operationInFlight = false")) continue;
            checked += 1;
            assertTrue(name + " must return early", firstStatement.trim().startsWith("if (")
                && firstStatement.endsWith("return;"));
            assertTrue(name + " is not bound to the started screen",
                firstStatement.contains("!activityStarted")
                    || firstStatement.contains("isCurrentOperationCallback(")
                    || firstStatement.contains("isCurrentHistoryCallback("));
        }
        assertTrue(helpers.contains("finishHistoryFailure"));
        assertTrue(helpers.contains("completeRawMutation"));
        assertTrue(checked >= 2);
    }

    private static int count(String source, String needle) {
        int found = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            found += 1;
        }
        return found;
    }

    private static String section(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = source.indexOf(end, first + start.length());
        assertTrue("missing source boundary: " + start, first >= 0 && last > first);
        return source.substring(first, last);
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }
}
