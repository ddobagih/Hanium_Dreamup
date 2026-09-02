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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.List;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminIncidentModels;

/** Visual incident ledger. Status actions only append records and never recover/control systems. */
public final class AdminIncidentPanel extends LinearLayout {
    public interface Listener {
        void onApplyStatus(String status);
        void onLoadMore();
        void onLoadMoreHistory();
        void onOpenDetail(String incidentId);
        void onRetry();
        void onUpdateStatus(
            AdminIncidentModels.Detail detail,
            String nextState,
            String reason,
            String observation,
            String evidenceSha256,
            char[] password,
            char[] totp
        );
    }

    private static final String[] STATUS_VALUES = {
        "", "OPEN", "ACKNOWLEDGED", "RESOLVED", "REOPENED"
    };
    private static final String[] STATUS_LABELS = {
        "모든 상태", "열림", "확인됨", "해결 기록됨", "재개됨"
    };

    private final Listener listener;
    private final Spinner statusInput;
    private final TextView stateText;
    private final ProgressBar loadingIndicator;
    private final LinearLayout items;
    private final Button moreButton;
    private final Button retryButton;
    private final LinearLayout detailGroup;
    private final TextView detailText;
    private final TextView historyStateText;
    private final LinearLayout events;
    private final Button historyMoreButton;
    private final EditText reasonInput;
    private final EditText observationInput;
    private final EditText evidenceInput;
    private final EditText passwordInput;
    private final EditText totpInput;
    private final LinearLayout actions;
    private String selectedIncidentId;
    private AdminIncidentModels.Detail displayedDetail;
    private Button pendingRetryButton;

    public AdminIncidentPanel(Context context, Listener listener) {
        super(context);
        if (listener == null) throw new IllegalArgumentException("incident listener is required");
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(0, dp(30), 0, dp(16));

        TextView heading = text("중대 사고 기록", 21);
        markAccessibilityHeading(heading);
        addView(heading, matchWrap());
        addView(text(
            "실제 CRITICAL 사고의 기록과 확인 상태만 표시합니다. 해결은 기록이며 자동 복구나 자동 제어를 수행하지 않습니다.",
            15
        ), matchWrap());

        statusInput = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            context, android.R.layout.simple_spinner_item, STATUS_LABELS
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        statusInput.setAdapter(adapter);
        statusInput.setMinimumHeight(dp(48));
        statusInput.setContentDescription("중대 사고 상태 필터");
        statusInput.setSaveEnabled(false);
        addView(statusInput, matchWrap());

        Button loadButton = button("중대 사고 기록 조회");
        loadButton.setOnClickListener(view -> listener.onApplyStatus(filterStatus()));
        addView(loadButton, matchWrap());

        stateText = text("조회 전입니다.", 16);
        stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(stateText, matchWrap());
        loadingIndicator = new ProgressBar(context);
        loadingIndicator.setIndeterminate(true);
        loadingIndicator.setContentDescription("중대 사고 기록을 불러오는 중");
        loadingIndicator.setVisibility(GONE);
        addView(loadingIndicator, wrapCentered());
        items = vertical();
        addView(items, matchWrap());

        moreButton = button("다음 중대 사고 불러오기");
        moreButton.setOnClickListener(view -> listener.onLoadMore());
        addView(moreButton, matchWrap());
        retryButton = button("중대 사고 조회 다시 시도");
        retryButton.setOnClickListener(view -> listener.onRetry());
        addView(retryButton, matchWrap());

        detailGroup = vertical();
        TextView detailHeading = text("선택한 중대 사고 상세와 상태 이력", 19);
        markAccessibilityHeading(detailHeading);
        detailGroup.addView(detailHeading, matchWrap());
        detailText = text("", 15);
        detailGroup.addView(detailText, matchWrap());
        historyStateText = text("상태 이력: 조회 전", 15);
        historyStateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        detailGroup.addView(historyStateText, matchWrap());
        events = vertical();
        detailGroup.addView(events, matchWrap());
        historyMoreButton = button("다음 이력 불러오기");
        historyMoreButton.setOnClickListener(view -> listener.onLoadMoreHistory());
        detailGroup.addView(historyMoreButton, matchWrap());

        detailGroup.addView(text(
            "상태 변경은 현재 허용된 다음 단계만 기록할 수 있습니다. 사유와 관찰에는 개인정보, 정확한 위치, 음성·영상 또는 원로그를 입력하지 마세요.",
            15
        ), matchWrap());
        reasonInput = input("상태 변경 사유 (8~500자)", false);
        observationInput = input("민감정보 없는 관찰 내용 (8~500자)", false);
        evidenceInput = input("관찰근거 SHA-256 소문자 64자", false);
        passwordInput = input("관리자 비밀번호 재인증", true);
        totpInput = input("6자리 추가 인증 코드", true);
        detailGroup.addView(reasonInput, matchWrap());
        detailGroup.addView(observationInput, matchWrap());
        detailGroup.addView(evidenceInput, matchWrap());
        detailGroup.addView(passwordInput, matchWrap());
        detailGroup.addView(totpInput, matchWrap());
        actions = vertical();
        detailGroup.addView(actions, matchWrap());
        addView(detailGroup, matchWrap());
    }

