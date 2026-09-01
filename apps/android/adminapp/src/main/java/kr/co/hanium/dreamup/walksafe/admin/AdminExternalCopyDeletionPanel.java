package kr.co.hanium.dreamup.walksafe.admin;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.time.Instant;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminExternalCopyDeletionController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminExternalCopyDeletionModels;

/** Accessible record-only UI for facts observed outside the app. */
public final class AdminExternalCopyDeletionPanel extends LinearLayout {
    public interface Listener {
        void onLoad(FilterDraft filter);
        void onLoadMore();
        void onRetryRead();
        void onRetryRecord();
        void onRecord(
            AdminExternalCopyDeletionModels.Item item,
            String nextState,
            String observedAt,
            String institutionReference,
            String evidenceSha256
        );
    }

    public static final class FilterDraft {
        private final String requestId;

        public FilterDraft(String requestId) { this.requestId = requestId == null ? "" : requestId; }

        public String requestId() { return requestId; }
    }

    private final Listener listener;
    private final EditText requestIdInput;
    private final TextView stateText;
    private final ProgressBar loading;
    private final LinearLayout items;
    private final Button more;
    private final Button retryRead;
    private final Button retryRecord;
    private final LinearLayout recordForm;
    private final TextView recordDescription;
    private final EditText observedAtInput;
    private final EditText institutionReferenceInput;
    private final EditText evidenceSha256Input;
    private AdminExternalCopyDeletionModels.Item pendingItem;
    private String pendingState;
    private String selectedCopyId;

