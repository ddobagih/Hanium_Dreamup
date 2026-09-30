package kr.co.hanium.dreamup.walksafe.admin;

import android.app.Activity;
import android.app.KeyguardManager;
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
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
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
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyPreparationException;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyRotation;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyUseAuthorization;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyUseRetry;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceProof;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminAuditController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminAuditModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryPackage;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryPackageSaver;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminExternalCopyDeletionController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminExternalCopyDeletionModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryCorrection;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeliveryCorrectionAttempt;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminInstitutionDelivery;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentRepository;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentStatusAttempt;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsApi;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOriginalEvidence;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminPendingRecoveryState;
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
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRecoveryCustodyAttempt;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportDecision;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionRepository;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminSecurityApi;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminMutableCredentials;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminSecurityController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminSecurityHttpClient;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminSecurityState;

public final class AdminBoundaryActivity extends Activity {
    /** S20-54: the audit's "열람 승인" is server approval and return preparation, not receipt. */
    static final String ORIGINAL_EVIDENCE_AUDIT_MEANING =
        "원본 열람 권한은 한 번만 쓰입니다. 서버 감사 기록의 원본 열람 승인은 '서버 반환 준비' 시점이며, "
            + "이 기기가 원본을 실제로 받았다는 뜻이 아닙니다.";
    /**
     * w24 misc-F7: true whether or not the server consumed the grant (a lost response, a request
     * cancelled before the server, a verification or a decoding failure): the token is gone.
     */
    static final String ORIGINAL_EVIDENCE_NOT_RECEIVED_STATUS =
        "이 기기에서 원본을 받아 표시하지 못했습니다. 기존 원본 열람 권한은 더는 쓸 수 없습니다.";
    static final String ORIGINAL_EVIDENCE_NEW_GRANT_NEXT_ACTION =
        "다시 열람하려면 비밀번호와 새 6자리 인증번호를 입력하고 '정확한 위치·원본 사진 일회 열람'을 눌러 "
            + "새 열람 권한을 받으세요. 자동 재시도하지 않습니다.";
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
    // S18-40: reason codes of an append-only delivery correction (labels follow this order).
    private static final String[] DELIVERY_CORRECTION_REASON_LABELS = {
        "잘못된 상태를 기록함 (WRONG_STATUS)",
        "다른 신고·건의 결과를 기록함 (WRONG_REPORT)",
        "같은 관찰을 중복 기록함 (DUPLICATE_ENTRY)",
        "기타, 메모에 설명 (OTHER)"
    };
    private static final AdminDeliveryCorrection.Reason[] DELIVERY_CORRECTION_REASONS = {
        AdminDeliveryCorrection.Reason.WRONG_STATUS,
        AdminDeliveryCorrection.Reason.WRONG_REPORT,
        AdminDeliveryCorrection.Reason.DUPLICATE_ENTRY,
        AdminDeliveryCorrection.Reason.OTHER
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
    // S18-36: the unresolved attempt's public identity survives process death for lookup.
    private final AdminReviewDecisionAttempt reviewDecisionAttempt =
        new AdminReviewDecisionAttempt(new PreferencesReviewAttemptStore());
    // S18-1: a user-authentication-bound key asks for the device credential once, then retries
    // only operations without one-time secrets (S18-1-F2).
    private final AdminDeviceKeyUseAuthorization keyUseAuthorization =
        new AdminDeviceKeyUseAuthorization();
    private final AdminDeviceKeyUseRetry keyUseRetry = new AdminDeviceKeyUseRetry(keyUseAuthorization);
    private boolean awaitingDeviceKeyUse;
    private static final byte[] DEVICE_KEY_PREFLIGHT_PAYLOAD =
        "walksafe.admin-device-key-preflight.v1".getBytes(StandardCharsets.UTF_8);
    private static final String DEVICE_KEY_REENTER_CREDENTIALS_MESSAGE =
        "기기 인증을 마쳤습니다. 비밀번호와 새 인증번호를 다시 입력해 주세요. "
            + "입력했던 값은 지웠으며 자동으로 다시 보내지 않습니다.";
    private static final String DEVICE_KEY_LOCK_SCREEN_MESSAGE =
        "화면 잠금(PIN·패턴·비밀번호)이 없어 관리자 기기키를 만들 수 없습니다. "
            + "기기 설정에서 화면 잠금을 켠 뒤 앱을 다시 실행하세요.";
    // S18-3: two-phase device key rotation (pending key until the server confirms).
    private AdminDeviceKeyStore deviceKeyStore;
    private AdminDeviceKeyStore.Descriptor pendingDeviceKeyDescriptor;
    // S18-1-F1: signer the clients were built with (active key, or the pinned replacement key).
    private AdminDeviceProof.Signer deviceKeySigner;
    // Survives recreate(): build the clients for the replacement (PENDING) key.
    private static volatile boolean replacementKeyLoginRequested;
    private AdminDeviceKeyStore.Descriptor replacementLoginDescriptor;
    private boolean activeDeviceKeyUnavailable;
    private LinearLayout deviceKeyReplacementGroup;
    private TextView deviceKeyReplacementText;
    private Button replacementLoginButton;
    private Button activeKeyReturnButton;
    private boolean deviceKeyReconcileInFlight;
    private static volatile boolean deviceKeyRotationCompletedNotice;
    private TextView deviceKeyRotationText;
    private AdminPendingRecoveryState<Bundle> pendingMutationRecovery =
        new AdminPendingRecoveryState<>(null, AdminBoundaryActivity::copyMutationRecoveryState);
    private boolean restoredReportStatusRecovery;
    private boolean restoredIncidentStatusRecovery;
    private boolean incidentRecoveryRefreshPending;
    private AdminRawCollectionController rawCollectionController;
    private String deviceId;
    private AdminDeviceKeyStore.Descriptor deviceKeyDescriptor;
    private final AtomicReference<AdminMutableCredentials> pendingSecurityCredentials =
        new AtomicReference<>();
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
    private EditText lostDevicePasswordInput;
    private EditText lostDeviceTotpInput;
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
    private TextView reviewRecentStatusText;
    private TextView deliveryHistoryStatusText;
    private Button deliveryHistoryNextButton;
    private TextView deliveryRecentStatusText;
    // S18-40: correction of the latest effective delivery event (HIGH reauthentication).
    private Spinner deliveryCorrectionReasonInput;
    private EditText deliveryCorrectionMemoInput;
    private EditText deliveryCorrectionPasswordInput;
    private EditText deliveryCorrectionTotpInput;
    private TextView deliveryCorrectionTargetText;
    private final AdminDeliveryCorrectionAttempt deliveryCorrectionAttempt =
        new AdminDeliveryCorrectionAttempt();
    private Button revokeCurrentButton;
    private LinearLayout contentRoot;
    private boolean operationInFlight;
    private volatile boolean activityStarted;
    private volatile long securityOperationGeneration;
    private static final int CONFIRM_LOCAL_SESSION = 6100;
    private static final int CONFIRM_DEVICE_KEY_USE = 6101;
    private static final String REVIEW_ATTEMPT_PREFS = "walksafe_admin_review_attempt";
    private static final String CUSTODY_ATTEMPT_PREFS = "walksafe_admin_custody_attempt";
    private boolean awaitingLocalUnlock;
    private long pendingLocalUnlockGeneration = -1L;
    private long confirmedLocalUnlockGeneration = -1L;
    private Button resumeLocalSessionButton;
    private boolean recordingDelivery;
    private final List<AdminOperationsApi.ReviewHistoryItem> reviewHistoryItems =
        new ArrayList<>();
    private String reviewHistoryNextCursor;
    private long reviewHistorySnapshotRevision = -1L;
    private long reviewHistoryTotalCount;
    private boolean reviewHistoryNeedsFirstPage;
    private AdminOperationsApi.Result reviewRecentPreview;
    private final List<AdminOperationsApi.DeliveryHistoryItem> deliveryHistoryItems =
        new ArrayList<>();
    private String deliveryHistoryNextCursor;
    private long deliveryHistorySnapshotRevision = -1L;
    private long deliveryHistoryTotalCount;
    private boolean deliveryHistoryNeedsFirstPage;
    private AdminOperationsApi.Result deliveryRecentPreview;
    private AdminDeliveryPackage pendingDeliveryPackage;
    private AdminDeliveryPackageSaver.Saved verifiedDeliveryPackage;
    private AdminReportModels.DeliveryFollowUp pendingSafReconnectFollowUp;
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
    // W20A-R1: report-wide latest review revision (all content revisions); the review
    // decision CAS compares with this, not with the content-scoped current_review.
    private int connectedOperationsLatestReviewRevision = -1;
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

        pendingMutationRecovery = new AdminPendingRecoveryState<>(
            savedInstanceState, AdminBoundaryActivity::copyMutationRecoveryState
        );
        deviceId = loadOrCreateDeviceId();
        reviewDecisionAttempt.restore();
        deviceKeyFailure = BuildConfig.ADMIN_SECURITY_WORKFLOWS_ENABLED
            ? "기기 키 준비 중" : null;
        setContentView(buildContent());
        render();
        if (BuildConfig.ADMIN_SECURITY_WORKFLOWS_ENABLED) {
            initializeDeviceKeyInBackground(savedInstanceState);
        } else {
            // Keep unapplied recovery for the next instance while security is unavailable.
            restoreReportPanelState(savedInstanceState);
        }
    }