    public String filterStatus() {
        int position = statusInput.getSelectedItemPosition();
        return position >= 0 && position < STATUS_VALUES.length ? STATUS_VALUES[position] : "";
    }

    public String selectedIncidentId() { return selectedIncidentId; }

    public void restore(String status, String incidentId) {
        String safeStatus = status == null ? "" : status;
        for (int index = 0; index < STATUS_VALUES.length; index++) {
            if (STATUS_VALUES[index].equals(safeStatus)) statusInput.setSelection(index);
        }
        if (incidentId != null) {
            try {
                selectedIncidentId = AdminIncidentModels.canonicalUuid(incidentId, "incident_id");
            } catch (IllegalArgumentException ignored) {
                selectedIncidentId = null;
            }
        }
    }

    public void render(AdminIncidentController.State state) {
        stateText.setText(switch (state.phase()) {
            case IDLE -> "조회 전입니다.";
            case LOADING_LIST -> "중대 사고 기록을 불러오는 중입니다…";
            case LOADING_MORE -> "다음 중대 사고 기록을 불러오는 중입니다…";
            case LOADING_DETAIL -> "중대 사고 상세와 첫 상태 이력을 불러오는 중입니다…";
            case LOADING_HISTORY -> "중대 사고 상태 이력을 첫 페이지부터 다시 불러오는 중입니다…";
            case LOADING_HISTORY_MORE -> "다음 중대 사고 상태 이력을 불러오는 중입니다…";
            case CONTENT -> "중대 사고 " + state.items().size() + "건을 표시합니다.";
            case EMPTY -> "조건에 맞는 실제 중대 사고 기록이 없습니다. '모든 상태'로 다시 조회해 보세요.";
            case ERROR -> "중대 사고 기록을 불러오지 못했습니다. '중대 사고 조회 다시 시도'를 누르세요. " + state.errorMessage();
        });
        loadingIndicator.setVisibility(isLoading(state.phase()) ? VISIBLE : GONE);
        moreButton.setVisibility(state.canLoadMore() ? VISIBLE : GONE);
        retryButton.setVisibility(state.phase() == AdminIncidentController.Phase.ERROR ? VISIBLE : GONE);
        renderItems(state.items());
        selectedIncidentId = state.selectedIncidentId();
        AdminIncidentModels.Detail detail = state.detail();
        detailGroup.setVisibility(detail == null ? GONE : VISIBLE);
        if (detail != null) {
            renderDetail(detail, state.historyItems());
            historyStateText.setText(
                "상태 이력 " + state.historyItems().size() + "/"
                    + state.historyTotalCount() + "건을 불러왔습니다."
                    + (state.historyNextCursor() == null ? " 전체 이력 조회 완료." : "")
            );
            historyMoreButton.setVisibility(state.canLoadMoreHistory() ? VISIBLE : GONE);
        } else {
            displayedDetail = null;
            pendingRetryButton = null;
        }
        if (state.errorMessage() != null && state.phase() != AdminIncidentController.Phase.ERROR) {
            stateText.append(" " + state.errorMessage());
        }
    }

    public void clearSensitiveInputs() {
        passwordInput.setText("");
        totpInput.setText("");
    }

    public void clearSubmittedEvidence() {
        reasonInput.setText("");
        observationInput.setText("");
        evidenceInput.setText("");
    }

    public void renderPendingStatusRetry(String nextState) {
        if (pendingRetryButton != null) {
            actions.removeView(pendingRetryButton);
            pendingRetryButton = null;
        }
        if (displayedDetail == null || nextState == null
            || displayedDetail.allowedNextStates().contains(nextState)) {
            return;
        }
        pendingRetryButton = button(
            actionLabel(nextState) + " (보존한 멱등키로 결과 확인)"
        );
        pendingRetryButton.setOnClickListener(view -> submit(displayedDetail, nextState));
        actions.addView(pendingRetryButton, matchWrap());
    }

    private void renderItems(List<AdminIncidentModels.Summary> values) {
        items.removeAllViews();
        for (AdminIncidentModels.Summary item : values) {
            LinearLayout card = vertical();
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(Color.rgb(246, 246, 246));
            TextView badge = text("CRITICAL · " + statusIcon(item.status()) + " " + statusLabel(item.status()), 16);
            badge.setTextColor(Color.rgb(150, 0, 0));
            card.addView(badge, matchWrap());
            card.addView(text(
                item.summary()
                    + "\n기준: " + reasonLabel(item.reasonCode())
                    + "\n탐지시각: " + item.detectedAt()
                    + "\n사고 ID: " + item.incidentId(),
                15
            ), matchWrap());
            Button detailButton = button("이 중대 사고 상세와 이력 보기");
            detailButton.setOnClickListener(view -> {
                selectedIncidentId = item.incidentId();
                listener.onOpenDetail(item.incidentId());
            });
            card.addView(detailButton, matchWrap());
            LayoutParams params = matchWrap();
            params.setMargins(0, dp(6), 0, dp(6));
            items.addView(card, params);
        }
    }