    public AdminExternalCopyDeletionPanel(Context context, Listener listener) {
        super(context);
        if (listener == null) throw new IllegalArgumentException("external-copy listener is required");
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(0, dp(28), 0, dp(16));

        TextView heading = text("외부기관 보관본 삭제 사실 기록", 21);
        markAccessibilityHeading(heading);
        addView(heading, matchWrap());
        TextView policy = text(
            "이 화면은 기관에 요청을 보내거나 삭제를 수행하지 않습니다. 앱 밖에서 직접 발송·회신·삭제를 확인한 뒤 그 사실만 기록합니다. 원문 회신, 이메일·전화번호 등 연락처는 입력하거나 저장하지 마세요.",
            15
        );
        policy.setPadding(dp(12), dp(12), dp(12), dp(12));
        policy.setBackgroundColor(Color.rgb(255, 247, 220));
        addView(policy, matchWrap());

        requestIdInput = input(
            "삭제 요청 UUID (선택)",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        requestIdInput.setContentDescription("외부기관 보관본 삭제 요청 UUID 필터");
        addView(requestIdInput, matchWrap());
        Button load = button("외부기관 보관본 목록 새로 조회");
        load.setOnClickListener(view -> listener.onLoad(filterDraft()));
        addView(load, matchWrap());

        stateText = text("조회 전입니다.", 16);
        stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(stateText, matchWrap());
        loading = new ProgressBar(context);
        loading.setIndeterminate(true);
        loading.setContentDescription("외부기관 보관본 목록을 불러오는 중");
        loading.setVisibility(GONE);
        addView(loading, wrapCentered());
        items = new LinearLayout(context);
        items.setOrientation(VERTICAL);
        addView(items, matchWrap());

        more = button("다음 외부기관 보관본 불러오기");
        more.setOnClickListener(view -> listener.onLoadMore());
        addView(more, matchWrap());
        retryRead = button("외부기관 보관본 조회 다시 시도");
        retryRead.setOnClickListener(view -> listener.onRetryRead());
        addView(retryRead, matchWrap());
        retryRecord = button("같은 사실·멱등키로 기록 결과 다시 확인");
        retryRecord.setOnClickListener(view -> listener.onRetryRecord());
        addView(retryRecord, matchWrap());

        recordForm = new LinearLayout(context);
        recordForm.setOrientation(VERTICAL);
        recordForm.setVisibility(GONE);
        recordDescription = text("", 16);
        recordDescription.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        recordForm.addView(recordDescription, matchWrap());
        observedAtInput = input(
            "앱 밖에서 사실을 확인한 UTC 시각 (예: 2026-09-01T01:00:00Z)",
            InputType.TYPE_CLASS_DATETIME
        );
        institutionReferenceInput = input(
            "기관 사건번호 등 최소 참조값 (회신 원문·연락처 금지)",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        evidenceSha256Input = input(
            "외부 증거 파일 SHA-256 소문자 64자 (파일·원문은 저장하지 않음)",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        );
        recordForm.addView(observedAtInput, matchWrap());
        recordForm.addView(institutionReferenceInput, matchWrap());
        recordForm.addView(evidenceSha256Input, matchWrap());
        Button confirm = button("선택한 앱 밖 사실만 기록");
        confirm.setOnClickListener(view -> confirmRecord());
        recordForm.addView(confirm, matchWrap());
        Button cancel = button("사실 기록 입력 취소");
        cancel.setOnClickListener(view -> clearTransientInputs());
        recordForm.addView(cancel, matchWrap());
        addView(recordForm, matchWrap());
    }

    public FilterDraft filterDraft() {
        return new FilterDraft(requestIdInput.getText().toString().trim());
    }

    public String selectedCopyId() { return selectedCopyId; }

    public void restore(FilterDraft filter, String selectedCopyId) {
        requestIdInput.setText(filter == null ? "" : filter.requestId());
        this.selectedCopyId = selectedCopyId;
    }

    public void render(AdminExternalCopyDeletionController.State state) {
        stateText.setText(switch (state.phase()) {
            case IDLE -> "조회 전입니다.";
            case LOADING -> "외부기관 보관본을 불러오는 중입니다…";
            case LOADING_MORE -> "다음 외부기관 보관본을 불러오는 중입니다…";
            case MUTATING -> state.message();
            case CONTENT -> state.message() == null
                ? "외부기관 보관본 " + state.items().size() + "건을 표시합니다."
                : state.message();
            case EMPTY -> "조건에 맞는 외부기관 보관본이 없습니다.";
            case CONFLICT, ERROR -> state.message();
        });
        stateText.setAccessibilityLiveRegion(
            state.phase() == AdminExternalCopyDeletionController.Phase.ERROR
                || state.phase() == AdminExternalCopyDeletionController.Phase.CONFLICT
                ? View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
                : View.ACCESSIBILITY_LIVE_REGION_POLITE
        );
        loading.setVisibility(
            state.phase() == AdminExternalCopyDeletionController.Phase.LOADING
                || state.phase() == AdminExternalCopyDeletionController.Phase.LOADING_MORE
                || state.phase() == AdminExternalCopyDeletionController.Phase.MUTATING
                ? VISIBLE
                : GONE
        );
        more.setVisibility(state.canLoadMore() ? VISIBLE : GONE);
        retryRead.setVisibility(
            state.phase() == AdminExternalCopyDeletionController.Phase.ERROR
                && !state.canRetryRecord() ? VISIBLE : GONE
        );
        retryRecord.setVisibility(state.canRetryRecord() ? VISIBLE : GONE);
        if (state.selectedCopyId() != null) selectedCopyId = state.selectedCopyId();

        items.removeAllViews();
        boolean selectedStillPresent = false;
        for (AdminExternalCopyDeletionModels.Item item : state.items()) {
            if (item.copyId().equals(selectedCopyId)) selectedStillPresent = true;
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(VERTICAL);
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(Color.rgb(242, 242, 242));
            card.addView(text(
                stateIcon(item.state()) + " " + stateLabel(item.state())
                    + " · revision " + item.revision()
                    + "\n기관: " + item.institution()
                    + "\n기관 전달 당시 상태: " + item.deliveryStatusAtLocalDeletion()
                    + " (외부 보관본 삭제 확인 아님)"
                    + "\n삭제 요청 ID: " + item.requestId()
                    + "\n보관본 ID: " + item.copyId()
                    + "\n사실 확인 시각: " + value(item.statusObservedAt())
                    + "\n서버 기록 시각: " + value(item.statusRecordedAt()),
                15
            ), matchWrap());
            for (String nextState : item.allowedNextStates()) {
                Button action = button(actionLabel(nextState));
                action.setEnabled(state.phase() != AdminExternalCopyDeletionController.Phase.MUTATING);
                action.setOnClickListener(view -> prepare(item, nextState));
                card.addView(action, matchWrap());
            }
            LayoutParams params = matchWrap();
            params.setMargins(0, dp(5), 0, dp(5));
            items.addView(card, params);
        }
        if (!selectedStillPresent && !state.items().isEmpty()) selectedCopyId = null;
        if (state.phase() == AdminExternalCopyDeletionController.Phase.MUTATING
            || state.phase() == AdminExternalCopyDeletionController.Phase.CONTENT
            || state.phase() == AdminExternalCopyDeletionController.Phase.CONFLICT) {
            clearTransientInputs();
        }
    }

    public void clearTransientInputs() {
        pendingItem = null;
        pendingState = null;
        observedAtInput.setText("");
        institutionReferenceInput.setText("");
        evidenceSha256Input.setText("");
        recordForm.setVisibility(GONE);
    }

    public void clearSessionBoundDrafts() {
        requestIdInput.setText("");
        selectedCopyId = null;
        clearTransientInputs();
    }

    private void prepare(AdminExternalCopyDeletionModels.Item item, String nextState) {
        pendingItem = item;
        pendingState = nextState;
        selectedCopyId = item.copyId();
        observedAtInput.setText(Instant.now().toString());
        institutionReferenceInput.setText("");
        evidenceSha256Input.setText("");
        recordDescription.setText(
            actionLabel(nextState) + " · 앱은 기관 통신이나 삭제를 수행하지 않습니다."
        );
        recordForm.setVisibility(VISIBLE);
        observedAtInput.requestFocus();
    }

    private void confirmRecord() {
        if (pendingItem == null || pendingState == null) return;
        AdminExternalCopyDeletionModels.Item item = pendingItem;
        String state = pendingState;
        String observedAt = observedAtInput.getText().toString().trim();
        String reference = institutionReferenceInput.getText().toString().trim();
        String digest = evidenceSha256Input.getText().toString().trim();
        clearTransientInputs();
        listener.onRecord(item, state, observedAt, reference, digest);
    }

    private EditText input(String hint, int inputType) {
        EditText input = new EditText(getContext());
        input.setHint(hint);
        input.setInputType(inputType);
        input.setSingleLine(false);
        input.setMinHeight(dp(48));
        input.setSaveEnabled(false);
        input.setId(View.NO_ID);
        input.setContentDescription(hint);
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

    private TextView text(String value, int sp) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(Color.BLACK);
        return text;
    }

    @SuppressWarnings("deprecation")
    private static void markAccessibilityHeading(TextView view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setAccessibilityHeading(true);
            return;
        }
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                    0, 1, 0, 1, true, false
                ));
            }
        });
    }

    private static String actionLabel(String state) {
        return switch (state) {
            case "REQUEST_SENT" -> "앱 밖 삭제 요청 발송 사실 기록";
            case "REPLY_ACKNOWLEDGED" -> "기관 회신 확인 사실 기록";
            case "REPLY_DELETION_CONFIRMED" -> "기관 삭제 확인 사실 기록";
            case "REPLY_DECLINED" -> "기관 거절 사실 기록";
            default -> "허용되지 않은 사실 기록";
        };
    }

    private static String stateLabel(String state) {
        return switch (state) {
            case "NOT_REQUESTED" -> "삭제 요청 전";
            case "REQUEST_SENT" -> "앱 밖 삭제 요청 발송 기록됨";
            case "REPLY_ACKNOWLEDGED" -> "기관 회신 확인됨";
            case "REPLY_DELETION_CONFIRMED" -> "기관 삭제 확인됨";
            case "REPLY_DECLINED" -> "기관 거절 확인됨";
            default -> "알 수 없음";
        };
    }

    private static String stateIcon(String state) {
        return switch (state) {
            case "REPLY_DELETION_CONFIRMED" -> "✓";
            case "REPLY_DECLINED" -> "!";
            case "NOT_REQUESTED" -> "○";
            default -> "↔";
        };
    }

    private static String value(String value) { return value == null ? "없음" : value; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static LayoutParams matchWrap() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private LayoutParams wrapCentered() {
        LayoutParams params = new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        return params;
    }
}
