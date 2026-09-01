package kr.co.hanium.dreamup.walksafe.admin;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminRawCollectionModels;

/** Metadata-only panel for the bounded raw quarantine review API. */
public final class AdminRawCollectionPanel extends LinearLayout {
    public interface Listener {
        void onLoad(char[] password, char[] totp);
        void onRetry(char[] password, char[] totp);
        void onSelect(String collectionId);
        void onDecide(
            AdminRawCollectionModels.Summary item,
            AdminRawCollectionModels.PurposeDecisionRequest request,
            char[] password,
            char[] totp
        );
        void onLegalHold(
            AdminRawCollectionModels.Summary item,
            AdminRawCollectionModels.LegalHoldRequest request,
            char[] password,
            char[] totp
        );
    }

    private static final String[] DECISION_SCOPES = {"REPORT", "TRAINING"};
    private static final String[] DECISION_SCOPE_LABELS = {
        "신고 사용 (REPORT)", "학습 사용 (TRAINING)"
    };
    private static final String[] DECISIONS = {"APPROVED", "REJECTED"};
    private static final String[] DECISION_LABELS = {"승인", "거절"};
    private static final String[] HOLD_ACTIONS = {"APPLY", "RELEASE"};
    private static final String[] HOLD_ACTION_LABELS = {"법적 보존 적용", "법적 보존 해제"};

    private final Listener listener;
    private final TextView stateText;
    private final ProgressBar loadingIndicator;
    private final LinearLayout items;
    private final Button retryButton;
    private final EditText listPasswordInput;
    private final EditText listTotpInput;
    private final LinearLayout selectedGroup;
    private final TextView selectedText;
    private final Spinner decisionScopeInput;
    private final Spinner decisionInput;
    private final EditText decisionReasonInput;
    private final LinearLayout trainingEvidenceGroup;
    private final EditText trainingConsentInput;
    private final EditText deidentificationInput;
    private final EditText sanitizedManifestInput;
    private final EditText targetDatasetInput;
    private final CheckBox exactLocationExcludedInput;
    private final CheckBox rawAudioExcludedInput;
    private final CheckBox thirdPartyFacesExcludedInput;
    private final EditText decisionPasswordInput;
    private final EditText decisionTotpInput;
    private final Button decisionButton;
    private final Spinner holdActionInput;
    private final EditText holdReasonInput;
    private final LinearLayout holdApplyGroup;
    private final EditText legalBasisInput;
    private final EditText authorityReferenceInput;
    private final EditText contactInput;
    private final EditText expiresAtInput;
    private final EditText holdPasswordInput;
    private final EditText holdTotpInput;
    private final Button holdButton;

    private AdminRawCollectionModels.Summary selectedItem;
    private String restoredSelection;
    private String reportIdempotencyKey = newIdempotencyKey();
    private String trainingIdempotencyKey = newIdempotencyKey();
    private String holdIdempotencyKey = newIdempotencyKey();
    private int observedReportRevision = -1;
    private int observedTrainingRevision = -1;
    private int observedHoldRevision = -1;

