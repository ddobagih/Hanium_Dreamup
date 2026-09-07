package kr.co.hanium.dreamup.walksafe.admin;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.time.Instant;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyStore;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminAuditController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminAuditModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryPackage;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryPackageSaver;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminExternalCopyDeletionController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminExternalCopyDeletionModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminInstitutionDelivery;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentRepository;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentStatusAttempt;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsApi;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOriginalEvidence;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRequestController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRequestModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRepository;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportWorkflowController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReviewDecisionAttempt;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRecoveryMessagePolicy;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRecoveryCustodyState;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportDecision;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionRepository;
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
    private static final String REPORT_STATUS_RECOVERY_ID_STATE =
        "admin_report_status_recovery_id";
    private static final String REPORT_STATUS_RECOVERY_TARGET_STATE =
        "admin_report_status_recovery_target";
    private static final String REPORT_STATUS_RECOVERY_VERSION_STATE =
        "admin_report_status_recovery_version";
    private static final String REPORT_STATUS_RECOVERY_CONFIRMED_STATE =
        "admin_report_status_recovery_confirmed";
    private static final String REQUEST_FILTER_REPORT_STATE = "admin_request_filter_report";
    private static final String REQUEST_FILTER_TYPE_STATE = "admin_request_filter_type";
    private static final String REQUEST_FILTER_STATUS_STATE = "admin_request_filter_status";
    private static final String REQUEST_SELECTED_ID_STATE = "admin_request_selected_id";
    private static final String EXTERNAL_COPY_FILTER_REQUEST_STATE =
        "admin_external_copy_filter_request";
    private static final String EXTERNAL_COPY_SELECTED_ID_STATE =
        "admin_external_copy_selected_id";
    private static final String AUDIT_EVENT_TYPE_STATE = "admin_audit_event_type";
    private static final String AUDIT_ACTOR_STATE = "admin_audit_actor";
    private static final String INCIDENT_FILTER_STATUS_STATE = "admin_incident_filter_status";
    private static final String INCIDENT_SELECTED_ID_STATE = "admin_incident_selected_id";
    private static final String INCIDENT_RECOVERY_ID_STATE = "admin_incident_recovery_id";
    private static final String INCIDENT_RECOVERY_ACTOR_STATE =
        "admin_incident_recovery_actor";
    private static final String INCIDENT_RECOVERY_NEXT_STATE = "admin_incident_recovery_next";
    private static final String INCIDENT_RECOVERY_VERSION_STATE =
        "admin_incident_recovery_version";
    private static final String INCIDENT_RECOVERY_KEY_STATE = "admin_incident_recovery_key";
    private static final String INCIDENT_RECOVERY_REASON_DIGEST_STATE =
        "admin_incident_recovery_reason_digest";
    private static final String INCIDENT_RECOVERY_OBSERVATION_DIGEST_STATE =
        "admin_incident_recovery_observation_digest";
    private static final String INCIDENT_RECOVERY_EVIDENCE_DIGEST_STATE =
        "admin_incident_recovery_evidence_digest";
    private static final String RAW_COLLECTION_SELECTED_ID_STATE =
        "admin_raw_collection_selected_id";
    private static final int OPEN_DELIVERY_PACKAGE_DOCUMENT = 7_300;
    private static final int CREATE_DELIVERY_PACKAGE_DOCUMENT = 7_301;
    private static final int LAST_DELIVERY_PACKAGE_DOCUMENT_REQUEST = 65_534;
    private static final String[] REVIEW_DECISION_LABELS = {
        "승인 (APPROVED)", "거절 (REJECTED)", "중복 신고 (DUPLICATE)"
    };
    private static final AdminReportDecision.Decision[] REVIEW_DECISIONS = {
        AdminReportDecision.Decision.APPROVED,
        AdminReportDecision.Decision.REJECTED,
        AdminReportDecision.Decision.DUPLICATE
    };
    private static final String[] DELIVERY_STATUS_LABELS = {
        "수동 제출 완료 (SUBMITTED)",
        "기관 접수 확인 (ACKNOWLEDGED)",
        "기관 처리 완료 (RESOLVED)",
        "수동 제출 실패 (FAILED)"
    };
    private static final AdminInstitutionDelivery.Status[] DELIVERY_STATUSES = {
        AdminInstitutionDelivery.Status.SUBMITTED,
        AdminInstitutionDelivery.Status.ACKNOWLEDGED,
        AdminInstitutionDelivery.Status.RESOLVED,
        AdminInstitutionDelivery.Status.FAILED
    };

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private AdminSecurityController controller;
    private AdminReportController reportController;
    private AdminReportRequestController reportRequestController;
    private AdminExternalCopyDeletionController externalCopyDeletionController;
    private AdminReportWorkflowController reportWorkflowController;
    private AdminAuditController auditController;
    private AdminIncidentController incidentController;
    private final AdminIncidentStatusAttempt incidentStatusAttempt =
        new AdminIncidentStatusAttempt();
    private final AdminReviewDecisionAttempt reviewDecisionAttempt =
        new AdminReviewDecisionAttempt();
    private boolean restoredReportStatusRecovery;
    private boolean restoredIncidentStatusRecovery;
    private boolean incidentRecoveryRefreshPending;
    private AdminRawCollectionController rawCollectionController;
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
    private LinearLayout loginGroup;
    private LinearLayout securityDetailsGroup;
    private LinearLayout recoveryStartGroup;
    private LinearLayout recoveryCompleteGroup;
    private LinearLayout sessionGroup;
    private LinearLayout sessionList;
    private LinearLayout custodyGroup;
    private Spinner custodyMaterialKindInput;
    private CheckBox custodyConfirmationInput;
    private LinearLayout operationsGroup;
    private LinearLayout reportOperationsFormGroup;
    private AdminReportPanel reportPanel;
    private AdminReportRequestPanel reportRequestPanel;
    private AdminExternalCopyDeletionPanel externalCopyDeletionPanel;
    private AdminAuditPanel auditPanel;
    private AdminIncidentPanel incidentPanel;
    private AdminRawCollectionPanel rawCollectionPanel;
    private EditText reportIdInput;
    private Spinner reviewDecisionInput;
    private EditText reviewReasonInput;
    private EditText reviewUserVisibleReasonInput;
    private EditText duplicateReportIdInput;
    private CheckBox locationReviewedInput;
    private CheckBox photoReviewedInput;
    private CheckBox privacyReviewedInput;
    private EditText originalEvidenceReasonInput;
    private EditText originalEvidencePasswordInput;
    private EditText originalEvidenceTotpInput;
    private Button originalEvidenceLoadButton;
    private TextView originalEvidenceStatusText;
    private TextView originalEvidenceLocationText;
    private ImageView originalEvidenceImage;
    private CheckBox originalEvidenceConfirmedInput;
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
    private CheckBox manualDeliveryCompletedInput;
    private Button reconnectDeliveryPackageButton;
    private TextView reviewHistoryStatusText;
    private Button reviewHistoryNextButton;
    private TextView deliveryHistoryStatusText;
    private Button deliveryHistoryNextButton;
    private Button revokeCurrentButton;
    private LinearLayout contentRoot;
    private boolean operationInFlight;
    private boolean recordingDelivery;
    private final List<AdminOperationsApi.ReviewHistoryItem> reviewHistoryItems =
        new ArrayList<>();
    private String reviewHistoryNextCursor;
    private long reviewHistorySnapshotRevision = -1L;
    private long reviewHistoryTotalCount;
    private boolean reviewHistoryNeedsFirstPage;
    private final List<AdminOperationsApi.DeliveryHistoryItem> deliveryHistoryItems =
        new ArrayList<>();
    private String deliveryHistoryNextCursor;
    private long deliveryHistorySnapshotRevision = -1L;
    private long deliveryHistoryTotalCount;
    private boolean deliveryHistoryNeedsFirstPage;
    private AdminDeliveryPackage pendingDeliveryPackage;
    private AdminDeliveryPackageSaver.Saved verifiedDeliveryPackage;
    private AdminOriginalEvidence originalEvidence;
    private Bitmap originalEvidenceBitmap;
    private final Handler originalEvidenceExpiryHandler = new Handler(Looper.getMainLooper());
    private Runnable originalEvidenceExpiryTask;
    private Future<?> originalEvidenceLoadTask;
    private final AtomicReference<PendingOriginalEvidenceCredentials>
        pendingOriginalEvidenceCredentials = new AtomicReference<>();
    private volatile long originalEvidenceGeneration;
    private String connectedOperationsReportId;
    private int connectedOperationsContentRevision = -1;
    private int connectedOperationsReviewRevision = -1;
    private String connectedOperationsReviewDecision;
    private int connectedOperationsLatestDeliveryRevision = -1;
    private int connectedOperationsDeliveryRevision = -1;
    private Integer connectedOperationsPackageRevision;
    private String connectedOperationsDeliveryStatus;
    private volatile String boundOperationsSessionId;
    private volatile String boundOperationsAdminId;
    private boolean operationsAccessBindingInitialized;
    private volatile boolean boundOperationsAccessActive;
    private volatile long operationsSessionGeneration;
    private long reportHistoryGeneration;
    private volatile long safSaveGeneration;
    private volatile long safReconnectGeneration;
    private long pendingSafSaveGeneration = -1L;
    private long pendingSafWorkflowGeneration = -1L;
    private long pendingSafSessionGeneration = -1L;
    private String pendingSafSessionId;
    private int nextSafRequestCode = CREATE_DELIVERY_PACKAGE_DOCUMENT;
    private int pendingSafRequestCode = -1;
    private boolean awaitingSafResult;
    private boolean awaitingSafReconnectResult;
    private long pendingSafReconnectGeneration = -1L;
    private long pendingSafReconnectSessionGeneration = -1L;
    private String pendingSafReconnectSessionId;
    private AdminDeliveryPackage.Eligibility pendingSafReconnectEligibility;

    private static final class PendingOriginalEvidenceCredentials {
        private final char[] password;
        private final char[] totp;

        private PendingOriginalEvidenceCredentials(char[] password, char[] totp) {
            this.password = password;
            this.totp = totp;
        }

        private char[] password() { return password; }
        private char[] totp() { return totp; }

        private synchronized void clear() {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
        }
    }

    private static final class OperationalConflictRefresh {
        private final AdminReportModels.Detail detail;
        private final AdminOperationsApi.Result history;

        private OperationalConflictRefresh(
            AdminReportModels.Detail detail,
            AdminOperationsApi.Result history
        ) {
            this.detail = detail;
            this.history = history;
        }
    }

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
                AdminRawCollectionRepository rawCollectionRepository = null;
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
                    rawCollectionRepository = new AdminRawCollectionHttpClient(
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
                    incidentRepository,
                    rawCollectionRepository
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
                            String requestType,
                            String nextStatus,
                            int expectedVersion,
                            String publicResponse,
                            String internalNote,
                            char[] password,
                            char[] totp
                        ) throws Exception {
                            return controller.updateAdminReportRequestStatus(
                                requestId,
                                requestType,
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
                externalCopyDeletionController = new AdminExternalCopyDeletionController(
                    new AdminExternalCopyDeletionController.Loader() {
                        @Override
                        public AdminExternalCopyDeletionModels.Page load(
                            AdminExternalCopyDeletionModels.Filter filter,
                            String cursor
                        ) throws Exception {
                            return controller.listAdminExternalCopyDeletions(
                                filter,
                                cursor,
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }

                        @Override
                        public AdminExternalCopyDeletionModels.Item record(
                            AdminExternalCopyDeletionModels.EventCommand command
                        ) throws Exception {
                            return controller.recordAdminExternalCopyDeletion(
                                command,
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
                        public AdminReportModels.StatusSnapshot updateStatus(
                            String reportId,
                            String nextStatus,
                            int expectedVersion,
                            char[] password,
                            char[] totp,
                            AdminReportWorkflowController.StatusDispatch dispatch
                        ) throws Exception {
                            return controller.updateAdminReportStatus(
                                reportId,
                                nextStatus,
                                expectedVersion,
                                password,
                                totp,
                                System.currentTimeMillis(),
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED,
                                dispatch
                            );
                        }

                        @Override
                        public AdminDeliveryPackage createPackage(
                            AdminDeliveryPackage.Eligibility eligibility,
                            char[] password,
                            char[] totp
                        ) throws Exception {
                            return controller.createAdminDeliveryPackage(
                                eligibility,
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
                        public AdminIncidentModels.HistoryPage loadHistory(
                            String incidentId,
                            String cursor
                        ) throws Exception {
                            return controller.getAdminIncidentHistory(
                                incidentId,
                                cursor,
                                BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                            );
                        }
                    }
                );
                rawCollectionController = new AdminRawCollectionController((password, totp) ->
                    controller.listAdminRawQuarantine(
                        password,
                        totp,
                        System.currentTimeMillis(),
                        BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    )
                );
            } catch (Exception error) {
                deviceKeyFailure = "키 사용 불가";
                operationsClientConfigured = false;
            }
        }
        setContentView(buildContent());
        render();
        restorePendingMutationRecovery(savedInstanceState);
        restoreReportPanelState(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (!awaitingSafResult && reportWorkflowController != null) {
            reportWorkflowController.suspendForLifecycle();
            if (reportPanel != null) {
                reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            }
        }
        if (reportPanel != null) {
            AdminReportPanel.FilterDraft draft = reportPanel.filterDraft();
            outState.putString(REPORT_FILTER_ID_STATE, draft.reportId());
            outState.putString(REPORT_FILTER_STATUS_STATE, draft.status());
            outState.putString(REPORT_FILTER_CLASS_STATE, draft.className());
            outState.putString(REPORT_FILTER_FROM_STATE, draft.createdFrom());
            outState.putString(REPORT_FILTER_TO_STATE, draft.createdTo());
            outState.putString(REPORT_SELECTED_ID_STATE, reportPanel.selectedReportId());
        }
        if (reportWorkflowController != null) {
            AdminReportWorkflowController.StatusDetailRecovery recovery =
                reportWorkflowController.statusDetailRecovery();
            if (recovery != null) {
                outState.putString(REPORT_STATUS_RECOVERY_ID_STATE, recovery.reportId());
                outState.putBoolean(
                    REPORT_STATUS_RECOVERY_CONFIRMED_STATE,
                    recovery.patchConfirmed()
                );
                if (recovery.patchConfirmed()) {
                    outState.putString(
                        REPORT_STATUS_RECOVERY_TARGET_STATE,
                        recovery.targetStatus()
                    );
                    outState.putInt(
                        REPORT_STATUS_RECOVERY_VERSION_STATE,
                        recovery.targetStatusVersion()
                    );
                }
            }
        }
        if (reportRequestPanel != null) {
            AdminReportRequestPanel.FilterDraft draft = reportRequestPanel.filterDraft();
            outState.putString(REQUEST_FILTER_REPORT_STATE, draft.reportId());
            outState.putString(REQUEST_FILTER_TYPE_STATE, draft.requestType());
            outState.putString(REQUEST_FILTER_STATUS_STATE, draft.status());
            outState.putString(REQUEST_SELECTED_ID_STATE, reportRequestPanel.selectedRequestId());
        }
        if (externalCopyDeletionPanel != null) {
            outState.putString(
                EXTERNAL_COPY_FILTER_REQUEST_STATE,
                externalCopyDeletionPanel.filterDraft().requestId()
            );
            outState.putString(
                EXTERNAL_COPY_SELECTED_ID_STATE,
                externalCopyDeletionPanel.selectedCopyId()
            );
        }
        if (auditPanel != null) {
            outState.putString(AUDIT_EVENT_TYPE_STATE, auditPanel.eventType());
            outState.putString(AUDIT_ACTOR_STATE, auditPanel.actorId());
        }
        if (incidentPanel != null) {
            outState.putString(INCIDENT_FILTER_STATUS_STATE, incidentPanel.filterStatus());
            outState.putString(INCIDENT_SELECTED_ID_STATE, incidentPanel.selectedIncidentId());
        }
        AdminIncidentStatusAttempt.RecoverySnapshot incidentRecovery =
            incidentStatusAttempt.recoverySnapshot();
        if (incidentRecovery != null) {
            outState.putString(INCIDENT_RECOVERY_ID_STATE, incidentRecovery.incidentId());
            outState.putString(INCIDENT_RECOVERY_ACTOR_STATE, incidentRecovery.actorId());
            outState.putString(INCIDENT_RECOVERY_NEXT_STATE, incidentRecovery.nextState());
            outState.putInt(INCIDENT_RECOVERY_VERSION_STATE, incidentRecovery.expectedVersion());
            outState.putString(INCIDENT_RECOVERY_KEY_STATE, incidentRecovery.idempotencyKey());
            outState.putString(
                INCIDENT_RECOVERY_REASON_DIGEST_STATE,
                incidentRecovery.reasonDigest()
            );
            outState.putString(
                INCIDENT_RECOVERY_OBSERVATION_DIGEST_STATE,
                incidentRecovery.observationDigest()
            );
            outState.putString(
                INCIDENT_RECOVERY_EVIDENCE_DIGEST_STATE,
                incidentRecovery.evidenceDigest()
            );
        }
        if (rawCollectionPanel != null) {
            outState.putString(
                RAW_COLLECTION_SELECTED_ID_STATE,
                rawCollectionPanel.selectedCollectionId()
            );
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        clearOriginalEvidence("화면을 벗어나 원본 증거를 메모리에서 지웠습니다.");
        clear(originalEvidenceReasonInput);
        clear(originalEvidencePasswordInput);
        clear(originalEvidenceTotpInput);
        if (reportController != null) reportController.invalidate();
        if (reportRequestController != null) reportRequestController.invalidate();
        if (externalCopyDeletionController != null) externalCopyDeletionController.invalidate();
        if (auditController != null) {
            auditController.invalidate();
            if (auditPanel != null) auditPanel.render(auditController.snapshot());
        }
        if (incidentController != null) incidentController.invalidate();
        if (rawCollectionController != null) rawCollectionController.invalidate();
        if (!awaitingSafResult && reportWorkflowController != null) {
            reportWorkflowController.suspendForLifecycle();
            if (reportPanel != null) {
                reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            }
        }
        if (reportPanel != null) reportPanel.clearHighRiskInputs();
        if (reportRequestPanel != null) reportRequestPanel.clearSensitiveInputs();
        if (externalCopyDeletionPanel != null) externalCopyDeletionPanel.clearTransientInputs();
        if (incidentPanel != null) incidentPanel.clearSensitiveInputs();
        if (rawCollectionPanel != null) rawCollectionPanel.clearSensitiveInputs();
        clearSensitiveInputs();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        clearOriginalEvidence("원본 증거를 메모리에서 지웠습니다.");
        if (reportRequestController != null) reportRequestController.invalidate();
        if (externalCopyDeletionController != null) externalCopyDeletionController.invalidate();
        if (incidentController != null) incidentController.invalidate();
        if (rawCollectionController != null) rawCollectionController.invalidate();
        if (pendingDeliveryPackage != null) pendingDeliveryPackage.destroy();
        pendingDeliveryPackage = null;
        verifiedDeliveryPackage = null;
        clearPendingSafReconnectBinding();
        if (controller != null) networkExecutor.execute(controller::close);
        networkExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_DELIVERY_PACKAGE_DOCUMENT) {
            handleExistingDeliveryPackageResult(
                resultCode == RESULT_OK && data != null ? data.getData() : null
            );
            return;
        }
        if (!isSafSaveRequestCode(requestCode)) return;
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode != pendingSafRequestCode) {
            if (uri != null) deleteSafDocument(uri);
            return;
        }
        awaitingSafResult = false;
        AdminDeliveryPackage packageValue = pendingDeliveryPackage;
        pendingDeliveryPackage = null;
        long saveGeneration = pendingSafSaveGeneration;
        long workflowGeneration = pendingSafWorkflowGeneration;
        long sessionGeneration = pendingSafSessionGeneration;
        String sessionId = pendingSafSessionId;
        clearPendingSafBinding();
        if (!isCurrentSafSaveBinding(
            saveGeneration, workflowGeneration, sessionGeneration, sessionId
        )) {
            if (packageValue != null) packageValue.destroy();
            if (uri != null) deleteSafDocument(uri);
            return;
        }
        if (uri == null || packageValue == null) {
            if (packageValue != null) packageValue.destroy();
            if (uri != null) deleteSafDocument(uri);
            reportWorkflowController.markSaveFailed(workflowGeneration, true);
            reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            return;
        }
        networkExecutor.execute(() -> {
            try {
                boolean serverSessionValid =
                    controller.refreshAndValidateSafSaveSession(sessionId);
                if (!serverSessionValid || !isCurrentSafSaveBinding(
                    saveGeneration, workflowGeneration, sessionGeneration, sessionId
                )) {
                    rejectSafSave(packageValue, uri, workflowGeneration,
                        "현재 관리자 세션을 서버에서 재확인하지 못해 제출본을 저장하지 않았습니다.");
                    return;
                }
                AdminReportModels.Detail beforeWrite = controller.getAdminReportDetail(
                    packageValue.reportId(),
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
                if (!packageValue.matchesFreshEligibility(beforeWrite)) {
                    rejectSafSave(packageValue, uri, workflowGeneration,
                        "신고 내용·검토·전달 버전이 바뀌어 새 제출본을 저장하지 않았습니다.");
                    return;
                }
            } catch (Exception error) {
                rejectSafSave(packageValue, uri, workflowGeneration,
                    "저장 직전 신고 상태를 다시 확인하지 못해 새 제출본을 저장하지 않았습니다.");
                return;
            }
            try {
                AdminDeliveryPackageSaver.Saved saved = AdminDeliveryPackageSaver.save(
                    packageValue,
                    safDestination(uri)
                );
                AdminReportModels.Detail afterWrite = controller.getAdminReportDetail(
                    saved.reportId(),
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
                if (!packageValue.matchesFreshEligibility(afterWrite)
                    || !saved.matchesFreshDetail(afterWrite)) {
                    throw new IOException("report package eligibility changed during SAF save");
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        deleteSafDocument(uri);
                        return;
                    }
                    if (!isCurrentSafSaveBinding(
                        saveGeneration, workflowGeneration, sessionGeneration, sessionId
                    ) || !reportWorkflowController.markSaved(workflowGeneration, saved.revision())) {
                        deleteSafDocument(uri);
                        return;
                    }
                    reportController.replaceDetail(afterWrite);
                    reportPanel.render(reportController.snapshot());
                    connectReportToOperations(afterWrite);
                    replaceVerifiedDeliveryPackage(saved);
                    reportPanel.renderWorkflow(reportWorkflowController.snapshot());
                });
            } catch (Exception error) {
                deleteSafDocument(uri);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (isCurrentSafSaveBinding(
                        saveGeneration, workflowGeneration, sessionGeneration, sessionId
                    ) && reportWorkflowController.markSaveFailed(workflowGeneration, false)) {
                        reportPanel.renderWorkflow(reportWorkflowController.snapshot());
                    }
                });
            }
        });
    }

    private void rejectSafSave(
        AdminDeliveryPackage packageValue,
        Uri uri,
        long workflowGeneration,
        String message
    ) {
        packageValue.destroy();
        deleteSafDocument(uri);
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            if (reportWorkflowController.markSaveFailed(workflowGeneration, false)) {
                reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            }
            resultText.setText(message + " 기존 제출본 연결과 입력은 유지했습니다.");
        });
    }

    private View buildContent() {
        LinearLayout content = new LinearLayout(this);
        contentRoot = content;
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(32));
        content.setBackgroundColor(Color.WHITE);
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);

        TextView title = text("워크세이프 관리자", 28);
        markAccessibilityHeading(title);
        content.addView(title, matchWrap());
        TextView introduction = text(
            "신고 검수와 수동 기관 제출 기록을 관리합니다.",
            16
        );
        introduction.setPadding(0, dp(6), 0, dp(10));
        content.addView(introduction, matchWrap());

        statusText = text("", 19);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, 28, 0, 8);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(statusText, matchWrap());

        loginGroup = group();
        content.addView(loginGroup, matchWrap());

        securityDetailsGroup = group();
        Button securityDetailsToggle = button("서버·기기 보안 정보 펼치기");
        securityDetailsToggle.setOnClickListener(view -> {
            boolean opening = securityDetailsGroup.getVisibility() != View.VISIBLE;
            securityDetailsGroup.setVisibility(opening ? View.VISIBLE : View.GONE);
            securityDetailsToggle.setText(
                opening ? "서버·기기 보안 정보 접기" : "서버·기기 보안 정보 펼치기"
            );
            securityDetailsToggle.setContentDescription(securityDetailsToggle.getText());
            if (opening) metadataText.requestFocus();
        });
        content.addView(securityDetailsToggle, matchWrap());
        securityDetailsGroup.setVisibility(View.GONE);
        content.addView(securityDetailsGroup, matchWrap());

        metadataText = text("", 14);
        metadataText.setGravity(Gravity.CENTER);
        securityDetailsGroup.addView(metadataText, matchWrap());

        custodyText = text("", 15);
        custodyText.setGravity(Gravity.CENTER);
        custodyText.setPadding(0, 8, 0, 0);
        custodyText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        securityDetailsGroup.addView(custodyText, matchWrap());

        deviceKeyText = text("", 13);
        deviceKeyText.setGravity(Gravity.CENTER);
        deviceKeyText.setPadding(0, 8, 0, 0);
        deviceKeyText.setTextIsSelectable(true);
        securityDetailsGroup.addView(deviceKeyText, matchWrap());

        resultText = text("", 16);
        resultText.setPadding(0, 20, 0, 20);
        resultText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(resultText, matchWrap());

        deviceLabelInput = input("이 기기의 알아보기 쉬운 이름", InputType.TYPE_CLASS_TEXT, false);
        deviceLabelInput.setText(defaultDeviceLabel());
        loginGroup.addView(deviceLabelInput, matchWrap());

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
        loginGroup.addView(adminIdInput, matchWrap());
        loginGroup.addView(passwordInput, matchWrap());
        loginGroup.addView(totpInput, matchWrap());

        loginButton = button("비밀번호와 추가 인증으로 로그인");
        loginButton.setOnClickListener(view -> login());
        loginGroup.addView(loginButton, matchWrap());

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
        custodyMaterialKindInput.setContentDescription("휴대전화 밖에 보관한 복구자료 유형");
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
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets safe = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout()
                );
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        }
        scroll.addView(content, matchWrap());
        return scroll;
    }

    private LinearLayout buildOperationsGroup() {
        LinearLayout group = group();
        TextView heading = text("오늘의 운영 업무", 23);
        heading.setPadding(0, 36, 0, 8);
        markAccessibilityHeading(heading);
        group.addView(heading, matchWrap());
        group.addView(text(
            "1. 신고 상세 확인  →  2. 위치·사진·개인정보 검수  →  3. 제출본 저장  →  "
                + "4. 앱 밖에서 기관에 수동 제출  →  5. 외부 접수번호 기록",
            16
        ), matchWrap());
        TextView manualPolicy = text(
            "중요: 이 앱은 기관으로 자료를 전송하지 않습니다. 성공 상태는 앱 밖 수동 제출을 실제로 완료한 뒤 기록하고, 실패 상태는 완료 확인 없이 실패 사실만 기록하세요.",
            15
        );
        manualPolicy.setPadding(dp(12), dp(12), dp(12), dp(12));
        manualPolicy.setBackgroundColor(Color.rgb(255, 247, 220));
        group.addView(manualPolicy, matchWrap());

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
            public void onRetryWorkflowDetail() {
                retryReportStatusDetail();
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

        LinearLayout requiredParallelOperationsGroup = group();
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
        requiredParallelOperationsGroup.addView(reportRequestPanel, matchWrap());

        externalCopyDeletionPanel = new AdminExternalCopyDeletionPanel(
            this,
            new AdminExternalCopyDeletionPanel.Listener() {
                @Override
                public void onLoad(AdminExternalCopyDeletionPanel.FilterDraft filter) {
                    loadFirstExternalCopyDeletionPage(filter);
                }

                @Override
                public void onLoadMore() {
                    loadNextExternalCopyDeletionPage();
                }

                @Override
                public void onRetryRead() {
                    retryExternalCopyDeletionRead();
                }

                @Override
                public void onRetryRecord() {
                    retryExternalCopyDeletionRecord();
                }

                @Override
                public void onRecord(
                    AdminExternalCopyDeletionModels.Item item,
                    String nextState,
                    String observedAt,
                    String institutionReference,
                    String evidenceSha256
                ) {
                    recordExternalCopyDeletionFact(
                        item,
                        nextState,
                        observedAt,
                        institutionReference,
                        evidenceSha256
                    );
                }
            }
        );
        requiredParallelOperationsGroup.addView(externalCopyDeletionPanel, matchWrap());

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
            public void onLoadMoreHistory() {
                loadNextIncidentHistoryPage();
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
        requiredParallelOperationsGroup.addView(incidentPanel, matchWrap());

        rawCollectionPanel = new AdminRawCollectionPanel(
            this,
            new AdminRawCollectionPanel.Listener() {
                @Override
                public void onLoad(char[] password, char[] totp) {
                    loadRawQuarantine(password, totp);
                }

                @Override
                public void onRetry(char[] password, char[] totp) {
                    retryRawQuarantine(password, totp);
                }

                @Override
                public void onSelect(String collectionId) {
                    selectRawCollection(collectionId);
                }

                @Override
                public void onDecide(
                    AdminRawCollectionModels.Summary item,
                    AdminRawCollectionModels.PurposeDecisionRequest request,
                    char[] password,
                    char[] totp
                ) {
                    runRawPurposeDecision(item, request, password, totp);
                }

                @Override
                public void onLegalHold(
                    AdminRawCollectionModels.Summary item,
                    AdminRawCollectionModels.LegalHoldRequest request,
                    char[] password,
                    char[] totp
                ) {
                    runRawLegalHold(item, request, password, totp);
                }
            }
        );
        requiredParallelOperationsGroup.addView(rawCollectionPanel, matchWrap());

        LinearLayout auditSupplementGroup = group();
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
        auditSupplementGroup.addView(auditPanel, matchWrap());

        reportOperationsFormGroup = group();
        reportOperationsFormGroup.setVisibility(View.GONE);
        reportIdInput = input("신고 UUID", InputType.TYPE_CLASS_TEXT, false);
        makeReadOnly(reportIdInput, "상세에서 연결한 신고 식별자");

        TextView reviewHeading = text("2. 선택한 신고 검수 결정", 20);
        reviewHeading.setPadding(0, 24, 0, 4);
        markAccessibilityHeading(reviewHeading);
        reportOperationsFormGroup.addView(reviewHeading, matchWrap());
        reportOperationsFormGroup.addView(text(
            "신고 상세의 '검수·수동 제출 준비로 이어가기'를 누르면 신고 ID가 연결됩니다. 위치·사진·개인정보를 모두 확인한 뒤 결정을 기록하세요.",
            15
        ), matchWrap());
        reportOperationsFormGroup.addView(reportIdInput, matchWrap());
        reviewDecisionInput = labeledSpinner(
            REVIEW_DECISION_LABELS,
            "신고 검수 결정"
        );
        reportOperationsFormGroup.addView(reviewDecisionInput, matchWrap());
        reviewReasonInput = input("내부 검토 사유 (사용자 비공개)", InputType.TYPE_CLASS_TEXT, false);
        reviewUserVisibleReasonInput = input(
            "사용자에게 보여줄 사유 (REJECTED/DUPLICATE 필수)",
            InputType.TYPE_CLASS_TEXT,
            false
        );
        duplicateReportIdInput = input("중복 대상 신고 UUID (DUPLICATE일 때만)", InputType.TYPE_CLASS_TEXT, false);
        reportOperationsFormGroup.addView(reviewReasonInput, matchWrap());
        reportOperationsFormGroup.addView(reviewUserVisibleReasonInput, matchWrap());
        reportOperationsFormGroup.addView(duplicateReportIdInput, matchWrap());
        locationReviewedInput = checkBox("위치 검토 완료");
        photoReviewedInput = checkBox("사진 검토 완료");
        privacyReviewedInput = checkBox("개인정보 검토 완료");
        reportOperationsFormGroup.addView(locationReviewedInput, matchWrap());
        reportOperationsFormGroup.addView(photoReviewedInput, matchWrap());
        reportOperationsFormGroup.addView(privacyReviewedInput, matchWrap());

        TextView originalEvidenceHeading = text("승인용 원본 증거 확인", 18);
        originalEvidenceHeading.setPadding(0, dp(20), 0, dp(4));
        markAccessibilityHeading(originalEvidenceHeading);
        reportOperationsFormGroup.addView(originalEvidenceHeading, matchWrap());
        reportOperationsFormGroup.addView(text(
            "APPROVED 결정에만 사용합니다. 비밀번호·6자리 추가 인증 후 정확한 위치와 원본 사진을 한 번 불러옵니다. 화면을 벗어나거나 시간이 만료되면 즉시 지우며 저장·복사·공유하지 않습니다.",
            15
        ), matchWrap());
        originalEvidenceReasonInput = input(
            "원본 열람 사유 (8자 이상, 내부 감사 기록)",
            InputType.TYPE_CLASS_TEXT,
            false
        );
        originalEvidencePasswordInput = input(
            "원본 열람 재인증 비밀번호",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            true
        );
        originalEvidenceTotpInput = input(
            "원본 열람 6자리 추가 인증",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            true
        );
        originalEvidencePasswordInput.setFilterTouchesWhenObscured(true);
        originalEvidenceTotpInput.setFilterTouchesWhenObscured(true);
        reportOperationsFormGroup.addView(originalEvidenceReasonInput, matchWrap());
        reportOperationsFormGroup.addView(originalEvidencePasswordInput, matchWrap());
        reportOperationsFormGroup.addView(originalEvidenceTotpInput, matchWrap());
        originalEvidenceLoadButton = button("정확한 위치·원본 사진 일회 열람");
        originalEvidenceLoadButton.setContentDescription(
            "승인 검토용 정확한 위치와 원본 사진을 재인증 후 한 번 불러오기"
        );
        originalEvidenceLoadButton.setOnClickListener(view -> loadOriginalEvidence());
        reportOperationsFormGroup.addView(originalEvidenceLoadButton, matchWrap());
        originalEvidenceStatusText = text("원본 증거를 아직 불러오지 않았습니다.", 15);
        originalEvidenceStatusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        reportOperationsFormGroup.addView(originalEvidenceStatusText, matchWrap());
        originalEvidenceLocationText = text("정확한 위치: 열람 전", 16);
        originalEvidenceLocationText.setTextIsSelectable(false);
        reportOperationsFormGroup.addView(originalEvidenceLocationText, matchWrap());
        originalEvidenceImage = new ImageView(this);
        originalEvidenceImage.setAdjustViewBounds(true);
        originalEvidenceImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        originalEvidenceImage.setContentDescription("승인 검토용 신고 원본 사진");
        originalEvidenceImage.setSaveEnabled(false);
        originalEvidenceImage.setId(View.NO_ID);
        originalEvidenceImage.setVisibility(View.GONE);
        reportOperationsFormGroup.addView(
            originalEvidenceImage,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
        );
        originalEvidenceConfirmedInput = checkBox(
            "현재 표시된 정확한 위치와 원본 사진을 직접 확인했습니다."
        );
        originalEvidenceConfirmedInput.setEnabled(false);
        reportOperationsFormGroup.addView(originalEvidenceConfirmedInput, matchWrap());
        reviewDecisionInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= REVIEW_DECISIONS.length
                    || REVIEW_DECISIONS[position] != AdminReportDecision.Decision.APPROVED) {
                    clearOriginalEvidence("승인 외 결정에는 원본 열람 grant를 보관하지 않습니다.");
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
                clearOriginalEvidence("결정을 다시 선택해야 원본 증거를 열람할 수 있습니다.");
            }
        });
        Button reviewButton = button("검토 결정 기록");
        reviewButton.setOnClickListener(view -> recordReviewDecision());
        reportOperationsFormGroup.addView(reviewButton, matchWrap());
        Button reviewHistoryButton = button("검토 결정 이력 확인");
        reviewHistoryButton.setOnClickListener(view -> readReviewDecisions(false));
        reportOperationsFormGroup.addView(reviewHistoryButton, matchWrap());
        reviewHistoryStatusText = text("검토 결정 이력: 조회 전", 15);
        reviewHistoryStatusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        reportOperationsFormGroup.addView(reviewHistoryStatusText, matchWrap());
        reviewHistoryNextButton = button("다음 이력 불러오기");
        reviewHistoryNextButton.setOnClickListener(view -> readReviewDecisions(true));
        reviewHistoryNextButton.setVisibility(View.GONE);
        reportOperationsFormGroup.addView(reviewHistoryNextButton, matchWrap());

        TextView deliveryHeading = text("3. 기관 수동 제출·접수 기록", 20);
        deliveryHeading.setPadding(0, 28, 0, 4);
        markAccessibilityHeading(deliveryHeading);
        reportOperationsFormGroup.addView(deliveryHeading, matchWrap());
        reportOperationsFormGroup.addView(text(
            "승인된 신고의 제출본을 저장하고 앱 밖에서 기관에 직접 제출하세요. 이 영역은 앱 밖에서 시도한 제출의 성공·접수·처리·실패 결과만 기록합니다.",
            15
        ), matchWrap());
        reportOperationsFormGroup.addView(text(
            "앱 재시작이나 재로그인 뒤 접수·처리 결과를 이어서 기록하려면, 이전에 저장한 같은 ZIP을 직접 다시 선택해 현재 신고와 해시를 확인해야 합니다. 앱은 ZIP을 내부에 복사하거나 기관으로 전송하지 않습니다.",
            15
        ), matchWrap());
        reconnectDeliveryPackageButton = button("기존 제출본 ZIP 다시 확인");
        reconnectDeliveryPackageButton.setContentDescription(
            "기존 제출본 ZIP을 직접 선택하여 현재 신고, 전달 버전, 콘텐츠 버전과 해시 다시 확인"
        );
        reconnectDeliveryPackageButton.setOnClickListener(
            view -> launchExistingDeliveryPackagePicker()
        );
        reconnectDeliveryPackageButton.setVisibility(View.GONE);
        reportOperationsFormGroup.addView(reconnectDeliveryPackageButton, matchWrap());
        institutionInput = input("기관", InputType.TYPE_CLASS_TEXT, false);
        deliveryChannelInput = input("수동 전달 채널 (예: 전화, 공문)", InputType.TYPE_CLASS_TEXT, false);
        deliveryRecipientInput = input("수신 부서 또는 담당자", InputType.TYPE_CLASS_TEXT, false);
        reportOperationsFormGroup.addView(institutionInput, matchWrap());
        reportOperationsFormGroup.addView(deliveryChannelInput, matchWrap());
        reportOperationsFormGroup.addView(deliveryRecipientInput, matchWrap());
        deliveryStatusInput = labeledSpinner(
            DELIVERY_STATUS_LABELS,
            "기관 수동 제출 기록 상태"
        );
        reportOperationsFormGroup.addView(deliveryStatusInput, matchWrap());
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
        makeReadOnly(expectedRevisionInput, "앱이 관리하는 현재 전달 기록 버전");
        makeReadOnly(packageRevisionInput, "앱이 검증한 기관 제출본 버전");
        makeReadOnly(idempotencyKeyInput, "앱이 관리하는 중복 기록 방지 식별자");
        reportOperationsFormGroup.addView(externalReceiptInput, matchWrap());
        reportOperationsFormGroup.addView(deliveryReasonInput, matchWrap());
        reportOperationsFormGroup.addView(evidenceSha256Input, matchWrap());
        reportOperationsFormGroup.addView(observedAtInput, matchWrap());
        reportOperationsFormGroup.addView(packageRevisionInput, matchWrap());
        reportOperationsFormGroup.addView(expectedRevisionInput, matchWrap());
        reportOperationsFormGroup.addView(idempotencyKeyInput, matchWrap());
        manualDeliveryCompletedInput = checkBox(
            "성공 상태 기록: 앱 밖에서 해당 기관으로 수동 제출을 실제로 수행한 사실을 확인했습니다."
        );
        reportOperationsFormGroup.addView(manualDeliveryCompletedInput, matchWrap());
        Button deliveryButton = button("수동 전달 결과 기록");
        deliveryButton.setOnClickListener(view -> recordDelivery());
        reportOperationsFormGroup.addView(deliveryButton, matchWrap());
        Button deliveryHistoryButton = button("수동 전달 상태 이력 확인");
        deliveryHistoryButton.setOnClickListener(view -> readDeliveries(false));
        reportOperationsFormGroup.addView(deliveryHistoryButton, matchWrap());
        deliveryHistoryStatusText = text("수동 전달 상태 이력: 조회 전", 15);
        deliveryHistoryStatusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        reportOperationsFormGroup.addView(deliveryHistoryStatusText, matchWrap());
        deliveryHistoryNextButton = button("다음 이력 불러오기");
        deliveryHistoryNextButton.setOnClickListener(view -> readDeliveries(true));
        deliveryHistoryNextButton.setVisibility(View.GONE);
        reportOperationsFormGroup.addView(deliveryHistoryNextButton, matchWrap());
        group.addView(reportOperationsFormGroup, matchWrap());

        TextView requiredParallelHeading = text("필수 병행 업무", 22);
        requiredParallelHeading.setPadding(0, dp(32), 0, dp(4));
        markAccessibilityHeading(requiredParallelHeading);
        group.addView(requiredParallelHeading, matchWrap());
        group.addView(text(
            "사용자 정정·삭제 요청과 중대 사고 기록은 주 신고 검수와 함께 빠짐없이 확인합니다.",
            15
        ), matchWrap());
        group.addView(requiredParallelOperationsGroup, matchWrap());

        TextView auditSupplementHeading = text("보조 감사 기록", 20);
        auditSupplementHeading.setPadding(0, dp(28), 0, dp(4));
        markAccessibilityHeading(auditSupplementHeading);
        group.addView(auditSupplementHeading, matchWrap());
        group.addView(text(
            "감사 이력은 필요할 때 펼쳐 확인하는 보조 기록입니다.",
            15
        ), matchWrap());
        Button auditToggle = button("감사 기록 펼치기");
        auditToggle.setOnClickListener(view -> {
            boolean opening = auditSupplementGroup.getVisibility() != View.VISIBLE;
            auditSupplementGroup.setVisibility(opening ? View.VISIBLE : View.GONE);
            auditToggle.setText(opening ? "감사 기록 접기" : "감사 기록 펼치기");
            auditToggle.setContentDescription(auditToggle.getText());
            if (opening) auditSupplementGroup.requestFocus();
        });
        group.addView(auditToggle, matchWrap());
        auditSupplementGroup.setVisibility(View.GONE);
        group.addView(auditSupplementGroup, matchWrap());
        return group;
    }

    private void recordReviewDecision() {
        try {
            String reportId = requireConnectedOperationsReportId();
            AdminReportDecision.Decision selectedDecision =
                REVIEW_DECISIONS[reviewDecisionInput.getSelectedItemPosition()];
            String evidenceGrantId = null;
            boolean retryingPendingDecision = reviewDecisionAttempt.isPending();
            if (selectedDecision == AdminReportDecision.Decision.APPROVED
                && !retryingPendingDecision) {
                if (originalEvidenceConfirmedInput == null
                    || !originalEvidenceConfirmedInput.isChecked()) {
                    if (originalEvidenceConfirmedInput != null) {
                        originalEvidenceConfirmedInput.requestFocus();
                    }
                    resultText.setText("승인 전 현재 표시된 정확한 위치와 원본 사진을 직접 확인해 주세요.");
                    return;
                }
                String sessionId = boundOperationsSessionId;
                if (originalEvidence == null
                    || sessionId == null
                    || !originalEvidence.matches(
                        reportId,
                        connectedOperationsContentRevision,
                        sessionId,
                        deviceId,
                        System.currentTimeMillis()
                    )) {
                    clearOriginalEvidence("원본 증거가 만료되었거나 현재 신고·세션과 다릅니다.");
                    resultText.setText("승인용 원본 증거를 현재 신고에서 다시 열람해 주세요.");
                    return;
                }
                evidenceGrantId = originalEvidence.grantId();
            } else if (selectedDecision != AdminReportDecision.Decision.APPROVED) {
                clearOriginalEvidence("승인 외 결정에는 원본 열람 grant를 사용하지 않습니다.");
            }
            AdminReportDecision decision = reviewDecisionAttempt.prepare(
                reportId,
                selectedDecision,
                normalized(reviewReasonInput),
                nullableNormalized(reviewUserVisibleReasonInput),
                nullableNormalized(duplicateReportIdInput),
                locationReviewedInput.isChecked(),
                photoReviewedInput.isChecked(),
                privacyReviewedInput.isChecked(),
                connectedOperationsContentRevision,
                evidenceGrantId
            );
            if (selectedDecision == AdminReportDecision.Decision.APPROVED
                && !retryingPendingDecision) {
                clearOriginalEvidence("확인한 원본 증거를 승인 요청에 한 번 결속했습니다.");
            }
            runOperationalOperation("검토 결정을 기록하고 있습니다.", () ->
                controller.recordReviewDecision(
                    reportId,
                    decision,
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                )
            );
        } catch (IllegalStateException error) {
            resultText.setText(error.getMessage());
        } catch (IllegalArgumentException error) {
            resultText.setText("검토 결정 입력값을 다시 확인해 주세요.");
        }
    }

    private void loadOriginalEvidence() {
        if (controller == null || operationInFlight) return;
        char[] password = null;
        char[] totp = null;
        try {
            String reportId = requireConnectedOperationsReportId();
            if (REVIEW_DECISIONS[reviewDecisionInput.getSelectedItemPosition()]
                != AdminReportDecision.Decision.APPROVED) {
                clearOriginalEvidence("승인 결정을 선택한 경우에만 원본 증거를 열람할 수 있습니다.");
                resultText.setText("원본 증거는 APPROVED 결정 검토에만 사용할 수 있습니다.");
                return;
            }
            String reason = normalized(originalEvidenceReasonInput);
            if (reason.length() < 8 || reason.length() > 500) {
                originalEvidenceReasonInput.requestFocus();
                resultText.setText("원본 열람 사유를 8자 이상 500자 이하로 입력해 주세요.");
                return;
            }
            password = takeMutableInput(originalEvidencePasswordInput);
            totp = takeMutableInput(originalEvidenceTotpInput);
            String sessionId = boundOperationsSessionId;
            if (!boundOperationsAccessActive || sessionId == null) {
                throw new IllegalStateException("관리자 세션을 다시 확인해 주세요.");
            }
            int contentRevision = connectedOperationsContentRevision;
            long sessionGeneration = operationsSessionGeneration;
            clearOriginalEvidence("재인증 후 원본 증거를 불러오고 있습니다.");
            long requestGeneration = originalEvidenceGeneration;
            operationInFlight = true;
            setInteractiveEnabled(contentRoot, false);
            resultText.setText("승인용 원본 증거를 안전하게 불러오고 있습니다.");
            PendingOriginalEvidenceCredentials requestCredentials =
                new PendingOriginalEvidenceCredentials(password, totp);
            password = null;
            totp = null;
            pendingOriginalEvidenceCredentials.set(requestCredentials);
            originalEvidenceLoadTask = networkExecutor.submit(() -> {
                try {
                    AdminOriginalEvidence loaded = controller.loadAdminOriginalEvidence(
                        reportId,
                        contentRevision,
                        reason,
                        requestCredentials.password(),
                        requestCredentials.totp(),
                        System.currentTimeMillis(),
                        BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    );
                    runOnUiThread(() -> {
                        if (requestGeneration == originalEvidenceGeneration) {
                            originalEvidenceLoadTask = null;
                        }
                        applyLoadedOriginalEvidence(
                            loaded,
                            reportId,
                            contentRevision,
                            sessionId,
                            sessionGeneration,
                            requestGeneration
                        );
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (requestGeneration == originalEvidenceGeneration) {
                            originalEvidenceLoadTask = null;
                        }
                        if (isDestroyed() || requestGeneration != originalEvidenceGeneration) return;
                        operationInFlight = false;
                        setInteractiveEnabled(contentRoot, true);
                        clearOriginalEvidence("원본 증거를 불러오지 못했습니다. 서버 상태와 재인증 정보를 확인해 주세요.");
                        resultText.setText("원본 증거를 불러오지 못했습니다. 자동 재시도하지 않습니다.");
                    });
                } finally {
                    requestCredentials.clear();
                    pendingOriginalEvidenceCredentials.compareAndSet(requestCredentials, null);
                }
            });
        } catch (IllegalArgumentException | IllegalStateException error) {
            if (password != null) Arrays.fill(password, '\0');
            if (totp != null) Arrays.fill(totp, '\0');
            resultText.setText(error.getMessage() == null
                ? "원본 열람 입력과 재인증 정보를 확인해 주세요."
                : error.getMessage());
        }
    }

    private void applyLoadedOriginalEvidence(
        AdminOriginalEvidence loaded,
        String reportId,
        int contentRevision,
        String sessionId,
        long sessionGeneration,
        long requestGeneration
    ) {
        if (loaded == null) return;
        boolean currentRequest = requestGeneration == originalEvidenceGeneration;
        if (isDestroyed()
            || !currentRequest
            || sessionGeneration != operationsSessionGeneration
            || !boundOperationsAccessActive
            || !sessionId.equals(boundOperationsSessionId)
            || !reportId.equals(connectedOperationsReportId)
            || contentRevision != connectedOperationsContentRevision
            || !loaded.matches(
                reportId,
                contentRevision,
                sessionId,
                deviceId,
                System.currentTimeMillis()
            )) {
            loaded.close();
            if (!isDestroyed() && currentRequest) {
                operationInFlight = false;
                setInteractiveEnabled(contentRoot, true);
                resultText.setText("신고·콘텐츠 버전 또는 관리자 세션이 바뀌어 원본 증거를 표시하지 않았습니다.");
            }
            return;
        }
        byte[] displayBytes = null;
        Bitmap decoded = null;
        try {
            displayBytes = loaded.copyImageBytes(System.currentTimeMillis());
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(displayBytes, 0, displayBytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1
                || bounds.outWidth > 8192 || bounds.outHeight > 8192
                || (long) bounds.outWidth * bounds.outHeight > 20_000_000L) {
                throw new IllegalStateException("원본 이미지 크기가 안전한 표시 범위를 벗어났습니다.");
            }
            int sampleSize = 1;
            while (bounds.outWidth / sampleSize > 1600 || bounds.outHeight / sampleSize > 1600) {
                sampleSize *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inMutable = true;
            options.inSampleSize = sampleSize;
            decoded = BitmapFactory.decodeByteArray(displayBytes, 0, displayBytes.length, options);
            if (decoded == null) throw new IllegalStateException("원본 이미지를 표시할 수 없습니다.");
            loaded.discardEncodedImageAfterDisplay();
        } catch (RuntimeException error) {
            if (decoded != null) destroyBitmap(decoded);
            loaded.close();
            operationInFlight = false;
            setInteractiveEnabled(contentRoot, true);
            clearOriginalEvidence("검증된 원본 이미지를 안전하게 표시하지 못했습니다.");
            resultText.setText("원본 이미지를 표시하지 않았습니다. 다른 작업을 진행하지 마세요.");
            return;
        } finally {
            if (displayBytes != null) Arrays.fill(displayBytes, (byte) 0);
        }

        originalEvidence = loaded;
        originalEvidenceBitmap = decoded;
        originalEvidenceImage.setImageBitmap(decoded);
        originalEvidenceImage.setVisibility(View.VISIBLE);
        originalEvidenceLocationText.setText(String.format(
            Locale.ROOT,
            "정확한 위치: 위도 %.7f, 경도 %.7f%s",
            loaded.latitude(),
            loaded.longitude(),
            loaded.accuracyMeters() == null
                ? ""
                : String.format(Locale.ROOT, ", 정확도 %.1fm", loaded.accuracyMeters())
        ));
        originalEvidenceStatusText.setText(
            "현재 신고·콘텐츠 버전·세션에 결속된 원본입니다. 확인 후 만료 전에 승인 결정을 기록하세요."
        );
        originalEvidenceConfirmedInput.setChecked(false);
        scheduleOriginalEvidenceExpiry(loaded);
        operationInFlight = false;
        setInteractiveEnabled(contentRoot, true);
        originalEvidenceConfirmedInput.requestFocus();
        resultText.setText("정확한 위치와 원본 사진을 표시했습니다. 직접 확인한 뒤 확인란을 선택하세요.");
    }

    private void scheduleOriginalEvidenceExpiry(AdminOriginalEvidence evidence) {
        if (originalEvidenceExpiryTask != null) {
            originalEvidenceExpiryHandler.removeCallbacks(originalEvidenceExpiryTask);
        }
        originalEvidenceExpiryTask = () -> {
            if (originalEvidence == evidence) {
                clearOriginalEvidence("원본 증거 열람 시간이 만료되어 메모리에서 지웠습니다.");
                resultText.setText("원본 증거가 만료되었습니다. 승인하려면 다시 열람해 주세요.");
            }
        };
        long delay = Math.max(1L, evidence.expiresAtEpochMs() - System.currentTimeMillis());
        originalEvidenceExpiryHandler.postDelayed(originalEvidenceExpiryTask, delay);
    }

    private void clearOriginalEvidence(String message) {
        originalEvidenceGeneration += 1L;
        if (originalEvidenceLoadTask != null) {
            originalEvidenceLoadTask.cancel(true);
            originalEvidenceLoadTask = null;
            operationInFlight = false;
            if (contentRoot != null) setInteractiveEnabled(contentRoot, true);
        }
        PendingOriginalEvidenceCredentials pendingCredentials =
            pendingOriginalEvidenceCredentials.getAndSet(null);
        if (pendingCredentials != null) pendingCredentials.clear();
        if (originalEvidenceExpiryTask != null) {
            originalEvidenceExpiryHandler.removeCallbacks(originalEvidenceExpiryTask);
            originalEvidenceExpiryTask = null;
        }
        if (originalEvidenceImage != null) {
            originalEvidenceImage.setImageDrawable(null);
            originalEvidenceImage.setVisibility(View.GONE);
        }
        if (originalEvidenceBitmap != null) destroyBitmap(originalEvidenceBitmap);
        originalEvidenceBitmap = null;
        if (originalEvidence != null) originalEvidence.close();
        originalEvidence = null;
        if (originalEvidenceConfirmedInput != null) {
            originalEvidenceConfirmedInput.setChecked(false);
            originalEvidenceConfirmedInput.setEnabled(false);
        }
        if (originalEvidenceLocationText != null) {
            originalEvidenceLocationText.setText("정확한 위치: 열람 전");
        }
        if (originalEvidenceStatusText != null) originalEvidenceStatusText.setText(message);
    }

    private void renderOriginalEvidenceConfirmationState() {
        if (originalEvidenceConfirmedInput == null) return;
        String sessionId = boundOperationsSessionId;
        if (originalEvidence == null || sessionId == null) {
            originalEvidenceConfirmedInput.setChecked(false);
            originalEvidenceConfirmedInput.setEnabled(false);
            return;
        }
        if (!originalEvidence.matches(
            connectedOperationsReportId,
            connectedOperationsContentRevision,
            sessionId,
            deviceId,
            System.currentTimeMillis()
        )) {
            clearOriginalEvidence("원본 증거가 만료되었거나 현재 신고·세션과 다릅니다.");
            return;
        }
        originalEvidenceConfirmedInput.setEnabled(!operationInFlight);
    }

    private static void destroyBitmap(Bitmap bitmap) {
        try {
            if (bitmap.isMutable() && !bitmap.isRecycled()) bitmap.eraseColor(Color.TRANSPARENT);
        } catch (RuntimeException ignored) {
            // Best-effort pixel clearing is followed by recycle and reference removal.
        }
        if (!bitmap.isRecycled()) bitmap.recycle();
    }

    private void readReviewDecisions(boolean nextPage) {
        try {
            String reportId = requireConnectedOperationsReportId();
            boolean firstPage = !nextPage || reviewHistoryNeedsFirstPage;
            if (!firstPage && reviewHistoryNextCursor == null) {
                throw new IllegalStateException("불러올 다음 검토 결정 이력이 없습니다.");
            }
            runReportHistoryOperation(
                true,
                firstPage,
                reportId,
                firstPage ? null : reviewHistoryNextCursor
            );
        } catch (IllegalStateException error) {
            resultText.setText(error.getMessage());
        }
    }

    private void recordDelivery() {
        try {
            String reportId = requireConnectedOperationsReportId();
            AdminInstitutionDelivery.Status deliveryStatus =
                DELIVERY_STATUSES[deliveryStatusInput.getSelectedItemPosition()];
            if (deliveryStatus != AdminInstitutionDelivery.Status.FAILED
                && !manualDeliveryCompletedInput.isChecked()) {
                manualDeliveryCompletedInput.requestFocus();
                resultText.setText("성공 상태는 앱 밖 수동 제출을 실제로 수행한 뒤 확인란을 선택하세요.");
                return;
            }
            String revision = normalized(expectedRevisionInput);
            String packageRevision = normalized(packageRevisionInput);
            if (!revision.matches("[0-9]{1,18}")) throw new IllegalArgumentException("invalid revision");
            if (!packageRevision.matches("[1-9][0-9]{0,17}")) {
                throw new IllegalArgumentException("invalid package revision");
            }
            long parsedPackageRevision = Long.parseLong(packageRevision);
            long parsedDeliveryRevision = Long.parseLong(revision);
            if (verifiedDeliveryPackage == null
                || !verifiedDeliveryPackage.matchesDelivery(
                    reportId,
                    parsedPackageRevision,
                    connectedOperationsContentRevision
                )
                || !verifiedDeliveryPackage.matchesFreshDetail(
                    reportController.snapshot().detail()
                )) {
                throw new IllegalArgumentException(
                    "delivery package does not match report, package revision, and content revision"
                );
            }
            if (parsedDeliveryRevision != connectedOperationsLatestDeliveryRevision
                || "RESOLVED".equals(connectedOperationsDeliveryStatus)) {
                throw new IllegalArgumentException("current delivery binding is stale");
            }
            AdminInstitutionDelivery delivery = new AdminInstitutionDelivery(
                normalized(institutionInput),
                normalized(deliveryChannelInput),
                normalized(deliveryRecipientInput),
                deliveryStatus,
                nullableNormalized(externalReceiptInput),
                normalized(deliveryReasonInput),
                nullableNormalized(evidenceSha256Input),
                normalized(observedAtInput),
                parsedPackageRevision,
                parsedDeliveryRevision,
                normalized(idempotencyKeyInput)
            );
            recordingDelivery = true;
            runOperationalOperation("수동 전달 결과를 내부 기록하고 있습니다.", () ->
                controller.recordDelivery(
                    reportId,
                    delivery,
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                )
            );
        } catch (IllegalStateException error) {
            resultText.setText(error.getMessage());
        } catch (IllegalArgumentException error) {
            resultText.setText("수동 전달 기록 입력값을 다시 확인해 주세요.");
        }
    }

    private void readDeliveries(boolean nextPage) {
        try {
            String reportId = requireConnectedOperationsReportId();
            boolean firstPage = !nextPage || deliveryHistoryNeedsFirstPage;
            if (!firstPage && deliveryHistoryNextCursor == null) {
                throw new IllegalStateException("불러올 다음 수동 전달 상태 이력이 없습니다.");
            }
            runReportHistoryOperation(
                false,
                firstPage,
                reportId,
                firstPage ? null : deliveryHistoryNextCursor
            );
        } catch (IllegalStateException error) {
            resultText.setText(error.getMessage());
        }
    }

    private void runReportHistoryOperation(
        boolean review,
        boolean firstPage,
        String reportId,
        String cursor
    ) {
        if (controller == null || operationInFlight) return;
        long generation = reportHistoryGeneration;
        operationInFlight = true;
        setInteractiveEnabled(contentRoot, false);
        resultText.setText(review
            ? "검토 결정 이력을 확인하고 있습니다."
            : "수동 전달 상태 이력을 확인하고 있습니다.");
        networkExecutor.execute(() -> {
            try {
                AdminOperationsApi.Result result = review
                    ? controller.readReviewDecisions(
                        reportId, cursor, BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    )
                    : controller.readDeliveries(
                        reportId, cursor, BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    );
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != reportHistoryGeneration
                        || !reportId.equals(connectedOperationsReportId)) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    try {
                        applyReportHistoryPage(review, firstPage, cursor, result);
                        resultText.setText(review
                            ? "검토 결정 이력을 갱신했습니다."
                            : "수동 전달 상태 이력을 갱신했습니다.");
                    } catch (IOException error) {
                        resultText.setText(
                            "이력 페이지 무결성을 확인하지 못했습니다. 기존 이력은 유지했습니다."
                        );
                    }
                    renderReportHistory();
                });
            } catch (AdminOperationsApi.HistoryCursorException error) {
                runOnUiThread(() -> finishHistoryFailure(
                    generation, reportId, review, true,
                    "이력 페이지 기준이 만료되었습니다. 기존 이력은 유지했으며 첫 페이지부터 다시 조회해 주세요."
                ));
            } catch (AdminOperationsApi.HistoryNotFoundException error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != reportHistoryGeneration) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    clearConnectedOperationsSelection();
                    resultText.setText(
                        "선택한 신고가 더 이상 존재하지 않아 업무 연결을 해제했습니다."
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() -> finishHistoryFailure(
                    generation, reportId, review, false,
                    "이력 페이지를 불러오지 못했습니다. 기존 이력과 다음 페이지 위치는 유지했습니다."
                ));
            }
        });
    }

    private void applyReportHistoryPage(
        boolean review,
        boolean firstPage,
        String requestedCursor,
        AdminOperationsApi.Result result
    ) throws IOException {
        if (result == null || !java.util.Objects.equals(result.reportId(), connectedOperationsReportId)
            || result.snapshotRevision() != result.totalCount()
            || (requestedCursor != null && requestedCursor.equals(result.nextCursor()))) {
            throw new IOException("report history page binding is invalid");
        }
        if (review) {
            if (result.kind() != AdminOperationsApi.ResultKind.REVIEW_HISTORY
                || (!firstPage && (result.snapshotRevision() != reviewHistorySnapshotRevision
                    || result.totalCount() != reviewHistoryTotalCount))) {
                throw new IOException("review history snapshot is invalid");
            }
            List<AdminOperationsApi.ReviewHistoryItem> combined = new ArrayList<>();
            if (!firstPage) combined.addAll(reviewHistoryItems);
            long expectedRevision = combined.isEmpty()
                ? 1L : combined.get(combined.size() - 1).revision() + 1L;
            for (AdminOperationsApi.ReviewHistoryItem item : result.reviewHistory()) {
                if (item.revision() != expectedRevision++) {
                    throw new IOException("review history pages are not contiguous");
                }
                combined.add(item);
            }
            requireLoadedCount(combined.size(), result.totalCount(), result.nextCursor());
            reviewHistoryItems.clear();
            reviewHistoryItems.addAll(combined);
            reviewHistorySnapshotRevision = result.snapshotRevision();
            reviewHistoryTotalCount = result.totalCount();
            reviewHistoryNextCursor = result.nextCursor();
            reviewHistoryNeedsFirstPage = false;
            return;
        }
        if (result.kind() != AdminOperationsApi.ResultKind.DELIVERY_HISTORY
            || (!firstPage && (result.snapshotRevision() != deliveryHistorySnapshotRevision
                || result.totalCount() != deliveryHistoryTotalCount))) {
            throw new IOException("delivery history snapshot is invalid");
        }
        List<AdminOperationsApi.DeliveryHistoryItem> combined = new ArrayList<>();
        if (!firstPage) combined.addAll(deliveryHistoryItems);
        long expectedRevision = combined.isEmpty()
            ? 1L : combined.get(combined.size() - 1).revision() + 1L;
        AdminOperationsApi.DeliveryHistoryItem previous = combined.isEmpty()
            ? null : combined.get(combined.size() - 1);
        for (AdminOperationsApi.DeliveryHistoryItem item : result.deliveryHistory()) {
            if (item.revision() != expectedRevision++
                || !validDeliveryHistoryTransition(previous, item)) {
                throw new IOException("delivery history pages are not contiguous");
            }
            combined.add(item);
            previous = item;
        }
        requireLoadedCount(combined.size(), result.totalCount(), result.nextCursor());
        deliveryHistoryItems.clear();
        deliveryHistoryItems.addAll(combined);
        deliveryHistorySnapshotRevision = result.snapshotRevision();
        deliveryHistoryTotalCount = result.totalCount();
        deliveryHistoryNextCursor = result.nextCursor();
        deliveryHistoryNeedsFirstPage = false;
    }

    private static void requireLoadedCount(int loaded, long total, String nextCursor)
        throws IOException {
        if (loaded > total || (nextCursor == null && loaded != total)
            || (nextCursor != null && loaded >= total)) {
            throw new IOException("report history loaded count is invalid");
        }
    }

    private void finishHistoryFailure(
        long generation,
        String reportId,
        boolean review,
        boolean reloadFirstPage,
        String message
    ) {
        if (isDestroyed() || generation != reportHistoryGeneration
            || !reportId.equals(connectedOperationsReportId)) return;
        operationInFlight = false;
        setInteractiveEnabled(contentRoot, true);
        if (reloadFirstPage) {
            if (review) reviewHistoryNeedsFirstPage = true;
            else deliveryHistoryNeedsFirstPage = true;
        }
        resultText.setText(message);
        renderReportHistory();
    }

    private void renderReportHistory() {
        if (reviewHistoryStatusText != null) {
            reviewHistoryStatusText.setText(historyText(
                "검토 결정 이력",
                reviewHistoryItems,
                reviewHistoryTotalCount,
                reviewHistorySnapshotRevision >= 0L
            ));
        }
        if (reviewHistoryNextButton != null) {
            reviewHistoryNextButton.setText(
                reviewHistoryNeedsFirstPage ? "첫 페이지 다시 불러오기" : "다음 이력 불러오기"
            );
            reviewHistoryNextButton.setVisibility(
                reviewHistoryNeedsFirstPage || reviewHistoryNextCursor != null
                    ? View.VISIBLE : View.GONE
            );
        }
        if (deliveryHistoryStatusText != null) {
            deliveryHistoryStatusText.setText(deliveryHistoryText());
        }
        if (deliveryHistoryNextButton != null) {
            deliveryHistoryNextButton.setText(
                deliveryHistoryNeedsFirstPage ? "첫 페이지 다시 불러오기" : "다음 이력 불러오기"
            );
            deliveryHistoryNextButton.setVisibility(
                deliveryHistoryNeedsFirstPage || deliveryHistoryNextCursor != null
                    ? View.VISIBLE : View.GONE
            );
        }
    }

    private static String historyText(
        String title,
        List<AdminOperationsApi.ReviewHistoryItem> items,
        long total,
        boolean loaded
    ) {
        if (!loaded) return title + ": 조회 전";
        StringBuilder message = new StringBuilder(title)
            .append(" ").append(items.size()).append("/").append(total).append("건");
        for (AdminOperationsApi.ReviewHistoryItem item : items) {
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
        return message.toString();
    }

    private String deliveryHistoryText() {
        if (deliveryHistorySnapshotRevision < 0L) return "수동 전달 상태 이력: 조회 전";
        StringBuilder message = new StringBuilder("수동 전달 상태 이력 ")
            .append(deliveryHistoryItems.size()).append("/")
            .append(deliveryHistoryTotalCount).append("건");
        for (AdminOperationsApi.DeliveryHistoryItem item : deliveryHistoryItems) {
            message.append("\n\nrevision ").append(item.revision())
                .append(" / status ").append(item.status())
                .append(" / package revision ")
                .append(item.packageRevision() == null ? "없음" : item.packageRevision())
                .append("\n기관: ").append(item.institution())
                .append("\n관찰시각: ").append(item.observedAt())
                .append("\n서버 기록시각: ").append(item.recordedAt())
                .append("\n외부 접수번호: ")
                .append(item.externalReceiptId() == null ? "없음" : item.externalReceiptId());
        }
        return message.toString();
    }

    private static boolean validDeliveryHistoryTransition(
        AdminOperationsApi.DeliveryHistoryItem previous,
        AdminOperationsApi.DeliveryHistoryItem next
    ) {
        boolean samePackage = previous != null
            && java.util.Objects.equals(previous.packageId(), next.packageId())
            && java.util.Objects.equals(previous.packageRevision(), next.packageRevision());
        if (!samePackage) {
            return next.status() == AdminInstitutionDelivery.Status.SUBMITTED
                || next.status() == AdminInstitutionDelivery.Status.FAILED;
        }
        return switch (previous.status()) {
            case FAILED -> next.status() == AdminInstitutionDelivery.Status.FAILED
                || next.status() == AdminInstitutionDelivery.Status.SUBMITTED;
            case SUBMITTED -> next.status() == AdminInstitutionDelivery.Status.ACKNOWLEDGED
                || next.status() == AdminInstitutionDelivery.Status.FAILED;
            case ACKNOWLEDGED -> next.status() == AdminInstitutionDelivery.Status.RESOLVED;
            case RESOLVED -> false;
        };
    }

    private void runOperationalOperation(String pendingMessage, OperationalOperation operation) {
        if (controller == null || operationInFlight) return;
        boolean deliveryMutation = recordingDelivery;
        String reportId = connectedOperationsReportId;
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
                    if (recordingDelivery && result.kind() == AdminOperationsApi.ResultKind.MUTATION) {
                        rotateDeliveryRecordInputsAfterSuccess();
                    }
                    if (!deliveryMutation
                        && result.kind() == AdminOperationsApi.ResultKind.MUTATION) {
                        reviewDecisionAttempt.clear();
                    }
                    recordingDelivery = false;
                    render();
                    if (result.kind() == AdminOperationsApi.ResultKind.MUTATION) {
                        refreshConnectedReportDetail();
                    }
                });
            } catch (AdminOperationsApi.MutationConflictException conflict) {
                if (!deliveryMutation) reviewDecisionAttempt.clear();
                OperationalConflictRefresh refresh = refreshOperationalConflict(
                    reportId,
                    deliveryMutation
                );
                runOnUiThread(() -> applyOperationalConflict(
                    reportId,
                    deliveryMutation,
                    refresh
                ));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    resultText.setText(
                        !deliveryMutation && reviewDecisionAttempt.isPending()
                            ? "검토 결정 기록 결과를 확정할 수 없습니다. 자동 재전송하지 않으며 같은 입력으로 다시 시도하면 기존 decision_id만 재사용합니다."
                            : "관리자 내부 업무를 완료하지 못했습니다. 입력과 서버 상태를 확인해 주세요."
                    );
                    recordingDelivery = false;
                    render();
                });
            }
        });
    }

    private OperationalConflictRefresh refreshOperationalConflict(
        String reportId,
        boolean deliveryMutation
    ) {
        AdminReportModels.Detail detail = null;
        AdminOperationsApi.Result history = null;
        if (reportId != null) {
            try {
                detail = controller.getAdminReportDetail(
                    reportId,
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
            } catch (Exception ignored) {
                // The conflict remains explicit even when reconciliation reads are unavailable.
            }
            try {
                history = deliveryMutation
                    ? controller.readDeliveries(
                        reportId,
                        null,
                        BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    )
                    : controller.readReviewDecisions(
                        reportId,
                        null,
                        BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    );
            } catch (Exception ignored) {
                // The operator can retry the read without replaying the mutation.
            }
        }
        return new OperationalConflictRefresh(detail, history);
    }

    private void applyOperationalConflict(
        String reportId,
        boolean deliveryMutation,
        OperationalConflictRefresh refresh
    ) {
        if (isDestroyed()) return;
        operationInFlight = false;
        setInteractiveEnabled(contentRoot, true);
        recordingDelivery = false;
        if (reportId == null || !reportId.equals(connectedOperationsReportId)) {
            render();
            return;
        }
        boolean consistentRefresh = operationalConflictSnapshotsMatch(
            reportId,
            deliveryMutation,
            refresh
        );
        boolean detailApplied = false;
        if (consistentRefresh) {
            reportController.replaceDetail(refresh.detail);
            reportPanel.render(reportController.snapshot());
            connectReportToOperations(refresh.detail);
            detailApplied = true;
        }
        boolean historyApplied = false;
        if (consistentRefresh) {
            try {
                applyReportHistoryPage(!deliveryMutation, true, null, refresh.history);
                historyApplied = true;
            } catch (IOException ignored) {
                // A fresh first-page read remains available and the mutation is never replayed.
            }
        }
        if (!historyApplied) {
            if (deliveryMutation) deliveryHistoryNeedsFirstPage = true;
            else reviewHistoryNeedsFirstPage = true;
        }
        renderReportHistory();
        render();
        resultText.setText(
            detailApplied && historyApplied
                ? "다른 관리자의 변경과 충돌했습니다. 자동 재제출하지 않고 최신 상세와 이력을 반영했습니다."
                : "다른 관리자의 변경과 충돌했습니다. 자동 재제출하지 않았으며 상세와 이력의 revision을 함께 확인할 수 없어 GET으로 다시 조회해 주세요."
        );
    }

    private static boolean operationalConflictSnapshotsMatch(
        String reportId,
        boolean deliveryMutation,
        OperationalConflictRefresh refresh
    ) {
        if (refresh.detail == null || refresh.history == null
            || !reportId.equals(refresh.detail.summary().id())
            || !reportId.equals(refresh.history.reportId())) {
            return false;
        }
        if (deliveryMutation) {
            return refresh.history.kind() == AdminOperationsApi.ResultKind.DELIVERY_HISTORY
                && refresh.detail.latestDeliveryRevision() == refresh.history.snapshotRevision();
        }
        if (refresh.history.kind() != AdminOperationsApi.ResultKind.REVIEW_HISTORY) {
            return false;
        }
        if (refresh.detail.review() != null) {
            return refresh.detail.review().revision() == refresh.history.snapshotRevision();
        }
        if (refresh.history.snapshotRevision() == 0L) return true;
        List<AdminOperationsApi.ReviewHistoryItem> items = refresh.history.reviewHistory();
        if (refresh.history.nextCursor() != null || items.isEmpty()) return false;
        AdminOperationsApi.ReviewHistoryItem latest = items.get(items.size() - 1);
        return latest.revision() == refresh.history.snapshotRevision()
            && latest.contentRevision() < refresh.detail.contentRevision();
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
                    .append(" / package revision ")
                    .append(item.packageRevision() == null ? "없음" : item.packageRevision())
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
            reconcileOperationsAccessBinding(false, null, null);
            statusText.setText("관리자 보안 기능은 잠겨 있습니다.");
            metadataText.setText(BuildConfig.ADMIN_WORKFLOW_STATE);
            custodyText.setText("복구자료 외부 보관 상태를 확인할 수 없습니다.");
            hideInteractiveGroups();
            operationsGroup.setVisibility(View.GONE);
            operationalLockText.setText("인증·복구·감사 기능을 승인하기 전에는 어떤 관리자 업무도 수행할 수 없습니다.");
            renderOriginalEvidenceConfirmationState();
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
        reconcileOperationsAccessBinding(
            accessActive,
            snapshot.currentSessionId(),
            snapshot.authenticatedAdminId()
        );
        boolean custodyAttested =
            snapshot.recoveryCustodyState() == AdminRecoveryCustodyState.ATTESTED;
        loginGroup.setVisibility(!accessActive && !recoveryActive ? View.VISIBLE : View.GONE);
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
        if (externalCopyDeletionPanel != null && externalCopyDeletionController != null) {
            externalCopyDeletionPanel.render(externalCopyDeletionController.snapshot());
        }
        if (auditPanel != null && auditController != null) {
            auditPanel.render(auditController.snapshot());
        }
        if (incidentPanel != null && incidentController != null) {
            incidentPanel.render(incidentController.snapshot());
        }
        if (rawCollectionPanel != null && rawCollectionController != null) {
            rawCollectionPanel.render(rawCollectionController.snapshot());
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
        renderOriginalEvidenceConfirmationState();
        if (operationsVisible && incidentRecoveryRefreshPending
            && incidentStatusAttempt.isPending()) {
            String recoveryIncidentId = incidentStatusAttempt.incidentId();
            incidentRecoveryRefreshPending = false;
            loadIncidentDetail(recoveryIncidentId);
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
        loginGroup.setVisibility(View.GONE);
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
            clearOriginalEvidence("신고 상세를 다시 조회하여 기존 원본 증거를 지웠습니다.");
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
                AdminReportController.State state = reportController.snapshot();
                reportPanel.render(state);
                if (state.detail() != null
                    && state.detail().summary().id().equals(connectedOperationsReportId)) {
                    connectReportToOperations(state.detail());
                }
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

    private void loadFirstExternalCopyDeletionPage(
        AdminExternalCopyDeletionPanel.FilterDraft draft
    ) {
        if (externalCopyDeletionController == null) return;
        try {
            executeExternalCopyDeletionRequest(externalCopyDeletionController.beginFirst(
                new AdminExternalCopyDeletionModels.Filter(draft.requestId())
            ));
        } catch (IllegalArgumentException | IllegalStateException error) {
            resultText.setText("삭제 요청 UUID 필터를 다시 확인해 주세요.");
        }
    }

    private void loadNextExternalCopyDeletionPage() {
        if (externalCopyDeletionController == null) return;
        try {
            executeExternalCopyDeletionRequest(externalCopyDeletionController.beginNext());
        } catch (IllegalStateException error) {
            resultText.setText("불러올 다음 외부기관 보관본 페이지가 없습니다.");
        }
    }

    private void retryExternalCopyDeletionRead() {
        if (externalCopyDeletionController == null) return;
        try {
            executeExternalCopyDeletionRequest(externalCopyDeletionController.beginRetry());
        } catch (IllegalStateException error) {
            resultText.setText("다시 시도할 외부기관 보관본 조회가 없습니다.");
        }
    }

    private void retryExternalCopyDeletionRecord() {
        if (externalCopyDeletionController == null) return;
        try {
            executeExternalCopyDeletionRequest(
                externalCopyDeletionController.beginRecordRetry()
            );
        } catch (IllegalStateException error) {
            resultText.setText("같은 사실·멱등키로 다시 확인할 기록이 없습니다.");
        }
    }

    private void recordExternalCopyDeletionFact(
        AdminExternalCopyDeletionModels.Item item,
        String nextState,
        String observedAt,
        String institutionReference,
        String evidenceSha256
    ) {
        if (externalCopyDeletionController == null) return;
        try {
            executeExternalCopyDeletionRequest(externalCopyDeletionController.beginRecord(
                item,
                nextState,
                observedAt,
                institutionReference,
                evidenceSha256
            ));
        } catch (IllegalArgumentException | IllegalStateException error) {
            resultText.setText(
                "앱 밖 사실 확인 시각·기관 참조값·증거 SHA-256과 현재 상태를 확인해 주세요. 회신 원문이나 연락처는 입력하지 마세요."
            );
        }
    }

    private void executeExternalCopyDeletionRequest(
        AdminExternalCopyDeletionController.Request request
    ) {
        externalCopyDeletionPanel.render(externalCopyDeletionController.snapshot());
        networkExecutor.execute(() -> {
            boolean applied = externalCopyDeletionController.execute(request);
            runOnUiThread(() -> {
                if (isDestroyed() || !applied) return;
                externalCopyDeletionPanel.render(externalCopyDeletionController.snapshot());
            });
        });
    }

    private void reconcileOperationsAccessBinding(
        boolean accessActive,
        String currentSessionId,
        String currentAdminId
    ) {
        if (!operationsAccessBindingInitialized
            || boundOperationsAccessActive != accessActive
            || !java.util.Objects.equals(boundOperationsSessionId, currentSessionId)
            || !java.util.Objects.equals(boundOperationsAdminId, currentAdminId)) {
            boolean restoringAccess = operationsAccessBindingInitialized
                && !boundOperationsAccessActive
                && accessActive;
            boolean preserveReportRecovery = restoringAccess
                && restoredReportStatusRecovery
                && reportWorkflowController != null
                && reportWorkflowController.statusDetailRecoveryReportId() != null;
            boolean preserveIncidentRecovery = restoringAccess
                && restoredIncidentStatusRecovery
                && incidentStatusAttempt.belongsToActor(currentAdminId);
            resetSessionBoundReportState(
                preserveReportRecovery,
                preserveIncidentRecovery
            );
            if (preserveReportRecovery) restoredReportStatusRecovery = false;
            if (preserveIncidentRecovery) {
                restoredIncidentStatusRecovery = false;
                incidentRecoveryRefreshPending = true;
            }
        }
        operationsAccessBindingInitialized = true;
        boundOperationsAccessActive = accessActive;
        boundOperationsSessionId = currentSessionId;
        boundOperationsAdminId = currentAdminId;
    }

    private void resetSessionBoundReportState(
        boolean preserveReportRecovery,
        boolean preserveIncidentRecovery
    ) {
        operationsSessionGeneration += 1L;
        reportHistoryGeneration += 1L;
        safSaveGeneration += 1L;
        safReconnectGeneration += 1L;
        clearPendingSafBinding();
        clearPendingSafReconnectBinding();
        clearOriginalEvidence("관리자 세션이 바뀌어 원본 증거를 지웠습니다.");
        connectedOperationsReportId = null;
        connectedOperationsContentRevision = -1;
        connectedOperationsReviewRevision = -1;
        connectedOperationsReviewDecision = null;
        connectedOperationsLatestDeliveryRevision = -1;
        connectedOperationsDeliveryRevision = -1;
        connectedOperationsPackageRevision = null;
        connectedOperationsDeliveryStatus = null;
        resetReportHistoryState();
        if (reportOperationsFormGroup != null) {
            reportOperationsFormGroup.setVisibility(View.GONE);
        }
        if (pendingDeliveryPackage != null) pendingDeliveryPackage.destroy();
        pendingDeliveryPackage = null;
        verifiedDeliveryPackage = null;
        awaitingSafResult = false;
        awaitingSafReconnectResult = false;
        recordingDelivery = false;
        clear(reportIdInput);
        if (expectedRevisionInput != null) expectedRevisionInput.setText("0");
        clear(packageRevisionInput);
        if (reconnectDeliveryPackageButton != null) {
            reconnectDeliveryPackageButton.setVisibility(View.GONE);
        }
        resetOperationsInputsForDifferentReport();
        if (reportPanel != null) reportPanel.clearSessionBoundDrafts();
        if (reportRequestPanel != null) reportRequestPanel.clearSessionBoundDrafts();
        if (externalCopyDeletionPanel != null) {
            externalCopyDeletionPanel.clearSessionBoundDrafts();
        }
        if (incidentPanel != null) {
            incidentPanel.clearSensitiveInputs();
            incidentPanel.clearSubmittedEvidence();
        }
        reviewDecisionAttempt.clear();
        if (!preserveIncidentRecovery) incidentStatusAttempt.clear();
        if (rawCollectionPanel != null) rawCollectionPanel.clearSessionBoundDrafts();
        if (reportController != null) reportController.clearSessionState();
        if (reportRequestController != null) reportRequestController.clearSessionState();
        if (externalCopyDeletionController != null) {
            externalCopyDeletionController.clearSessionState();
        }
        if (reportWorkflowController != null && !preserveReportRecovery) {
            reportWorkflowController.clearSessionState();
        }
        if (auditController != null) auditController.clearSessionState();
        if (incidentController != null) incidentController.clearSessionState();
        if (rawCollectionController != null) rawCollectionController.clearSessionState();
    }

    private void resetReportHistoryState() {
        reviewHistoryItems.clear();
        reviewHistoryNextCursor = null;
        reviewHistorySnapshotRevision = -1L;
        reviewHistoryTotalCount = 0L;
        reviewHistoryNeedsFirstPage = false;
        deliveryHistoryItems.clear();
        deliveryHistoryNextCursor = null;
        deliveryHistorySnapshotRevision = -1L;
        deliveryHistoryTotalCount = 0L;
        deliveryHistoryNeedsFirstPage = false;
        renderReportHistory();
    }

    private void clearConnectedOperationsSelection() {
        reportHistoryGeneration += 1L;
        invalidatePendingDeliveryPackageWork();
        verifiedDeliveryPackage = null;
        clearOriginalEvidence("선택한 신고가 없어 원본 증거를 지웠습니다.");
        connectedOperationsReportId = null;
        connectedOperationsContentRevision = -1;
        connectedOperationsReviewRevision = -1;
        connectedOperationsReviewDecision = null;
        connectedOperationsLatestDeliveryRevision = -1;
        connectedOperationsDeliveryRevision = -1;
        connectedOperationsPackageRevision = null;
        connectedOperationsDeliveryStatus = null;
        clear(reportIdInput);
        resetOperationsInputsForDifferentReport();
        resetReportHistoryState();
        if (reportOperationsFormGroup != null) {
            reportOperationsFormGroup.setVisibility(View.GONE);
        }
    }

    private String requireConnectedOperationsReportId() {
        String reportId = normalized(reportIdInput);
        if (connectedOperationsReportId == null
            || !connectedOperationsReportId.equals(reportId)
            || connectedOperationsContentRevision < 0) {
            throw new IllegalStateException(
                "연결된 신고가 유효하지 않습니다. 신고 상세에서 작업을 다시 연결해 주세요."
            );
        }
        return reportId;
    }

    private void connectReportToOperations(AdminReportModels.Detail detail) {
        String reportId = detail.summary().id();
        boolean reportChanged = connectedOperationsReportId != null
            && !reportId.equals(connectedOperationsReportId);
        boolean contentRevisionChanged = connectedOperationsContentRevision >= 0
            && connectedOperationsContentRevision != detail.contentRevision();
        int reviewRevision = detail.review() == null ? -1 : detail.review().revision();
        String reviewDecision = detail.review() == null ? null : detail.review().decision();
        boolean reviewChanged = connectedOperationsContentRevision >= 0
            && (connectedOperationsReviewRevision != reviewRevision
                || !java.util.Objects.equals(connectedOperationsReviewDecision, reviewDecision));
        int deliveryRevision = detail.delivery() == null ? -1 : detail.delivery().revision();
        Integer packageRevision = detail.delivery() == null
            ? null : detail.delivery().packageRevision();
        String deliveryStatus = detail.delivery() == null ? null : detail.delivery().status();
        boolean deliveryTupleChanged = !reportChanged && !contentRevisionChanged && !reviewChanged
            && connectedOperationsContentRevision >= 0
            && (connectedOperationsDeliveryRevision != deliveryRevision
                || !java.util.Objects.equals(connectedOperationsPackageRevision, packageRevision)
                || !java.util.Objects.equals(connectedOperationsDeliveryStatus, deliveryStatus)
                || connectedOperationsLatestDeliveryRevision
                    != detail.latestDeliveryRevision());
        if (reportChanged || contentRevisionChanged || reviewChanged) {
            reportHistoryGeneration += 1L;
            resetReportHistoryState();
            invalidatePendingDeliveryPackageWork();
            verifiedDeliveryPackage = null;
            clearOriginalEvidence(
                reportChanged
                    ? "다른 신고를 선택해 이전 원본 증거를 지웠습니다."
                    : contentRevisionChanged
                        ? "신고 콘텐츠 버전이 바뀌어 이전 원본 증거를 지웠습니다."
                        : "검토 결정이 바뀌어 이전 원본 증거를 지웠습니다."
            );
            resetOperationsInputsForDifferentReport();
        } else if (deliveryTupleChanged) {
            reportHistoryGeneration += 1L;
            resetReportHistoryState();
            invalidatePendingDeliveryPackageWork();
            resetPackageBoundDeliveryDraft();
        }
        connectedOperationsReportId = reportId;
        connectedOperationsContentRevision = detail.contentRevision();
        connectedOperationsReviewRevision = reviewRevision;
        connectedOperationsReviewDecision = reviewDecision;
        connectedOperationsLatestDeliveryRevision = detail.latestDeliveryRevision();
        connectedOperationsDeliveryRevision = deliveryRevision;
        connectedOperationsPackageRevision = packageRevision;
        connectedOperationsDeliveryStatus = deliveryStatus;
        reportIdInput.setText(reportId);
        expectedRevisionInput.setText(Integer.toString(detail.latestDeliveryRevision()));
        packageRevisionInput.setText(
            verifiedDeliveryPackage != null
                && verifiedDeliveryPackage.matchesFreshDetail(detail)
                ? Integer.toString(verifiedDeliveryPackage.revision())
                : ""
        );
        reconnectDeliveryPackageButton.setVisibility(
            canReconnectDeliveryPackage(detail) ? View.VISIBLE : View.GONE
        );
        resultText.setText(
            "선택한 신고를 아래 폼에 연결했습니다. 기관 전달은 앱 밖에서 수행한 사실만 기록하세요."
        );
        reportOperationsFormGroup.setVisibility(View.VISIBLE);
        reviewDecisionInput.requestFocus();
    }

    private static boolean canReconnectDeliveryPackage(AdminReportModels.Detail detail) {
        try {
            AdminDeliveryPackage.Eligibility.fromDetail(detail);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private void resetOperationsInputsForDifferentReport() {
        if (reviewDecisionInput != null) reviewDecisionInput.setSelection(0);
        clear(reviewReasonInput);
        clear(reviewUserVisibleReasonInput);
        clear(duplicateReportIdInput);
        if (locationReviewedInput != null) locationReviewedInput.setChecked(false);
        if (photoReviewedInput != null) photoReviewedInput.setChecked(false);
        if (privacyReviewedInput != null) privacyReviewedInput.setChecked(false);
        clear(originalEvidenceReasonInput);
        clear(originalEvidencePasswordInput);
        clear(originalEvidenceTotpInput);
        clearOriginalEvidence("승인용 원본 증거를 아직 불러오지 않았습니다.");

        clear(institutionInput);
        clear(deliveryChannelInput);
        clear(deliveryRecipientInput);
        if (deliveryStatusInput != null) deliveryStatusInput.setSelection(0);
        clear(externalReceiptInput);
        clear(deliveryReasonInput);
        clear(evidenceSha256Input);
        if (observedAtInput != null) observedAtInput.setText(Instant.now().toString());
        if (manualDeliveryCompletedInput != null) manualDeliveryCompletedInput.setChecked(false);
        if (idempotencyKeyInput != null) idempotencyKeyInput.setText(UUID.randomUUID().toString());
    }

    private void resetPackageBoundDeliveryDraft() {
        clear(externalReceiptInput);
        clear(evidenceSha256Input);
        if (observedAtInput != null) observedAtInput.setText(Instant.now().toString());
        if (manualDeliveryCompletedInput != null) manualDeliveryCompletedInput.setChecked(false);
        if (idempotencyKeyInput != null) idempotencyKeyInput.setText(UUID.randomUUID().toString());
    }

    private void replaceVerifiedDeliveryPackage(AdminDeliveryPackageSaver.Saved saved) {
        verifiedDeliveryPackage = saved;
        packageRevisionInput.setText(Integer.toString(saved.revision()));
        resetPackageBoundDeliveryDraft();
    }

    private void invalidatePendingDeliveryPackageWork() {
        safSaveGeneration += 1L;
        safReconnectGeneration += 1L;
        if (pendingDeliveryPackage != null) pendingDeliveryPackage.destroy();
        pendingDeliveryPackage = null;
        awaitingSafResult = false;
        awaitingSafReconnectResult = false;
        clearPendingSafBinding();
        clearPendingSafReconnectBinding();
        if (reportWorkflowController != null) reportWorkflowController.invalidate();
    }

    private void restorePendingMutationRecovery(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        String recoveryReportId = savedInstanceState.getString(
            REPORT_STATUS_RECOVERY_ID_STATE
        );
        if (reportWorkflowController != null && recoveryReportId != null) {
            try {
                boolean patchConfirmed = savedInstanceState.getBoolean(
                    REPORT_STATUS_RECOVERY_CONFIRMED_STATE,
                    false
                );
                reportWorkflowController.restoreStatusDetailRecovery(
                    recoveryReportId,
                    patchConfirmed
                        ? savedInstanceState.getString(REPORT_STATUS_RECOVERY_TARGET_STATE)
                        : null,
                    patchConfirmed
                        ? savedInstanceState.getInt(REPORT_STATUS_RECOVERY_VERSION_STATE, 0)
                        : 0,
                    patchConfirmed
                );
                restoredReportStatusRecovery = true;
                reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            } catch (IllegalArgumentException ignored) {
                reportWorkflowController.clearSessionState();
            }
        }
        String recoveryIncidentId = savedInstanceState.getString(INCIDENT_RECOVERY_ID_STATE);
        if (recoveryIncidentId == null) return;
        try {
            incidentStatusAttempt.restore(
                recoveryIncidentId,
                savedInstanceState.getString(INCIDENT_RECOVERY_ACTOR_STATE),
                savedInstanceState.getString(INCIDENT_RECOVERY_NEXT_STATE),
                savedInstanceState.getInt(INCIDENT_RECOVERY_VERSION_STATE, 0),
                savedInstanceState.getString(INCIDENT_RECOVERY_KEY_STATE),
                savedInstanceState.getString(INCIDENT_RECOVERY_REASON_DIGEST_STATE),
                savedInstanceState.getString(INCIDENT_RECOVERY_OBSERVATION_DIGEST_STATE),
                savedInstanceState.getString(INCIDENT_RECOVERY_EVIDENCE_DIGEST_STATE)
            );
            restoredIncidentStatusRecovery = true;
            incidentRecoveryRefreshPending = true;
            resultText.setText(
                "이전 중대 사고 상태 기록 결과를 복원했습니다. 재로그인 후 최신 이력을 확인하며 새 멱등키를 만들지 않습니다."
            );
        } catch (IllegalArgumentException ignored) {
            incidentStatusAttempt.clear();
        }
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
        if (externalCopyDeletionPanel != null) {
            externalCopyDeletionPanel.restore(
                new AdminExternalCopyDeletionPanel.FilterDraft(
                    savedInstanceState.getString(EXTERNAL_COPY_FILTER_REQUEST_STATE, "")
                ),
                savedInstanceState.getString(EXTERNAL_COPY_SELECTED_ID_STATE)
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
        if (rawCollectionPanel != null && rawCollectionController != null) {
            String selectedId = savedInstanceState.getString(RAW_COLLECTION_SELECTED_ID_STATE);
            rawCollectionPanel.restore(selectedId);
            if (selectedId != null) {
                try {
                    rawCollectionController.restoreSelection(selectedId);
                } catch (IllegalArgumentException ignored) {
                    // Invalid saved identifiers never cross the administrator boundary.
                }
            }
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
                detail,
                password,
                totp
            );
            executeReportWorkflow(request);
        } catch (IllegalArgumentException | IllegalStateException error) {
            resultText.setText("제출본 생성 재인증 입력을 확인해 주세요.");
        }
    }

    private void retryReportStatusDetail() {
        try {
            executeReportWorkflow(reportWorkflowController.beginStatusDetailRetry());
        } catch (IllegalStateException error) {
            resultText.setText("최신 상세만 다시 조회할 상태 변경 결과가 없습니다.");
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
        long workflowGeneration = reportWorkflowController.generationToken();
        AdminDeliveryPackage packageValue = reportWorkflowController.consumePackage();
        if (packageValue == null) {
            reportWorkflowController.markSaveFailed(workflowGeneration, false);
            reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            return;
        }
        if (pendingDeliveryPackage != null) pendingDeliveryPackage.destroy();
        pendingDeliveryPackage = packageValue;
        pendingSafSaveGeneration = ++safSaveGeneration;
        pendingSafWorkflowGeneration = workflowGeneration;
        pendingSafSessionGeneration = operationsSessionGeneration;
        pendingSafSessionId = boundOperationsSessionId;
        pendingSafRequestCode = allocateSafRequestCode();
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
            startActivityForResult(intent, pendingSafRequestCode);
        } catch (RuntimeException error) {
            awaitingSafResult = false;
            pendingDeliveryPackage.destroy();
            pendingDeliveryPackage = null;
            long failedWorkflowGeneration = pendingSafWorkflowGeneration;
            boolean current = isCurrentSafSaveBinding(
                pendingSafSaveGeneration,
                failedWorkflowGeneration,
                pendingSafSessionGeneration,
                pendingSafSessionId
            );
            clearPendingSafBinding();
            if (current && reportWorkflowController.markSaveFailed(failedWorkflowGeneration, false)) {
                reportPanel.renderWorkflow(reportWorkflowController.snapshot());
            }
        }
    }

    private void launchExistingDeliveryPackagePicker() {
        try {
            String reportId = requireConnectedOperationsReportId();
            AdminReportModels.Detail detail = reportController.snapshot().detail();
            if (detail == null || !reportId.equals(detail.summary().id())
                || detail.contentRevision() != connectedOperationsContentRevision
                || detail.latestDeliveryRevision()
                    != connectedOperationsLatestDeliveryRevision) {
                throw new IllegalStateException("현재 신고 상세를 다시 확인해 주세요.");
            }
            AdminDeliveryPackage.Eligibility eligibility =
                AdminDeliveryPackage.Eligibility.fromDetail(detail);
            if (awaitingSafReconnectResult) {
                throw new IllegalStateException("기존 제출본 선택 화면이 이미 열려 있습니다.");
            }
            pendingSafReconnectGeneration = ++safReconnectGeneration;
            pendingSafReconnectSessionGeneration = operationsSessionGeneration;
            pendingSafReconnectSessionId = boundOperationsSessionId;
            pendingSafReconnectEligibility = eligibility;
            awaitingSafReconnectResult = true;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(intent, OPEN_DELIVERY_PACKAGE_DOCUMENT);
            resultText.setText(
                "이전에 저장한 v2 제출본 ZIP을 직접 선택하세요. 파일은 수정·삭제·공유하지 않고 서버 증명과 대조합니다."
            );
        } catch (IllegalArgumentException | IllegalStateException error) {
            resultText.setText(
                "현재 콘텐츠의 승인과 전달 상태를 먼저 새로 조회해 주세요. 완료된 전달은 다시 연결할 수 없습니다."
            );
        } catch (RuntimeException error) {
            awaitingSafReconnectResult = false;
            clearPendingSafReconnectBinding();
            resultText.setText("ZIP 파일 선택 화면을 열지 못했습니다.");
        }
    }

    private void handleExistingDeliveryPackageResult(Uri uri) {
        awaitingSafReconnectResult = false;
        long reconnectGeneration = pendingSafReconnectGeneration;
        long sessionGeneration = pendingSafReconnectSessionGeneration;
        String sessionId = pendingSafReconnectSessionId;
        AdminDeliveryPackage.Eligibility eligibility = pendingSafReconnectEligibility;
        clearPendingSafReconnectBinding();
        if (uri == null) {
            resultText.setText("기존 제출본 선택을 취소했습니다. 원본 파일은 변경하지 않았습니다.");
            return;
        }
        if (!isCurrentSafReconnectBinding(
            reconnectGeneration, sessionGeneration, sessionId, eligibility
        )) {
            resultText.setText("로그인 또는 신고 상태가 바뀌어 선택한 파일을 사용하지 않았습니다.");
            return;
        }
        networkExecutor.execute(() -> {
            try {
                if (!controller.refreshAndValidateSafSaveSession(sessionId)) {
                    throw new IOException("administrator session is no longer current");
                }
                InputStream locatorInput = getContentResolver().openInputStream(uri);
                if (locatorInput == null) throw new IOException("SAF locator input unavailable");
                AdminDeliveryPackage.Reference reference =
                    AdminDeliveryPackageSaver.inspectUntrusted(locatorInput);
                if (!eligibility.reportId().equals(reference.reportId())) {
                    throw new IOException("selected package belongs to another report");
                }
                AdminReportModels.Detail beforeRead = controller.getAdminReportDetail(
                    eligibility.reportId(),
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
                if (!eligibility.matchesExact(beforeRead)) {
                    throw new IOException("report eligibility changed before package verification");
                }
                AdminDeliveryPackage.Proof proof = controller.getAdminDeliveryPackageProof(
                    reference.reportId(),
                    reference.packageRevision(),
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
                if (!proof.matchesReference(reference) || !proof.matchesFreshDetail(beforeRead)) {
                    throw new IOException("package proof does not match the fresh report detail");
                }
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("SAF input unavailable");
                AdminDeliveryPackageSaver.Saved saved =
                    AdminDeliveryPackageSaver.verifyExisting(proof, input);
                AdminReportModels.Detail afterRead = controller.getAdminReportDetail(
                    eligibility.reportId(),
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
                if (!eligibility.matchesExact(afterRead)
                    || !proof.matchesFreshDetail(afterRead)
                    || !saved.matchesProof(proof)
                    || !saved.matchesFreshDetail(afterRead)) {
                    throw new IOException("report state changed during package verification");
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || !isCurrentSafReconnectBinding(
                        reconnectGeneration, sessionGeneration, sessionId, eligibility
                    )) return;
                    reportController.replaceDetail(afterRead);
                    reportPanel.render(reportController.snapshot());
                    connectReportToOperations(afterRead);
                    replaceVerifiedDeliveryPackage(saved);
                    resultText.setText(
                        "선택한 ZIP의 서버 증명, 전체 해시, v2 매니페스트를 확인해 현재 신고에 연결했습니다. 기관 제출은 자동으로 수행하지 않습니다."
                    );
                    packageRevisionInput.requestFocus();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || !isCurrentSafReconnectBinding(
                        reconnectGeneration, sessionGeneration, sessionId, eligibility
                    )) return;
                    resultText.setText(
                        "선택한 ZIP을 현재 신고에 연결하지 않았습니다. 기존 제출본 연결과 입력, 선택한 원본 파일은 그대로 유지했습니다."
                    );
                });
            }
        });
    }

    private boolean isCurrentSafReconnectBinding(
        long reconnectGeneration,
        long sessionGeneration,
        String sessionId,
        AdminDeliveryPackage.Eligibility eligibility
    ) {
        if (eligibility == null
            || reconnectGeneration < 0L
            || reconnectGeneration != safReconnectGeneration
            || sessionGeneration != operationsSessionGeneration
            || !boundOperationsAccessActive
            || !java.util.Objects.equals(sessionId, boundOperationsSessionId)
            || !java.util.Objects.equals(eligibility.reportId(), connectedOperationsReportId)
            || eligibility.contentRevision() != connectedOperationsContentRevision
            || eligibility.reviewRevision() != connectedOperationsReviewRevision
            || eligibility.latestDeliveryRevision()
                != connectedOperationsLatestDeliveryRevision
            || controller == null) {
            return false;
        }
        AdminSecurityController.Snapshot snapshot = controller.snapshot();
        return snapshot.isAccessSessionActive()
            && java.util.Objects.equals(sessionId, snapshot.currentSessionId());
    }

    private boolean isCurrentSafSaveBinding(
        long saveGeneration,
        long workflowGeneration,
        long sessionGeneration,
        String sessionId
    ) {
        if (saveGeneration < 0L
            || saveGeneration != safSaveGeneration
            || workflowGeneration < 0L
            || reportWorkflowController == null
            || workflowGeneration != reportWorkflowController.generationToken()
            || sessionGeneration != operationsSessionGeneration
            || !boundOperationsAccessActive
            || !java.util.Objects.equals(sessionId, boundOperationsSessionId)
            || controller == null) {
            return false;
        }
        AdminSecurityController.Snapshot snapshot = controller.snapshot();
        return snapshot.isAccessSessionActive()
            && java.util.Objects.equals(sessionId, snapshot.currentSessionId());
    }

    private void clearPendingSafBinding() {
        pendingSafSaveGeneration = -1L;
        pendingSafWorkflowGeneration = -1L;
        pendingSafSessionGeneration = -1L;
        pendingSafSessionId = null;
        pendingSafRequestCode = -1;
    }

    private void clearPendingSafReconnectBinding() {
        pendingSafReconnectGeneration = -1L;
        pendingSafReconnectSessionGeneration = -1L;
        pendingSafReconnectSessionId = null;
        pendingSafReconnectEligibility = null;
    }

    private int allocateSafRequestCode() {
        int allocated = nextSafRequestCode;
        nextSafRequestCode = allocated == LAST_DELIVERY_PACKAGE_DOCUMENT_REQUEST
            ? CREATE_DELIVERY_PACKAGE_DOCUMENT
            : allocated + 1;
        return allocated;
    }

    private static boolean isSafSaveRequestCode(int requestCode) {
        return requestCode >= CREATE_DELIVERY_PACKAGE_DOCUMENT
            && requestCode <= LAST_DELIVERY_PACKAGE_DOCUMENT_REQUEST;
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

    private void loadNextIncidentHistoryPage() {
        if (incidentController == null) return;
        try {
            executeIncidentRequest(incidentController.beginNextHistoryPage());
        } catch (IllegalStateException error) {
            resultText.setText("불러올 다음 중대 사고 상태 이력이 없습니다.");
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
                AdminIncidentController.State state = incidentController.snapshot();
                incidentPanel.render(state);
                reconcileIncidentStatusAttempt(state.detail(), state.historyItems());
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
            String actorId = controller.snapshot().authenticatedAdminId();
            boolean retryingPendingAttempt = detail != null
                && incidentStatusAttempt.canRetry(
                    actorId,
                    detail.summary().incidentId(),
                    nextState
                );
            if (detail == null || (!retryingPendingAttempt
                && (!detail.allowedNextStates().contains(nextState)
                    || !AdminIncidentModels.isAllowedTransition(
                        detail.summary().status(),
                        nextState
                    )))) {
                throw new IllegalArgumentException("incident transition is not allowed");
            }
            AdminIncidentModels.StatusRequest request = incidentStatusAttempt.prepare(
                actorId,
                detail.summary().incidentId(),
                nextState,
                detail.summary().statusVersion(),
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
                    incidentStatusAttempt.clear();
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        operationInFlight = false;
                        setInteractiveEnabled(contentRoot, true);
                        incidentPanel.clearSubmittedEvidence();
                        incidentPanel.renderPendingStatusRetry(null);
                        resultText.setText(
                            "중대 사고 상태를 기록했습니다. 이 기록은 자동 복구나 자동 제어를 수행하지 않습니다."
                        );
                        loadIncidentDetail(detail.summary().incidentId());
                    });
                } catch (AdminIncidentRepository.StatusConflictException conflict) {
                    incidentStatusAttempt.clear();
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        operationInFlight = false;
                        setInteractiveEnabled(contentRoot, true);
                        incidentPanel.renderPendingStatusRetry(null);
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
                        resultText.setText(
                            "중대 사고 상태 기록 결과를 확정할 수 없습니다. 새 요청을 만들지 않고 최신 상세에서 같은 멱등키의 결과를 확인합니다."
                        );
                        loadIncidentDetail(detail.summary().incidentId());
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

    private void reconcileIncidentStatusAttempt(
        AdminIncidentModels.Detail detail,
        List<AdminIncidentModels.Event> events
    ) {
        AdminIncidentStatusAttempt.Reconciliation reconciliation =
            incidentStatusAttempt.reconcile(detail, events);
        incidentPanel.renderPendingStatusRetry(
            incidentStatusAttempt.isPending()
                && detail != null
                && incidentStatusAttempt.incidentId().equals(detail.summary().incidentId())
                ? incidentStatusAttempt.nextState()
                : null
        );
        switch (reconciliation) {
            case NONE -> { }
            case UNCONFIRMED -> resultText.setText(
                "최신 이력만으로는 보존한 멱등키의 성공을 확정할 수 없습니다. 같은 내용을 다시 입력하면 기존 키만 재사용합니다."
            );
            case CHANGED -> resultText.setText(
                "최신 상세가 이전 기록 시도와 다른 상태로 변경되었습니다. 자동 재제출하지 않았습니다."
            );
        }
    }

    private void loadRawQuarantine(char[] password, char[] totp) {
        if (rawCollectionController == null || rawCollectionPanel == null) {
            if (password != null) Arrays.fill(password, '\0');
            if (totp != null) Arrays.fill(totp, '\0');
            return;
        }
        executeRawCollectionRequest(rawCollectionController.beginLoad(password, totp));
    }

    private void retryRawQuarantine(char[] password, char[] totp) {
        if (rawCollectionController == null || rawCollectionPanel == null) {
            if (password != null) Arrays.fill(password, '\0');
            if (totp != null) Arrays.fill(totp, '\0');
            return;
        }
        try {
            executeRawCollectionRequest(rawCollectionController.beginRetry(password, totp));
        } catch (IllegalStateException error) {
            if (password != null) Arrays.fill(password, '\0');
            if (totp != null) Arrays.fill(totp, '\0');
            resultText.setText("다시 시도할 원시자료 검역 조회가 없습니다.");
        }
    }

    private void selectRawCollection(String collectionId) {
        if (rawCollectionController == null || rawCollectionPanel == null) return;
        try {
            rawCollectionController.select(collectionId);
            rawCollectionPanel.render(rawCollectionController.snapshot());
        } catch (IllegalArgumentException error) {
            resultText.setText("현재 검역 목록에서 선택할 항목을 다시 확인해 주세요.");
        }
    }

    private void executeRawCollectionRequest(AdminRawCollectionController.Request request) {
        rawCollectionPanel.render(rawCollectionController.snapshot());
        networkExecutor.execute(() -> {
            boolean applied = rawCollectionController.execute(request);
            runOnUiThread(() -> {
                if (isDestroyed() || !applied) return;
                rawCollectionPanel.render(rawCollectionController.snapshot());
            });
        });
    }

    private void runRawPurposeDecision(
        AdminRawCollectionModels.Summary item,
        AdminRawCollectionModels.PurposeDecisionRequest request,
        char[] password,
        char[] totp
    ) {
        char[] ownedPassword = password == null ? null : password.clone();
        char[] ownedTotp = totp == null ? null : totp.clone();
        if (password != null) Arrays.fill(password, '\0');
        if (totp != null) Arrays.fill(totp, '\0');
        try {
            if (item == null || request == null
                || request.expectedRevision() != item.decisionRevision(request.scope())) {
                throw new IllegalArgumentException("raw decision CAS binding is invalid");
            }
            if (operationInFlight) throw new IllegalStateException("operation is already running");
            operationInFlight = true;
            setInteractiveEnabled(contentRoot, false);
            resultText.setText(
                "원시자료 " + request.scope() + " 결정을 기록하고 있습니다. 자동 재제출하지 않습니다."
            );
            networkExecutor.execute(() -> {
                try {
                    controller.decideAdminRawCollectionPurpose(
                        item,
                        request,
                        ownedPassword,
                        ownedTotp,
                        System.currentTimeMillis(),
                        BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    );
                    runOnUiThread(() -> completeRawMutation(
                        "원시자료 목적별 결정을 기록했습니다. 새 재인증으로 최신 revision을 조회해 주세요."
                    ));
                } catch (AdminRawCollectionRepository.ConflictException conflict) {
                    runOnUiThread(() -> completeRawMutation(
                        "다른 변경으로 결정 revision 또는 멱등 결속이 충돌했습니다. 재제출하지 말고 새 재인증으로 최신 목록을 조회해 주세요."
                    ));
                } catch (Exception error) {
                    runOnUiThread(() -> completeRawMutation(
                        "결정 결과를 확정할 수 없습니다. 재제출하지 말고 새 재인증으로 최신 목록에서 확인해 주세요."
                    ));
                } finally {
                    if (ownedPassword != null) Arrays.fill(ownedPassword, '\0');
                    if (ownedTotp != null) Arrays.fill(ownedTotp, '\0');
                }
            });
        } catch (IllegalArgumentException | IllegalStateException error) {
            if (ownedPassword != null) Arrays.fill(ownedPassword, '\0');
            if (ownedTotp != null) Arrays.fill(ownedTotp, '\0');
            resultText.setText("최신 항목, 결정 증거와 재인증 정보를 확인해 주세요.");
        }
    }

    private void runRawLegalHold(
        AdminRawCollectionModels.Summary item,
        AdminRawCollectionModels.LegalHoldRequest request,
        char[] password,
        char[] totp
    ) {
        char[] ownedPassword = password == null ? null : password.clone();
        char[] ownedTotp = totp == null ? null : totp.clone();
        if (password != null) Arrays.fill(password, '\0');
        if (totp != null) Arrays.fill(totp, '\0');
        try {
            if (item == null || request == null
                || request.expectedRevision() != item.legalHoldRevision()) {
                throw new IllegalArgumentException("raw legal hold CAS binding is invalid");
            }
            if (operationInFlight) throw new IllegalStateException("operation is already running");
            operationInFlight = true;
            setInteractiveEnabled(contentRoot, false);
            resultText.setText(
                "원시자료 법적 보존 기록을 저장하고 있습니다. 자동 재제출하지 않습니다."
            );
            networkExecutor.execute(() -> {
                try {
                    controller.recordAdminRawCollectionLegalHold(
                        item.collectionId(),
                        request,
                        ownedPassword,
                        ownedTotp,
                        System.currentTimeMillis(),
                        BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    );
                    runOnUiThread(() -> completeRawMutation(
                        "원시자료 법적 보존 기록을 저장했습니다. 새 재인증으로 최신 revision을 조회해 주세요."
                    ));
                } catch (AdminRawCollectionRepository.ConflictException conflict) {
                    runOnUiThread(() -> completeRawMutation(
                        "다른 변경으로 법적 보존 revision 또는 멱등 결속이 충돌했습니다. 재제출하지 말고 새 재인증으로 최신 목록을 조회해 주세요."
                    ));
                } catch (Exception error) {
                    runOnUiThread(() -> completeRawMutation(
                        "법적 보존 결과를 확정할 수 없습니다. 재제출하지 말고 새 재인증으로 최신 목록에서 확인해 주세요."
                    ));
                } finally {
                    if (ownedPassword != null) Arrays.fill(ownedPassword, '\0');
                    if (ownedTotp != null) Arrays.fill(ownedTotp, '\0');
                }
            });
        } catch (IllegalArgumentException | IllegalStateException error) {
            if (ownedPassword != null) Arrays.fill(ownedPassword, '\0');
            if (ownedTotp != null) Arrays.fill(ownedTotp, '\0');
            resultText.setText("최신 항목, 법적 보존 근거와 재인증 정보를 확인해 주세요.");
        }
    }

    private void completeRawMutation(String message) {
        if (isDestroyed()) return;
        operationInFlight = false;
        setInteractiveEnabled(contentRoot, true);
        resultText.setText(message);
        if (rawCollectionController != null && rawCollectionPanel != null) {
            rawCollectionController.requireRefresh(
                "변경 결과 확인을 위해 새 비밀번호·추가 인증으로 최신 검역 목록을 조회해 주세요. 자동 재제출하지 않습니다."
            );
            rawCollectionPanel.render(rawCollectionController.snapshot());
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

    private static char[] takeMutableInput(EditText input) {
        Editable value = input.getText();
        char[] copy = new char[value.length()];
        value.getChars(0, value.length(), copy, 0);
        value.clear();
        return copy;
    }

    private void setInteractiveEnabled(View view, boolean enabled) {
        if (view == originalEvidenceConfirmedInput) {
            if (!enabled) {
                originalEvidenceConfirmedInput.setEnabled(false);
            } else {
                renderOriginalEvidenceConfirmationState();
            }
        } else if (view instanceof Button || view instanceof EditText || view instanceof Spinner || view instanceof CheckBox) {
            view.setEnabled(enabled);
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                setInteractiveEnabled(group.getChildAt(index), enabled);
            }
        }
    }

    private void rotateDeliveryRecordInputsAfterSuccess() {
        try {
            long nextRevision = Long.parseLong(normalized(expectedRevisionInput)) + 1L;
            expectedRevisionInput.setText(Long.toString(nextRevision));
        } catch (NumberFormatException ignored) {
            expectedRevisionInput.setText("0");
        }
        idempotencyKeyInput.setText(UUID.randomUUID().toString());
        observedAtInput.setText(Instant.now().toString());
        manualDeliveryCompletedInput.setChecked(false);
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

    @SuppressWarnings("deprecation")
    private static void markAccessibilityHeading(TextView view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setAccessibilityHeading(true);
            return;
        }
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                View host,
                AccessibilityNodeInfo info
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                    0, 1, 0, 1, true, false
                ));
            }
        });
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
        view.setContentDescription(hint);
        view.setSingleLine(true);
        view.setInputType(inputType);
        view.setMinHeight(dp(48));
        view.setSaveEnabled(false);
        view.setId(View.NO_ID);
        if (sensitive) view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setContentDescription(label);
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
        spinner.setMinimumHeight(dp(48));
        spinner.setContentDescription("선택 항목");
        spinner.setSaveEnabled(false);
        return spinner;
    }

    private Spinner labeledSpinner(String[] labels, String description) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_item,
            labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(48));
        spinner.setContentDescription(description);
        spinner.setSaveEnabled(false);
        return spinner;
    }

    private CheckBox checkBox(String label) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setTextColor(Color.BLACK);
        checkBox.setMinHeight(dp(48));
        checkBox.setContentDescription(label);
        checkBox.setSaveEnabled(false);
        return checkBox;
    }

    private static void makeReadOnly(EditText input, String description) {
        input.setKeyListener(null);
        input.setFocusable(false);
        input.setLongClickable(false);
        input.setContentDescription(description);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