    private void renderDetail(
        AdminIncidentModels.Detail detail,
        List<AdminIncidentModels.Event> history
    ) {
        displayedDetail = detail;
        pendingRetryButton = null;
        AdminIncidentModels.Summary summary = detail.summary();
        detailText.setText(
            "CRITICAL · " + statusIcon(summary.status()) + " " + statusLabel(summary.status())
                + "\n요약: " + summary.summary()
                + "\n기준: " + reasonLabel(summary.reasonCode())
                + "\n시작시각: " + summary.startedAt()
                + "\n탐지시각: " + summary.detectedAt()
                + "\n현재 version: " + summary.statusVersion()
                + "\n사고 ID: " + summary.incidentId()
        );
        events.removeAllViews();
        for (AdminIncidentModels.Event event : history) {
            TextView eventText = text(
                "기록 " + event.revision() + " · " + statusIcon(event.nextState())
                    + " " + statusLabel(event.nextState())
                    + "\n이전 상태: " + (event.previousState() == null ? "최초 기록" : statusLabel(event.previousState()))
                    + "\n사유: " + event.reason()
                    + "\n관찰: " + event.observation()
                    + "\n근거 SHA-256: " + event.evidenceSha256()
                    + "\n관찰시각: " + event.observedAt()
                    + "\n기록자: " + (event.actorId() == null ? "허용된 내부 producer" : event.actorId()),
                14
            );
            eventText.setPadding(dp(10), dp(10), dp(10), dp(10));
            events.addView(eventText, matchWrap());
        }
        actions.removeAllViews();
        for (String nextState : detail.allowedNextStates()) {
            Button action = button(actionLabel(nextState));
            action.setOnClickListener(view -> submit(detail, nextState));
            actions.addView(action, matchWrap());
        }
    }

    private void submit(AdminIncidentModels.Detail detail, String nextState) {
        char[] password = passwordInput.getText().toString().toCharArray();
        char[] totp = totpInput.getText().toString().toCharArray();
        String reason = reasonInput.getText().toString();
        String observation = observationInput.getText().toString();
        String evidence = evidenceInput.getText().toString();
        clearSensitiveInputs();
        listener.onUpdateStatus(detail, nextState, reason, observation, evidence, password, totp);
    }

    private EditText input(String hint, boolean sensitive) {
        EditText input = new EditText(getContext());
        input.setHint(hint);
        input.setMinHeight(dp(48));
        input.setSingleLine(false);
        input.setSaveEnabled(false);
        input.setId(View.NO_ID);
        input.setContentDescription(hint);
        input.setInputType(sensitive
            ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImportantForAutofill(
            sensitive ? View.IMPORTANT_FOR_AUTOFILL_NO : View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        );
        return input;
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

    private static String statusIcon(String status) {
        return switch (status) {
            case "OPEN" -> "!";
            case "ACKNOWLEDGED" -> "✓";
            case "RESOLVED" -> "■";
            case "REOPENED" -> "↺";
            default -> "?";
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "OPEN" -> "열림";
            case "ACKNOWLEDGED" -> "확인됨";
            case "RESOLVED" -> "해결 기록됨";
            case "REOPENED" -> "재개됨";
            default -> "알 수 없음";
        };
    }

    private static String actionLabel(String nextState) {
        return switch (nextState) {
            case "ACKNOWLEDGED" -> "이 중대 사고를 확인한 것으로 기록";
            case "RESOLVED" -> "해결된 것으로 기록 (자동 복구 아님)";
            case "REOPENED" -> "같은 중대 사고를 재개 기록";
            default -> "허용되지 않은 상태";
        };
    }

    private static String reasonLabel(String reasonCode) {
        return switch (reasonCode) {
            case "USER_SAFETY_RISK" -> "사용자 안전 위험";
            case "PERSONAL_DATA_BREACH" -> "개인정보 침해";
            case "DELETION_INTEGRITY_FAILURE" -> "삭제 무결성 실패";
            case "CORE_SERVICE_TOTAL_OUTAGE" -> "핵심 서비스 전체 장애";
            case "IRREVERSIBLE_DATA_LOSS" -> "되돌릴 수 없는 데이터 손실";
            default -> "허용되지 않은 기준";
        };
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static boolean isLoading(AdminIncidentController.Phase phase) {
        return phase == AdminIncidentController.Phase.LOADING_LIST
            || phase == AdminIncidentController.Phase.LOADING_MORE
            || phase == AdminIncidentController.Phase.LOADING_DETAIL
            || phase == AdminIncidentController.Phase.LOADING_HISTORY
            || phase == AdminIncidentController.Phase.LOADING_HISTORY_MORE;
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