    public AdminRawCollectionPanel(Context context, Listener listener) {
        super(context);
        if (listener == null) throw new IllegalArgumentException("raw collection listener is required");
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(0, dp(30), 0, dp(16));

        TextView heading = text("원시자료 검역 검토", 21);
        markAccessibilityHeading(heading);
        addView(heading, matchWrap());
        addView(text(
            "현재 QUARANTINED 항목 중 최신 최대 100건만 표시합니다. 이 API에는 페이지가 없어 100건을 넘는 항목은 이 화면에서 확인할 수 없습니다.",
            15
        ), matchWrap());
        addView(text(
            "이 화면은 메타데이터와 승인 기록만 다루며 원시 객체를 열람·다운로드하지 않습니다. 변경은 최신 revision과 멱등 UUID에 결속됩니다.",
            15
        ), matchWrap());

        listPasswordInput = input(
            "검역 목록 조회 관리자 비밀번호", true,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        listTotpInput = input(
            "검역 목록 조회 6자리 추가 인증", true,
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );
        addView(listPasswordInput, matchWrap());
        addView(listTotpInput, matchWrap());
        Button loadButton = button("현재 검역 목록 조회 (최대 100건)");
        loadButton.setOnClickListener(view -> submitList(false));
        addView(loadButton, matchWrap());

        stateText = text("조회 전입니다.", 16);
        stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(stateText, matchWrap());
        loadingIndicator = new ProgressBar(context);
        loadingIndicator.setIndeterminate(true);
        loadingIndicator.setContentDescription("원시자료 검역 목록을 불러오는 중");
        loadingIndicator.setVisibility(GONE);
        addView(loadingIndicator, wrapCentered());
        items = vertical();
        addView(items, matchWrap());
        retryButton = button("검역 목록 다시 시도");
        retryButton.setOnClickListener(view -> submitList(true));
        retryButton.setVisibility(GONE);
        addView(retryButton, matchWrap());

        selectedGroup = vertical();
        TextView selectedHeading = text("선택 항목의 승인·법적 보존 기록", 19);
        markAccessibilityHeading(selectedHeading);
        selectedGroup.addView(selectedHeading, matchWrap());
        selectedText = text("", 15);
        selectedGroup.addView(selectedText, matchWrap());

        TextView decisionHeading = text("목적별 승인 또는 거절", 18);
        decisionHeading.setPadding(0, dp(18), 0, dp(4));
        markAccessibilityHeading(decisionHeading);
        selectedGroup.addView(decisionHeading, matchWrap());
        decisionScopeInput = spinner(DECISION_SCOPE_LABELS, "검토 목적");
        decisionInput = spinner(DECISION_LABELS, "승인 또는 거절");
        decisionReasonInput = input("결정 사유 (1~500자)", false, InputType.TYPE_CLASS_TEXT);
        selectedGroup.addView(decisionScopeInput, matchWrap());
        selectedGroup.addView(decisionInput, matchWrap());
        selectedGroup.addView(decisionReasonInput, matchWrap());

        trainingEvidenceGroup = vertical();
        trainingEvidenceGroup.addView(text(
            "TRAINING 승인에만 현재 동의·비식별화·정제 manifest 증거와 대상 dataset, 세 제외 확인이 모두 필요합니다.",
            14
        ), matchWrap());
        trainingConsentInput = input(
            "현재 학습 동의 receipt SHA-256", false, InputType.TYPE_CLASS_TEXT
        );
        deidentificationInput = input(
            "비식별화 receipt SHA-256", false, InputType.TYPE_CLASS_TEXT
        );
        sanitizedManifestInput = input(
            "정제 manifest SHA-256", false, InputType.TYPE_CLASS_TEXT
        );
        targetDatasetInput = input("대상 dataset UUID", false, InputType.TYPE_CLASS_TEXT);
        exactLocationExcludedInput = checkBox("정확한 위치가 제외됨을 확인");
        rawAudioExcludedInput = checkBox("원음이 제외됨을 확인");
        thirdPartyFacesExcludedInput = checkBox("제3자 얼굴이 제외됨을 확인");
        trainingEvidenceGroup.addView(trainingConsentInput, matchWrap());
        trainingEvidenceGroup.addView(deidentificationInput, matchWrap());
        trainingEvidenceGroup.addView(sanitizedManifestInput, matchWrap());
        trainingEvidenceGroup.addView(targetDatasetInput, matchWrap());
        trainingEvidenceGroup.addView(exactLocationExcludedInput, matchWrap());
        trainingEvidenceGroup.addView(rawAudioExcludedInput, matchWrap());
        trainingEvidenceGroup.addView(thirdPartyFacesExcludedInput, matchWrap());
        selectedGroup.addView(trainingEvidenceGroup, matchWrap());

        decisionPasswordInput = input(
            "결정 기록 관리자 비밀번호", true,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        decisionTotpInput = input(
            "결정 기록 6자리 추가 인증", true,
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );
        selectedGroup.addView(decisionPasswordInput, matchWrap());
        selectedGroup.addView(decisionTotpInput, matchWrap());
        decisionButton = button("목적별 결정 기록");
        decisionButton.setOnClickListener(view -> submitDecision());
        selectedGroup.addView(decisionButton, matchWrap());

        TextView holdHeading = text("법적 보존 적용 또는 해제", 18);
        holdHeading.setPadding(0, dp(22), 0, dp(4));
        markAccessibilityHeading(holdHeading);
        selectedGroup.addView(holdHeading, matchWrap());
        holdActionInput = spinner(HOLD_ACTION_LABELS, "법적 보존 작업");
        holdReasonInput = input("법적 보존 사유 (1~500자)", false, InputType.TYPE_CLASS_TEXT);
        selectedGroup.addView(holdActionInput, matchWrap());
        selectedGroup.addView(holdReasonInput, matchWrap());
        holdApplyGroup = vertical();
        legalBasisInput = input("법적 근거 (1~500자)", false, InputType.TYPE_CLASS_TEXT);
        authorityReferenceInput = input(
            "권한·문서 참조 (1~160자)", false, InputType.TYPE_CLASS_TEXT
        );
        contactInput = input("담당 연락처 (1~160자)", false, InputType.TYPE_CLASS_TEXT);
        expiresAtInput = input(
            "보존 만료시각 (RFC3339, 미래)", false, InputType.TYPE_CLASS_TEXT
        );
        holdApplyGroup.addView(legalBasisInput, matchWrap());
        holdApplyGroup.addView(authorityReferenceInput, matchWrap());
        holdApplyGroup.addView(contactInput, matchWrap());
        holdApplyGroup.addView(expiresAtInput, matchWrap());
        selectedGroup.addView(holdApplyGroup, matchWrap());
        holdPasswordInput = input(
            "법적 보존 관리자 비밀번호", true,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        holdTotpInput = input(
            "법적 보존 6자리 추가 인증", true,
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );
        selectedGroup.addView(holdPasswordInput, matchWrap());
        selectedGroup.addView(holdTotpInput, matchWrap());
        holdButton = button("법적 보존 기록");
        holdButton.setOnClickListener(view -> submitLegalHold());
        selectedGroup.addView(holdButton, matchWrap());
        selectedGroup.setVisibility(GONE);
        addView(selectedGroup, matchWrap());

        decisionScopeInput.setOnItemSelectedListener(simpleSelectionListener(this::renderEvidenceMode));
        decisionInput.setOnItemSelectedListener(simpleSelectionListener(this::renderEvidenceMode));
        holdActionInput.setOnItemSelectedListener(simpleSelectionListener(this::renderHoldMode));
        renderEvidenceMode();
        renderHoldMode();
    }

    public String selectedCollectionId() {
        return selectedItem == null ? restoredSelection : selectedItem.collectionId();
    }

    public void restore(String collectionId) {
        if (collectionId == null) return;
        try {
            restoredSelection = AdminRawCollectionModels.canonicalUuid(
                collectionId, "collection_id"
            );
        } catch (IllegalArgumentException ignored) {
            restoredSelection = null;
        }
    }

    public void render(AdminRawCollectionController.State state) {
        stateText.setText(switch (state.phase()) {
            case IDLE -> "조회 전입니다.";
            case LOADING -> "현재 검역 목록을 불러오는 중입니다…";
            case CONTENT -> "현재 검역 항목 " + state.items().size()
                + "건을 표시합니다. 서버 상한은 100건입니다.";
            case EMPTY -> "현재 표시 가능한 검역 항목이 없습니다.";
            case ERROR -> state.errorMessage();
        });
        loadingIndicator.setVisibility(
            state.phase() == AdminRawCollectionController.Phase.LOADING ? VISIBLE : GONE
        );
        retryButton.setVisibility(
            state.phase() == AdminRawCollectionController.Phase.ERROR ? VISIBLE : GONE
        );
        renderItems(state.items());
        bindSelection(state.selected());
        boolean actionable = state.phase() == AdminRawCollectionController.Phase.CONTENT
            && selectedItem != null;
        decisionButton.setEnabled(actionable);
        holdButton.setEnabled(actionable);
    }

    public void clearSensitiveInputs() {
        listPasswordInput.setText("");
        listTotpInput.setText("");
        decisionPasswordInput.setText("");
        decisionTotpInput.setText("");
        holdPasswordInput.setText("");
        holdTotpInput.setText("");
    }

    public void clearSessionBoundDrafts() {
        selectedItem = null;
        restoredSelection = null;
        observedReportRevision = -1;
        observedTrainingRevision = -1;
        observedHoldRevision = -1;
        reportIdempotencyKey = newIdempotencyKey();
        trainingIdempotencyKey = newIdempotencyKey();
        holdIdempotencyKey = newIdempotencyKey();
        clearDecisionDraft();
        clearHoldDraft();
        clearSensitiveInputs();
        selectedGroup.setVisibility(GONE);
    }

    private void renderItems(List<AdminRawCollectionModels.Summary> values) {
        items.removeAllViews();
        for (AdminRawCollectionModels.Summary item : values) {
            LinearLayout card = vertical();
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(Color.rgb(246, 246, 246));
            card.addView(text(
                "QUARANTINED · " + purposeLabel(item.purpose())
                    + "\n객체 " + item.objectCount() + "개 · " + item.totalBytes() + " bytes"
                    + "\n검역 만료: " + present(item.quarantineExpiresAt())
                    + "\nmanifest SHA-256: " + item.manifestSha256()
                    + "\ncollection ID: " + item.collectionId(),
                15
            ), matchWrap());
            Button select = button("이 검역 항목 선택");
            select.setOnClickListener(view -> listener.onSelect(item.collectionId()));
            card.addView(select, matchWrap());
            LayoutParams params = matchWrap();
            params.setMargins(0, dp(6), 0, dp(6));
            items.addView(card, params);
        }
    }

    private void bindSelection(AdminRawCollectionModels.Summary next) {
        String previousId = selectedItem == null ? null : selectedItem.collectionId();
        String nextId = next == null ? null : next.collectionId();
        if (!same(previousId, nextId)) {
            selectedItem = next;
            clearSensitiveInputs();
            clearDecisionDraft();
            clearHoldDraft();
            reportIdempotencyKey = newIdempotencyKey();
            trainingIdempotencyKey = newIdempotencyKey();
            holdIdempotencyKey = newIdempotencyKey();
            observedReportRevision = next == null ? -1 : next.reportDecisionRevision();
            observedTrainingRevision = next == null ? -1 : next.trainingDecisionRevision();
            observedHoldRevision = next == null ? -1 : next.legalHoldRevision();
        } else if (next != null) {
            if (observedReportRevision != next.reportDecisionRevision()) {
                reportIdempotencyKey = newIdempotencyKey();
                observedReportRevision = next.reportDecisionRevision();
            }
            if (observedTrainingRevision != next.trainingDecisionRevision()) {
                trainingIdempotencyKey = newIdempotencyKey();
                observedTrainingRevision = next.trainingDecisionRevision();
            }
            if (observedHoldRevision != next.legalHoldRevision()) {
                holdIdempotencyKey = newIdempotencyKey();
                observedHoldRevision = next.legalHoldRevision();
            }
            selectedItem = next;
        }
        restoredSelection = nextId;
        selectedGroup.setVisibility(next == null ? GONE : VISIBLE);
        if (next != null) {
            selectedText.setText(
                "REPORT: " + decisionLabel(next.reportDecision())
                    + " · revision " + next.reportDecisionRevision()
                    + "\nTRAINING: " + decisionLabel(next.trainingDecision())
                    + " · revision " + next.trainingDecisionRevision()
                    + "\n법적 보존: " + (next.legalHoldActive() ? "활성" : "비활성")
                    + " · revision " + next.legalHoldRevision()
                    + "\ncollection ID: " + next.collectionId()
            );
        }
    }

    private void submitDecision() {
        if (selectedItem == null) return;
        char[] password = editableChars(decisionPasswordInput);
        char[] totp = editableChars(decisionTotpInput);
        clearSensitiveInputs();
        try {
            String scope = DECISION_SCOPES[decisionScopeInput.getSelectedItemPosition()];
            String decision = DECISIONS[decisionInput.getSelectedItemPosition()];
            boolean trainingApproval = "TRAINING".equals(scope)
                && "APPROVED".equals(decision);
            AdminRawCollectionModels.PurposeDecisionRequest request =
                new AdminRawCollectionModels.PurposeDecisionRequest(
                    scope,
                    decision,
                    selectedItem.decisionRevision(scope),
                    "REPORT".equals(scope) ? reportIdempotencyKey : trainingIdempotencyKey,
                    decisionReasonInput.getText().toString(),
                    trainingApproval ? trainingConsentInput.getText().toString() : null,
                    trainingApproval ? deidentificationInput.getText().toString() : null,
                    trainingApproval ? sanitizedManifestInput.getText().toString() : null,
                    trainingApproval ? targetDatasetInput.getText().toString() : null,
                    trainingApproval && exactLocationExcludedInput.isChecked(),
                    trainingApproval && rawAudioExcludedInput.isChecked(),
                    trainingApproval && thirdPartyFacesExcludedInput.isChecked()
                );
            listener.onDecide(selectedItem, request, password, totp);
        } catch (IllegalArgumentException error) {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
            stateText.setText("결정 사유, revision, 증거와 제외 확인을 다시 확인해 주세요.");
        } catch (RuntimeException error) {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
            throw error;
        }
    }

    private void submitList(boolean retry) {
        char[] password = editableChars(listPasswordInput);
        char[] totp = editableChars(listTotpInput);
        clearSensitiveInputs();
        try {
            if (retry) listener.onRetry(password, totp);
            else listener.onLoad(password, totp);
        } catch (RuntimeException error) {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
            throw error;
        }
    }

    private void submitLegalHold() {
        if (selectedItem == null) return;
        char[] password = editableChars(holdPasswordInput);
        char[] totp = editableChars(holdTotpInput);
        clearSensitiveInputs();
        try {
            String action = HOLD_ACTIONS[holdActionInput.getSelectedItemPosition()];
            boolean apply = "APPLY".equals(action);
            AdminRawCollectionModels.LegalHoldRequest request =
                new AdminRawCollectionModels.LegalHoldRequest(
                    action,
                    selectedItem.legalHoldRevision(),
                    holdIdempotencyKey,
                    holdReasonInput.getText().toString(),
                    apply ? legalBasisInput.getText().toString() : null,
                    apply ? authorityReferenceInput.getText().toString() : null,
                    apply ? contactInput.getText().toString() : null,
                    apply ? expiresAtInput.getText().toString() : null
                );
            listener.onLegalHold(selectedItem, request, password, totp);
        } catch (IllegalArgumentException error) {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
            stateText.setText("법적 보존 사유, 근거, 참조, 연락처와 만료시각을 다시 확인해 주세요.");
        } catch (RuntimeException error) {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
            throw error;
        }
    }

    private void renderEvidenceMode() {
        boolean visible = decisionScopeInput.getSelectedItemPosition() == 1
            && decisionInput.getSelectedItemPosition() == 0;
        trainingEvidenceGroup.setVisibility(visible ? VISIBLE : GONE);
    }

    private void renderHoldMode() {
        holdApplyGroup.setVisibility(
            holdActionInput.getSelectedItemPosition() == 0 ? VISIBLE : GONE
        );
    }

    private void clearDecisionDraft() {
        decisionReasonInput.setText("");
        trainingConsentInput.setText("");
        deidentificationInput.setText("");
        sanitizedManifestInput.setText("");
        targetDatasetInput.setText("");
        exactLocationExcludedInput.setChecked(false);
        rawAudioExcludedInput.setChecked(false);
        thirdPartyFacesExcludedInput.setChecked(false);
    }

    private void clearHoldDraft() {
        holdReasonInput.setText("");
        legalBasisInput.setText("");
        authorityReferenceInput.setText("");
        contactInput.setText("");
        expiresAtInput.setText("");
    }

    private Spinner spinner(String[] labels, String description) {
        Spinner spinner = new Spinner(getContext());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            getContext(), android.R.layout.simple_spinner_item, labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(48));
        spinner.setContentDescription(description);
        spinner.setSaveEnabled(false);
        return spinner;
    }

    private EditText input(String hint, boolean sensitive, int inputType) {
        EditText input = new EditText(getContext());
        input.setHint(hint);
        input.setMinHeight(dp(48));
        input.setSingleLine(false);
        input.setSaveEnabled(false);
        input.setId(View.NO_ID);
        input.setContentDescription(hint);
        input.setInputType(inputType | (sensitive ? 0 : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setImportantForAutofill(
            sensitive
                ? View.IMPORTANT_FOR_AUTOFILL_NO
                : View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        );
        if (sensitive) input.setFilterTouchesWhenObscured(true);
        return input;
    }

    private CheckBox checkBox(String label) {
        CheckBox checkBox = new CheckBox(getContext());
        checkBox.setText(label);
        checkBox.setMinHeight(dp(48));
        checkBox.setSaveEnabled(false);
        checkBox.setId(View.NO_ID);
        checkBox.setContentDescription(label);
        return checkBox;
    }

    private Button button(String label) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setContentDescription(label);
        return button;
    }

    private TextView text(String value, int sp) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(Color.BLACK);
        return text;
    }

    private LinearLayout vertical() {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(VERTICAL);
        return group;
    }

    private static char[] editableChars(EditText input) {
        char[] value = new char[input.length()];
        input.getText().getChars(0, value.length, value, 0);
        return value;
    }

    private static android.widget.AdapterView.OnItemSelectedListener simpleSelectionListener(
        Runnable update
    ) {
        return new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                android.widget.AdapterView<?> parent,
                View view,
                int position,
                long id
            ) {
                update.run();
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
                update.run();
            }
        };
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

    private static String decisionLabel(String value) {
        if (value == null) return "미결정";
        return "APPROVED".equals(value) ? "승인" : "거절";
    }

    private static String purposeLabel(String value) {
        return "AUTO_REPORT".equals(value) ? "자동 신고 원천" : "일반 원시자료";
    }

    private static String present(String value) {
        return value == null ? "없음" : value;
    }

    private static String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    private static boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LayoutParams wrapCentered() {
        LayoutParams parameters = new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        parameters.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        return parameters;
    }

    private static LayoutParams matchWrap() {
        return new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }
}
