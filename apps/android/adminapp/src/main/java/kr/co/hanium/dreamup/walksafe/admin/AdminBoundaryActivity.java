package kr.co.hanium.dreamup.walksafe.admin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.time.Instant;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyStore;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminAuditController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminAuditModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryPackage;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryPackageSaver;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminInstitutionDelivery;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentRepository;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsApi;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRequestController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRequestModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRepository;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportWorkflowController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRecoveryMessagePolicy;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRecoveryCustodyState;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportDecision;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminSecurityApi;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminSecurityController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminSecurityHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminSecurityState;

public final class AdminBoundaryActivity extends Activity {
    private static final String DEVICE_PREFS = "walksafe_admin_device_identity";
    private static final String DEVICE_ID_KEY = "device_id";
    private static final String REPORT_FILTER_ID_STATE = "admin_report_filter_id";
    private static final String REPORT_FILTER_STATUS_STATE = "admin_report_filter_status";
    private static final String REPORT_FILTER_CLASS_STATE = "admin_report_filter_class";
    private static final String REPORT_FILTER_FROM_STATE = "admin_report_filter_from";
    private static final String REPORT_FILTER_TO_STATE = "admin_report_filter_to";
    private static final String REPORT_SELECTED_ID_STATE = "admin_report_selected_id";
    private static final String REQUEST_FILTER_REPORT_STATE = "admin_request_filter_report";
    private static final String REQUEST_FILTER_TYPE_STATE = "admin_request_filter_type";
    private static final String REQUEST_FILTER_STATUS_STATE = "admin_request_filter_status";
    private static final String REQUEST_SELECTED_ID_STATE = "admin_request_selected_id";
    private static final String AUDIT_EVENT_TYPE_STATE = "admin_audit_event_type";
    private static final String AUDIT_ACTOR_STATE = "admin_audit_actor";
    private static final String INCIDENT_FILTER_STATUS_STATE = "admin_incident_filter_status";
    private static final String INCIDENT_SELECTED_ID_STATE = "admin_incident_selected_id";
    private static final int CREATE_DELIVERY_PACKAGE_DOCUMENT = 7_301;

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private AdminSecurityController controller;
    private AdminReportController reportController;
    private AdminReportRequestController reportRequestController;
    private AdminReportWorkflowController reportWorkflowController;
    private AdminAuditController auditController;
    private AdminIncidentController incidentController;
    private String deviceId;
    private AdminDeviceKeyStore.Descriptor deviceKeyDescriptor;
    private String deviceKeyFailure;
    private boolean operationsClientConfigured;

