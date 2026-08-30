package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public final class AdminOperationsUxStaticTest {
    private final String activity = read("src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminBoundaryActivity.java");
    private final String reports = read("src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminReportPanel.java");
    private final String requests = read("src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminReportRequestPanel.java");
    private final String incidents = read("src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminIncidentPanel.java");
    private final String audits = read("src/main/java/kr/co/hanium/dreamup/walksafe/admin/AdminAuditPanel.java");
    private final String reportController = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminReportController.java"
    );
    private final String requestController = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminReportRequestController.java"
    );
    private final String workflowController = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminReportWorkflowController.java"
    );
    private final String incidentController = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminIncidentController.java"
    );
    private final String auditController = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminAuditController.java"
    );
    private final String securityController = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java"
    );

    @Test
    public void primaryWorkflowIsFollowedByAlwaysVisibleRequiredParallelWork() {
        int reportPanel = activity.indexOf("group.addView(reportPanel, matchWrap())");
        int review = activity.indexOf("2. 선택한 신고 검수 결정");
        int delivery = activity.indexOf("3. 기관 수동 제출·접수 기록");
        int requiredHeading = activity.indexOf("필수 병행 업무");
        int requiredGroup = activity.indexOf(
            "group.addView(requiredParallelOperationsGroup, matchWrap())"
        );
        int auditHeading = activity.indexOf("보조 감사 기록");

        assertTrue(reportPanel > 0);
        assertTrue(review > reportPanel);
        assertTrue(delivery > review);
        assertTrue(requiredHeading > delivery);
        assertTrue(requiredGroup > requiredHeading);
        assertTrue(auditHeading > requiredGroup);
        assertTrue(activity.contains(
            "requiredParallelOperationsGroup.addView(reportRequestPanel, matchWrap())"
        ));
        assertTrue(activity.contains(
            "requiredParallelOperationsGroup.addView(incidentPanel, matchWrap())"
        ));
        assertTrue(activity.contains("auditSupplementGroup.addView(auditPanel, matchWrap())"));
        assertFalse(activity.contains("requiredParallelOperationsGroup.setVisibility(View.GONE)"));
        assertTrue(activity.contains("이 앱은 기관으로 자료를 전송하지 않습니다"));
        assertTrue(activity.contains("앱 밖에서 기관에 직접 제출하세요"));
        assertTrue(activity.contains("reportOperationsFormGroup.setVisibility(View.GONE)"));
        assertTrue(activity.contains("reportOperationsFormGroup.setVisibility(View.VISIBLE)"));
        assertTrue(activity.contains("감사 기록 펼치기"));
        assertTrue(activity.contains("auditSupplementGroup.setVisibility(View.GONE)"));
        assertFalse(activity.contains("기타 운영 기록"));
        assertFalse(activity.contains("Intent.ACTION_SEND"));
    }

    @Test
    public void changingConnectedReportClearsCrossReportDraftsButSameReportDoesNot() {
        String connect = between(
            activity,
            "private void connectReportToOperations(",
            "private void refreshConnectedReportDetail()"
        );
        String reset = between(
            activity,
            "private void resetOperationsInputsForDifferentReport()",
            "private void refreshConnectedReportDetail()"
        );

        assertTrue(connect.contains("boolean reportChanged = !reportId.equals(connectedOperationsReportId)"));
        assertTrue(connect.contains("if (reportChanged)"));
        assertTrue(connect.contains("resetOperationsInputsForDifferentReport()"));
        assertTrue(connect.contains("detail.delivery() == null ? 0 : detail.delivery().revision()"));
        assertTrue(connect.contains("verifiedDeliveryPackage.matchesDelivery("));
        for (String input : new String[] {
            "reviewReasonInput", "reviewUserVisibleReasonInput", "duplicateReportIdInput",
            "institutionInput", "deliveryChannelInput", "deliveryRecipientInput",
            "externalReceiptInput", "deliveryReasonInput", "evidenceSha256Input"
        }) {
            assertTrue(reset.contains("clear(" + input + ")"));
        }
        assertTrue(reset.contains("reviewDecisionInput.setSelection(0)"));
        assertTrue(reset.contains("deliveryStatusInput.setSelection(0)"));
        assertTrue(reset.contains("locationReviewedInput.setChecked(false)"));
        assertTrue(reset.contains("photoReviewedInput.setChecked(false)"));
        assertTrue(reset.contains("privacyReviewedInput.setChecked(false)"));
        assertTrue(reset.contains("observedAtInput.setText(Instant.now().toString())"));
        assertTrue(reset.contains("manualDeliveryCompletedInput.setChecked(false)"));
        assertTrue(reset.contains("idempotencyKeyInput.setText(UUID.randomUUID().toString())"));
    }

    @Test
    public void reportOperationsRequireTheReadOnlyConnectedReportBinding() {
        assertTrue(activity.contains(
            "makeReadOnly(reportIdInput, \"상세에서 연결한 신고 식별자\")"
        ));
        for (String method : new String[] {
            "private void recordReviewDecision()",
            "private void readReviewDecisions()",
            "private void recordDelivery()",
            "private void readDeliveries()"
        }) {
            String body = between(activity, method, "\n    }");
            assertTrue(method, body.contains("requireConnectedOperationsReportId()"));
        }
        String binding = between(
            activity,
            "private String requireConnectedOperationsReportId()",
            "private void connectReportToOperations("
        );
        assertTrue(binding.contains("String reportId = normalized(reportIdInput)"));
        assertTrue(binding.contains("connectedOperationsReportId == null"));
        assertTrue(binding.contains("!connectedOperationsReportId.equals(reportId)"));
        assertTrue(binding.contains("신고 상세에서 작업을 다시 연결해 주세요"));
    }

    @Test
    public void authenticationBindingChangeClearsAllSessionBoundReportDrafts() {
        String render = between(activity, "private void render()", "private void renderDevices(");
        String boundary = between(
            activity,
            "private void resetSessionBoundReportState()",
            "private String requireConnectedOperationsReportId()"
        );
        assertTrue(render.contains("reconcileOperationsAccessBinding("));
        assertTrue(activity.contains("!java.util.Objects.equals("));
        assertTrue(boundary.contains("connectedOperationsReportId = null"));
        assertTrue(boundary.contains("reportOperationsFormGroup.setVisibility(View.GONE)"));
        assertTrue(boundary.contains("pendingDeliveryPackage.destroy()"));
        assertTrue(boundary.contains("pendingDeliveryPackage = null"));
        assertTrue(boundary.contains("verifiedDeliveryPackage = null"));
        assertTrue(boundary.contains("resetOperationsInputsForDifferentReport()"));
        assertTrue(boundary.contains("clear(reportIdInput)"));
        assertTrue(boundary.contains("expectedRevisionInput.setText(\"0\")"));
        assertTrue(boundary.contains("clear(packageRevisionInput)"));
        assertTrue(boundary.contains("reportPanel.clearSessionBoundDrafts()"));
        assertTrue(boundary.contains("reportRequestPanel.clearSessionBoundDrafts()"));
        assertTrue(boundary.contains("incidentPanel.clearSubmittedEvidence()"));
        assertTrue(boundary.contains("if (reportOperationsFormGroup != null)"));
        assertTrue(reports.contains("sessionBoundDraftsCleared = true"));
        assertTrue(reports.contains("if (sessionBoundDraftsCleared)"));
    }

    @Test
    public void authenticationBindingClearDropsEveryControllerSnapshotToIdle() {
        String boundary = between(
            activity,
            "private void resetSessionBoundReportState()",
            "private String requireConnectedOperationsReportId()"
        );
        assertTrue(boundary.contains("reportController.clearSessionState()"));
        assertTrue(boundary.contains("reportRequestController.clearSessionState()"));
        assertTrue(boundary.contains("reportWorkflowController.clearSessionState()"));
        assertTrue(boundary.contains("incidentController.clearSessionState()"));
        assertTrue(boundary.contains("auditController.clearSessionState()"));
        assertFalse(boundary.contains(".invalidate()"));

        for (String controller : new String[] {
            reportController, requestController, workflowController,
            incidentController, auditController
        }) {
            String clear = between(
                controller,
                "public synchronized void clearSessionState()",
                "\n    }"
            );
            assertTrue(clear.contains("generation += 1"));
            assertTrue(clear.contains("Phase.IDLE"));
        }
        assertTrue(between(
            reportController,
            "public synchronized void clearSessionState()",
            "\n    }"
        ).contains("retryRequest = null"));
        String requestClear = between(
            requestController,
            "public synchronized void clearSessionState()",
            "\n    }"
        );
        assertTrue(requestClear.contains("activeMutation.destroy()"));
        assertTrue(requestClear.contains("retryRequest = null"));
        assertTrue(between(
            workflowController,
            "public synchronized void clearSessionState()",
            "\n    }"
        ).contains("clearPackage()"));
        assertTrue(between(
            incidentController,
            "public synchronized void clearSessionState()",
            "\n    }"
        ).contains("retryRequest = null"));
        assertTrue(between(
            auditController,
            "public synchronized void clearSessionState()",
            "\n    }"
        ).contains("retry = null"));
    }

    @Test
    public void safCompletionAndWorkflowCredentialsStayBoundToTheirLaunchGeneration() {
        String result = between(
            activity,
            "protected void onActivityResult(",
            "private View buildContent()"
        );
        int staleRequestGuard = result.indexOf("requestCode != pendingSafRequestCode");
        int pendingPackageRead = result.indexOf(
            "AdminDeliveryPackage packageValue = pendingDeliveryPackage"
        );
        assertTrue(staleRequestGuard > 0 && staleRequestGuard < pendingPackageRead);
        assertTrue(activity.contains("pendingSafRequestCode = allocateSafRequestCode()"));
        assertTrue(activity.contains("startActivityForResult(intent, pendingSafRequestCode)"));
        assertTrue(activity.contains("saveGeneration != safSaveGeneration"));
        assertTrue(activity.contains(
            "workflowGeneration != reportWorkflowController.generationToken()"
        ));
        assertTrue(activity.contains("sessionGeneration != operationsSessionGeneration"));
        assertTrue(result.contains("reportWorkflowController.markSaved(workflowGeneration"));
        assertTrue(result.contains("reportWorkflowController.markSaveFailed(workflowGeneration"));
        assertFalse(result.contains("reportWorkflowController.markSaved(saved.revision())"));

        assertTrue(workflowController.contains("private Request activeRequest"));
        assertTrue(workflowController.contains("replaceActiveRequest(request)"));
        assertTrue(workflowController.contains(
            "if (activeRequest != null && activeRequest != request) activeRequest.destroy()"
        ));
        String clear = between(
            workflowController,
            "public synchronized void clearSessionState()",
            "\n    }"
        );
        assertTrue(clear.contains("destroyActiveRequest()"));
        String invalidate = between(
            workflowController,
            "public synchronized void invalidate()",
            "\n    }"
        );
        assertTrue(invalidate.contains("destroyActiveRequest()"));
        assertTrue(workflowController.contains("if (activeRequest != null) activeRequest.destroy()"));
        assertTrue(workflowController.contains("activeRequest = null"));
        assertTrue(workflowController.contains("request.destroy()"));
        assertTrue(workflowController.contains("clearActiveRequest(request)"));
        assertTrue(workflowController.contains(
            "if (expectedGeneration != generation || state.phase != Phase.PACKAGE_READY) return false"
        ));
    }

    @Test
    public void safBytesAreWrittenOnlyAfterFreshServerSessionValidation() {
        String result = between(
            activity,
            "protected void onActivityResult(",
            "private View buildContent()"
        );
        int serverRefresh = result.indexOf("controller.refreshAndValidateSafSaveSession(sessionId)");
        int persistedWrite = result.indexOf("AdminDeliveryPackageSaver.save(");
        assertTrue(serverRefresh > 0 && serverRefresh < persistedWrite);
        assertTrue(result.contains("rejectSafSaveBeforeWrite(packageValue, uri)"));
        assertTrue(result.contains("packageValue.destroy()"));
        assertTrue(result.contains("deleteSafDocument(uri)"));
        assertTrue(result.contains("resetSessionBoundReportState()"));

        String validation = between(
            securityController,
            "public synchronized boolean refreshAndValidateSafSaveSession(",
            "public synchronized void reauthenticate("
        );
        int refresh = validation.indexOf("refresh()");
        int decision = validation.indexOf("boolean valid =");
        assertTrue(refresh > 0 && refresh < decision);
        assertTrue(validation.contains("securityState == AdminSecurityState.NORMAL"));
        assertTrue(validation.contains(
            "recoveryCustodyState == AdminRecoveryCustodyState.ATTESTED"
        ));
        assertTrue(validation.contains("expectedSessionId.equals(currentSessionId)"));
        assertTrue(validation.contains("item.isCurrent()"));
        assertTrue(validation.contains("!item.isRevoked()"));
        assertTrue(validation.contains("failClosed()"));

        String invalidate = between(
            workflowController,
            "public synchronized void invalidate()",
            "public synchronized void clearSessionState()"
        );
        assertTrue(invalidate.contains("state = new State(Phase.IDLE, null, null, null)"));
    }

    @Test
    public void spinnerLabelsAndExplicitMappingsStayInExactProtocolOrder() {
        int approved = activity.indexOf("승인 (APPROVED)");
        int rejected = activity.indexOf("거절 (REJECTED)");
        int duplicate = activity.indexOf("중복 신고 (DUPLICATE)");
        int submitted = activity.indexOf("수동 제출 완료 (SUBMITTED)");
        int acknowledged = activity.indexOf("기관 접수 확인 (ACKNOWLEDGED)");
        int resolved = activity.indexOf("기관 처리 완료 (RESOLVED)");
        int failed = activity.indexOf("수동 제출 실패 (FAILED)");

        assertTrue(approved > 0 && approved < rejected && rejected < duplicate);
        assertTrue(submitted > 0 && submitted < acknowledged && acknowledged < resolved && resolved < failed);
        String compactActivity = activity.replaceAll("\\s+", "");
        assertTrue(compactActivity.contains(
            "privatestaticfinalString[]REVIEW_DECISION_LABELS={"
                + "\"승인(APPROVED)\",\"거절(REJECTED)\",\"중복신고(DUPLICATE)\"};"
        ));
        assertTrue(compactActivity.contains(
            "privatestaticfinalAdminReportDecision.Decision[]REVIEW_DECISIONS={"
                + "AdminReportDecision.Decision.APPROVED,"
                + "AdminReportDecision.Decision.REJECTED,"
                + "AdminReportDecision.Decision.DUPLICATE};"
        ));
        assertTrue(compactActivity.contains(
            "privatestaticfinalString[]DELIVERY_STATUS_LABELS={"
                + "\"수동제출완료(SUBMITTED)\","
                + "\"기관접수확인(ACKNOWLEDGED)\","
                + "\"기관처리완료(RESOLVED)\","
                + "\"수동제출실패(FAILED)\"};"
        ));
        assertTrue(compactActivity.contains(
            "privatestaticfinalAdminInstitutionDelivery.Status[]DELIVERY_STATUSES={"
                + "AdminInstitutionDelivery.Status.SUBMITTED,"
                + "AdminInstitutionDelivery.Status.ACKNOWLEDGED,"
                + "AdminInstitutionDelivery.Status.RESOLVED,"
                + "AdminInstitutionDelivery.Status.FAILED};"
        ));
        assertTrue(activity.contains(
            "REVIEW_DECISIONS[reviewDecisionInput.getSelectedItemPosition()]"
        ));
        assertTrue(activity.contains(
            "DELIVERY_STATUSES[deliveryStatusInput.getSelectedItemPosition()]"
        ));
        assertFalse(activity.contains("AdminReportDecision.Decision.values()["));
        assertFalse(activity.contains("AdminInstitutionDelivery.Status.values()["));
    }

    @Test
    public void failedDeliveryDoesNotClaimSuccessfulManualCompletion() {
        String delivery = between(
            activity,
            "private void recordDelivery()",
            "private void readDeliveries()"
        );
        assertTrue(delivery.contains("AdminInstitutionDelivery.Status deliveryStatus ="));
        assertTrue(delivery.contains(
            "deliveryStatus != AdminInstitutionDelivery.Status.FAILED"
        ));
        assertTrue(delivery.contains("&& !manualDeliveryCompletedInput.isChecked()"));
        assertTrue(delivery.contains("deliveryStatus,"));
    }

    @Test
    public void reportsExposePageScopedSummaryPriorityAndReviewSteps() {
        assertTrue(reports.contains("현재 불러온 목록 "));
        assertTrue(reports.contains("우선 업무: 새 신고 "));
        assertTrue(reports.contains("처리 순서\\n1) 상세 확인"));
        assertTrue(reports.contains("검수·수동 제출 준비로 이어가기"));
        assertTrue(reports.contains("조건을 줄이거나 '모든 상태'로 다시 조회하세요"));
        assertTrue(reports.contains("서버 상태를 확인한 뒤 '다시 시도'를 누르세요"));
    }

    @Test
    public void asynchronousPanelsExposeLabeledProgressAndActionableRecovery() {
        for (String panel : new String[] {reports, requests, incidents, audits}) {
            assertTrue(panel.contains("new ProgressBar"));
            assertTrue(panel.contains("setIndeterminate(true)"));
            assertTrue(panel.contains("setContentDescription"));
            assertTrue(panel.contains("ACCESSIBILITY_LIVE_REGION_POLITE"));
            assertTrue(panel.contains("setMinHeight(dp(48))"));
        }
        assertTrue(requests.contains("'다시 시도'를 누르세요"));
        assertTrue(incidents.contains("'중대 사고 조회 다시 시도'를 누르세요"));
        assertTrue(audits.contains("'감사 기록 다시 시도'를 누르세요"));
    }

    @Test
    public void activityUsesDensityIndependentTouchTargetsAndSemanticHeadings() {
        assertTrue(activity.contains("content.setPadding(dp(20), dp(20), dp(20), dp(32))"));
        assertTrue(activity.contains("markAccessibilityHeading(title)"));
        assertTrue(activity.contains("markAccessibilityHeading(requiredParallelHeading)"));
        assertTrue(activity.contains("view.setAccessibilityHeading(true)"));
        assertTrue(activity.contains("AccessibilityNodeInfo.CollectionItemInfo.obtain("));
        assertTrue(activity.contains("true, false"));
        assertTrue(activity.contains("button.setMinHeight(dp(48))"));
        assertTrue(activity.contains("view.setMinHeight(dp(48))"));
        assertTrue(activity.contains("spinner.setMinimumHeight(dp(48))"));
        assertTrue(activity.contains("checkBox.setMinHeight(dp(48))"));
        assertTrue(activity.contains("view instanceof Spinner || view instanceof CheckBox"));
        assertTrue(activity.contains("View.ACCESSIBILITY_LIVE_REGION_POLITE"));
        assertFalse(activity.contains("resultText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE)"));
        assertTrue(activity.contains("승인 (APPROVED)"));
        assertTrue(activity.contains("기관 접수 확인 (ACKNOWLEDGED)"));
        assertTrue(activity.contains("makeReadOnly(expectedRevisionInput"));
        assertTrue(activity.contains("makeReadOnly(packageRevisionInput"));
        assertTrue(activity.contains("makeReadOnly(idempotencyKeyInput"));
        assertTrue(activity.contains("앱 밖에서 해당 기관으로 수동 제출을 실제로 수행"));
        assertTrue(activity.contains("rotateDeliveryRecordInputsAfterSuccess()"));
        assertTrue(activity.contains("idempotencyKeyInput.setText(UUID.randomUUID().toString())"));
    }

    @Test
    public void everyOperationsPanelProvidesPreApi28HeadingSemantics() {
        for (String panel : new String[] {reports, requests, incidents, audits}) {
            assertTrue(panel.contains("markAccessibilityHeading("));
            assertTrue(panel.contains("view.setAccessibilityHeading(true)"));
            assertTrue(panel.contains("AccessibilityNodeInfo.CollectionItemInfo.obtain("));
            assertTrue(panel.contains("true, false"));
        }
        assertTrue(reports.contains("markAccessibilityHeading(summaryText)"));
        assertTrue(incidents.contains("markAccessibilityHeading(detailHeading)"));
        assertTrue(requests.contains("markAccessibilityHeading(heading)"));
        assertTrue(audits.contains("markAccessibilityHeading(heading)"));
    }

    @Test
    public void manualDeliveryActionOnlyRecordsTheExternalFactThroughTheController() {
        String delivery = between(
            activity,
            "private void recordDelivery()",
            "private void readDeliveries()"
        );
        assertTrue(activity.contains("이 앱은 기관으로 자료를 전송하지 않습니다"));
        assertTrue(activity.contains("수동 전달 결과 기록"));
        assertTrue(delivery.contains("controller.recordDelivery("));
        assertTrue(delivery.contains("BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED"));
        assertFalse(delivery.contains("startActivity"));
        assertFalse(delivery.contains("new Intent"));
        assertFalse(delivery.contains("ACTION_SEND"));
    }

    @Test
    public void signedOutLoginGroupPrecedesDetailedSecurityMetadataAndHidesAfterAccess() {
        int loginGroup = activity.indexOf("content.addView(loginGroup, matchWrap())");
        int metadata = activity.indexOf("securityDetailsGroup.addView(metadataText, matchWrap())");
        assertTrue(loginGroup > 0);
        assertTrue(metadata > loginGroup);
        assertTrue(activity.contains("loginGroup.addView(adminIdInput, matchWrap())"));
        assertTrue(activity.contains("loginGroup.addView(passwordInput, matchWrap())"));
        assertTrue(activity.contains("loginGroup.addView(totpInput, matchWrap())"));
        assertTrue(activity.contains(
            "loginGroup.setVisibility(!accessActive && !recoveryActive ? View.VISIBLE : View.GONE)"
        ));
        assertTrue(activity.contains("loginGroup.setVisibility(View.GONE)"));
        assertTrue(activity.contains("서버·기기 보안 정보 펼치기"));
        assertTrue(activity.contains("securityDetailsGroup.setVisibility(View.GONE)"));
        assertTrue(activity.contains("securityDetailsGroup.setVisibility(opening ? View.VISIBLE : View.GONE)"));
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        if (startIndex < 0 || endIndex < 0) throw new AssertionError("missing source boundary");
        return source.substring(startIndex, endIndex);
    }
}
