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
    private final String packageSaver = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminDeliveryPackageSaver.java"
    );
    private final String originalEvidence = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOriginalEvidence.java"
    );
    private final String operationsHttp = read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminOperationsHttpClient.java"
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
    public void contentOrReviewChangesClearFullStateWhileDeliveryTupleOnlyResetsPackageDraft() {
        String connect = between(
            activity,
            "private void connectReportToOperations(",
            "private void refreshConnectedReportDetail()"
        );
        String fullReset = between(
            activity,
            "private void resetOperationsInputsForDifferentReport()",
            "private void resetPackageBoundDeliveryDraft()"
        );
        String packageReset = between(
            activity,
            "private void resetPackageBoundDeliveryDraft()",
            "private void replaceVerifiedDeliveryPackage("
        );
        String tupleOnlyBranch = between(
            connect,
            "} else if (deliveryTupleChanged)",
            "connectedOperationsReportId = reportId"
        );

        assertTrue(connect.contains("boolean reportChanged = connectedOperationsReportId != null"));
        assertTrue(connect.contains("&& !reportId.equals(connectedOperationsReportId)"));
        assertTrue(connect.contains("boolean contentRevisionChanged"));
        assertTrue(connect.contains("boolean reviewChanged"));
        assertTrue(connect.contains("boolean deliveryTupleChanged"));
        assertTrue(connect.contains("if (reportChanged || contentRevisionChanged || reviewChanged)"));
        assertTrue(connect.contains("verifiedDeliveryPackage = null"));
        assertTrue(connect.contains("resetOperationsInputsForDifferentReport()"));
        assertTrue(connect.contains("} else if (deliveryTupleChanged)"));
        assertTrue(connect.contains("resetPackageBoundDeliveryDraft()"));
        assertFalse(tupleOnlyBranch.contains("verifiedDeliveryPackage = null"));
        assertFalse(tupleOnlyBranch.contains("resetOperationsInputsForDifferentReport()"));
        assertFalse(tupleOnlyBranch.contains("clearOriginalEvidence("));
        assertTrue(connect.contains("connectedOperationsReviewRevision = reviewRevision"));
        assertTrue(connect.contains("connectedOperationsContentRevision = detail.contentRevision()"));
        assertTrue(connect.contains(
            "connectedOperationsLatestDeliveryRevision = detail.latestDeliveryRevision()"
        ));
        assertTrue(connect.contains(
            "expectedRevisionInput.setText(Integer.toString(detail.latestDeliveryRevision()))"
        ));
        assertTrue(connect.contains("verifiedDeliveryPackage.matchesFreshDetail(detail)"));
        for (String input : new String[] {
            "reviewReasonInput", "reviewUserVisibleReasonInput", "duplicateReportIdInput",
            "institutionInput", "deliveryChannelInput", "deliveryRecipientInput",
            "externalReceiptInput", "deliveryReasonInput", "evidenceSha256Input"
        }) {
            assertTrue(fullReset.contains("clear(" + input + ")"));
        }
        assertTrue(fullReset.contains("reviewDecisionInput.setSelection(0)"));
        assertTrue(fullReset.contains("deliveryStatusInput.setSelection(0)"));
        assertTrue(fullReset.contains("locationReviewedInput.setChecked(false)"));
        assertTrue(fullReset.contains("photoReviewedInput.setChecked(false)"));
        assertTrue(fullReset.contains("privacyReviewedInput.setChecked(false)"));
        assertTrue(fullReset.contains("clearOriginalEvidence("));

        for (String input : new String[] {"externalReceiptInput", "evidenceSha256Input"}) {
            assertTrue(packageReset.contains("clear(" + input + ")"));
        }
        assertTrue(packageReset.contains("observedAtInput.setText(Instant.now().toString())"));
        assertTrue(packageReset.contains("manualDeliveryCompletedInput.setChecked(false)"));
        assertTrue(packageReset.contains("idempotencyKeyInput.setText(UUID.randomUUID().toString())"));
        for (String preserved : new String[] {
            "reviewReasonInput", "reviewUserVisibleReasonInput", "duplicateReportIdInput",
            "institutionInput", "deliveryChannelInput", "deliveryRecipientInput",
            "deliveryReasonInput", "originalEvidence"
        }) {
            assertFalse(preserved, packageReset.contains(preserved));
        }
    }

    @Test
    public void reportOperationsRequireTheReadOnlyConnectedReportBinding() {
        assertTrue(activity.contains(
            "makeReadOnly(reportIdInput, \"상세에서 연결한 신고 식별자\")"
        ));
        for (String method : new String[] {
            "private void recordReviewDecision()",
            "private void readReviewDecisions(boolean nextPage)",
            "private void recordDelivery()",
            "private void readDeliveries(boolean nextPage)"
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
        assertTrue(binding.contains("connectedOperationsContentRevision < 0"));
        String recordReview = between(
            activity,
            "private void recordReviewDecision()",
            "private void readReviewDecisions(boolean nextPage)"
        );
        assertTrue(recordReview.contains("connectedOperationsContentRevision"));
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
        assertTrue(boundary.contains("connectedOperationsContentRevision = -1"));
        assertTrue(boundary.contains("connectedOperationsReviewRevision = -1"));
        assertTrue(boundary.contains("connectedOperationsReviewDecision = null"));
        assertTrue(boundary.contains("connectedOperationsLatestDeliveryRevision = -1"));
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
    public void safBytesUseFreshEligibilityBeforeAndAfterWriteAndDeleteOnlyTheNewDocument() {
        String result = between(
            activity,
            "protected void onActivityResult(",
            "private View buildContent()"
        );
        int serverRefresh = result.indexOf("controller.refreshAndValidateSafSaveSession(sessionId)");
        int beforeDetail = result.indexOf("AdminReportModels.Detail beforeWrite");
        int beforeEligibility = result.indexOf("packageValue.matchesFreshEligibility(beforeWrite)");
        int persistedWrite = result.indexOf("AdminDeliveryPackageSaver.save(");
        int afterDetail = result.indexOf("AdminReportModels.Detail afterWrite");
        int afterEligibility = result.indexOf("packageValue.matchesFreshEligibility(afterWrite)");
        int replaceBinding = result.indexOf("replaceVerifiedDeliveryPackage(saved)");
        String postWriteFailure = result.substring(result.lastIndexOf("} catch (Exception error) {"));
        assertTrue(serverRefresh > 0 && serverRefresh < beforeDetail);
        assertTrue(beforeDetail < beforeEligibility && beforeEligibility < persistedWrite);
        assertTrue(persistedWrite < afterDetail && afterDetail < afterEligibility);
        assertTrue(afterEligibility < replaceBinding);
        assertTrue(result.contains("saved.matchesFreshDetail(afterWrite)"));
        assertTrue(result.contains("rejectSafSave(packageValue, uri, workflowGeneration"));
        assertTrue(result.contains("packageValue.destroy()"));
        assertTrue(result.contains("deleteSafDocument(uri)"));
        assertTrue(postWriteFailure.contains("deleteSafDocument(uri)"));
        assertFalse(postWriteFailure.contains("verifiedDeliveryPackage = null"));
        assertFalse(postWriteFailure.contains("resetPackageBoundDeliveryDraft()"));
        assertTrue(result.contains("기존 제출본 연결과 입력은 유지했습니다."));
        assertFalse(result.contains("verifiedDeliveryPackage = null"));
        assertFalse(result.contains("resetOperationsInputsForDifferentReport()"));

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
    public void packageBeginAndFailurePathsPreserveTheExistingBindingAndDraft() {
        String begin = between(
            activity,
            "private void runDeliveryPackageCreation(",
            "private void executeReportWorkflow("
        );
        String launch = between(
            activity,
            "private void launchPackageDocumentPicker()",
            "private void launchExistingDeliveryPackagePicker()"
        );
        String reject = between(
            activity,
            "private void rejectSafSave(",
            "private View buildContent()"
        );
        String reconnect = between(
            activity,
            "private void handleExistingDeliveryPackageResult(",
            "private boolean isCurrentSafReconnectBinding("
        );

        assertTrue(begin.contains("reportWorkflowController.beginPackage("));
        assertTrue(begin.contains("detail,"));
        for (String source : new String[] {begin, launch, reject, reconnect}) {
            assertFalse(source.contains("verifiedDeliveryPackage = null"));
            assertFalse(source.contains("packageRevisionInput.setText(\"\")"));
            assertFalse(source.contains("resetOperationsInputsForDifferentReport()"));
        }
        assertFalse(reject.contains("resetPackageBoundDeliveryDraft()"));
        assertTrue(reject.contains("deleteSafDocument(uri)"));
        assertTrue(reject.contains("기존 제출본 연결과 입력은 유지했습니다."));
        assertFalse(reconnect.contains("deleteSafDocument"));
        assertTrue(reconnect.contains(
            "기존 제출본 연결과 입력, 선택한 원본 파일은 그대로 유지했습니다."
        ));
    }

    @Test
    public void verifiedSuccessAloneAtomicallyReplacesBindingAndResetsPackageDraft() {
        String saveResult = between(
            activity,
            "protected void onActivityResult(",
            "private View buildContent()"
        );
        String reconnect = between(
            activity,
            "private void handleExistingDeliveryPackageResult(",
            "private boolean isCurrentSafReconnectBinding("
        );
        String replace = between(
            activity,
            "private void replaceVerifiedDeliveryPackage(",
            "private void invalidatePendingDeliveryPackageWork()"
        );
        String reset = between(
            activity,
            "private void resetPackageBoundDeliveryDraft()",
            "private void replaceVerifiedDeliveryPackage("
        );

        int assignment = replace.indexOf("verifiedDeliveryPackage = saved");
        int packageRevision = replace.indexOf("packageRevisionInput.setText(");
        int draftReset = replace.indexOf("resetPackageBoundDeliveryDraft()");
        assertTrue(assignment >= 0 && assignment < packageRevision && packageRevision < draftReset);
        assertTrue(saveResult.contains("replaceVerifiedDeliveryPackage(saved)"));
        assertTrue(reconnect.contains("replaceVerifiedDeliveryPackage(saved)"));
        for (String input : new String[] {"externalReceiptInput", "evidenceSha256Input"}) {
            assertTrue(reset.contains("clear(" + input + ")"));
        }
        assertTrue(reset.contains("manualDeliveryCompletedInput.setChecked(false)"));
        assertTrue(reset.contains("observedAtInput.setText(Instant.now().toString())"));
        assertTrue(reset.contains("idempotencyKeyInput.setText(UUID.randomUUID().toString())"));
        assertFalse(saveResult.contains("controller.recordDelivery("));
        assertFalse(reconnect.contains("controller.recordDelivery("));
    }

    @Test
    public void manualDeliveryUsesGlobalLatestCasAndLeavesPackageSwitchToTheServer() {
        String connect = between(
            activity,
            "private void connectReportToOperations(",
            "private void refreshConnectedReportDetail()"
        );
        String delivery = between(
            activity,
            "private void recordDelivery()",
            "private void readDeliveries(boolean nextPage)"
        );
        assertTrue(connect.contains(
            "connectedOperationsLatestDeliveryRevision = detail.latestDeliveryRevision()"
        ));
        assertTrue(connect.contains(
            "expectedRevisionInput.setText(Integer.toString(detail.latestDeliveryRevision()))"
        ));
        assertTrue(delivery.contains(
            "parsedDeliveryRevision != connectedOperationsLatestDeliveryRevision"
        ));
        assertTrue(delivery.contains("!verifiedDeliveryPackage.matchesFreshDetail("));
        assertTrue(delivery.contains("\"RESOLVED\".equals(connectedOperationsDeliveryStatus)"));
        assertFalse(delivery.contains("parsedPackageRevision != connectedOperationsPackageRevision"));
        assertFalse(delivery.contains("parsedDeliveryRevision != connectedOperationsDeliveryRevision"));
    }

    @Test
    public void existingSafPackageUsesUntrustedLocatorThenExactProofAndFullVerification() {
        String launch = between(
            activity,
            "private void launchExistingDeliveryPackagePicker()",
            "private void handleExistingDeliveryPackageResult("
        );
        String result = between(
            activity,
            "private void handleExistingDeliveryPackageResult(",
            "private boolean isCurrentSafReconnectBinding("
        );
        assertTrue(launch.contains("Intent.ACTION_OPEN_DOCUMENT"));
        assertTrue(launch.contains("Intent.CATEGORY_OPENABLE"));
        assertTrue(launch.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"));
        assertFalse(launch.contains("takePersistableUriPermission"));
        assertFalse(launch.contains("ACTION_SEND"));
        assertFalse(launch.contains("verifiedDeliveryPackage = null"));
        assertFalse(launch.contains("packageRevisionInput.setText(\"\")"));
        assertFalse(result.contains("deleteSafDocument"));
        assertFalse(result.contains("openOutputStream"));

        int sessionRefresh = result.indexOf(
            "controller.refreshAndValidateSafSaveSession(sessionId)"
        );
        int locatorRead = result.indexOf("InputStream locatorInput");
        int locatorInspection = result.indexOf("AdminDeliveryPackageSaver.inspectUntrusted(");
        int freshDetail = result.indexOf("AdminReportModels.Detail beforeRead");
        int proof = result.indexOf("controller.getAdminDeliveryPackageProof(");
        int fullRead = result.indexOf("InputStream input", locatorRead + 1);
        int verification = result.indexOf("AdminDeliveryPackageSaver.verifyExisting(");
        int afterDetail = result.indexOf("AdminReportModels.Detail afterRead");
        assertTrue(sessionRefresh > 0 && sessionRefresh < locatorRead);
        assertTrue(locatorRead < locatorInspection && locatorInspection < freshDetail);
        assertTrue(freshDetail < proof && proof < fullRead && fullRead < verification);
        assertTrue(verification < afterDetail);
        assertTrue(result.contains("reference.reportId(),"));
        assertTrue(result.contains("reference.packageRevision(),"));
        assertTrue(result.contains("eligibility.matchesExact(beforeRead)"));
        assertTrue(result.contains("eligibility.matchesExact(afterRead)"));
        assertTrue(result.contains("proof.matchesReference(reference)"));
        assertTrue(result.contains("proof.matchesFreshDetail(beforeRead)"));
        assertTrue(result.contains("proof.matchesFreshDetail(afterRead)"));
        assertTrue(result.contains("saved.matchesProof(proof)"));
        assertTrue(result.contains("saved.matchesFreshDetail(afterRead)"));
        assertTrue(result.contains("replaceVerifiedDeliveryPackage(saved)"));
        assertFalse(result.contains("verifiedDeliveryPackage = null"));
        assertFalse(result.contains("packageRevisionInput.setText(\"\")"));
        assertTrue(result.contains("기존 제출본 연결과 입력, 선택한 원본 파일은 그대로 유지했습니다."));

        assertTrue(packageSaver.contains("inspectUntrusted(InputStream input)"));
        assertTrue(packageSaver.contains("verifyExisting("));
        assertTrue(packageSaver.contains("AdminDeliveryPackage.Proof proof"));
        assertTrue(packageSaver.contains("AdminDeliveryPackage.verifyAgainstProof(proof, bytes)"));
        assertTrue(packageSaver.contains("Arrays.fill(bytes, (byte) 0)"));
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
            "private void readDeliveries(boolean nextPage)"
        );
        assertTrue(delivery.contains("AdminInstitutionDelivery.Status deliveryStatus ="));
        assertTrue(delivery.contains(
            "deliveryStatus != AdminInstitutionDelivery.Status.FAILED"
        ));
        assertTrue(delivery.contains("&& !manualDeliveryCompletedInput.isChecked()"));
        assertTrue(delivery.contains("deliveryStatus,"));
    }

    @Test
    public void approvalOriginalEvidenceIsHumanConfirmedMemoryOnlyAndLifecycleBound() {
        assertTrue(activity.contains("정확한 위치·원본 사진 일회 열람"));
        assertTrue(activity.contains("현재 표시된 정확한 위치와 원본 사진을 직접 확인했습니다."));
        assertTrue(activity.contains(
            "승인 검토용 정확한 위치와 원본 사진을 재인증 후 한 번 불러오기"
        ));
        assertTrue(activity.contains(
            "originalEvidenceStatusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE)"
        ));
        assertTrue(activity.contains(
            "originalEvidenceImage.setContentDescription(\"승인 검토용 신고 원본 사진\")"
        ));
        assertTrue(activity.contains("originalEvidenceLocationText.setTextIsSelectable(false)"));
        assertTrue(activity.contains("getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE)"));
        assertTrue(activity.contains("takeMutableInput(originalEvidencePasswordInput)"));
        assertTrue(activity.contains("takeMutableInput(originalEvidenceTotpInput)"));
        assertTrue(activity.contains("value.getChars(0, value.length(), copy, 0)"));

        String approval = between(
            activity,
            "private void recordReviewDecision()",
            "private void loadOriginalEvidence()"
        );
        assertTrue(approval.contains("selectedDecision == AdminReportDecision.Decision.APPROVED"));
        assertTrue(approval.contains("originalEvidenceConfirmedInput.isChecked()"));
        assertTrue(approval.contains("originalEvidence.matches("));
        assertTrue(approval.contains("connectedOperationsContentRevision"));
        assertTrue(approval.contains("boundOperationsSessionId"));
        assertTrue(approval.contains("deviceId,"));
        assertTrue(approval.contains("evidenceGrantId = originalEvidence.grantId()"));
        assertTrue(approval.indexOf("clearOriginalEvidence(")
            < approval.indexOf("runOperationalOperation("));

        String apply = between(
            activity,
            "private void applyLoadedOriginalEvidence(",
            "private void scheduleOriginalEvidenceExpiry("
        );
        assertTrue(apply.contains(
            "boolean currentRequest = requestGeneration == originalEvidenceGeneration"
        ));
        assertTrue(apply.contains("|| !currentRequest"));
        assertTrue(apply.contains("sessionGeneration != operationsSessionGeneration"));
        assertTrue(apply.contains("loaded.close()"));
        assertTrue(apply.contains("Arrays.fill(displayBytes, (byte) 0)"));
        assertTrue(apply.contains("loaded.discardEncodedImageAfterDisplay()"));

        String clear = between(
            activity,
            "private void clearOriginalEvidence(",
            "private static void destroyBitmap("
        );
        assertTrue(clear.contains("originalEvidenceLoadTask.cancel(true)"));
        assertTrue(clear.contains("originalEvidence.close()"));
        assertTrue(clear.contains("setImageDrawable(null)"));
        assertTrue(activity.contains("bitmap.eraseColor(Color.TRANSPARENT)"));
        assertTrue(activity.contains("bitmap.recycle()"));
        assertTrue(activity.contains("protected void onStop()"));
        assertTrue(between(activity, "protected void onStop()", "protected void onDestroy()")
            .contains("clearOriginalEvidence("));
        assertTrue(between(activity, "protected void onDestroy()", "protected void onActivityResult(")
            .contains("clearOriginalEvidence("));
        assertTrue(between(activity, "private void resetSessionBoundReportState()", "private String requireConnectedOperationsReportId()")
            .contains("clearOriginalEvidence("));
        assertTrue(between(activity, "private void loadReportDetail(", "private void retryReportRequest()")
            .contains("clearOriginalEvidence("));
        assertTrue(activity.contains(
            "if (reportChanged || contentRevisionChanged || reviewChanged)"
        ));

        assertTrue(originalEvidence.contains("Arrays.fill(bytes, (byte) 0)"));
        assertTrue(originalEvidence.contains("deviceId.equals(expectedDeviceId)"));
        assertTrue(originalEvidence.contains("expiresAtEpochMs > nowEpochMs"));
        assertTrue(operationsHttp.contains("connection.setInstanceFollowRedirects(false)"));
        assertTrue(operationsHttp.contains("connection.setUseCaches(false)"));
        assertTrue(operationsHttp.contains("Thread.currentThread().isInterrupted()"));
        assertFalse(originalEvidence.contains("SharedPreferences"));
        assertFalse(originalEvidence.contains("android.util.Log"));
        assertFalse(originalEvidence.contains("Clipboard"));
        assertFalse(activity.contains("outState.putString(\"originalEvidence"));
    }

    @Test
    public void originalEvidenceConfirmationIsReconciledAfterOperationsReenableTheForm() {
        String operation = between(
            activity,
            "private void runOperationalOperation(",
            "private String operationalResultMessage("
        );
        String render = between(
            activity,
            "private void render()",
            "private void renderDevices("
        );
        String confirmation = between(
            activity,
            "private void renderOriginalEvidenceConfirmationState()",
            "private static void destroyBitmap("
        );

        assertTrue(operation.contains("setInteractiveEnabled(contentRoot, true)"));
        assertTrue(operation.contains("render()"));
        assertTrue(render.contains("renderOriginalEvidenceConfirmationState()"));
        assertTrue(confirmation.contains("originalEvidence.matches("));
        assertTrue(confirmation.contains("connectedOperationsReportId"));
        assertTrue(confirmation.contains("connectedOperationsContentRevision"));
        assertTrue(confirmation.contains("boundOperationsSessionId"));
        assertTrue(confirmation.contains("originalEvidenceConfirmedInput.setChecked(false)"));
        assertTrue(confirmation.contains("originalEvidenceConfirmedInput.setEnabled(false)"));
        assertTrue(confirmation.contains("clearOriginalEvidence("));
        assertTrue(confirmation.contains(
            "originalEvidenceConfirmedInput.setEnabled(!operationInFlight)"
        ));
    }

    @Test
    public void everyInteractiveReenableRevalidatesOriginalEvidenceAtTheSharedBoundary() {
        String load = between(
            activity,
            "private void loadOriginalEvidence()",
            "private void applyLoadedOriginalEvidence("
        );
        String apply = between(
            activity,
            "private void applyLoadedOriginalEvidence(",
            "private void scheduleOriginalEvidenceExpiry("
        );
        String clear = between(
            activity,
            "private void clearOriginalEvidence(",
            "private void renderOriginalEvidenceConfirmationState()"
        );
        String operational = between(
            activity,
            "private void runOperationalOperation(",
            "private String operationalResultMessage("
        );
        String security = between(
            activity,
            "private void runSecurityOperation(",
            "private void render()"
        );
        String incident = between(
            activity,
            "private void runIncidentStatusUpdate(",
            "private void loadAudits("
        );
        String interactive = between(
            activity,
            "private void setInteractiveEnabled(",
            "private void rotateDeliveryRecordInputsAfterSuccess()"
        );

        assertTrue(occurrences(load, "setInteractiveEnabled(contentRoot, true)") == 1);
        assertTrue(occurrences(apply, "setInteractiveEnabled(contentRoot, true)") == 3);
        assertTrue(occurrences(clear, "setInteractiveEnabled(contentRoot, true)") == 1);
        assertTrue(occurrences(operational, "setInteractiveEnabled(contentRoot, true)") == 2);
        assertTrue(occurrences(security, "setInteractiveEnabled(contentRoot, true)") == 2);
        assertTrue(occurrences(incident, "setInteractiveEnabled(contentRoot, true)") == 3);
        assertTrue(occurrences(activity, "setInteractiveEnabled(contentRoot, true)") == 15);

        assertFalse(activity.contains("private static void setInteractiveEnabled("));
        assertTrue(interactive.contains("if (view == originalEvidenceConfirmedInput)"));
        assertTrue(interactive.contains("if (!enabled)"));
        assertTrue(interactive.contains("originalEvidenceConfirmedInput.setEnabled(false)"));
        assertTrue(interactive.contains("renderOriginalEvidenceConfirmationState()"));
        assertTrue(interactive.indexOf("if (view == originalEvidenceConfirmedInput)")
            < interactive.indexOf("view.setEnabled(enabled)"));
        assertFalse(activity.contains("originalEvidenceConfirmedInput.setEnabled(true)"));

        int evidenceAssignment = apply.indexOf("originalEvidence = loaded");
        int operationFinished = apply.indexOf("operationInFlight = false", evidenceAssignment);
        int formReenabled = apply.indexOf(
            "setInteractiveEnabled(contentRoot, true)",
            operationFinished
        );
        assertTrue(evidenceAssignment >= 0
            && evidenceAssignment < operationFinished
            && operationFinished < formReenabled);
    }

    @Test
    public void queuedOriginalEvidenceCredentialsAreClearedOnCancelAndExecution() {
        String holder = between(
            activity,
            "private static final class PendingOriginalEvidenceCredentials",
            "@Override\n    protected void onCreate("
        );
        String load = between(
            activity,
            "private void loadOriginalEvidence()",
            "private void applyLoadedOriginalEvidence("
        );
        String clear = between(
            activity,
            "private void clearOriginalEvidence(",
            "private void renderOriginalEvidenceConfirmationState()"
        );

        assertTrue(activity.contains("AtomicReference<PendingOriginalEvidenceCredentials>"));
        assertTrue(activity.contains(
            "pendingOriginalEvidenceCredentials = new AtomicReference<>()"
        ));
        assertTrue(load.contains("new PendingOriginalEvidenceCredentials(password, totp)"));
        assertTrue(load.contains("pendingOriginalEvidenceCredentials.set(requestCredentials)"));
        assertTrue(load.contains("requestCredentials.password()"));
        assertTrue(load.contains("requestCredentials.totp()"));
        assertTrue(load.contains("requestCredentials.clear()"));
        assertTrue(load.contains(
            "pendingOriginalEvidenceCredentials.compareAndSet(requestCredentials, null)"
        ));
        assertTrue(clear.contains("pendingOriginalEvidenceCredentials.getAndSet(null)"));
        assertTrue(clear.contains("pendingCredentials.clear()"));
        assertTrue(holder.contains("synchronized void clear()"));
        assertTrue(holder.contains("Arrays.fill(password, '\\0')"));
        assertTrue(holder.contains("Arrays.fill(totp, '\\0')"));
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
            "private void readDeliveries(boolean nextPage)"
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

    @Test
    public void reportHistoriesExposeMemoryOnlyPagingProgressAndExplicitRecovery() {
        assertTrue(occurrences(activity, "다음 이력 불러오기") >= 2);
        assertTrue(activity.contains("reviewHistoryNextCursor"));
        assertTrue(activity.contains("deliveryHistoryNextCursor"));
        assertTrue(activity.contains("reviewHistoryTotalCount"));
        assertTrue(activity.contains(".append(items.size()).append(\"/\").append(total)"));
        assertTrue(activity.contains("deliveryHistoryItems.size()"));
        assertTrue(activity.contains("첫 페이지부터 다시 조회해 주세요"));
        assertTrue(activity.contains("기존 이력과 다음 페이지 위치는 유지했습니다"));
        assertTrue(activity.contains("clearConnectedOperationsSelection()"));
        assertTrue(activity.contains("resetReportHistoryState()"));
        assertFalse(activity.contains("putString(\"reviewHistoryNextCursor"));
        assertFalse(activity.contains("putString(\"deliveryHistoryNextCursor"));
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

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count += 1;
            offset += needle.length();
        }
        return count;
    }
}