    private TextView statusText;
    private TextView metadataText;
    private TextView deviceKeyText;
    private TextView custodyText;
    private TextView resultText;
    private TextView operationalLockText;
    private EditText adminIdInput;
    private EditText passwordInput;
    private EditText totpInput;
    private EditText deviceLabelInput;
    private EditText recoveryCodeInput;
    private EditText newPasswordInput;
    private EditText newTotpInput;
    private Button loginButton;
    private LinearLayout recoveryStartGroup;
    private LinearLayout recoveryCompleteGroup;
    private LinearLayout sessionGroup;
    private LinearLayout sessionList;
    private LinearLayout custodyGroup;
    private Spinner custodyMaterialKindInput;
    private CheckBox custodyConfirmationInput;
    private LinearLayout operationsGroup;
    private AdminReportPanel reportPanel;
    private AdminReportRequestPanel reportRequestPanel;
    private AdminAuditPanel auditPanel;
    private AdminIncidentPanel incidentPanel;
    private EditText reportIdInput;
    private Spinner reviewDecisionInput;
    private EditText reviewReasonInput;
    private EditText reviewUserVisibleReasonInput;
    private EditText duplicateReportIdInput;
    private CheckBox locationReviewedInput;
    private CheckBox photoReviewedInput;
    private CheckBox privacyReviewedInput;
    private EditText institutionInput;
    private EditText deliveryChannelInput;
    private EditText deliveryRecipientInput;
    private Spinner deliveryStatusInput;
    private EditText externalReceiptInput;
    private EditText deliveryReasonInput;
    private EditText evidenceSha256Input;
    private EditText observedAtInput;
    private EditText expectedRevisionInput;
    private EditText packageRevisionInput;
    private EditText idempotencyKeyInput;
    private Button revokeCurrentButton;
    private LinearLayout contentRoot;
    private boolean operationInFlight;
    private AdminDeliveryPackage pendingDeliveryPackage;
    private AdminDeliveryPackageSaver.Saved verifiedDeliveryPackage;
    private boolean awaitingSafResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        deviceId = loadOrCreateDeviceId();
        if (BuildConfig.ADMIN_SECURITY_WORKFLOWS_ENABLED) {
            try {
                AdminDeviceKeyStore keyStore = new AdminDeviceKeyStore(this);
                deviceKeyDescriptor = keyStore.ensureKey();
                AdminSecurityHttpClient securityClient = new AdminSecurityHttpClient(
                    BuildConfig.WALKSAFE_ADMIN_API_ORIGIN,
                    BuildConfig.DEBUG,
                    deviceKeyDescriptor,
                    keyStore
                );
                AdminOperationsApi operationsApi = null;
                AdminReportRepository reportRepository = null;
                AdminIncidentRepository incidentRepository = null;
                if (BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED) {
                    operationsApi = new AdminOperationsHttpClient(
                        BuildConfig.WALKSAFE_ADMIN_API_ORIGIN,
                        BuildConfig.DEBUG,
                        deviceKeyDescriptor,
                        keyStore
                    );
                    reportRepository = new AdminReportHttpClient(
                        BuildConfig.WALKSAFE_ADMIN_API_ORIGIN,
                        BuildConfig.DEBUG,
                        deviceKeyDescriptor,
                        keyStore
                    );
                    incidentRepository = new AdminIncidentHttpClient(
                        BuildConfig.WALKSAFE_ADMIN_API_ORIGIN,
                        BuildConfig.DEBUG,
                        deviceKeyDescriptor,
                        keyStore
                    );
                    operationsClientConfigured = true;
                }
                controller = new AdminSecurityController(
                    securityClient,
                    operationsApi,
                    reportRepository,
                    incidentRepository
                );
                reportController = new AdminReportController(new AdminReportController.Loader() {
                    @Override
                    public AdminReportModels.Page loadPage(
                        AdminReportModels.Filters filters,
                        String cursor
                    ) throws Exception {
                        return controller.listAdminReports(
                            filters,
                            cursor,
                            BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                        );
                    }

                    @Override
                    public AdminReportModels.Detail loadDetail(String reportId) throws Exception {
                        return controller.getAdminReportDetail(
                            reportId,
                            BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                        );
                    }
                });
                reportRequestController = new AdminReportRequestController(
                    new AdminReportRequestController.Loader() {
                        @Override
                        public AdminReportRequestModels.Page loadPage(
                            AdminReportRequestModels.Filters filters,
                            String cursor
                        ) throws Exception {
                            return controller.listAdminReportRequests(
                                filters,
                                cursor,
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }

                        @Override
                        public AdminReportRequestModels.Detail loadDetail(String requestId)
                            throws Exception {
                            return controller.getAdminReportRequestDetail(
                                requestId,
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }

                        @Override
                        public AdminReportRequestModels.StatusSnapshot updateStatus(
                            String requestId,
                            String nextStatus,
                            int expectedVersion,
                            String publicResponse,
                            String internalNote,
                            char[] password,
                            char[] totp
                        ) throws Exception {
                            return controller.updateAdminReportRequestStatus(
                                requestId,
                                nextStatus,
                                expectedVersion,
                                publicResponse,
                                internalNote,
                                password,
                                totp,
                                System.currentTimeMillis(),
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }
                    }
                );
                reportWorkflowController = new AdminReportWorkflowController(
                    new AdminReportWorkflowController.Loader() {
                        @Override
                        public AdminReportModels.StatusSnapshot updateStatus(
                            String reportId,
                            String nextStatus,
                            int expectedVersion,
                            char[] password,
                            char[] totp
                        ) throws Exception {
                            return controller.updateAdminReportStatus(
                                reportId,
                                nextStatus,
                                expectedVersion,
                                password,
                                totp,
                                System.currentTimeMillis(),
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }

                        @Override
                        public AdminDeliveryPackage createPackage(
                            String reportId,
                            String password,
                            String totp
                        ) throws Exception {
                            return controller.createAdminDeliveryPackage(
                                reportId,
                                password,
                                totp,
                                System.currentTimeMillis(),
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }

                        @Override
                        public AdminReportModels.Detail refreshDetail(String reportId) throws Exception {
                            return controller.getAdminReportDetail(
                                reportId,
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }
                    }
                );
                auditController = new AdminAuditController((filters, cursor) ->
                    controller.listAdminAudits(
                        filters,
                        cursor,
                        BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    )
                );
                incidentController = new AdminIncidentController(
                    new AdminIncidentController.Loader() {
                        @Override
                        public AdminIncidentModels.Page loadPage(
                            AdminIncidentModels.Filters filters,
                            String cursor
                        ) throws Exception {
                            return controller.listAdminIncidents(
                                filters,
                                cursor,
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }

                        @Override
                        public AdminIncidentModels.Detail loadDetail(String incidentId)
                            throws Exception {
                            return controller.getAdminIncidentDetail(
                                incidentId,
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }
                    }
                );
            } catch (Exception error) {
                deviceKeyFailure = "키 사용 불가";
                operationsClientConfigured = false;
            }
        }
        setContentView(buildContent());
        render();
        restoreReportPanelState(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (reportPanel != null) {
            AdminReportPanel.FilterDraft draft = reportPanel.filterDraft();
            outState.putString(REPORT_FILTER_ID_STATE, draft.reportId());
            outState.putString(REPORT_FILTER_STATUS_STATE, draft.status());
            outState.putString(REPORT_FILTER_CLASS_STATE, draft.className());
            outState.putString(REPORT_FILTER_FROM_STATE, draft.createdFrom());
            outState.putString(REPORT_FILTER_TO_STATE, draft.createdTo());
            outState.putString(REPORT_SELECTED_ID_STATE, reportPanel.selectedReportId());
        }
        if (reportRequestPanel != null) {
            AdminReportRequestPanel.FilterDraft draft = reportRequestPanel.filterDraft();
            outState.putString(REQUEST_FILTER_REPORT_STATE, draft.reportId());
            outState.putString(REQUEST_FILTER_TYPE_STATE, draft.requestType());
            outState.putString(REQUEST_FILTER_STATUS_STATE, draft.status());
            outState.putString(REQUEST_SELECTED_ID_STATE, reportRequestPanel.selectedRequestId());
        }
        if (auditPanel != null) {
            outState.putString(AUDIT_EVENT_TYPE_STATE, auditPanel.eventType());
            outState.putString(AUDIT_ACTOR_STATE, auditPanel.actorId());
        }
        if (incidentPanel != null) {
            outState.putString(INCIDENT_FILTER_STATUS_STATE, incidentPanel.filterStatus());
            outState.putString(INCIDENT_SELECTED_ID_STATE, incidentPanel.selectedIncidentId());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        if (reportController != null) reportController.invalidate();
        if (reportRequestController != null) reportRequestController.invalidate();
        if (auditController != null) auditController.invalidate();
        if (incidentController != null) incidentController.invalidate();
        if (!awaitingSafResult && reportWorkflowController != null) {
            reportWorkflowController.invalidate();
        }
        if (reportPanel != null) reportPanel.clearHighRiskInputs();
        if (reportRequestPanel != null) reportRequestPanel.clearSensitiveInputs();
        if (incidentPanel != null) incidentPanel.clearSensitiveInputs();
        clearSensitiveInputs();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (reportRequestController != null) reportRequestController.invalidate();
        if (incidentController != null) incidentController.invalidate();
        if (pendingDeliveryPackage != null) pendingDeliveryPackage.destroy();
        pendingDeliveryPackage = null;
        verifiedDeliveryPackage = null;
        if (controller != null) networkExecutor.execute(controller::close);
        networkExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_DELIVERY_PACKAGE_DOCUMENT) return;
        awaitingSafResult = false;
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        AdminDeliveryPackage packageValue = pendingDeliveryPackage;
        pendingDeliveryPackage = null;
        if (uri == null || packageValue == null) {
            if (packageValue != null) packageValue.destroy();
            if (uri != null) deleteSafDocument(uri);
            reportWorkflowController.markSaveFailed(true);
            reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            return;
        }
        networkExecutor.execute(() -> {
            try {
                AdminDeliveryPackageSaver.Saved saved = AdminDeliveryPackageSaver.save(
                    packageValue,
                    safDestination(uri)
                );
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    verifiedDeliveryPackage = saved;
                    packageRevisionInput.setText(Integer.toString(saved.revision()));
                    reportWorkflowController.markSaved(saved.revision());
                    reportPanel.renderWorkflow(reportWorkflowController.snapshot());
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    reportWorkflowController.markSaveFailed(false);
                    reportPanel.renderWorkflow(reportWorkflowController.snapshot());
                });
            }
        });
    }

    private View buildContent() {
        LinearLayout content = new LinearLayout(this);
        contentRoot = content;
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(48, 48, 48, 48);
        content.setBackgroundColor(Color.WHITE);
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);

        TextView title = text("워크세이프 관리자", 24);
        title.setGravity(Gravity.CENTER);
        content.addView(title, matchWrap());

        statusText = text("", 19);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 28, 0, 8);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(statusText, matchWrap());

        metadataText = text("", 14);
        metadataText.setGravity(Gravity.CENTER);
        content.addView(metadataText, matchWrap());

        custodyText = text("", 15);
        custodyText.setGravity(Gravity.CENTER);
        custodyText.setPadding(0, 8, 0, 0);
        custodyText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(custodyText, matchWrap());

        deviceKeyText = text("", 13);
        deviceKeyText.setGravity(Gravity.CENTER);
        deviceKeyText.setPadding(0, 8, 0, 0);
        deviceKeyText.setTextIsSelectable(true);
        content.addView(deviceKeyText, matchWrap());

        resultText = text("", 16);
        resultText.setPadding(0, 20, 0, 20);
        resultText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        content.addView(resultText, matchWrap());

        deviceLabelInput = input("이 기기의 알아보기 쉬운 이름", InputType.TYPE_CLASS_TEXT, false);
        deviceLabelInput.setText(defaultDeviceLabel());
        content.addView(deviceLabelInput, matchWrap());

        adminIdInput = input("관리자 ID", InputType.TYPE_CLASS_TEXT, false);
        passwordInput = input(
            "비밀번호",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            true
        );
        totpInput = input(
            "6자리 추가 인증 코드",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            true
        );
        content.addView(adminIdInput, matchWrap());
        content.addView(passwordInput, matchWrap());
        content.addView(totpInput, matchWrap());

        loginButton = button("비밀번호와 추가 인증으로 로그인");
        loginButton.setOnClickListener(view -> login());
        content.addView(loginButton, matchWrap());

        recoveryStartGroup = group();
        recoveryStartGroup.addView(text("휴대전화 밖에 보관한 복구코드만 사용합니다.", 16), matchWrap());
        recoveryCodeInput = input(
            "외부 보관 복구코드",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            true
        );
        recoveryStartGroup.addView(recoveryCodeInput, matchWrap());
        Button startRecovery = button("분실 복구 시작 및 기존 세션 폐기");
        startRecovery.setOnClickListener(view -> startRecovery());
        recoveryStartGroup.addView(startRecovery, matchWrap());
        content.addView(recoveryStartGroup, matchWrap());

        recoveryCompleteGroup = group();
        recoveryCompleteGroup.addView(text("별도 관리 경로에서 교체한 비밀번호와 새 TOTP 코드를 확인합니다.", 16), matchWrap());
        newPasswordInput = input(
            "새 비밀번호",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            true
        );
        newTotpInput = input(
            "새 TOTP secret의 6자리 코드",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            true
        );
        recoveryCompleteGroup.addView(newPasswordInput, matchWrap());
        recoveryCompleteGroup.addView(newTotpInput, matchWrap());
        Button completeRecovery = button("복구 완료");
        completeRecovery.setOnClickListener(view -> completeRecovery());
        recoveryCompleteGroup.addView(completeRecovery, matchWrap());
        content.addView(recoveryCompleteGroup, matchWrap());

        custodyGroup = group();
        custodyGroup.addView(text(
            "서버 키, 앱 서명 키, 관리자 복구자료를 서로 분리해 각각 암호화 백업했고, 같은 저장공간이나 계정에 키를 함께 두지 않은 경우에만 확인하세요. 관리자 복구자료는 이 휴대전화 밖에 있어야 합니다. 앱은 복구자료 원문을 저장하거나 전송하지 않습니다.",
            16
        ), matchWrap());
        custodyMaterialKindInput = enumSpinner(AdminSecurityApi.RecoveryMaterialKind.values());
        custodyGroup.addView(custodyMaterialKindInput, matchWrap());
        custodyConfirmationInput = checkBox(
            "서버 키·앱 서명 키·관리자 복구자료를 서로 분리해 각각 암호화 백업했고, 같은 저장공간이나 계정에 키를 함께 두지 않았음을 직접 확인했습니다."
        );
        custodyGroup.addView(custodyConfirmationInput, matchWrap());
        Button attestCustody = button("복구자료 외부 보관 확인 기록");
        attestCustody.setOnClickListener(view -> attestRecoveryCustody());
        custodyGroup.addView(attestCustody, matchWrap());
        content.addView(custodyGroup, matchWrap());

        sessionGroup = group();
        Button refreshButton = button("서버 보안상태와 기기 세션 새로고침");
        refreshButton.setOnClickListener(view -> refresh());
        sessionGroup.addView(refreshButton, matchWrap());
        revokeCurrentButton = button("로그아웃");
        revokeCurrentButton.setOnClickListener(view -> revokeCurrentSession());
        sessionGroup.addView(revokeCurrentButton, matchWrap());
        sessionList = group();
        sessionGroup.addView(sessionList, matchWrap());
        content.addView(sessionGroup, matchWrap());

        operationsGroup = buildOperationsGroup();
        content.addView(operationsGroup, matchWrap());

        operationalLockText = text("", 16);
        operationalLockText.setPadding(0, 28, 0, 0);
        content.addView(operationalLockText, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, matchWrap());
        return scroll;
    }

    private LinearLayout buildOperationsGroup() {
        LinearLayout group = group();
        TextView heading = text("신고 검토 및 수동 기관 전달 기록", 20);
        heading.setPadding(0, 36, 0, 8);
        group.addView(heading, matchWrap());
        group.addView(text(
            "이 화면은 이미 사람이 수행한 기관 전달 사실만 내부 서버에 기록합니다. 기관으로 직접 전송하지 않습니다.",
            15
        ), matchWrap());

        reportPanel = new AdminReportPanel(this, new AdminReportPanel.Listener() {
            @Override
            public void onApplyFilters(AdminReportPanel.FilterDraft filters) {
                loadFirstReportPage(filters);
            }

            @Override
            public void onLoadMore() {
                loadNextReportPage();
            }

            @Override
            public void onOpenDetail(String reportId) {
                loadReportDetail(reportId);
            }

            @Override
            public void onRetry() {
                retryReportRequest();
            }

            @Override
            public void onUseInOperations(AdminReportModels.Detail detail) {
                connectReportToOperations(detail);
            }

            @Override
            public void onUpdateStatus(
                AdminReportModels.Detail detail,
                String nextStatus,
                char[] password,
                char[] totp
            ) {
                runStatusUpdate(detail, nextStatus, password, totp);
            }

            @Override
            public void onCreateDeliveryPackage(
                AdminReportModels.Detail detail,
                char[] password,
                char[] totp
            ) {
                runDeliveryPackageCreation(detail, password, totp);
            }
        });
        group.addView(reportPanel, matchWrap());

        reportRequestPanel = new AdminReportRequestPanel(
            this,
            new AdminReportRequestPanel.Listener() {
                @Override
                public void onApplyFilters(AdminReportRequestPanel.FilterDraft filters) {
                    loadFirstReportRequestPage(filters);
                }

                @Override
                public void onLoadMore() {
                    loadNextReportRequestPage();
                }

                @Override
                public void onOpenDetail(String requestId) {
                    loadReportRequestDetail(requestId);
                }

                @Override
                public void onRetry() {
                    retryReportRequestRead();
                }

                @Override
                public void onUpdateStatus(
                    AdminReportRequestModels.Detail detail,
                    String nextStatus,
                    String publicResponse,
                    String internalNote,
                    char[] password,
                    char[] totp
                ) {
                    runReportRequestStatusUpdate(
                        detail,
                        nextStatus,
                        publicResponse,
                        internalNote,
                        password,
                        totp
                    );
                }
            }
        );
        group.addView(reportRequestPanel, matchWrap());

        incidentPanel = new AdminIncidentPanel(this, new AdminIncidentPanel.Listener() {
            @Override
            public void onApplyStatus(String status) {
                loadFirstIncidentPage(status);
            }

            @Override
            public void onLoadMore() {
                loadNextIncidentPage();
            }

            @Override
            public void onOpenDetail(String incidentId) {
                loadIncidentDetail(incidentId);
            }

            @Override
            public void onRetry() {
                retryIncidentRequest();
            }

            @Override
            public void onUpdateStatus(
                AdminIncidentModels.Detail detail,
                String nextState,
                String reason,
                String observation,
                String evidenceSha256,
                char[] password,
                char[] totp
            ) {
                runIncidentStatusUpdate(
                    detail,
                    nextState,
                    reason,
                    observation,
                    evidenceSha256,
                    password,
                    totp
                );
            }
        });
        group.addView(incidentPanel, matchWrap());

        auditPanel = new AdminAuditPanel(this, new AdminAuditPanel.Listener() {
            @Override
            public void onLoad(String eventType, String actorId) {
                loadAudits(eventType, actorId);
            }

            @Override
            public void onLoadMore() {
                loadMoreAudits();
            }

            @Override
            public void onRetry() {
                retryAudits();
            }
        });
        group.addView(auditPanel, matchWrap());

        reportIdInput = input("신고 UUID", InputType.TYPE_CLASS_TEXT, false);
        group.addView(reportIdInput, matchWrap());

        TextView reviewHeading = text("검토 결정 (세 검토 항목을 모두 확인해야 저장됩니다)", 17);
        reviewHeading.setPadding(0, 24, 0, 4);
        group.addView(reviewHeading, matchWrap());
        reviewDecisionInput = enumSpinner(AdminReportDecision.Decision.values());
        group.addView(reviewDecisionInput, matchWrap());
        reviewReasonInput = input("내부 검토 사유 (사용자 비공개)", InputType.TYPE_CLASS_TEXT, false);
        reviewUserVisibleReasonInput = input(
            "사용자에게 보여줄 사유 (REJECTED/DUPLICATE 필수)",
            InputType.TYPE_CLASS_TEXT,
            false
        );
        duplicateReportIdInput = input("중복 대상 신고 UUID (DUPLICATE일 때만)", InputType.TYPE_CLASS_TEXT, false);
        group.addView(reviewReasonInput, matchWrap());
        group.addView(reviewUserVisibleReasonInput, matchWrap());
        group.addView(duplicateReportIdInput, matchWrap());
        locationReviewedInput = checkBox("위치 검토 완료");
        photoReviewedInput = checkBox("사진 검토 완료");
        privacyReviewedInput = checkBox("개인정보 검토 완료");
        group.addView(locationReviewedInput, matchWrap());
        group.addView(photoReviewedInput, matchWrap());
        group.addView(privacyReviewedInput, matchWrap());
        Button reviewButton = button("검토 결정 기록");
        reviewButton.setOnClickListener(view -> recordReviewDecision());
        group.addView(reviewButton, matchWrap());
        Button reviewHistoryButton = button("검토 결정 이력 확인");
        reviewHistoryButton.setOnClickListener(view -> readReviewDecisions());
        group.addView(reviewHistoryButton, matchWrap());

        TextView deliveryHeading = text("수동 기관 전달 기록", 17);
        deliveryHeading.setPadding(0, 28, 0, 4);
        group.addView(deliveryHeading, matchWrap());
        institutionInput = input("기관", InputType.TYPE_CLASS_TEXT, false);
        deliveryChannelInput = input("수동 전달 채널 (예: 전화, 공문)", InputType.TYPE_CLASS_TEXT, false);
        deliveryRecipientInput = input("수신 부서 또는 담당자", InputType.TYPE_CLASS_TEXT, false);
        group.addView(institutionInput, matchWrap());
        group.addView(deliveryChannelInput, matchWrap());
        group.addView(deliveryRecipientInput, matchWrap());
        deliveryStatusInput = enumSpinner(AdminInstitutionDelivery.Status.values());
        group.addView(deliveryStatusInput, matchWrap());
        externalReceiptInput = input(
            "외부 접수번호 (ACKNOWLEDGED/RESOLVED 필수)",
            InputType.TYPE_CLASS_TEXT,
            false
        );
        deliveryReasonInput = input("기록 사유", InputType.TYPE_CLASS_TEXT, false);
        evidenceSha256Input = input("증빙 SHA-256 소문자 64자 (없으면 비움)", InputType.TYPE_CLASS_TEXT, false);
        observedAtInput = input("실제 관찰시각 (RFC3339 UTC)", InputType.TYPE_CLASS_DATETIME, false);
        observedAtInput.setText(Instant.now().toString());
        expectedRevisionInput = input("예상 최신 revision", InputType.TYPE_CLASS_NUMBER, false);
        expectedRevisionInput.setText("0");
        packageRevisionInput = input("저장 검증된 제출본 revision", InputType.TYPE_CLASS_NUMBER, false);
        idempotencyKeyInput = input("멱등 UUID", InputType.TYPE_CLASS_TEXT, false);
        idempotencyKeyInput.setText(UUID.randomUUID().toString());
        group.addView(externalReceiptInput, matchWrap());
        group.addView(deliveryReasonInput, matchWrap());
        group.addView(evidenceSha256Input, matchWrap());
        group.addView(observedAtInput, matchWrap());
        group.addView(packageRevisionInput, matchWrap());
        group.addView(expectedRevisionInput, matchWrap());
        group.addView(idempotencyKeyInput, matchWrap());
        Button deliveryButton = button("이미 수행한 수동 전달 사실 기록");
        deliveryButton.setOnClickListener(view -> recordDelivery());
        group.addView(deliveryButton, matchWrap());
        Button deliveryHistoryButton = button("수동 전달 상태 이력 확인");
        deliveryHistoryButton.setOnClickListener(view -> readDeliveries());
        group.addView(deliveryHistoryButton, matchWrap());
        return group;
    }

    private void recordReviewDecision() {
        try {
            String reportId = normalized(reportIdInput);
            AdminReportDecision decision = new AdminReportDecision(
                AdminReportDecision.Decision.valueOf(reviewDecisionInput.getSelectedItem().toString()),
                normalized(reviewReasonInput),
                nullableNormalized(reviewUserVisibleReasonInput),
                nullableNormalized(duplicateReportIdInput),
                locationReviewedInput.isChecked(),
                photoReviewedInput.isChecked(),
                privacyReviewedInput.isChecked()
            );
            runOperationalOperation("검토 결정을 기록하고 있습니다.", () ->
                controller.recordReviewDecision(
                    reportId,
                    decision,
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                )
            );
        } catch (IllegalArgumentException error) {
            resultText.setText("검토 결정 입력값을 다시 확인해 주세요.");
        }
    }

    private void readReviewDecisions() {
        String reportId = normalized(reportIdInput);
        runOperationalOperation("검토 결정 이력을 확인하고 있습니다.", () ->
            controller.readReviewDecisions(reportId, BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED)
        );
    }

    private void recordDelivery() {
        try {
            String reportId = normalized(reportIdInput);
            String revision = normalized(expectedRevisionInput);
            String packageRevision = normalized(packageRevisionInput);
            if (!revision.matches("[0-9]{1,18}")) throw new IllegalArgumentException("invalid revision");
            if (!packageRevision.matches("[1-9][0-9]{0,17}")) {
                throw new IllegalArgumentException("invalid package revision");
            }
            long parsedPackageRevision = Long.parseLong(packageRevision);
            if (verifiedDeliveryPackage == null
                || !verifiedDeliveryPackage.matchesDelivery(reportId, parsedPackageRevision)) {
                throw new IllegalArgumentException("delivery package does not match report and revision");
            }
            AdminInstitutionDelivery delivery = new AdminInstitutionDelivery(
                normalized(institutionInput),
                normalized(deliveryChannelInput),
                normalized(deliveryRecipientInput),
                AdminInstitutionDelivery.Status.valueOf(deliveryStatusInput.getSelectedItem().toString()),
                nullableNormalized(externalReceiptInput),
                normalized(deliveryReasonInput),
                nullableNormalized(evidenceSha256Input),
                normalized(observedAtInput),
                parsedPackageRevision,
                Long.parseLong(revision),
                normalized(idempotencyKeyInput)
            );
            runOperationalOperation("수동 전달 사실을 내부 기록하고 있습니다.", () ->
                controller.recordDelivery(
                    reportId,
                    delivery,
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                )
            );
        } catch (IllegalArgumentException error) {
            resultText.setText("수동 전달 기록 입력값을 다시 확인해 주세요.");
        }
    }

    private void readDeliveries() {
        String reportId = normalized(reportIdInput);
        runOperationalOperation("수동 전달 상태 이력을 확인하고 있습니다.", () ->
            controller.readDeliveries(reportId, BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED)
        );
    }

    private void runOperationalOperation(String pendingMessage, OperationalOperation operation) {
        if (controller == null || operationInFlight) return;
        operationInFlight = true;
        setInteractiveEnabled(contentRoot, false);
        resultText.setText(pendingMessage);
        networkExecutor.execute(() -> {
            try {
                AdminOperationsApi.Result result = operation.run();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    resultText.setText(operationalResultMessage(result));
                    render();
                    if (result.kind() == AdminOperationsApi.ResultKind.MUTATION) {
                        refreshConnectedReportDetail();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    resultText.setText("관리자 내부 업무를 완료하지 못했습니다. 입력과 서버 상태를 확인해 주세요.");
                    render();
                });
            }
        });
    }

    private String operationalResultMessage(AdminOperationsApi.Result result) {
        if (result.kind() == AdminOperationsApi.ResultKind.REVIEW_HISTORY) {
            StringBuilder message = new StringBuilder("검토 결정 이력 ")
                .append(result.reviewHistory().size()).append("건");
            for (AdminOperationsApi.ReviewHistoryItem item : result.reviewHistory()) {
                message.append("\n\nrevision ").append(item.revision())
                    .append(" / ").append(item.decision())
                    .append("\n결정시각: ").append(item.decidedAt())
                    .append("\n내부 사유: ").append(item.reason())
                    .append("\n사용자 공개 사유: ")
                    .append(item.userVisibleReason() == null ? "없음" : item.userVisibleReason());
                if (item.duplicateOfReportId() != null) {
                    message.append("\n중복 대상: ").append(item.duplicateOfReportId());
                }
            }
            return message.append("\n\ncorrelation: ").append(result.correlationId()).toString();
        }
        if (result.kind() == AdminOperationsApi.ResultKind.DELIVERY_HISTORY) {
            StringBuilder message = new StringBuilder("수동 기관 전달 상태 이력 ")
                .append(result.deliveryHistory().size()).append("건");
            for (AdminOperationsApi.DeliveryHistoryItem item : result.deliveryHistory()) {
                message.append("\n\nrevision ").append(item.revision())
                    .append(" / status ").append(item.status())
                    .append(" / package revision ").append(item.packageRevision())
                    .append("\n기관: ").append(item.institution())
                    .append("\n관찰시각: ").append(item.observedAt())
                    .append("\n서버 기록시각: ").append(item.recordedAt())
                    .append("\n외부 접수번호: ")
                    .append(item.externalReceiptId() == null ? "없음" : item.externalReceiptId());
            }
            return message.append("\n\ncorrelation: ").append(result.correlationId()).toString();
        }
        return getString(
            R.string.admin_operation_result,
            result.statusCode(),
            result.correlationId()
        );
    }

    private void login() {
        String adminId = adminIdInput.getText().toString();
        String password = passwordInput.getText().toString();
        String totp = totpInput.getText().toString();
        String deviceLabel = deviceLabelInput.getText().toString();
        clearSensitiveInputs();
        runSecurityOperation("로그인 정보를 확인하고 있습니다.", "관리자 로그인이 확인되었습니다.",
            AdminRecoveryMessagePolicy.Phase.GENERAL, () ->
            controller.login(adminId, password, totp, deviceId, deviceLabel)
        );
    }

    private void refresh() {
        runSecurityOperation("서버 보안상태를 확인하고 있습니다.", "서버 보안상태를 갱신했습니다.",
            AdminRecoveryMessagePolicy.Phase.GENERAL, controller::refresh);
    }

    private void startRecovery() {
        String adminId = adminIdInput.getText().toString();
        String recoveryCode = recoveryCodeInput.getText().toString();
        String deviceLabel = deviceLabelInput.getText().toString();
        clearSensitiveInputs();
        runSecurityOperation("기존 세션을 폐기하고 복구를 시작하고 있습니다.", "복구가 시작되었습니다. 운영 업무는 계속 잠겨 있습니다.",
            AdminRecoveryMessagePolicy.Phase.RECOVERY_START, () ->
            controller.startRecovery(adminId, recoveryCode, deviceId, deviceLabel)
        );
    }

    private void completeRecovery() {
        String newPassword = newPasswordInput.getText().toString();
        String newTotp = newTotpInput.getText().toString();
        String deviceLabel = deviceLabelInput.getText().toString();
        clearSensitiveInputs();
        runSecurityOperation("새 인증수단을 확인하고 있습니다.", "관리자 접근 복구가 완료되었습니다.",
            AdminRecoveryMessagePolicy.Phase.RECOVERY_COMPLETE, () ->
            controller.completeRecovery(newPassword, newTotp, deviceId, deviceLabel)
        );
    }

    private void attestRecoveryCustody() {
        if (!custodyConfirmationInput.isChecked()) {
            resultText.setText("세 키의 개별 암호화 백업과 저장공간·계정 분리를 확인한 뒤 확인란을 선택해 주세요.");
            return;
        }
        AdminSecurityApi.RecoveryMaterialKind materialKind =
            AdminSecurityApi.RecoveryMaterialKind.valueOf(
                custodyMaterialKindInput.getSelectedItem().toString()
            );
        custodyConfirmationInput.setChecked(false);
        runSecurityOperation(
            "복구자료 보관 확인 상태를 갱신하고 있습니다.",
            "복구자료가 휴대전화 밖에 분리 보관된 상태를 확인했습니다.",
            AdminRecoveryMessagePolicy.Phase.CUSTODY_ATTESTATION,
            () -> controller.attestRecoveryCustody(materialKind)
        );
    }

    private void revokeCurrentSession() {
        String sessionId = controller.snapshot().currentSessionId();
        if (sessionId == null) {
            resultText.setText("로그아웃할 현재 세션을 확인할 수 없습니다.");
            return;
        }
        revokeSession(sessionId, true);
    }

    private void revokeSession(String sessionId, boolean currentSession) {
        runSecurityOperation(
            currentSession
                ? "서버에서 현재 세션을 폐기하고 로그아웃하고 있습니다."
                : "선택한 기기 세션을 폐기하고 있습니다.",
            currentSession ? "로그아웃했습니다." : "기기 세션을 폐기했습니다.",
            AdminRecoveryMessagePolicy.Phase.GENERAL, () ->
            controller.revokeSession(sessionId)
        );
    }

    private void reportLostDevice(String lostDeviceId) {
        runSecurityOperation(
            "분실 기기의 모든 세션과 장치 키를 폐기하고 있습니다.",
            "분실 기기의 모든 세션과 장치 키를 폐기했습니다.",
            AdminRecoveryMessagePolicy.Phase.LOST_DEVICE_REPORT,
            () -> controller.reportLostDevice(lostDeviceId)
        );
    }

    private void runSecurityOperation(
        String pendingMessage,
        String successMessage,
        AdminRecoveryMessagePolicy.Phase phase,
        SecurityOperation operation
    ) {
        if (controller == null) return;
        if (operationInFlight) return;
        operationInFlight = true;
        setInteractiveEnabled(contentRoot, false);
        resultText.setText(pendingMessage);
        networkExecutor.execute(() -> {
            try {
                operation.run();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    resultText.setText(successMessage);
                    render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    resultText.setText(AdminRecoveryMessagePolicy.failureMessage(phase, error));
                    render();
                });
            }
        });
    }

    private void render() {
        if (deviceKeyDescriptor == null) {
            deviceKeyText.setText(getString(
                R.string.admin_device_key_state,
                valueOrPending(deviceKeyFailure)
            ));
        } else {
            deviceKeyText.setText(getString(
                R.string.admin_device_registration_descriptor,
                deviceKeyDescriptor.registrationDescriptor(deviceId)
            ));
        }
        if (controller == null) {
            statusText.setText("관리자 보안 기능은 잠겨 있습니다.");
            metadataText.setText(BuildConfig.ADMIN_WORKFLOW_STATE);
            custodyText.setText("복구자료 외부 보관 상태를 확인할 수 없습니다.");
            hideInteractiveGroups();
            operationsGroup.setVisibility(View.GONE);
            operationalLockText.setText("인증·복구·감사 기능을 승인하기 전에는 어떤 관리자 업무도 수행할 수 없습니다.");
            return;
        }

        AdminSecurityController.Snapshot snapshot = controller.snapshot();
        statusText.setText(stateLabel(snapshot.securityState()));
        metadataText.setText(getString(
            R.string.admin_state_metadata,
            valueOrPending(snapshot.stateVersion()),
            valueOrPending(snapshot.observedAt())
        ));
        custodyText.setText(custodyLabel(snapshot));

        boolean accessActive = snapshot.isAccessSessionActive();
        boolean recoveryActive = snapshot.isRecoveryActive();
        boolean custodyAttested =
            snapshot.recoveryCustodyState() == AdminRecoveryCustodyState.ATTESTED;
        loginButton.setVisibility(!accessActive && !recoveryActive ? View.VISIBLE : View.GONE);
        recoveryStartGroup.setVisibility(
            !recoveryActive && snapshot.securityState() != AdminSecurityState.NORMAL ? View.VISIBLE : View.GONE
        );
        recoveryCompleteGroup.setVisibility(recoveryActive ? View.VISIBLE : View.GONE);
        custodyGroup.setVisibility(
            accessActive && snapshot.securityState() == AdminSecurityState.NORMAL && !custodyAttested
                ? View.VISIBLE
                : View.GONE
        );
        sessionGroup.setVisibility(accessActive ? View.VISIBLE : View.GONE);
        revokeCurrentButton.setEnabled(snapshot.currentSessionId() != null);
        renderDevices(snapshot.sessions(), snapshot.devices());

        boolean operationsVisible = BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
            && operationsClientConfigured
            && accessActive
            && snapshot.securityState() == AdminSecurityState.NORMAL
            && custodyAttested;
        operationsGroup.setVisibility(operationsVisible ? View.VISIBLE : View.GONE);
        if (reportPanel != null && reportController != null) {
            reportPanel.render(reportController.snapshot());
            if (reportWorkflowController != null) {
                reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            }
        }
        if (reportRequestPanel != null && reportRequestController != null) {
            reportRequestPanel.render(reportRequestController.snapshot());
        }
        if (auditPanel != null && auditController != null) {
            auditPanel.render(auditController.snapshot());
        }
        if (incidentPanel != null && incidentController != null) {
            incidentPanel.render(incidentController.snapshot());
        }
        if (!BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED) {
            operationalLockText.setText(R.string.admin_operations_default_locked);
        } else if (!operationsClientConfigured) {
            operationalLockText.setText("장치 서명 키를 검증할 수 없어 관리자 운영 업무를 잠갔습니다.");
        } else if (!custodyAttested) {
            operationalLockText.setText(
                "복구자료의 휴대전화 밖 보관이 확인되지 않아 고위험 작업과 관리자 운영 업무를 잠갔습니다."
            );
        } else if (!operationsVisible) {
            operationalLockText.setText("정상 관리자 세션과 추가 인증을 완료하면 내부 운영 업무를 사용할 수 있습니다.");
        } else {
            operationalLockText.setText(
                "내부 운영 기능이 열렸습니다. 기관 전달은 수동 기록만 가능하며 앱은 외부 전송을 수행하지 않습니다."
            );
        }
    }

    private void renderDevices(
        java.util.List<AdminSecurityApi.SessionInfo> sessions,
        java.util.List<AdminSecurityApi.DeviceInfo> devices
    ) {
        sessionList.removeAllViews();
        if (sessions.isEmpty()) {
            sessionList.addView(text("활성 기기 세션이 없습니다.", 15), matchWrap());
        }
        Set<String> activeSessionDevices = new HashSet<>();
        for (AdminSecurityApi.SessionInfo session : sessions) {
            if (!session.isRevoked()) activeSessionDevices.add(session.deviceId());
            TextView description = text(
                session.deviceLabel() + (session.isCurrent() ? " (현재 기기)" : "")
                    + "\n최근 사용: " + session.lastSeenAt()
                    + (session.isRevoked() ? "\n폐기됨" : ""),
                15
            );
            description.setPadding(0, 20, 0, 4);
            sessionList.addView(description, matchWrap());
            Button revoke = button(session.isCurrent() ? "로그아웃" : "이 기기 세션 폐기");
            revoke.setEnabled(!session.isRevoked());
            revoke.setOnClickListener(view -> revokeSession(session.sessionId(), session.isCurrent()));
            sessionList.addView(revoke, matchWrap());
        }
        for (AdminSecurityApi.DeviceInfo device : devices) {
            boolean keyOnly = !activeSessionDevices.contains(device.deviceId());
            TextView description = text(
                (keyOnly ? "활성 장치 키만 남은 기기" : "활성 장치 키가 있는 기기")
                    + (device.isCurrent() ? " (현재 기기)" : "")
                    + "\n기기 ID: " + device.deviceId(),
                15
            );
            description.setPadding(0, 20, 0, 4);
            sessionList.addView(description, matchWrap());
            if (!device.isCurrent()) {
                Button reportLost = button("이 기기를 분실 신고하고 모든 세션·키 폐기");
                reportLost.setOnClickListener(view -> reportLostDevice(device.deviceId()));
                sessionList.addView(reportLost, matchWrap());
            }
        }
    }

    private void hideInteractiveGroups() {
        loginButton.setVisibility(View.GONE);
        recoveryStartGroup.setVisibility(View.GONE);
        recoveryCompleteGroup.setVisibility(View.GONE);
        custodyGroup.setVisibility(View.GONE);
        sessionGroup.setVisibility(View.GONE);
        operationsGroup.setVisibility(View.GONE);
    }

    private void clearSensitiveInputs() {
        clear(passwordInput);
        clear(totpInput);
        clear(recoveryCodeInput);
        clear(newPasswordInput);
        clear(newTotpInput);
    }

    private void loadFirstReportPage(AdminReportPanel.FilterDraft draft) {
        if (reportController == null) return;
        try {
            AdminReportModels.Filters filters = new AdminReportModels.Filters(
                draft.reportId(),
                draft.status(),
                draft.className(),
                draft.createdFrom(),
                draft.createdTo()
            );
            executeReportRequest(reportController.beginFirstPage(filters));
        } catch (IllegalArgumentException error) {
            resultText.setText("신고 ID, 상태, 유형, 생성시각 조건을 다시 확인해 주세요.");
        }
    }

    private void loadNextReportPage() {
        if (reportController == null) return;
        try {
            executeReportRequest(reportController.beginNextPage());
        } catch (IllegalStateException error) {
            resultText.setText("불러올 다음 신고 페이지가 없습니다.");
        }
    }

    private void loadReportDetail(String reportId) {
        if (reportController == null) return;
        try {
            executeReportRequest(reportController.beginDetail(reportId));
        } catch (IllegalArgumentException error) {
            resultText.setText("상세를 확인할 신고 UUID가 올바르지 않습니다.");
        }
    }

    private void retryReportRequest() {
        if (reportController == null) return;
        try {
            executeReportRequest(reportController.beginRetry());
        } catch (IllegalStateException error) {
            resultText.setText("다시 시도할 신고 요청이 없습니다.");
        }
    }

    private void executeReportRequest(AdminReportController.Request request) {
        reportPanel.render(reportController.snapshot());
        networkExecutor.execute(() -> {
            boolean applied = reportController.execute(request);
            runOnUiThread(() -> {
                if (isDestroyed() || !applied) return;
                reportPanel.render(reportController.snapshot());
            });
        });
    }

    private void loadFirstReportRequestPage(AdminReportRequestPanel.FilterDraft draft) {
        if (reportRequestController == null) return;
        try {
            executeReportRequestRead(reportRequestController.beginFirstPage(
                new AdminReportRequestModels.Filters(
                    draft.reportId(),
                    draft.requestType(),
                    draft.status()
                )
            ));
        } catch (IllegalArgumentException | IllegalStateException error) {
            resultText.setText("신고 ID, 요청 유형, 처리 상태 조건을 다시 확인해 주세요.");
        }
    }

    private void loadNextReportRequestPage() {
        if (reportRequestController == null) return;
        try {
            executeReportRequestRead(reportRequestController.beginNextPage());
        } catch (IllegalStateException error) {
            resultText.setText("불러올 다음 사용자 요청 페이지가 없습니다.");
        }
    }

    private void loadReportRequestDetail(String requestId) {
        if (reportRequestController == null) return;
        try {
            executeReportRequestRead(reportRequestController.beginDetail(requestId));
        } catch (IllegalArgumentException | IllegalStateException error) {
            resultText.setText("상세를 확인할 사용자 요청 UUID가 올바르지 않습니다.");
        }
    }

    private void retryReportRequestRead() {
        if (reportRequestController == null) return;
        try {
            executeReportRequestRead(reportRequestController.beginRetry());
        } catch (IllegalStateException error) {
            resultText.setText("다시 시도할 사용자 요청 조회가 없습니다.");
        }
    }

    private void executeReportRequestRead(AdminReportRequestController.Request request) {
        reportRequestPanel.render(reportRequestController.snapshot());
        networkExecutor.execute(() -> {
            boolean applied = reportRequestController.execute(request);
            runOnUiThread(() -> {
                if (isDestroyed() || !applied) return;
                reportRequestPanel.render(reportRequestController.snapshot());
            });
        });
    }

    private void runReportRequestStatusUpdate(
        AdminReportRequestModels.Detail detail,
        String nextStatus,
        String publicResponse,
        String internalNote,
        char[] password,
        char[] totp
    ) {
        try {
            AdminReportRequestController.Request request =
                reportRequestController.beginStatusUpdate(
                    detail.summary().requestId(),
                    nextStatus,
                    detail.summary().statusVersion(),
                    publicResponse,
                    internalNote,
                    password,
                    totp
                );
            reportRequestPanel.render(reportRequestController.snapshot());
            networkExecutor.execute(() -> {
                boolean applied = reportRequestController.execute(request);
                runOnUiThread(() -> {
                    if (isDestroyed() || !applied) return;
                    reportRequestPanel.render(reportRequestController.snapshot());
                });
            });
        } catch (IllegalArgumentException | IllegalStateException error) {
            resultText.setText("상태 변경 입력과 재인증 정보를 확인해 주세요.");
        }
    }

    private void connectReportToOperations(AdminReportModels.Detail detail) {
        String reportId = detail.summary().id();
        reportIdInput.setText(reportId);
        int currentDeliveryRevision = detail.delivery() == null ? 0 : detail.delivery().revision();
        expectedRevisionInput.setText(Integer.toString(currentDeliveryRevision));
        packageRevisionInput.setText(
            verifiedDeliveryPackage != null
                && verifiedDeliveryPackage.matchesDelivery(reportId, verifiedDeliveryPackage.revision())
                ? Integer.toString(verifiedDeliveryPackage.revision())
                : ""
        );
        resultText.setText(
            "선택한 신고를 아래 폼에 연결했습니다. 기관 전달은 앱 밖에서 수행한 사실만 기록하세요."
        );
        reportIdInput.requestFocus();
    }

    private void refreshConnectedReportDetail() {
        if (reportPanel == null || reportPanel.selectedReportId() == null) return;
        if (!reportPanel.selectedReportId().equals(normalized(reportIdInput))) return;
        loadReportDetail(reportPanel.selectedReportId());
    }

    private void restoreReportPanelState(Bundle savedInstanceState) {
        if (reportPanel == null || savedInstanceState == null) return;
        reportPanel.restore(
            new AdminReportPanel.FilterDraft(
                savedInstanceState.getString(REPORT_FILTER_ID_STATE, ""),
                savedInstanceState.getString(REPORT_FILTER_STATUS_STATE, ""),
                savedInstanceState.getString(REPORT_FILTER_CLASS_STATE, ""),
                savedInstanceState.getString(REPORT_FILTER_FROM_STATE, ""),
                savedInstanceState.getString(REPORT_FILTER_TO_STATE, "")
            ),
            savedInstanceState.getString(REPORT_SELECTED_ID_STATE)
        );
        if (reportRequestPanel != null) {
            reportRequestPanel.restore(
                new AdminReportRequestPanel.FilterDraft(
                    savedInstanceState.getString(REQUEST_FILTER_REPORT_STATE, ""),
                    savedInstanceState.getString(REQUEST_FILTER_TYPE_STATE, ""),
                    savedInstanceState.getString(REQUEST_FILTER_STATUS_STATE, "")
                ),
                savedInstanceState.getString(REQUEST_SELECTED_ID_STATE)
            );
        }
        if (auditPanel != null) {
            auditPanel.restore(
                savedInstanceState.getString(AUDIT_EVENT_TYPE_STATE, ""),
                savedInstanceState.getString(AUDIT_ACTOR_STATE, "")
            );
        }
        if (incidentPanel != null) {
            incidentPanel.restore(
                savedInstanceState.getString(INCIDENT_FILTER_STATUS_STATE, ""),
                savedInstanceState.getString(INCIDENT_SELECTED_ID_STATE)
            );
        }
    }

    private void runStatusUpdate(
        AdminReportModels.Detail detail,
        String nextStatus,
        char[] password,
        char[] totp
    ) {
        try {
            executeReportWorkflow(reportWorkflowController.beginStatus(
                detail.summary().id(),
                nextStatus,
                detail.summary().statusVersion(),
                password,
                totp
            ));
        } catch (IllegalArgumentException | IllegalStateException error) {
            resultText.setText("상태 변경 재인증 입력을 확인해 주세요.");
        }
    }

    private void runDeliveryPackageCreation(
        AdminReportModels.Detail detail,
        char[] password,
        char[] totp
    ) {
        try {
            AdminReportWorkflowController.Request request = reportWorkflowController.beginPackage(
                detail.summary().id(),
                password,
                totp
            );
            verifiedDeliveryPackage = null;
            packageRevisionInput.setText("");
            executeReportWorkflow(request);
        } catch (IllegalArgumentException | IllegalStateException error) {
            resultText.setText("제출본 생성 재인증 입력을 확인해 주세요.");
        }
    }

    private void executeReportWorkflow(AdminReportWorkflowController.Request request) {
        reportPanel.renderWorkflow(reportWorkflowController.snapshot());
        networkExecutor.execute(() -> {
            boolean applied = reportWorkflowController.execute(request);
            runOnUiThread(() -> {
                if (isDestroyed() || !applied) return;
                AdminReportWorkflowController.State state = reportWorkflowController.snapshot();
                if (state.refreshedDetail() != null) {
                    reportController.replaceDetail(state.refreshedDetail());
                    reportPanel.render(reportController.snapshot());
                }
                reportPanel.renderWorkflow(state);
                if (state.phase() == AdminReportWorkflowController.Phase.PACKAGE_READY) {
                    launchPackageDocumentPicker();
                }
            });
        });
    }

    private void launchPackageDocumentPicker() {
        AdminDeliveryPackage packageValue = reportWorkflowController.consumePackage();
        if (packageValue == null) {
            reportWorkflowController.markSaveFailed(false);
            reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            return;
        }
        if (pendingDeliveryPackage != null) pendingDeliveryPackage.destroy();
        pendingDeliveryPackage = packageValue;
        awaitingSafResult = true;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(
                Intent.EXTRA_TITLE,
                "walksafe-report-" + reportWorkflowController.snapshot().reportId()
                    + "-r" + packageValue.revision() + ".zip"
            );
        try {
            startActivityForResult(intent, CREATE_DELIVERY_PACKAGE_DOCUMENT);
        } catch (RuntimeException error) {
            awaitingSafResult = false;
            pendingDeliveryPackage.destroy();
            pendingDeliveryPackage = null;
            reportWorkflowController.markSaveFailed(false);
            reportPanel.renderWorkflow(reportWorkflowController.snapshot());
        }
    }

    private AdminDeliveryPackageSaver.Destination safDestination(Uri uri) {
        return new AdminDeliveryPackageSaver.Destination() {
            @Override
            public OutputStream openOutput() throws IOException {
                OutputStream output = getContentResolver().openOutputStream(uri, "wt");
                if (output == null) throw new IOException("SAF output unavailable");
                return output;
            }

            @Override
            public InputStream openInput() throws IOException {
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("SAF input unavailable");
                return input;
            }

            @Override
            public void delete() throws IOException {
                if (!deleteSafDocument(uri)) throw new IOException("SAF document cleanup failed");
            }
        };
    }

    private boolean deleteSafDocument(Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                return DocumentsContract.deleteDocument(getContentResolver(), uri);
            }
            return getContentResolver().delete(uri, null, null) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void loadFirstIncidentPage(String status) {
        if (incidentController == null) return;
        try {
            String optionalStatus = status == null || status.trim().isEmpty() ? null : status;
            executeIncidentRequest(incidentController.beginFirstPage(
                new AdminIncidentModels.Filters(optionalStatus)
            ));
        } catch (IllegalArgumentException error) {
            resultText.setText("중대 사고 상태 조건을 다시 확인해 주세요.");
        }
    }

    private void loadNextIncidentPage() {
        if (incidentController == null) return;
        try {
            executeIncidentRequest(incidentController.beginNextPage());
        } catch (IllegalStateException error) {
            resultText.setText("불러올 다음 중대 사고 페이지가 없습니다.");
        }
    }

    private void loadIncidentDetail(String incidentId) {
        if (incidentController == null) return;
        try {
            executeIncidentRequest(incidentController.beginDetail(incidentId));
        } catch (IllegalArgumentException error) {
            resultText.setText("상세를 확인할 중대 사고 UUID가 올바르지 않습니다.");
        }
    }

    private void retryIncidentRequest() {
        if (incidentController == null) return;
        try {
            executeIncidentRequest(incidentController.beginRetry());
        } catch (IllegalStateException error) {
            resultText.setText("다시 시도할 중대 사고 조회가 없습니다.");
        }
    }

    private void executeIncidentRequest(AdminIncidentController.Request request) {
        incidentPanel.render(incidentController.snapshot());
        networkExecutor.execute(() -> {
            boolean applied = incidentController.execute(request);
            runOnUiThread(() -> {
                if (isDestroyed() || !applied) return;
                incidentPanel.render(incidentController.snapshot());
            });
        });
    }

    private void runIncidentStatusUpdate(
        AdminIncidentModels.Detail detail,
        String nextState,
        String reason,
        String observation,
        String evidenceSha256,
        char[] password,
        char[] totp
    ) {
        try {
            if (detail == null || !detail.allowedNextStates().contains(nextState)
                || !AdminIncidentModels.isAllowedTransition(detail.summary().status(), nextState)) {
                throw new IllegalArgumentException("incident transition is not allowed");
            }
            AdminIncidentModels.StatusRequest request = new AdminIncidentModels.StatusRequest(
                nextState,
                detail.summary().statusVersion(),
                UUID.randomUUID().toString(),
                reason,
                observation,
                evidenceSha256
            );
            if (operationInFlight) throw new IllegalStateException("operation is already running");
            operationInFlight = true;
            setInteractiveEnabled(contentRoot, false);
            resultText.setText("중대 사고 상태 기록을 저장하고 있습니다. 복구나 제어는 수행하지 않습니다.");
            networkExecutor.execute(() -> {
                try {
                    controller.updateAdminIncidentStatus(
                        detail.summary().incidentId(),
                        request,
                        password,
                        totp,
                        System.currentTimeMillis(),
                        BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    );
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        operationInFlight = false;
                        setInteractiveEnabled(contentRoot, true);
                        incidentPanel.clearSubmittedEvidence();
                        resultText.setText(
                            "중대 사고 상태를 기록했습니다. 이 기록은 자동 복구나 자동 제어를 수행하지 않습니다."
                        );
                        loadIncidentDetail(detail.summary().incidentId());
                    });
                } catch (AdminIncidentRepository.StatusConflictException conflict) {
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        operationInFlight = false;
                        setInteractiveEnabled(contentRoot, true);
                        resultText.setText(
                            "다른 변경으로 상태 version이 달라졌습니다. 자동 재제출하지 않고 최신 기록을 조회합니다."
                        );
                        loadIncidentDetail(detail.summary().incidentId());
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        operationInFlight = false;
                        setInteractiveEnabled(contentRoot, true);
                        resultText.setText("중대 사고 상태 기록을 저장하지 못했습니다. 입력과 서버 상태를 확인해 주세요.");
                    });
                } finally {
                    Arrays.fill(password, '\0');
                    Arrays.fill(totp, '\0');
                }
            });
        } catch (IllegalArgumentException | IllegalStateException error) {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
            resultText.setText("허용된 다음 상태, 사유, 관찰, SHA-256과 재인증 정보를 확인해 주세요.");
        }
    }

    private void loadAudits(String eventType, String actorId) {
        try {
            executeAuditRequest(auditController.begin(new AdminAuditModels.Filters(eventType, actorId)));
        } catch (IllegalArgumentException error) {
            resultText.setText("감사 사건 유형과 관리자 ID 조건을 확인해 주세요.");
        }
    }

    private void loadMoreAudits() {
        try {
            executeAuditRequest(auditController.beginNext());
        } catch (IllegalStateException error) {
            resultText.setText("불러올 다음 감사 페이지가 없습니다.");
        }
    }

    private void retryAudits() {
        try {
            executeAuditRequest(auditController.beginRetry());
        } catch (IllegalStateException error) {
            resultText.setText("다시 시도할 감사 요청이 없습니다.");
        }
    }

    private void executeAuditRequest(AdminAuditController.Request request) {
        auditPanel.render(auditController.snapshot());
        networkExecutor.execute(() -> {
            boolean applied = auditController.execute(request);
            runOnUiThread(() -> {
                if (isDestroyed() || !applied) return;
                auditPanel.render(auditController.snapshot());
            });
        });
    }

    private static void clear(EditText input) {
        if (input != null) input.getText().clear();
    }

    private static void setInteractiveEnabled(View view, boolean enabled) {
        if (view instanceof Button || view instanceof EditText) view.setEnabled(enabled);
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                setInteractiveEnabled(group.getChildAt(index), enabled);
            }
        }
    }

    private String loadOrCreateDeviceId() {
        var preferences = getSharedPreferences(DEVICE_PREFS, MODE_PRIVATE);
        String existing = preferences.getString(DEVICE_ID_KEY, null);
        if (existing != null && existing.matches("admin-device-[0-9a-f-]{36}")) return existing;
        String created = "admin-device-" + UUID.randomUUID();
        preferences.edit().putString(DEVICE_ID_KEY, created).apply();
        return created;
    }

    private static String defaultDeviceLabel() {
        String label = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        return label.isEmpty() ? "Android 관리자 기기" : label;
    }

    private TextView text(String value, int sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(Color.BLACK);
        return view;
    }

    private EditText input(String hint, int inputType, boolean sensitive) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setInputType(inputType);
        view.setSingleLine(true);
        view.setSaveEnabled(false);
        view.setId(View.NO_ID);
        if (sensitive) view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout group() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        return group;
    }

    private Spinner enumSpinner(Enum<?>[] values) {
        Spinner spinner = new Spinner(this);
        String[] labels = new String[values.length];
        for (int index = 0; index < values.length; index++) labels[index] = values[index].name();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSaveEnabled(false);
        return spinner;
    }

    private CheckBox checkBox(String label) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextColor(Color.BLACK);
        checkBox.setSaveEnabled(false);
        return checkBox;
    }

    private static String normalized(EditText input) {
        return input.getText().toString().trim();
    }

    private static String nullableNormalized(EditText input) {
        String value = normalized(input);
        return value.isEmpty() ? null : value;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private static String valueOrPending(String value) {
        return value == null ? "확인 전" : value;
    }

    private static String custodyLabel(AdminSecurityController.Snapshot snapshot) {
        if (snapshot.recoveryCustodyState() == AdminRecoveryCustodyState.ATTESTED) {
            return "복구자료 외부 보관 확인됨 · 확인시각 "
                + valueOrPending(snapshot.recoveryCustodyAttestedAt());
        }
        if (snapshot.recoveryCustodyState() == AdminRecoveryCustodyState.UNATTESTED) {
            return "복구자료 외부 보관 미확인 · 고위험 작업 잠금";
        }
        return "복구자료 외부 보관 상태 확인 전 · 고위험 작업 잠금";
    }

    private static String stateLabel(AdminSecurityState state) {
        return switch (state) {
            case SIGNED_OUT -> "로그인하지 않았습니다.";
            case AUTHENTICATING -> "로그인을 확인하고 있습니다.";
            case NORMAL -> "관리자 인증 상태가 정상입니다.";
            case RECOVERY_REQUIRED -> "관리자 복구가 필요합니다. 고위험 작업은 잠겨 있습니다.";
            case RECOVERY_IN_PROGRESS -> "관리자 복구 중입니다. 고위험 작업은 잠겨 있습니다.";
            case FAIL_CLOSED -> "서버 상태를 신뢰할 수 없어 관리자 업무를 잠갔습니다.";
        };
    }

    @FunctionalInterface
    private interface SecurityOperation {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface OperationalOperation {
        AdminOperationsApi.Result run() throws Exception;
    }
}
