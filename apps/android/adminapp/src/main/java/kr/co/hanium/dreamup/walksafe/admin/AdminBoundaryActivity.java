package kr.co.hanium.dreamup.walksafe.admin;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminDeviceKeyStore;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminInstitutionDelivery;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsApi;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminOperationsHttpClient;
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

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private AdminSecurityController controller;
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
    private EditText reportIdInput;
    private Spinner reviewDecisionInput;
    private EditText reviewReasonInput;
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
    private EditText idempotencyKeyInput;
    private Button revokeCurrentButton;
    private LinearLayout contentRoot;
    private boolean operationInFlight;

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
                if (BuildConfig.ADMIN_OPERATIONAL_WORKFLOWS_ENABLED) {
                    operationsApi = new AdminOperationsHttpClient(
                        BuildConfig.WALKSAFE_ADMIN_API_ORIGIN,
                        BuildConfig.DEBUG,
                        deviceKeyDescriptor,
                        keyStore
                    );
                    operationsClientConfigured = true;
                }
                controller = new AdminSecurityController(securityClient, operationsApi);
            } catch (Exception error) {
                deviceKeyFailure = "키 사용 불가";
                operationsClientConfigured = false;
            }
        }
        setContentView(buildContent());
        render();
    }

    @Override
    protected void onStop() {
        clearSensitiveInputs();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) networkExecutor.execute(controller::close);
        networkExecutor.shutdown();
        super.onDestroy();
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
        revokeCurrentButton = button("현재 기기 세션 폐기");
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

        reportIdInput = input("신고 UUID", InputType.TYPE_CLASS_TEXT, false);
        group.addView(reportIdInput, matchWrap());

        TextView reviewHeading = text("검토 결정 (세 검토 항목을 모두 확인해야 저장됩니다)", 17);
        reviewHeading.setPadding(0, 24, 0, 4);
        group.addView(reviewHeading, matchWrap());
        reviewDecisionInput = enumSpinner(AdminReportDecision.Decision.values());
        group.addView(reviewDecisionInput, matchWrap());
        reviewReasonInput = input("결정 사유", InputType.TYPE_CLASS_TEXT, false);
        duplicateReportIdInput = input("중복 대상 신고 UUID (DUPLICATE일 때만)", InputType.TYPE_CLASS_TEXT, false);
        group.addView(reviewReasonInput, matchWrap());
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
        idempotencyKeyInput = input("멱등 UUID", InputType.TYPE_CLASS_TEXT, false);
        idempotencyKeyInput.setText(UUID.randomUUID().toString());
        group.addView(externalReceiptInput, matchWrap());
        group.addView(deliveryReasonInput, matchWrap());
        group.addView(evidenceSha256Input, matchWrap());
        group.addView(observedAtInput, matchWrap());
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
            String revision = normalized(expectedRevisionInput);
            if (!revision.matches("[0-9]{1,18}")) throw new IllegalArgumentException("invalid revision");
            AdminInstitutionDelivery delivery = new AdminInstitutionDelivery(
                normalized(institutionInput),
                normalized(deliveryChannelInput),
                normalized(deliveryRecipientInput),
                AdminInstitutionDelivery.Status.valueOf(deliveryStatusInput.getSelectedItem().toString()),
                nullableNormalized(externalReceiptInput),
                normalized(deliveryReasonInput),
                nullableNormalized(evidenceSha256Input),
                normalized(observedAtInput),
                Long.parseLong(revision),
                normalized(idempotencyKeyInput)
            );
            String reportId = normalized(reportIdInput);
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
                    .append("\n사유: ").append(item.reason());
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
            resultText.setText("폐기할 현재 세션을 확인할 수 없습니다.");
            return;
        }
        revokeSession(sessionId);
    }

    private void revokeSession(String sessionId) {
        runSecurityOperation("선택한 기기 세션을 폐기하고 있습니다.", "기기 세션을 폐기했습니다.",
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
            Button revoke = button(session.isCurrent() ? "현재 세션 폐기" : "이 기기 세션 폐기");
            revoke.setEnabled(!session.isRevoked());
            revoke.setOnClickListener(view -> revokeSession(session.sessionId()));
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