    private void initializeDeviceKeyInBackground(Bundle savedInstanceState) {
        networkExecutor.execute(() -> {
            AdminDeviceKeyStore keyStore = null;
            try {
                keyStore = new AdminDeviceKeyStore(getApplicationContext());
                AdminDeviceKeyStore.Descriptor descriptor = null;
                AdminDeviceKeyStore.Descriptor pending;
                // Rotating Activities may overlap, but must not race to create the same key.
                synchronized (AdminDeviceKeyStore.class) {
                    try {
                        descriptor = keyStore.ensureKey();
                    } catch (AdminDeviceKeyRotation.ActiveKeyUnavailableException unavailable) {
                        // S18-1-F1: keep the device identity; only the replacement path remains.
                        descriptor = null;
                    }
                    pending = pendingDeviceKeyOrNull(keyStore);
                }
                AdminDeviceKeyStore readyKeyStore = keyStore;
                AdminDeviceKeyStore.Descriptor active = descriptor;
                boolean replacementLogin = replacementKeyLoginRequested && pending != null;
                runOnUiThread(() -> {
                    pendingDeviceKeyDescriptor = pending;
                    if (replacementLogin) {
                        completeSecurityInitialization(
                            readyKeyStore,
                            pending,
                            payload -> readyKeyStore.rotation().signPinned(
                                pending.version(), pending.marker(), payload
                            ),
                            savedInstanceState
                        );
                    } else if (active != null) {
                        completeSecurityInitialization(
                            readyKeyStore, active, readyKeyStore, savedInstanceState
                        );
                    } else {
                        showActiveDeviceKeyUnavailable(readyKeyStore);
                    }
                });
            } catch (Exception error) {
                AdminDeviceKeyStore failedKeyStore = keyStore;
                boolean lockScreenRequired = AdminDeviceKeyUseAuthorization.requiresLockScreen(error);
                // S18-23/S18-21: the step that failed (metadata, key entry, key store) is shown
                // as a secret-free code with one next action, never as the exception message.
                AdminDeviceKeyPreparationException.Code failureCode =
                    AdminDeviceKeyPreparationException.classify(error);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    deviceKeyFailure = "기기 키 초기화 실패(" + failureCode.name() + ")";
                    resultText.setText(lockScreenRequired
                        ? DEVICE_KEY_LOCK_SCREEN_MESSAGE
                        : AdminDeviceKeyPreparationException.guidance(failureCode)
                    );
                    if (!lockScreenRequired) deviceKeyStore = failedKeyStore;
                    render();
                });
            }
        });
    }

    /**
     * S18-1-F1: the registered key entry is gone; only the replacement key path remains.
     * adm-F4: the first action is a device/app restart; replacement only if it persists.
     */
    private void showActiveDeviceKeyUnavailable(AdminDeviceKeyStore keyStore) {
        if (isDestroyed() || isFinishing()) return;
        deviceKeyStore = keyStore;
        activeDeviceKeyUnavailable = true;
        // S18-23: the same secret-free code family as the other preparation failures.
        deviceKeyFailure = "기기 키 사용 불가("
            + AdminDeviceKeyPreparationException.Code.REGISTERED_KEY_MISSING.name() + ")";
        resultText.setText(AdminDeviceKeyPreparationException.guidance(
            AdminDeviceKeyPreparationException.Code.REGISTERED_KEY_MISSING
        ));
        render();
    }

    private void completeSecurityInitialization(
        AdminDeviceKeyStore keyStore,
        AdminDeviceKeyStore.Descriptor descriptor,
        AdminDeviceProof.Signer signer,
        Bundle savedInstanceState
    ) {
        if (isDestroyed() || isFinishing()) return;
        deviceKeyDescriptor = descriptor;
        deviceKeyStore = keyStore;
        deviceKeySigner = signer;
        replacementLoginDescriptor = signer == keyStore ? null : descriptor;
        deviceKeyFailure = null;
        if (deviceKeyRotationCompletedNotice) {
            deviceKeyRotationCompletedNotice = false;
            resultText.setText("새 기기키로 교체했습니다. 이전 키는 서버와 기기에서 폐기되었습니다. 새 키로 다시 로그인하세요.");
        } else if (replacementLoginDescriptor != null) {
            resultText.setText("교체 키(버전 " + descriptor.version() + ")로 로그인합니다. "
                + "서버가 이 키를 확인한 로그인에서만 기기의 기본 키로 전환합니다.");
        }
        try {
            AdminSecurityHttpClient securityClient = new AdminSecurityHttpClient(
                BuildConfig.WALKSAFE_ADMIN_API_ORIGIN,
                BuildConfig.DEBUG,
                deviceKeyDescriptor,
                signer
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
                    signer
                );
                reportRepository = new AdminReportHttpClient(
                    BuildConfig.WALKSAFE_ADMIN_API_ORIGIN,
                    BuildConfig.DEBUG,
                    deviceKeyDescriptor,
                    signer
                );
                incidentRepository = new AdminIncidentHttpClient(
                    BuildConfig.WALKSAFE_ADMIN_API_ORIGIN,
                    BuildConfig.DEBUG,
                    deviceKeyDescriptor,
                    signer
                );
                rawCollectionRepository = new AdminRawCollectionHttpClient(
                    BuildConfig.WALKSAFE_ADMIN_API_ORIGIN,
                    BuildConfig.DEBUG,
                    deviceKeyDescriptor,
                    signer
                );
                operationsClientConfigured = true;
            }
            controller = new AdminSecurityController(
                securityClient,
                operationsApi,
                reportRepository,
                incidentRepository,
                rawCollectionRepository,
                new PreferencesCustodyAttemptStore()
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
            if (controller != null) controller.close();
            controller = null;
            operationsClientConfigured = false;
            resultText.setText(
                "관리자 클라이언트·화면 상태 초기화에 실패했습니다. 기기 키 오류로 단정하지 않으며 관리자 기능을 잠금 상태로 유지합니다."
            );
            render();
            return;
        }
        if (!activityStarted && controller != null) controller.suspendForBackground(false);
        render();
        try {
            pendingMutationRecovery.restore(this::restorePendingMutationRecovery);
        } catch (RuntimeException error) {
            if (controller != null) controller.close();
            controller = null;
            operationsClientConfigured = false;
            render();
            resultText.setText(
                "이전 작업 복구 상태를 적용하지 못했습니다. 복구 정보를 유지하고 관리자 기능을 잠갔습니다."
            );
            return;
        }
        restoreReportPanelState(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Bundle unappliedRecovery = pendingMutationRecovery.snapshot();
        if (unappliedRecovery != null) outState.putAll(unappliedRecovery);
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
        if (reportWorkflowController != null && unappliedRecovery == null) {
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
        if (incidentRecovery != null && unappliedRecovery == null) {
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
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (controller != null && controller.isLocallyLocked() && !controller.hasRetainedSession()) {
            controller.allowFreshAuthentication();
        }
        setInteractiveEnabled(contentRoot, !operationInFlight);
        render();
        completeLocalUnlockIfReady();
        runDeviceKeyUseRetryIfReady();
    }

    @Override
    protected void onStop() {
        AdminMutableCredentials queuedCredentials = pendingSecurityCredentials.getAndSet(null);
        if (queuedCredentials != null) queuedCredentials.close();
        activityStarted = false;
        securityOperationGeneration += 1L;
        operationInFlight = false;
        if (controller != null) {
            controller.suspendForBackground(
                awaitingSafResult || awaitingSafReconnectResult || awaitingLocalUnlock
                    || awaitingDeviceKeyUse
            );
            if (controller.isLocallyLocked()) hideInteractiveGroups();
        }
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
        if (controller != null) controller.close();
        networkExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CONFIRM_DEVICE_KEY_USE) {
            boolean expected = awaitingDeviceKeyUse;
            awaitingDeviceKeyUse = false;
            AdminDeviceKeyUseAuthorization.Decision decision =
                keyUseRetry.onCredentialResult(expected && resultCode == RESULT_OK);
            if (decision == AdminDeviceKeyUseAuthorization.Decision.RETRY_ONCE) {
                runDeviceKeyUseRetryIfReady();
            } else if (decision == AdminDeviceKeyUseAuthorization.Decision.REENTER_CREDENTIALS) {
                // S18-1-F2: the key is usable now; secrets are never replayed from memory.
                resultText.setText(DEVICE_KEY_REENTER_CREDENTIALS_MESSAGE);
            } else {
                resultText.setText("기기 인증을 완료하지 않아 기기키 서명 작업을 다시 시도하지 않았습니다.");
            }
            return;
        }
        if (requestCode == CONFIRM_LOCAL_SESSION) {
            boolean expected = awaitingLocalUnlock;
            awaitingLocalUnlock = false;
            if (expected && resultCode == RESULT_OK && controller != null
                && controller.isLocalGenerationCurrent(pendingLocalUnlockGeneration)) {
                confirmedLocalUnlockGeneration = pendingLocalUnlockGeneration;
                completeLocalUnlockIfReady();
            } else {
                resultText.setText("기기 인증을 완료하지 않아 관리자 업무 잠금을 유지합니다.");
            }
            pendingLocalUnlockGeneration = -1L;
            return;
        }
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

        // S18-1-F1: an invalidated or missing device key is replaced before login, without
        // deleting app data (the device identity and the server audit trail stay the same).
        deviceKeyReplacementGroup = group();
        deviceKeyReplacementText = text("", 13);
        deviceKeyReplacementText.setTextIsSelectable(true);
        deviceKeyReplacementText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        deviceKeyReplacementGroup.addView(deviceKeyReplacementText, matchWrap());
        Button prepareReplacementButton = button("교체용 새 기기키 준비(키 무효화·분실 시)");
        prepareReplacementButton.setOnClickListener(view -> prepareReplacementDeviceKey());
        deviceKeyReplacementGroup.addView(prepareReplacementButton, matchWrap());
        replacementLoginButton = button("운영자가 등록한 교체 키로 로그인 전환");
        replacementLoginButton.setOnClickListener(view -> switchReplacementKeyLogin(true));
        deviceKeyReplacementGroup.addView(replacementLoginButton, matchWrap());
        activeKeyReturnButton = button("기존 기기키로 돌아가기");
        activeKeyReturnButton.setOnClickListener(view -> switchReplacementKeyLogin(false));
        deviceKeyReplacementGroup.addView(activeKeyReturnButton, matchWrap());
        deviceKeyReplacementGroup.setVisibility(View.GONE);
        content.addView(deviceKeyReplacementGroup, matchWrap());

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
        resumeLocalSessionButton = button("기기 인증 후 기존 관리자 세션 재개");
        resumeLocalSessionButton.setOnClickListener(view -> requestLocalSessionResume());
        resumeLocalSessionButton.setVisibility(View.GONE);
        content.addView(resumeLocalSessionButton, matchWrap());

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
        sessionGroup.addView(text(
            "다른 기기를 분실 처리하면 그 기기의 모든 세션과 장치 키가 폐기됩니다. 처리 전 아래 비밀번호와 새 6자리 인증번호로 다시 인증합니다. 재인증을 할 수 없으면(복구자료 보관 미확인 포함) 복구코드로 분실 복구를 시작하세요. 복구는 기존 세션과 다른 기기 키를 모두 폐기합니다.",
            15
        ), matchWrap());
        lostDevicePasswordInput = input(
            "분실 처리 재인증 비밀번호",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            true
        );
        lostDeviceTotpInput = input(
            "분실 처리 새 6자리 추가 인증",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            true
        );
        lostDevicePasswordInput.setFilterTouchesWhenObscured(true);
        lostDeviceTotpInput.setFilterTouchesWhenObscured(true);
        sessionGroup.addView(lostDevicePasswordInput, matchWrap());
        sessionGroup.addView(lostDeviceTotpInput, matchWrap());
        sessionList = group();
        sessionGroup.addView(sessionList, matchWrap());
        deviceKeyRotationText = text("", 13);
        deviceKeyRotationText.setTextIsSelectable(true);
        deviceKeyRotationText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sessionGroup.addView(deviceKeyRotationText, matchWrap());
        Button prepareRotationButton = button("새 기기키 준비(기존 키는 확인 전까지 유지)");
        prepareRotationButton.setOnClickListener(view -> prepareDeviceKeyRotation());
        sessionGroup.addView(prepareRotationButton, matchWrap());
        Button confirmRotationButton = button("등록한 새 기기키 확인 후 교체");
        confirmRotationButton.setOnClickListener(view -> confirmDeviceKeyRotation());
        sessionGroup.addView(confirmRotationButton, matchWrap());
        Button abandonRotationButton = button("준비한 새 기기키 취소");
        abandonRotationButton.setOnClickListener(view -> abandonDeviceKeyRotation());
        sessionGroup.addView(abandonRotationButton, matchWrap());
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
            "검토 결정, 신고 처리 상태, 수동 기관 전달 상태는 별개입니다. 승인이 자동 전달이나 기관 접수를 뜻하지 않습니다.",
            15
        ), matchWrap());
        group.addView(text(
            "로그인이나 직전 재인증에 사용한 TOTP는 다시 쓰지 말고 새 코드가 표시된 뒤 입력하세요.",
            15
        ), matchWrap());
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
        reportOperationsFormGroup.addView(text(ORIGINAL_EVIDENCE_AUDIT_MEANING, 15), matchWrap());
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
        // w24 misc-F8: TalkBack reads the visible name first, as the next-action notice names it.
        originalEvidenceLoadButton.setContentDescription(
            "정확한 위치·원본 사진 일회 열람. 승인 검토용 정확한 위치와 원본 사진을 재인증 후 한 번 불러오기"
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
        Button reviewRecentButton = button("최근 검토 이력 보기");
        reviewRecentButton.setOnClickListener(view -> readRecentHistory(true));
        reportOperationsFormGroup.addView(reviewRecentButton, matchWrap());
        reviewRecentStatusText = text("최근 검토 이력: 조회 전", 15);
        reviewRecentStatusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        reportOperationsFormGroup.addView(reviewRecentStatusText, matchWrap());
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
        Button deliveryRecentButton = button("최근 수동 전달 이력 보기");
        deliveryRecentButton.setOnClickListener(view -> readRecentHistory(false));
        reportOperationsFormGroup.addView(deliveryRecentButton, matchWrap());
        deliveryRecentStatusText = text("최근 수동 전달 이력: 조회 전", 15);
        deliveryRecentStatusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        reportOperationsFormGroup.addView(deliveryRecentStatusText, matchWrap());
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
        TextView deliveryCorrectionHeading = text("잘못 기록한 전달 상태 정정", 18);
        deliveryCorrectionHeading.setPadding(0, 20, 0, 4);
        markAccessibilityHeading(deliveryCorrectionHeading);
        reportOperationsFormGroup.addView(deliveryCorrectionHeading, matchWrap());
        reportOperationsFormGroup.addView(text(
            "기존 기록은 지우거나 고치지 않습니다. 현재 유효한 가장 최근 전달 기록 1건에 정정 기록(사유 코드와 메모)을 덧붙이고, "
                + "그 기록은 현재 상태에서만 빠집니다. 더 이전 기록은 한 단계씩 차례로 정정합니다. "
                + "먼저 수동 전달 상태 이력을 끝까지 불러오고, 비밀번호와 새 인증번호로 재인증합니다. "
                + "실제로 해결된 뒤 다시 손상된 경우는 정정 대상이 아니며 새 신고로 접수됩니다.",
            15
        ), matchWrap());
        deliveryCorrectionReasonInput = labeledSpinner(
            DELIVERY_CORRECTION_REASON_LABELS,
            "전달 기록 정정 사유 코드"
        );
        reportOperationsFormGroup.addView(deliveryCorrectionReasonInput, matchWrap());
        deliveryCorrectionMemoInput = input(
            "정정 메모 (필수, 500자 이하)",
            InputType.TYPE_CLASS_TEXT,
            false
        );
        deliveryCorrectionPasswordInput = input(
            "정정 재인증 비밀번호",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
            true
        );
        deliveryCorrectionTotpInput = input(
            "정정 재인증 6자리 추가 인증",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            true
        );
        deliveryCorrectionPasswordInput.setFilterTouchesWhenObscured(true);
        deliveryCorrectionTotpInput.setFilterTouchesWhenObscured(true);
        reportOperationsFormGroup.addView(deliveryCorrectionMemoInput, matchWrap());
        reportOperationsFormGroup.addView(deliveryCorrectionPasswordInput, matchWrap());
        reportOperationsFormGroup.addView(deliveryCorrectionTotpInput, matchWrap());
        deliveryCorrectionTargetText = text("정정 대상: 전달 이력을 끝까지 불러온 뒤 표시", 15);
        deliveryCorrectionTargetText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        reportOperationsFormGroup.addView(deliveryCorrectionTargetText, matchWrap());
        Button deliveryCorrectionButton = button("최신 유효 전달 기록 정정 (재인증)");
        deliveryCorrectionButton.setOnClickListener(view -> correctLatestDelivery());
        reportOperationsFormGroup.addView(deliveryCorrectionButton, matchWrap());
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
            if (reviewDecisionAttempt.needsLookup()) {
                // S18-36: resolve the earlier decision_id before any new decision.
                lookupRestoredReviewDecision();
                return;
            }
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
                evidenceGrantId,
                // S18-35/W20A-R1: bind the detail's report-wide latest review revision
                // (0 = none). current_review is null after a content correction, but the
                // server CAS still counts earlier decisions; a concurrent decision makes
                // the server answer 409 and the app re-reads.
                connectedOperationsLatestReviewRevision
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
                    requestCredentials.clear();
                    Bitmap decoded;
                    try {
                        decoded = decodeOriginalEvidence(loaded);
                    } catch (RuntimeException error) {
                        loaded.close();
                        // w24 misc-F7: the grant was already used for these bytes, so an image
                        // that cannot be decoded takes the same new-grant path as a lost one.
                        throw new AdminOperationsApi.OriginalEvidenceNotReceivedException(error);
                    }
                    runOnUiThread(() -> {
                        if (requestGeneration == originalEvidenceGeneration) {
                            originalEvidenceLoadTask = null;
                        }
                        applyLoadedOriginalEvidence(
                            loaded,
                            decoded,
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
                        if (error instanceof AdminOperationsApi.OriginalEvidenceNotReceivedException) {
                            // S20-54: the one-time grant is spent; only a new grant can re-open it.
                            clearOriginalEvidence(ORIGINAL_EVIDENCE_NOT_RECEIVED_STATUS);
                            resultText.setText(ORIGINAL_EVIDENCE_NEW_GRANT_NEXT_ACTION);
                            return;
                        }
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

    private static Bitmap decodeOriginalEvidence(AdminOriginalEvidence loaded) {
        byte[] displayBytes = null;
        Bitmap decoded = null;
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("original evidence decode was cancelled");
            }
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
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("original evidence decode was cancelled");
            }
            loaded.discardEncodedImageAfterDisplay();
            Bitmap result = decoded;
            decoded = null;
            return result;
        } finally {
            if (displayBytes != null) Arrays.fill(displayBytes, (byte) 0);
            if (decoded != null) destroyBitmap(decoded);
        }
    }

    private void applyLoadedOriginalEvidence(
        AdminOriginalEvidence loaded,
        Bitmap decoded,
        String reportId,
        int contentRevision,
        String sessionId,
        long sessionGeneration,
        long requestGeneration
    ) {
        if (loaded == null) {
            if (decoded != null) destroyBitmap(decoded);
            return;
        }
        boolean currentRequest = requestGeneration == originalEvidenceGeneration;
        if (isDestroyed()
            || !activityStarted
            || controller == null
            || controller.isLocallyLocked()
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
            if (decoded != null) destroyBitmap(decoded);
            if (!isDestroyed() && activityStarted && currentRequest) {
                operationInFlight = false;
                setInteractiveEnabled(contentRoot, true);
                resultText.setText("신고·콘텐츠 버전 또는 관리자 세션이 바뀌어 원본 증거를 표시하지 않았습니다.");
            }
            return;
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

    private void readRecentHistory(boolean review) {
        try {
            runRecentHistoryOperation(review, requireConnectedOperationsReportId());
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
            AdminReportModels.Detail detail = reportController.snapshot().detail();
            AdminOperationsApi.DeliveryHistoryItem latest = latestEffectiveDeliveryForCorrection();
            AdminReportModels.DeliveryFollowUp followUp =
                AdminReportModels.DeliveryFollowUp.fromLatest(detail, latest);
            boolean continuingSubmission = followUp != null && followUp.allows(deliveryStatus);
            boolean packageMatches = continuingSubmission
                ? followUp.matchesLatest(detail, latest)
                    && followUp.matchesSavedPackage(verifiedDeliveryPackage)
                    && followUp.matchesPackage(
                        reportId, followUp.packageId(), parsedPackageRevision
                    )
                    && latest.institution().equals(normalized(institutionInput))
                : (deliveryStatus == AdminInstitutionDelivery.Status.SUBMITTED
                    || deliveryStatus == AdminInstitutionDelivery.Status.FAILED)
                    && verifiedDeliveryPackage != null
                    && verifiedDeliveryPackage.matchesDelivery(
                        reportId, parsedPackageRevision, connectedOperationsContentRevision
                    )
                    && verifiedDeliveryPackage.matchesFreshDetail(detail);
            if (!packageMatches) {
                throw new IllegalArgumentException(
                    "delivery package does not match the new submission or prior submitted package"
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

    /**
     * S18-40: the only correctable event is the latest effective one of a fully loaded history
     * whose snapshot is the report's current delivery revision.
     */
    private AdminOperationsApi.DeliveryHistoryItem latestEffectiveDeliveryForCorrection() {
        if (deliveryHistoryNeedsFirstPage
            || deliveryHistoryNextCursor != null
            || deliveryHistorySnapshotRevision < 1L
            || deliveryHistorySnapshotRevision != connectedOperationsLatestDeliveryRevision
            || deliveryHistoryItems.size() != deliveryHistoryTotalCount) {
            return null;
        }
        for (int index = deliveryHistoryItems.size() - 1; index >= 0; index--) {
            AdminOperationsApi.DeliveryHistoryItem item = deliveryHistoryItems.get(index);
            if (!item.corrected()) return item;
        }
        return null;
    }

    private void correctLatestDelivery() {
        if (controller == null || operationInFlight) return;
        char[] password = null;
        char[] totp = null;
        try {
            String reportId = requireConnectedOperationsReportId();
            AdminOperationsApi.DeliveryHistoryItem target = latestEffectiveDeliveryForCorrection();
            if (target == null) {
                resultText.setText(
                    "정정하려면 먼저 수동 전달 상태 이력을 끝까지 불러와 현재 기록 버전과 맞춰 주세요."
                );
                return;
            }
            // Review F3: the same intent keeps its ID (a resend after an uncertain result);
            // another target, revision, reason or memo gets a new ID.
            AdminDeliveryCorrection correction = deliveryCorrectionAttempt.prepare(
                target.id(),
                connectedOperationsLatestDeliveryRevision,
                DELIVERY_CORRECTION_REASONS[deliveryCorrectionReasonInput.getSelectedItemPosition()],
                normalized(deliveryCorrectionMemoInput)
            );
            password = takeMutableInput(deliveryCorrectionPasswordInput);
            totp = takeMutableInput(deliveryCorrectionTotpInput);
            char[] requestPassword = password;
            char[] requestTotp = totp;
            password = null;
            totp = null;
            boolean resendOfUncertainAttempt = deliveryCorrectionAttempt.markSent(correction);
            long generation = securityOperationGeneration;
            long sessionGeneration = operationsSessionGeneration;
            operationInFlight = true;
            setInteractiveEnabled(contentRoot, false);
            resultText.setText("재인증 후 최신 유효 전달 기록의 정정을 내부 기록하고 있습니다.");
            networkExecutor.execute(() -> {
                boolean recorded = false;
                boolean idConflict = false;
                String message;
                try {
                    controller.correctDelivery(
                        reportId,
                        correction,
                        requestPassword,
                        requestTotp,
                        System.currentTimeMillis(),
                        resendOfUncertainAttempt,
                        BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    );
                    recorded = true;
                    message = "revision " + target.revision() + " 기록을 정정했습니다. 원래 기록은 이력에 그대로 남고 "
                        + "현재 유효 상태에서만 빠집니다. 전달 이력을 다시 불러와 확인해 주세요.";
                } catch (AdminOperationsApi.CorrectionIdConflictException conflict) {
                    idConflict = true;
                    message = "이 정정 ID는 이미 다른 정정 내용에 쓰였습니다. 새 정정 ID로 바꿨으니 "
                        + "전달 이력을 처음부터 다시 불러와 앞선 정정이 기록됐는지 확인한 뒤 필요하면 다시 정정해 주세요.";
                } catch (AdminOperationsApi.MutationConflictException conflict) {
                    message = "다른 변경이 먼저 기록되어 정정하지 않았습니다. 이력을 다시 불러와 확인해 주세요.";
                } catch (Exception error) {
                    message = "정정을 기록하지 못했습니다. 재인증 정보와 서버 상태를 확인해 주세요. "
                        + "자동 재시도하지 않으며 같은 정정을 다시 보내면 같은 정정 ID만 사용합니다.";
                } finally {
                    Arrays.fill(requestPassword, '\0');
                    Arrays.fill(requestTotp, '\0');
                }
                boolean succeeded = recorded;
                boolean renewId = idConflict;
                String finalMessage = message;
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != securityOperationGeneration
                        || sessionGeneration != operationsSessionGeneration) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    resultText.setText(finalMessage);
                    if (succeeded) {
                        deliveryCorrectionAttempt.succeeded();
                        clear(deliveryCorrectionMemoInput);
                        // Review F6: the loaded history still shows the corrected event as
                        // effective, whether or not the detail tuple changes.
                        deliveryHistoryNeedsFirstPage = true;
                        refreshConnectedReportDetail();
                    } else if (renewId) {
                        deliveryCorrectionAttempt.renewAfterIdConflict();
                        deliveryHistoryNeedsFirstPage = true;
                    }
                    render();
                });
            });
        } catch (IllegalArgumentException | IllegalStateException error) {
            if (password != null) Arrays.fill(password, '\0');
            if (totp != null) Arrays.fill(totp, '\0');
            resultText.setText("정정 사유 코드와 메모(500자 이하), 재인증 정보를 확인해 주세요.");
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
        // adm-F7: onStop advances securityOperationGeneration (reportHistoryGeneration does not),
        // so a read that outlives a stop/restart cannot end a newer operation's progress state.
        long securityGeneration = securityOperationGeneration;
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
                    if (!isCurrentHistoryCallback(generation, securityGeneration)
                        || !reportId.equals(connectedOperationsReportId)) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    try {
                        applyReportHistoryPage(review, firstPage, cursor, result);
                        String refreshed = review
                            ? "검토 결정 이력을 갱신했습니다."
                            : "수동 전달 상태 이력을 갱신했습니다.";
                        // Review F3: an earlier correction whose result was lost is confirmed
                        // by its ID in the history; the next correction gets a new ID.
                        if (!review && deliveryCorrectionAttempt.confirmedBy(deliveryHistoryItems)) {
                            clear(deliveryCorrectionMemoInput);
                            refreshed = "수동 전달 상태 이력을 갱신했습니다. 앞서 결과를 확인하지 못한 정정이 "
                                + "서버에 기록된 것을 이력에서 확인했습니다. 다음 정정은 새 정정 ID를 사용합니다.";
                        }
                        resultText.setText(refreshed);
                    } catch (IOException error) {
                        resultText.setText(
                            "이력 페이지 무결성을 확인하지 못했습니다. 기존 이력은 유지했습니다."
                        );
                    }
                    renderReportHistory();
                });
            } catch (AdminOperationsApi.HistoryCursorException error) {
                runOnUiThread(() -> finishHistoryFailure(
                    generation, securityGeneration, reportId, review, true,
                    "이력 페이지 기준이 만료되었습니다. 기존 이력은 유지했으며 첫 페이지부터 다시 조회해 주세요."
                ));
            } catch (AdminOperationsApi.HistoryNotFoundException error) {
                runOnUiThread(() -> {
                    if (!isCurrentHistoryCallback(generation, securityGeneration)) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    clearConnectedOperationsSelection();
                    resultText.setText(
                        "선택한 신고가 더 이상 존재하지 않아 업무 연결을 해제했습니다."
                    );
                });
            } catch (Exception error) {
                runOnUiThread(() -> finishHistoryFailure(
                    generation, securityGeneration, reportId, review, false,
                    "이력 페이지를 불러오지 못했습니다. 기존 이력과 다음 페이지 위치는 유지했습니다."
                ));
            }
        });
    }

    private void runRecentHistoryOperation(boolean review, String reportId) {
        if (controller == null || operationInFlight) return;
        long generation = reportHistoryGeneration;
        long securityGeneration = securityOperationGeneration;
        operationInFlight = true;
        setInteractiveEnabled(contentRoot, false);
        resultText.setText(review
            ? "최근 검토 이력을 확인하고 있습니다."
            : "최근 수동 전달 이력을 확인하고 있습니다.");
        networkExecutor.execute(() -> {
            try {
                AdminOperationsApi.Result result = review
                    ? controller.readRecentReviewDecisions(
                        reportId, BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    )
                    : controller.readRecentDeliveries(
                        reportId, BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                    );
                runOnUiThread(() -> {
                    if (!isCurrentHistoryCallback(generation, securityGeneration)
                        || !reportId.equals(connectedOperationsReportId)) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    try {
                        applyRecentHistoryPreview(review, result);
                        resultText.setText("최근 이력을 확인했습니다. 정정 대상 판단에는 전체 이력을 끝까지 불러와야 합니다.");
                    } catch (IOException invalid) {
                        resultText.setText("최근 이력의 무결성을 확인하지 못했습니다. 기존 조회 결과는 유지했습니다.");
                    }
                    renderReportHistory();
                });
            } catch (AdminOperationsApi.HistoryNotFoundException missing) {
                runOnUiThread(() -> {
                    if (!isCurrentHistoryCallback(generation, securityGeneration)) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    clearConnectedOperationsSelection();
                    resultText.setText("선택한 신고가 더 이상 존재하지 않아 업무 연결을 해제했습니다.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!isCurrentHistoryCallback(generation, securityGeneration)
                        || !reportId.equals(connectedOperationsReportId)) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    resultText.setText("최근 이력을 불러오지 못했습니다. 전체 이력과 기존 조회 결과는 유지했습니다.");
                    renderReportHistory();
                });
            }
        });
    }

    private void applyRecentHistoryPreview(boolean review, AdminOperationsApi.Result result)
        throws IOException {
        if (result == null || !java.util.Objects.equals(result.reportId(), connectedOperationsReportId)
            || result.snapshotRevision() != result.totalCount() || result.nextCursor() != null
            || result.kind() != (review
                ? AdminOperationsApi.ResultKind.REVIEW_HISTORY
                : AdminOperationsApi.ResultKind.DELIVERY_HISTORY)) {
            throw new IOException("recent history report or snapshot is invalid");
        }
        long total = result.totalCount();
        long minimumKnownRevision = review
            ? connectedOperationsLatestReviewRevision : connectedOperationsLatestDeliveryRevision;
        if (total < minimumKnownRevision) {
            throw new IOException("recent history predates connected report detail");
        }
        int count = result.returnedItemCount();
        if (count > 25 || (total == 0L) != (count == 0)
            || (total > 0L && count > total)) {
            throw new IOException("recent history item count is invalid");
        }
        long expectedRevision = total - count + 1L;
        if (review) {
            for (AdminOperationsApi.ReviewHistoryItem item : result.reviewHistory()) {
                if (item.revision() != expectedRevision++) {
                    throw new IOException("recent review history is not a contiguous suffix");
                }
            }
            reviewRecentPreview = result;
        } else {
            for (AdminOperationsApi.DeliveryHistoryItem item : result.deliveryHistory()) {
                if (item.revision() != expectedRevision++) {
                    throw new IOException("recent delivery history is not a contiguous suffix");
                }
            }
            deliveryRecentPreview = result;
        }
    }

    /** adm-F7: a history-read result belongs to the started screen and both its generations. */
    private boolean isCurrentHistoryCallback(long generation, long securityGeneration) {
        return !isDestroyed() && activityStarted
            && securityGeneration == securityOperationGeneration
            && generation == reportHistoryGeneration;
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
        // S18-40: transitions follow the effective chain; corrected events carry no state.
        AdminOperationsApi.DeliveryHistoryItem previous = null;
        for (int index = combined.size() - 1; index >= 0; index--) {
            if (!combined.get(index).corrected()) {
                previous = combined.get(index);
                break;
            }
        }
        for (AdminOperationsApi.DeliveryHistoryItem item : result.deliveryHistory()) {
            if (item.revision() != expectedRevision++
                || (!item.corrected() && !validDeliveryHistoryTransition(previous, item))) {
                throw new IOException("delivery history pages are not contiguous");
            }
            combined.add(item);
            if (!item.corrected()) previous = item;
        }
        AdminOperationsHttpClient.requireCorrectionsStepBack(combined);
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
        long securityGeneration,
        String reportId,
        boolean review,
        boolean reloadFirstPage,
        String message
    ) {
        if (!isCurrentHistoryCallback(generation, securityGeneration)
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
        if (reviewRecentStatusText != null) {
            reviewRecentStatusText.setText(reviewRecentPreview == null
                ? "최근 검토 이력: 조회 전"
                : historyText(
                    "최근 검토 이력 미리보기 (전체 이력 아님)",
                    reviewRecentPreview.reviewHistory(),
                    reviewRecentPreview.totalCount(),
                    true
                ));
        }
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
        if (deliveryRecentStatusText != null) {
            deliveryRecentStatusText.setText(recentDeliveryHistoryText());
        }
        if (deliveryCorrectionTargetText != null) {
            deliveryCorrectionTargetText.setText(deliveryCorrectionTargetMessage());
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
        if (reconnectDeliveryPackageButton != null && reportController != null) {
            reconnectDeliveryPackageButton.setVisibility(
                canReconnectDeliveryPackage(reportController.snapshot().detail())
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
            if (item.correction() != null) {
                // Review F8: the Korean reason label (as in the reason picker), not the enum code.
                message.append("\n정정됨, 현재 상태에서 제외: ")
                    .append(deliveryCorrectionReasonLabel(item.correction().reason()))
                    .append(" / ").append(item.correction().memo())
                    .append("\n정정 기록시각: ").append(item.correction().createdAt());
            }
        }
        return message.toString();
    }

    private String recentDeliveryHistoryText() {
        if (deliveryRecentPreview == null) return "최근 수동 전달 이력: 조회 전";
        StringBuilder message = new StringBuilder("최근 수동 전달 이력 미리보기 (전체 이력 아님) ")
            .append(deliveryRecentPreview.deliveryHistory().size()).append("/")
            .append(deliveryRecentPreview.totalCount()).append("건");
        for (AdminOperationsApi.DeliveryHistoryItem item : deliveryRecentPreview.deliveryHistory()) {
            message.append("\n\nrevision ").append(item.revision())
                .append(" / status ").append(item.status())
                .append(" / package revision ")
                .append(item.packageRevision() == null ? "없음" : item.packageRevision())
                .append("\n기관: ").append(item.institution())
                .append("\n서버 기록시각: ").append(item.recordedAt());
            if (item.corrected()) message.append("\n정정됨, 현재 상태에서 제외");
        }
        return message.toString();
    }

    private String deliveryCorrectionTargetMessage() {
        AdminOperationsApi.DeliveryHistoryItem target = latestEffectiveDeliveryForCorrection();
        if (target != null) {
            return "정정 대상: revision " + target.revision() + " / status " + target.status()
                + " (현재 유효한 가장 최근 기록)";
        }
        return deliveryHistorySnapshotRevision < 0L || deliveryHistoryNextCursor != null
            ? "정정 대상: 전달 이력을 끝까지 불러온 뒤 표시"
            : "정정 대상: 없음 (유효한 전달 기록이 없거나 이력이 현재 버전과 다릅니다)";
    }

    private static String deliveryCorrectionReasonLabel(AdminDeliveryCorrection.Reason reason) {
        for (int index = 0; index < DELIVERY_CORRECTION_REASONS.length; index++) {
            if (DELIVERY_CORRECTION_REASONS[index] == reason) {
                return DELIVERY_CORRECTION_REASON_LABELS[index];
            }
        }
        return "알 수 없는 정정 사유";
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
        if (controller == null || operationInFlight || !activityStarted || controller.isLocallyLocked()) return;
        long generation = securityOperationGeneration;
        boolean deliveryMutation = recordingDelivery;
        String reportId = connectedOperationsReportId;
        operationInFlight = true;
        setInteractiveEnabled(contentRoot, false);
        resultText.setText(pendingMessage);
        networkExecutor.execute(() -> {
            try {
                AdminOperationsApi.Result result = operation.run();
                keyUseAuthorization.onSuccess();
                runOnUiThread(() -> {
                    if (isDestroyed() || !activityStarted
                        || generation != securityOperationGeneration) return;
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
                runOnUiThread(() -> {
                    if (isDestroyed() || !activityStarted
                        || generation != securityOperationGeneration) return;
                    applyOperationalConflict(reportId, deliveryMutation, refresh);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || !activityStarted
                        || generation != securityOperationGeneration) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    if (handleDeviceKeyUseFailure(error, () ->
                        runOperationalOperation(pendingMessage, operation))) {
                        recordingDelivery = false;
                        return;
                    }
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
        if (isDestroyed() || !activityStarted) return;
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
        AdminMutableCredentials credentials = takeSecurityCredentials(passwordInput, totpInput);
        String deviceLabel = deviceLabelInput.getText().toString();
        clearSensitiveInputs();
        runCredentialOperation("로그인 정보를 확인하고 있습니다.", "관리자 로그인이 확인되었습니다.",
            AdminRecoveryMessagePolicy.Phase.GENERAL, credentials, true, (password, totp) -> {
                controller.login(adminId, password, totp, deviceId, deviceLabel);
                promoteReplacementKeyAfterServerLogin();
            }
        );
    }

    private void refresh() {
        runSecurityOperation("서버 보안상태를 확인하고 있습니다.", "서버 보안상태를 갱신했습니다.",
            AdminRecoveryMessagePolicy.Phase.GENERAL, controller::refresh);
    }

    private void startRecovery() {
        String adminId = adminIdInput.getText().toString();
        AdminMutableCredentials credentials = takeSecurityCredentials(recoveryCodeInput, null);
        String deviceLabel = deviceLabelInput.getText().toString();
        clearSensitiveInputs();
        // Recovery start signs nothing with the device key, so it is not preflighted.
        runCredentialOperation("기존 세션을 폐기하고 복구를 시작하고 있습니다.", "복구가 시작되었습니다. 운영 업무는 계속 잠겨 있습니다.",
            AdminRecoveryMessagePolicy.Phase.RECOVERY_START, credentials, false, (recoveryCode, unused) ->
            controller.startRecovery(adminId, recoveryCode, deviceId, deviceLabel)
        );
    }

    private void completeRecovery() {
        AdminMutableCredentials credentials = takeSecurityCredentials(newPasswordInput, newTotpInput);
        String deviceLabel = deviceLabelInput.getText().toString();
        clearSensitiveInputs();
        runCredentialOperation("새 인증수단을 확인하고 있습니다.", "관리자 접근 복구가 완료되었습니다.",
            AdminRecoveryMessagePolicy.Phase.RECOVERY_COMPLETE, credentials, true, (newPassword, newTotp) -> {
                controller.completeRecovery(newPassword, newTotp, deviceId, deviceLabel);
                promoteReplacementKeyAfterServerLogin();
            }
        );
    }

    private AdminMutableCredentials takeSecurityCredentials(EditText primary, EditText secondary) {
        char[] first = takeMutableInput(primary);
        try {
            return new AdminMutableCredentials(
                first,
                secondary == null ? new char[0] : takeMutableInput(secondary)
            );
        } catch (RuntimeException error) {
            Arrays.fill(first, '\0');
            throw error;
        }
    }

    private void runCredentialOperation(
        String pendingMessage,
        String successMessage,
        AdminRecoveryMessagePolicy.Phase phase,
        AdminMutableCredentials credentials,
        boolean signsWithDeviceKey,
        CredentialOperation operation
    ) {
        if (controller == null || !activityStarted || operationInFlight) {
            credentials.close();
            return;
        }
        pendingSecurityCredentials.set(credentials);
        try {
            // S18-1-F2: the credentials are consumed by this attempt, so it is never replayed
            // after the device credential screen; the operator re-enters them instead.
            runSecurityOperation(pendingMessage, successMessage, phase, () -> {
                try {
                    // Ask for the device credential before any secret or TOTP leaves the app.
                    if (signsWithDeviceKey) preflightDeviceKeySignature();
                    // S18-20: caller-owned char[] copies go all the way to the HTTP encoder; no
                    // String of the secret is made, and both copies are zeroed after the attempt.
                    char[][] values = credentials.takeForDispatch();
                    try {
                        operation.run(values[0], values[1]);
                    } finally {
                        Arrays.fill(values[0], '\0');
                        Arrays.fill(values[1], '\0');
                    }
                } finally {
                    credentials.close();
                    pendingSecurityCredentials.compareAndSet(credentials, null);
                }
            }, true);
        } catch (RuntimeException error) {
            credentials.close();
            pendingSecurityCredentials.compareAndSet(credentials, null);
            throw error;
        }
    }

    @FunctionalInterface
    private interface CredentialOperation {
        void run(char[] primary, char[] secondary) throws Exception;
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
            "사용자의 복구자료 외부 보관 확인을 기록했습니다. 실제 백업 복원 여부는 별도 확인이 필요합니다.",
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
            // S18-18: a failed logout is still a completed local lock; only the server revoke
            // is unconfirmed, and the failure text says so separately.
            currentSession
                ? AdminRecoveryMessagePolicy.Phase.CURRENT_SESSION_LOGOUT
                : AdminRecoveryMessagePolicy.Phase.GENERAL, () ->
            controller.revokeSession(sessionId)
        );
    }

    private void reportLostDevice(String lostDeviceId) {
        if (controller == null || !activityStarted || operationInFlight) return;
        char[] password = takeMutableInput(lostDevicePasswordInput);
        char[] totp = takeMutableInput(lostDeviceTotpInput);
        if (password.length == 0 || totp.length == 0) {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
            (password.length == 0 ? lostDevicePasswordInput : lostDeviceTotpInput).requestFocus();
            resultText.setText("분실 처리 전 비밀번호와 새 6자리 인증번호를 입력해 주세요.");
            return;
        }
        // The controller wipes both buffers after the reconfirmation attempt. S18-1-F2: the
        // reauthentication consumes the TOTP, so this work is never replayed automatically.
        runSecurityOperation(
            "재인증 후 분실 기기의 모든 세션과 장치 키를 폐기하고 있습니다.",
            "분실 기기의 모든 세션과 장치 키를 폐기했습니다.",
            AdminRecoveryMessagePolicy.Phase.LOST_DEVICE_REPORT,
            () -> {
                try {
                    preflightDeviceKeySignature();
                } catch (Exception error) {
                    Arrays.fill(password, '\0');
                    Arrays.fill(totp, '\0');
                    throw error;
                }
                controller.reportLostDevice(
                    lostDeviceId,
                    password,
                    totp,
                    System.currentTimeMillis()
                );
            },
            true
        );
    }

    private void requestLocalSessionResume() {
        if (controller == null || !controller.isLocallyLocked() || awaitingLocalUnlock
            || operationInFlight) return;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        Intent confirmation = keyguard == null || !keyguard.isDeviceSecure() ? null
            : keyguard.createConfirmDeviceCredentialIntent(
                "관리자 업무 잠금 해제", "기기 인증 후 기존 세션의 서버 상태를 다시 확인합니다."
            );
        if (confirmation == null) {
            resultText.setText("기기 PIN·패턴·비밀번호를 설정해야 기존 관리자 세션을 재개할 수 있습니다.");
            return;
        }
        pendingLocalUnlockGeneration = controller.localGeneration();
        awaitingLocalUnlock = true;
        try {
            startActivityForResult(confirmation, CONFIRM_LOCAL_SESSION);
        } catch (RuntimeException error) {
            awaitingLocalUnlock = false;
            pendingLocalUnlockGeneration = -1L;
            resultText.setText("기기 인증 화면을 열지 못해 관리자 업무 잠금을 유지합니다.");
        }
    }

    private void completeLocalUnlockIfReady() {
        if (!activityStarted || confirmedLocalUnlockGeneration < 0L) return;
        long generation = confirmedLocalUnlockGeneration;
        confirmedLocalUnlockGeneration = -1L;
        runSecurityOperation(
            "기존 관리자 세션을 다시 확인하고 있습니다.",
            "기기 인증과 세션 확인을 마쳤습니다. 민감 작업에는 새 작업별 인증이 필요합니다.",
            AdminRecoveryMessagePolicy.Phase.GENERAL,
            () -> controller.resumeLocalSession(generation)
        );
    }

    private void runSecurityOperation(
        String pendingMessage,
        String successMessage,
        AdminRecoveryMessagePolicy.Phase phase,
        SecurityOperation operation
    ) {
        runSecurityOperation(pendingMessage, successMessage, phase, operation, false);
    }

    /**
     * @param credentialBound true when the operation consumes one-time secrets (password, TOTP,
     *     recovery code); such an operation is never replayed after the device credential screen
     */
    private void runSecurityOperation(
        String pendingMessage,
        String successMessage,
        AdminRecoveryMessagePolicy.Phase phase,
        SecurityOperation operation,
        boolean credentialBound
    ) {
        if (controller == null || !activityStarted) return;
        long generation = securityOperationGeneration;
        long sessionGeneration = controller.localGeneration();
        if (operationInFlight) return;
        operationInFlight = true;
        setInteractiveEnabled(contentRoot, false);
        resultText.setText(pendingMessage);
        networkExecutor.execute(() -> {
            try {
                if (!activityStarted || generation != securityOperationGeneration
                    || !controller.isLocalGenerationCurrent(sessionGeneration)) return;
                operation.run();
                keyUseAuthorization.onSuccess();
                runOnUiThread(() -> {
                    if (isDestroyed() || !activityStarted
                        || generation != securityOperationGeneration) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    resultText.setText(successMessage);
                    render();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || !activityStarted
                        || generation != securityOperationGeneration) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, true);
                    if (handleDeviceKeyUseFailure(error, credentialBound ? null : () ->
                        runSecurityOperation(pendingMessage, successMessage, phase, operation))) {
                        return;
                    }
                    // adm-F6: the logout text claims the local deletion only if it happened.
                    resultText.setText(AdminRecoveryMessagePolicy.failureMessage(
                        phase, error, controller != null && controller.hasRetainedSession()
                    ));
                    render();
                });
            }
        });
    }

    private void render() {
        boolean locallyLocked = controller != null && controller.isLocallyLocked();
        resumeLocalSessionButton.setVisibility(locallyLocked ? View.VISIBLE : View.GONE);
        if (locallyLocked) {
            hideInteractiveGroups();
            statusText.setText("화면 이탈로 관리자 업무가 잠겼습니다.");
            operationalLockText.setText("기기 인증 후 기존 세션을 재확인하세요. 서버 로그아웃이나 자동 재전송은 수행하지 않습니다.");
            // Do not turn a temporary local lock into a session change: confirmed
            // mutation recovery and pending manual-delivery identity must survive.
            return;
        }
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
            // S18-1-F1: a missing/invalid key must not leave data deletion as the only way out.
            renderDeviceKeyReplacement(true);
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
        AdminSecurityApi.RecoveryMaterialKind pendingCustodyMaterialKind =
            controller.pendingRecoveryCustodyMaterialKind();
        boolean custodyAttemptPending = pendingCustodyMaterialKind != null;
        if (custodyAttemptPending) {
            custodyMaterialKindInput.setSelection(pendingCustodyMaterialKind.ordinal());
            custodyText.append("\n이전 보관 확인 결과가 확정되지 않았습니다. 같은 시도로 다시 확인해 주세요.");
        }
        custodyMaterialKindInput.setEnabled(!custodyAttemptPending);

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
            accessActive && snapshot.securityState() == AdminSecurityState.NORMAL
                && (!custodyAttested || custodyAttemptPending)
                ? View.VISIBLE
                : View.GONE
        );
        sessionGroup.setVisibility(accessActive ? View.VISIBLE : View.GONE);
        renderDeviceKeyReplacement(!accessActive);
        revokeCurrentButton.setEnabled(snapshot.currentSessionId() != null);
        renderDevices(snapshot.sessions(), snapshot.devices());
        renderDeviceKeyRotation(accessActive, snapshot.devices());

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
        String partialNotice = controller == null ? null
            : AdminSecurityController.partialDeviceListNotice(
                controller.deviceTotal(),
                devices.size(),
                controller.devicesComplete()
            );
        if (partialNotice != null) {
            TextView partial = text(partialNotice, 15);
            partial.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            sessionList.addView(partial, matchWrap());
        }
        for (AdminSecurityApi.DeviceInfo device : devices) {
            boolean keyOnly = !activeSessionDevices.contains(device.deviceId());
            String identification = deviceIdentificationText(device);
            TextView description = text(
                (keyOnly ? "활성 장치 키만 남은 기기" : "활성 장치 키가 있는 기기")
                    + (device.isCurrent() ? " (현재 기기)" : "")
                    + "\n" + identification,
                15
            );
            description.setPadding(0, 20, 0, 4);
            sessionList.addView(description, matchWrap());
            if (!device.isCurrent()) {
                Button reportLost = button("이 기기를 분실 신고하고 모든 세션·키 폐기");
                reportLost.setContentDescription(
                    "재인증 후 분실 처리: " + identification.replace('\n', ' ')
                );
                reportLost.setOnClickListener(view -> reportLostDevice(device.deviceId()));
                sessionList.addView(reportLost, matchWrap());
            }
        }
    }

    static String deviceIdentificationText(AdminSecurityApi.DeviceInfo device) {
        return "기기 이름: " + (device.deviceLabel() == null ? "세션 기록 없음" : device.deviceLabel())
            + "\n최근 활동: " + (device.lastSeenAt() == null ? "기록 없음" : device.lastSeenAt())
            + "\n키 버전: " + (device.keyVersion() < 1 ? "확인 불가" : Integer.toString(device.keyVersion()))
            + ", 키 지문 앞 8자: " + (device.keyMarkerPrefix() == null ? "확인 불가" : device.keyMarkerPrefix())
            + "\n기기 ID: " + device.deviceId();
    }

    private void hideInteractiveGroups() {
        loginGroup.setVisibility(View.GONE);
        deviceKeyReplacementGroup.setVisibility(View.GONE);
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
        clear(lostDevicePasswordInput);
        clear(lostDeviceTotpInput);
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
        connectedOperationsLatestReviewRevision = -1;
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
        // S18-36: an unresolved decision_id is kept for lookup after the next login.
        reviewDecisionAttempt.suspendForSessionChange();
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
        reviewRecentPreview = null;
        deliveryRecentPreview = null;
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
        connectedOperationsLatestReviewRevision = -1;
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
                || connectedOperationsLatestReviewRevision != detail.latestReviewRevision()
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
        connectedOperationsLatestReviewRevision = detail.latestReviewRevision();
        connectedOperationsReviewDecision = reviewDecision;
        connectedOperationsLatestDeliveryRevision = detail.latestDeliveryRevision();
        connectedOperationsDeliveryRevision = deliveryRevision;
        connectedOperationsPackageRevision = packageRevision;
        connectedOperationsDeliveryStatus = deliveryStatus;
        reportIdInput.setText(reportId);
        expectedRevisionInput.setText(Integer.toString(detail.latestDeliveryRevision()));
        packageRevisionInput.setText(
            verifiedDeliveryPackage != null
                && (verifiedDeliveryPackage.matchesFreshDetail(detail)
                    || (deliveryFollowUp(detail) != null
                        && deliveryFollowUp(detail).matchesSavedPackage(verifiedDeliveryPackage)))
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

    private AdminReportModels.DeliveryFollowUp deliveryFollowUp(AdminReportModels.Detail detail) {
        return AdminReportModels.DeliveryFollowUp.fromLatest(
            detail, latestEffectiveDeliveryForCorrection()
        );
    }

    private boolean canReconnectDeliveryPackage(AdminReportModels.Detail detail) {
        if (deliveryFollowUp(detail) != null) return true;
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
        if (deliveryCorrectionReasonInput != null) deliveryCorrectionReasonInput.setSelection(0);
        clear(deliveryCorrectionMemoInput);
        clear(deliveryCorrectionPasswordInput);
        clear(deliveryCorrectionTotpInput);
        deliveryCorrectionAttempt.reset();
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

    private static Bundle copyMutationRecoveryState(Bundle source) {
        if (source == null) return null;
        // Copy entries without getters/defaults so value types and absent keys stay unchanged.
        Bundle copy = new Bundle(source);
        for (String key : new ArrayList<>(copy.keySet())) {
            if (!AdminPendingRecoveryState.isRecoveryKey(key)) copy.remove(key);
        }
        return copy.isEmpty() ? null : copy;
    }

    private void restorePendingMutationRecovery(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        String recoveryReportId = savedInstanceState.getString(
            REPORT_STATUS_RECOVERY_ID_STATE
        );
        if (recoveryReportId != null) {
            if (reportWorkflowController == null) {
                throw new IllegalStateException("report recovery controller is unavailable");
            }
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
            } catch (IllegalArgumentException error) {
                reportWorkflowController.clearSessionState();
                throw new IllegalStateException("saved report recovery could not be restored", error);
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
        } catch (IllegalArgumentException error) {
            incidentStatusAttempt.clear();
            throw new IllegalStateException("saved incident recovery could not be restored", error);
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
            AdminReportModels.DeliveryFollowUp followUp = deliveryFollowUp(detail);
            AdminDeliveryPackage.Eligibility currentEligibility;
            try {
                currentEligibility = AdminDeliveryPackage.Eligibility.fromDetail(detail);
            } catch (IllegalArgumentException notApprovedForNewSubmission) {
                if (followUp == null) throw notApprovedForNewSubmission;
                currentEligibility = null;
            }
            AdminDeliveryPackage.Eligibility eligibility = currentEligibility;
            if (awaitingSafReconnectResult) {
                throw new IllegalStateException("기존 제출본 선택 화면이 이미 열려 있습니다.");
            }
            pendingSafReconnectGeneration = ++safReconnectGeneration;
            pendingSafReconnectSessionGeneration = operationsSessionGeneration;
            pendingSafReconnectSessionId = boundOperationsSessionId;
            pendingSafReconnectEligibility = eligibility;
            pendingSafReconnectFollowUp = followUp;
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
                "새 제출은 현재 승인, 기존 제출 후속 기록은 수동 전달 이력 전체를 먼저 확인해 주세요."
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
        AdminReportModels.DeliveryFollowUp followUp = pendingSafReconnectFollowUp;
        clearPendingSafReconnectBinding();
        if (uri == null) {
            resultText.setText("기존 제출본 선택을 취소했습니다. 원본 파일은 변경하지 않았습니다.");
            return;
        }
        if (!isCurrentSafReconnectBinding(
            reconnectGeneration, sessionGeneration, sessionId, eligibility, followUp
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
                AdminReportModels.DeliveryPackageUse packageUse;
                try {
                    packageUse = AdminReportModels.deliveryPackageUse(
                        eligibility, followUp, reference.reportId(), reference.packageRevision()
                    );
                } catch (IllegalArgumentException invalidReference) {
                    throw new IOException("selected package belongs to another report or submission",
                        invalidReference);
                }
                AdminDeliveryPackage.Eligibility selectedEligibility =
                    packageUse == AdminReportModels.DeliveryPackageUse.NEW_SUBMISSION
                        ? eligibility : null;
                AdminReportModels.DeliveryFollowUp selectedFollowUp =
                    packageUse == AdminReportModels.DeliveryPackageUse.FOLLOW_UP
                        ? followUp : null;
                if (!isCurrentSafReconnectBinding(
                    reconnectGeneration, sessionGeneration, sessionId,
                    selectedEligibility, selectedFollowUp
                )) throw new IOException("report or submitted package changed during selection");
                String reportId = reference.reportId();
                AdminReportModels.Detail beforeRead = controller.getAdminReportDetail(
                    reportId,
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
                if (selectedFollowUp == null
                    ? !selectedEligibility.matchesExact(beforeRead)
                    : !selectedFollowUp.matchesDetail(beforeRead)) {
                    throw new IOException("report eligibility changed before package verification");
                }
                AdminDeliveryPackage.Proof proof = controller.getAdminDeliveryPackageProof(
                    reference.reportId(),
                    reference.packageRevision(),
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
                if (!proof.matchesReference(reference)
                    || (selectedFollowUp == null && !proof.matchesFreshDetail(beforeRead))) {
                    throw new IOException("package proof does not match the fresh report detail");
                }
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("SAF input unavailable");
                AdminDeliveryPackageSaver.Saved saved =
                    AdminDeliveryPackageSaver.verifyExisting(proof, input);
                AdminReportModels.Detail afterRead = controller.getAdminReportDetail(
                    reportId,
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
                if (!saved.matchesProof(proof)
                    || (selectedFollowUp == null
                        ? !selectedEligibility.matchesExact(afterRead)
                            || !proof.matchesFreshDetail(afterRead)
                            || !saved.matchesFreshDetail(afterRead)
                        : !selectedFollowUp.matchesDetail(afterRead)
                            || !selectedFollowUp.matchesSavedPackage(saved))) {
                    throw new IOException("report state changed during package verification");
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || !isCurrentSafReconnectBinding(
                        reconnectGeneration, sessionGeneration, sessionId,
                        selectedEligibility, selectedFollowUp
                    )) return;
                    if (selectedFollowUp != null
                        && (afterRead.contentRevision() != connectedOperationsContentRevision
                            || afterRead.latestReviewRevision()
                                != connectedOperationsLatestReviewRevision)) {
                        resultText.setText("신고 내용이나 검수가 바뀌었습니다. 상세와 전달 이력을 다시 확인해 주세요.");
                        return;
                    }
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
                        reconnectGeneration, sessionGeneration, sessionId, eligibility, followUp
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
        AdminDeliveryPackage.Eligibility eligibility,
        AdminReportModels.DeliveryFollowUp followUp
    ) {
        if ((eligibility == null && followUp == null)
            || reconnectGeneration < 0L
            || reconnectGeneration != safReconnectGeneration
            || sessionGeneration != operationsSessionGeneration
            || !boundOperationsAccessActive
            || !java.util.Objects.equals(sessionId, boundOperationsSessionId)
            || controller == null) {
            return false;
        }
        boolean currentSubmissionValid = eligibility != null
            && java.util.Objects.equals(eligibility.reportId(), connectedOperationsReportId)
            && eligibility.contentRevision() == connectedOperationsContentRevision
            && eligibility.reviewRevision() == connectedOperationsReviewRevision
            && eligibility.latestDeliveryRevision()
                == connectedOperationsLatestDeliveryRevision;
        boolean followUpValid = followUp != null
            && java.util.Objects.equals(followUp.reportId(), connectedOperationsReportId)
            && followUp.matchesLatest(
                reportController.snapshot().detail(), latestEffectiveDeliveryForCorrection()
            );
        if (!currentSubmissionValid && !followUpValid) return false;
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
        pendingSafReconnectFollowUp = null;
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
            // S18-16: the result applies only to the same screen activity and session.
            long generation = securityOperationGeneration;
            long sessionGeneration = operationsSessionGeneration;
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
                        if (!isCurrentOperationCallback(generation, sessionGeneration)) return;
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
                        if (!isCurrentOperationCallback(generation, sessionGeneration)) return;
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
                        if (!isCurrentOperationCallback(generation, sessionGeneration)) return;
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
            long generation = securityOperationGeneration;
            long sessionGeneration = operationsSessionGeneration;
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
                        generation, sessionGeneration,
                        "원시자료 목적별 결정을 기록했습니다. 새 재인증으로 최신 revision을 조회해 주세요."
                    ));
                } catch (AdminRawCollectionRepository.ConflictException conflict) {
                    runOnUiThread(() -> completeRawMutation(
                        generation, sessionGeneration,
                        "다른 변경으로 결정 revision 또는 멱등 결속이 충돌했습니다. 재제출하지 말고 새 재인증으로 최신 목록을 조회해 주세요."
                    ));
                } catch (Exception error) {
                    runOnUiThread(() -> completeRawMutation(
                        generation, sessionGeneration,
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
            long generation = securityOperationGeneration;
            long sessionGeneration = operationsSessionGeneration;
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
                        generation, sessionGeneration,
                        "원시자료 법적 보존 기록을 저장했습니다. 새 재인증으로 최신 revision을 조회해 주세요."
                    ));
                } catch (AdminRawCollectionRepository.ConflictException conflict) {
                    runOnUiThread(() -> completeRawMutation(
                        generation, sessionGeneration,
                        "다른 변경으로 법적 보존 revision 또는 멱등 결속이 충돌했습니다. 재제출하지 말고 새 재인증으로 최신 목록을 조회해 주세요."
                    ));
                } catch (Exception error) {
                    runOnUiThread(() -> completeRawMutation(
                        generation, sessionGeneration,
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

    /**
     * S18-16: a network result may change the screen only while the Activity is started and
     * still in the operation and session generation it was dispatched in. Leaving the screen
     * (onStop) or a session change makes the late result a no-op; it never re-enables work.
     */
    private boolean isCurrentOperationCallback(long generation, long sessionGeneration) {
        return !isDestroyed() && activityStarted
            && generation == securityOperationGeneration
            && sessionGeneration == operationsSessionGeneration;
    }

    private void completeRawMutation(long generation, long sessionGeneration, String message) {
        if (!isCurrentOperationCallback(generation, sessionGeneration)) return;
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

    // --- S18-1: device credential for a user-authentication-bound device key -------------

    /**
     * Returns true when the failure was a device-key authentication event it handled.
     *
     * @param retry the same operation to run once more after the device credential, or null
     *     when the failed attempt consumed one-time secrets (S18-1-F2: never replayed)
     */
    private boolean handleDeviceKeyUseFailure(Throwable error, Runnable retry) {
        AdminDeviceKeyUseAuthorization.Decision decision = keyUseRetry.onFailure(error, retry);
        if (decision == AdminDeviceKeyUseAuthorization.Decision.STOP_KEY_INVALIDATED) {
            // S18-1-F3: a time-bound key is invalidated by removing/resetting the lock screen.
            resultText.setText("기기 화면 잠금(PIN·패턴·비밀번호)이 제거되거나 초기화되어 관리자 기기키를 더 쓸 수 없습니다. "
                + "관리자 업무를 잠급니다. 로그인 화면의 '교체용 새 기기키 준비'로 새 키를 만들고 운영자가 "
                + "--replace-active로 등록한 뒤 '교체 키로 로그인 전환'을 사용하세요. 앱 데이터는 지우지 마세요.");
            render();
            return true;
        }
        if (decision == AdminDeviceKeyUseAuthorization.Decision.STOP) {
            resultText.setText("기기 인증 후에도 기기키 서명을 완료하지 못했습니다. 작업을 반복 전송하지 않습니다.");
            render();
            return true;
        }
        if (decision != AdminDeviceKeyUseAuthorization.Decision.REQUEST_DEVICE_CREDENTIAL) return false;
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        Intent confirmation = keyguard == null || !keyguard.isDeviceSecure() ? null
            : keyguard.createConfirmDeviceCredentialIntent(
                "관리자 기기키 사용 확인",
                retry != null
                    ? "기기 인증 후 같은 작업을 한 번만 다시 시도합니다."
                    : "기기 인증 후 비밀번호와 새 인증번호를 다시 입력해야 합니다."
            );
        if (confirmation == null) {
            keyUseRetry.cancelPending();
            resultText.setText("기기 PIN·패턴·비밀번호가 없어 관리자 기기키로 서명할 수 없습니다.");
            render();
            return true;
        }
        awaitingDeviceKeyUse = true;
        try {
            startActivityForResult(confirmation, CONFIRM_DEVICE_KEY_USE);
            resultText.setText("관리자 기기키 사용을 위해 기기 인증이 필요합니다.");
        } catch (RuntimeException launchError) {
            awaitingDeviceKeyUse = false;
            keyUseRetry.cancelPending();
            resultText.setText("기기 인증 화면을 열지 못해 작업을 다시 시도하지 않았습니다.");
        }
        render();
        return true;
    }

    private void runDeviceKeyUseRetryIfReady() {
        if (!activityStarted) return;
        Runnable retry = keyUseRetry.takeReady();
        if (retry != null) retry.run();
    }

    /**
     * S18-1-F2: signs a fixed local payload (never sent) so a key that needs the device
     * credential asks for it before a password, TOTP or recovery material is dispatched.
     */
    private void preflightDeviceKeySignature() throws GeneralSecurityException {
        AdminDeviceProof.Signer signer = deviceKeySigner;
        if (signer != null) signer.sign(DEVICE_KEY_PREFLIGHT_PAYLOAD.clone());
    }

    // --- S18-3: two-phase device key rotation ------------------------------------------------

    private static AdminDeviceKeyStore.Descriptor pendingDeviceKeyOrNull(AdminDeviceKeyStore keyStore) {
        try {
            return keyStore.rotation().pendingOrNull();
        } catch (Exception ignored) {
            // A pending key that cannot be read was never usable; the active key stays in use.
            return null;
        }
    }

    // --- S18-1-F1: replacement key when the active key is invalidated or missing ------------

    /** Creates (or shows) the next-version key without a session; the active key is untouched. */
    private void prepareReplacementDeviceKey() {
        AdminDeviceKeyStore keyStore = deviceKeyStore;
        if (keyStore == null || operationInFlight) return;
        resultText.setText("교체용 새 기기키를 만들고 있습니다. 기존 키와 기기 ID는 그대로 둡니다.");
        networkExecutor.execute(() -> {
            try {
                AdminDeviceKeyStore.Descriptor pending;
                synchronized (AdminDeviceKeyStore.class) {
                    pending = keyStore.rotation().preparePending();
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    pendingDeviceKeyDescriptor = pending;
                    resultText.setText("교체용 새 기기키를 준비했습니다. 아래 안내대로 운영자에게 등록을 요청하세요.");
                    render();
                });
            } catch (Exception error) {
                boolean lockScreenRequired = AdminDeviceKeyUseAuthorization.requiresLockScreen(error);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    resultText.setText(lockScreenRequired
                        ? DEVICE_KEY_LOCK_SCREEN_MESSAGE
                        : "교체용 새 기기키를 만들지 못했습니다. 기존 상태를 그대로 유지합니다.");
                    render();
                });
            }
        });
    }

    /** Rebuilds the clients for the replacement key, or back for the stored active key. */
    private void switchReplacementKeyLogin(boolean replacement) {
        if (operationInFlight) return;
        if (replacement && pendingDeviceKeyDescriptor == null) {
            resultText.setText("먼저 교체용 새 기기키를 준비하고 운영자 등록을 마치세요.");
            return;
        }
        replacementKeyLoginRequested = replacement;
        recreate();
    }

    private void renderDeviceKeyReplacement(boolean signedOut) {
        if (deviceKeyReplacementGroup == null) return;
        AdminDeviceKeyStore.Descriptor pending = pendingDeviceKeyDescriptor;
        boolean replacementMode = replacementLoginDescriptor != null;
        deviceKeyReplacementGroup.setVisibility(
            signedOut && deviceKeyStore != null ? View.VISIBLE : View.GONE
        );
        replacementLoginButton.setVisibility(
            pending != null && !replacementMode ? View.VISIBLE : View.GONE
        );
        activeKeyReturnButton.setVisibility(
            replacementMode && !activeDeviceKeyUnavailable ? View.VISIBLE : View.GONE
        );
        if (replacementMode) {
            deviceKeyReplacementText.setText(
                "교체 키(버전 " + replacementLoginDescriptor.version() + ")로 로그인합니다. "
                    + "서버가 이 키로 한 로그인을 받아들일 때만 기기의 기본 키로 전환합니다. "
                    + "운영자 등록 전이면 로그인이 거부되며, 기존 기기키로 돌아갈 수 있습니다."
            );
        } else if (pending != null) {
            deviceKeyReplacementText.setText(
                "교체용 새 기기키(버전 " + pending.version() + ")를 준비했습니다. 운영자는 신뢰 CLI에 "
                    + "--replace-active --key-version " + pending.version() + " --security-level "
                    + pending.securityLevel() + " 과 아래 descriptor를 등록합니다(기존 키 즉시 폐기·"
                    + "이 기기의 세션 종료). 등록 뒤 '교체 키로 로그인 전환'을 누르세요.\n"
                    + pending.registrationDescriptor(deviceId)
            );
        } else {
            deviceKeyReplacementText.setText(
                "화면 잠금을 제거·초기화해 기기키가 무효화됐거나 키를 잃었다면 앱 데이터를 지우지 말고 "
                    + "교체용 새 기기키를 준비하세요. 정상 교체는 로그인 후 세션 화면에서 합니다."
            );
        }
    }

    /**
     * Worker thread, after controller.login/completeRecovery returned: the server accepted a
     * LOGIN or RECOVERY_COMPLETE proof signed by the replacement key, which it issues and
     * verifies only for its ACTIVE key. Only then does the replacement become the local key.
     */
    private void promoteReplacementKeyAfterServerLogin() {
        AdminDeviceKeyStore keyStore = deviceKeyStore;
        AdminDeviceKeyStore.Descriptor bound = replacementLoginDescriptor;
        if (keyStore == null || bound == null) return;
        int serverKeyVersion = 0;
        String serverMarkerPrefix = null;
        for (AdminSecurityApi.DeviceInfo device : controller.snapshot().devices()) {
            if (!device.isCurrent()) continue;
            serverKeyVersion = Math.max(0, device.keyVersion());
            serverMarkerPrefix = device.keyMarkerPrefix();
        }
        boolean promoted;
        try {
            synchronized (AdminDeviceKeyStore.class) {
                promoted = keyStore.rotation().promoteAfterReplacementLogin(
                    bound.version(), bound.marker(), serverKeyVersion, serverMarkerPrefix
                );
            }
        } catch (GeneralSecurityException | RuntimeException error) {
            // The session is valid and the pinned signer keeps using the replacement key;
            // the next replacement login retries the local switch.
            promoted = false;
        }
        if (!promoted) return;
        replacementKeyLoginRequested = false;
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            replacementLoginDescriptor = null;
            activeDeviceKeyUnavailable = false;
            pendingDeviceKeyDescriptor = null;
            render();
        });
    }

    private void prepareDeviceKeyRotation() {
        AdminDeviceKeyStore keyStore = deviceKeyStore;
        if (keyStore == null || controller == null) return;
        runSecurityOperation(
            "새 기기키를 준비하고 있습니다. 기존 키는 계속 사용합니다.",
            "새 기기키를 준비했습니다. 아래 descriptor를 운영자 CLI로 등록한 뒤 교체 확인을 누르세요.",
            AdminRecoveryMessagePolicy.Phase.GENERAL,
            () -> {
                AdminDeviceKeyStore.Descriptor pending;
                synchronized (AdminDeviceKeyStore.class) {
                    pending = keyStore.rotation().preparePending();
                }
                runOnUiThread(() -> pendingDeviceKeyDescriptor = pending);
            }
        );
    }

    private void confirmDeviceKeyRotation() {
        AdminDeviceKeyStore keyStore = deviceKeyStore;
        AdminDeviceKeyStore.Descriptor active = deviceKeyDescriptor;
        AdminDeviceKeyStore.Descriptor pending = pendingDeviceKeyDescriptor;
        if (keyStore == null || controller == null || active == null) return;
        if (pending == null) {
            resultText.setText("먼저 새 기기키를 준비하고 운영자 CLI로 등록하세요.");
            return;
        }
        runSecurityOperation(
            "새 기기키의 소유를 서버에 증명하고 있습니다.",
            "새 기기키로 교체했습니다.",
            AdminRecoveryMessagePolicy.Phase.GENERAL,
            () -> {
                controller.confirmDeviceKeyRotation(new AdminSecurityApi.DeviceKeyRotationRequest(
                    active.version(),
                    active.marker(),
                    pending.version(),
                    pending.marker(),
                    payload -> keyStore.rotation().signPending(payload)
                ));
                completeDeviceKeyRotation(keyStore);
            }
        );
    }

    private void abandonDeviceKeyRotation() {
        AdminDeviceKeyStore keyStore = deviceKeyStore;
        if (keyStore == null || controller == null || pendingDeviceKeyDescriptor == null) return;
        runSecurityOperation(
            "준비한 새 기기키를 지우고 있습니다.",
            "준비한 새 기기키를 지웠습니다. 기존 기기키를 그대로 사용합니다.",
            AdminRecoveryMessagePolicy.Phase.GENERAL,
            () -> {
                synchronized (AdminDeviceKeyStore.class) {
                    keyStore.rotation().abandonPending();
                }
                runOnUiThread(() -> pendingDeviceKeyDescriptor = null);
            }
        );
    }

    /** Runs on the worker after the server confirmed; the clients hold the old descriptor. */
    private void completeDeviceKeyRotation(AdminDeviceKeyStore keyStore) throws Exception {
        synchronized (AdminDeviceKeyStore.class) {
            keyStore.rotation().promotePending();
        }
        deviceKeyRotationCompletedNotice = true;
        runOnUiThread(() -> {
            if (!isDestroyed() && !isFinishing()) recreate();
        });
    }

    private void renderDeviceKeyRotation(
        boolean accessActive,
        java.util.List<AdminSecurityApi.DeviceInfo> devices
    ) {
        if (deviceKeyRotationText == null) return;
        AdminDeviceKeyStore.Descriptor pending = pendingDeviceKeyDescriptor;
        if (pending == null) {
            deviceKeyRotationText.setText("기기키 교체: 새 키를 준비하면 운영자 CLI 등록과 새 키 소유 확인이 끝날 때까지 기존 키를 유지합니다.");
            return;
        }
        deviceKeyRotationText.setText(
            "교체 대기 중인 새 기기키(버전 " + pending.version() + "). 운영자 CLI에 --key-version "
                + pending.version() + " 과 아래 descriptor를 등록한 뒤 교체 확인을 누르세요. "
                + "확인 전 실패하면 기존 키를 계속 사용합니다.\n"
                + pending.registrationDescriptor(deviceId)
        );
        if (!accessActive || devices == null || deviceKeyReconcileInFlight) return;
        for (AdminSecurityApi.DeviceInfo device : devices) {
            if (!device.isCurrent()) continue;
            String prefix = device.keyMarkerPrefix();
            if (device.keyVersion() == pending.version() && prefix != null
                && pending.marker().startsWith(prefix)) {
                // The confirmation response was lost but the server already switched keys.
                reconcileDeviceKeyRotation(device.keyVersion(), prefix);
            }
        }
    }

    private void reconcileDeviceKeyRotation(int serverKeyVersion, String serverMarkerPrefix) {
        AdminDeviceKeyStore keyStore = deviceKeyStore;
        if (keyStore == null) return;
        deviceKeyReconcileInFlight = true;
        networkExecutor.execute(() -> {
            AdminDeviceKeyRotation.ReconcileOutcome outcome;
            try {
                synchronized (AdminDeviceKeyStore.class) {
                    outcome = keyStore.rotation().reconcile(serverKeyVersion, serverMarkerPrefix);
                }
            } catch (Exception error) {
                outcome = AdminDeviceKeyRotation.ReconcileOutcome.UNKNOWN;
            }
            AdminDeviceKeyRotation.ReconcileOutcome applied = outcome;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                if (applied == AdminDeviceKeyRotation.ReconcileOutcome.PROMOTED) {
                    deviceKeyRotationCompletedNotice = true;
                    recreate();
                    return;
                }
                deviceKeyReconcileInFlight = false;
            });
        });
    }

    // --- S18-36: resolve a restored review attempt before any new decision -------------------

    private void lookupRestoredReviewDecision() {
        String restoredReportId = reviewDecisionAttempt.restoredReportId();
        String restoredDecisionId = reviewDecisionAttempt.restoredDecisionId();
        if (restoredReportId == null || restoredDecisionId == null) return;
        if (controller == null || operationInFlight || !activityStarted || controller.isLocallyLocked()) {
            return;
        }
        long generation = securityOperationGeneration;
        operationInFlight = true;
        setInteractiveEnabled(contentRoot, false);
        resultText.setText("새 결정을 만들기 전에 이전 검토 결정(decision_id)의 서버 기록을 확인하고 있습니다.");
        networkExecutor.execute(() -> {
            try {
                AdminOperationsApi.Result result = controller.lookupReviewDecision(
                    restoredReportId,
                    restoredDecisionId,
                    BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED
                );
                keyUseAuthorization.onSuccess();
                AdminReviewDecisionAttempt.LookupOutcome outcome = result.mutationId() == null
                    ? AdminReviewDecisionAttempt.LookupOutcome.NOT_RECORDED
                    : AdminReviewDecisionAttempt.LookupOutcome.RECORDED;
                runOnUiThread(() -> {
                    if (isDestroyed() || !activityStarted
                        || generation != securityOperationGeneration) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, !operationInFlight);
                    reviewDecisionAttempt.applyLookup(restoredDecisionId, outcome);
                    resultText.setText(outcome == AdminReviewDecisionAttempt.LookupOutcome.RECORDED
                        ? "이전 검토 결정이 이미 기록되어 있습니다. 새 결정을 만들지 않았습니다."
                        : "이전 검토 결정은 기록되지 않았습니다. 같은 입력이면 같은 decision_id로 다시 보내고, 입력이 다르면 새 결정으로 기록합니다.");
                    render();
                    if (outcome == AdminReviewDecisionAttempt.LookupOutcome.RECORDED
                        && restoredReportId.equals(connectedOperationsReportId)) {
                        refreshConnectedReportDetail();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || !activityStarted
                        || generation != securityOperationGeneration) return;
                    operationInFlight = false;
                    setInteractiveEnabled(contentRoot, !operationInFlight);
                    if (handleDeviceKeyUseFailure(error, this::lookupRestoredReviewDecision)) return;
                    resultText.setText("이전 검토 결정 결과를 확인하지 못했습니다. 새 decision_id를 만들지 않으며 다시 확인해야 합니다.");
                    render();
                });
            }
        });
    }

    /** Only attempt identity and pending state; no recovery code, OTP, token or key is stored. */
    private final class PreferencesCustodyAttemptStore
        implements AdminRecoveryCustodyAttempt.Store {
        private static final String ADMIN_ID = "admin_id";
        private static final String DEVICE_ID = "device_id";
        private static final String MATERIAL_KIND = "material_kind";
        private static final String CUSTODY_REFERENCE = "custody_reference";
        private static final String EXPECTED_STATE_VERSION = "expected_state_version";
        private static final String STATUS = "status";

        @Override
        public AdminRecoveryCustodyAttempt.Snapshot load() {
            var preferences = getSharedPreferences(CUSTODY_ATTEMPT_PREFS, MODE_PRIVATE);
            String reference = preferences.getString(CUSTODY_REFERENCE, null);
            if (reference == null) return null;
            return new AdminRecoveryCustodyAttempt.Snapshot(
                preferences.getString(ADMIN_ID, null),
                preferences.getString(DEVICE_ID, null),
                AdminSecurityApi.RecoveryMaterialKind.valueOf(
                    preferences.getString(MATERIAL_KIND, "")
                ),
                reference,
                preferences.getString(EXPECTED_STATE_VERSION, null),
                preferences.getString(STATUS, null)
            );
        }

        @Override
        public boolean save(AdminRecoveryCustodyAttempt.Snapshot pending) {
            return getSharedPreferences(CUSTODY_ATTEMPT_PREFS, MODE_PRIVATE).edit()
                .putString(ADMIN_ID, pending.adminId())
                .putString(DEVICE_ID, pending.deviceId())
                .putString(MATERIAL_KIND, pending.materialKind().name())
                .putString(CUSTODY_REFERENCE, pending.custodyReference())
                .putString(EXPECTED_STATE_VERSION, pending.expectedStateVersion())
                .putString(STATUS, pending.status())
                .commit();
        }

        @Override
        public boolean clear() {
            return getSharedPreferences(CUSTODY_ATTEMPT_PREFS, MODE_PRIVATE)
                .edit().clear().commit();
        }
    }

    /** Non-secret attempt identity only; the reason text, image and credentials are never kept. */
    private final class PreferencesReviewAttemptStore implements AdminReviewDecisionAttempt.Store {
        private static final String REPORT_ID = "report_id";
        private static final String DECISION_ID = "decision_id";
        private static final String REQUEST_DIGEST = "request_digest";
        private static final String CONTENT_REVISION = "content_revision";
        private static final String EXPECTED_REVIEW_REVISION = "expected_review_revision";
        private static final String EVIDENCE_GRANT_ID = "evidence_grant_id";

        @Override
        public AdminReviewDecisionAttempt.Snapshot load() {
            var preferences = getSharedPreferences(REVIEW_ATTEMPT_PREFS, MODE_PRIVATE);
            String decisionId = preferences.getString(DECISION_ID, null);
            if (decisionId == null) return null;
            return new AdminReviewDecisionAttempt.Snapshot(
                preferences.getString(REPORT_ID, null),
                decisionId,
                preferences.getString(REQUEST_DIGEST, null),
                preferences.getInt(CONTENT_REVISION, -1),
                preferences.getLong(EXPECTED_REVIEW_REVISION, -1L),
                preferences.getString(EVIDENCE_GRANT_ID, null)
            );
        }

        @Override
        public void save(AdminReviewDecisionAttempt.Snapshot snapshot) {
            getSharedPreferences(REVIEW_ATTEMPT_PREFS, MODE_PRIVATE).edit()
                .putString(REPORT_ID, snapshot.reportId())
                .putString(DECISION_ID, snapshot.decisionId())
                .putString(REQUEST_DIGEST, snapshot.requestDigest())
                .putInt(CONTENT_REVISION, snapshot.contentRevision())
                .putLong(EXPECTED_REVIEW_REVISION, snapshot.expectedReviewRevision())
                .putString(EVIDENCE_GRANT_ID, snapshot.evidenceGrantId())
                .commit();
        }

        @Override
        public void clear() {
            getSharedPreferences(REVIEW_ATTEMPT_PREFS, MODE_PRIVATE).edit().clear().commit();
        }
    }

    private String loadOrCreateDeviceId() {
        var preferences = getSharedPreferences(DEVICE_PREFS, MODE_PRIVATE);
        String existing = preferences.getString(DEVICE_ID_KEY, null);
        if (existing != null && existing.matches("admin-device-[0-9a-f-]{36}")) return existing;
        String created = "admin-device-" + UUID.randomUUID();
        if (!preferences.edit().putString(DEVICE_ID_KEY, created).commit()) {
            throw new IllegalStateException("administrator device identity was not committed");
        }
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
            return "사용자의 복구자료 외부 보관 확인 기록됨 · 백업·복원 미검증 · 확인시각 "
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
