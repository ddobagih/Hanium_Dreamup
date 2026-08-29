package kr.co.hanium.dreamup.walksafe.admin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRequestController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRequestModels;

/** Vertically reflowing user request list, detail, and explicitly separated response panel. */
public final class AdminReportRequestPanel extends LinearLayout {
    public interface Listener {
        void onApplyFilters(FilterDraft filters);
        void onLoadMore();
        void onOpenDetail(String requestId);
        void onRetry();
        void onUpdateStatus(
            AdminReportRequestModels.Detail detail,
            String nextStatus,
            String publicResponse,
            String internalNote,
            char[] password,
            char[] totp
        );
    }

    public static final class FilterDraft {
        private final String reportId;
        private final String requestType;
        private final String status;

        public FilterDraft(String reportId, String requestType, String status) {
            this.reportId = normalized(reportId);
            this.requestType = normalized(requestType);
            this.status = normalized(status);
        }

        public String reportId() { return reportId; }
        public String requestType() { return requestType; }
        public String status() { return status; }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private static final String[] TYPE_VALUES = {"", "CORRECTION", "DELETE"};
    private static final String[] TYPE_LABELS = {"모든 요청 유형", "내용 정정 요청", "삭제 요청"};
    private static final String[] STATUS_VALUES = {
        "", "RECEIVED", "ACKNOWLEDGED", "RESOLVED", "REJECTED"
    };
    private static final String[] STATUS_LABELS = {
        "모든 처리 상태", "접수됨", "확인 중", "처리 완료", "처리 거절"
    };

    private final Listener listener;
    private final EditText reportIdInput;
    private final Spinner requestTypeInput;
    private final Spinner statusInput;
    private final TextView stateText;
    private final LinearLayout listContainer;
    private final LinearLayout detailContainer;
    private final Button loadMoreButton;
    private final Button retryButton;
    private final LinearLayout workflowActions;
    private final TextView workflowText;
    private final EditText publicResponseInput;
    private final EditText internalNoteInput;
    private final EditText highRiskPassword;
    private final EditText highRiskTotp;
    private final Button highRiskConfirm;
    private final List<Button> mutationButtons = new ArrayList<>();
    private AdminReportRequestModels.Detail displayedDetail;
    private String pendingStatus;
    private String selectedRequestId;

    public AdminReportRequestPanel(Context context, Listener listener) {
        super(context);
        if (listener == null) throw new IllegalArgumentException("report request listener is required");
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(0, dp(24), 0, dp(16));

        TextView heading = text("사용자 요청", 21);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) heading.setAccessibilityHeading(true);
        addView(heading, matchWrap());
        addView(text(
            "사용자가 신고 내용 정정 또는 삭제를 요청한 내역입니다. 여기서 실제 신고를 즉시 수정·삭제하거나 기관에 자동 전송하지 않습니다.",
            15
        ), matchWrap());

        reportIdInput = input("신고 UUID (선택)", "사용자 요청의 신고 UUID 필터");
        requestTypeInput = spinner(TYPE_LABELS, "사용자 요청 유형 필터");
        statusInput = spinner(STATUS_LABELS, "사용자 요청 처리 상태 필터");
        addView(reportIdInput, matchWrap());
        addView(requestTypeInput, matchWrap());
        addView(statusInput, matchWrap());
        Button apply = button("조건으로 사용자 요청 조회", "현재 조건으로 사용자 요청 목록 조회");
        apply.setOnClickListener(view -> listener.onApplyFilters(filterDraft()));
        addView(apply, matchWrap());

        stateText = text("조회 전입니다.", 16);
        stateText.setPadding(0, dp(12), 0, dp(8));
        stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(stateText, matchWrap());
        listContainer = verticalGroup();
        addView(listContainer, matchWrap());
        loadMoreButton = button(
            "다음 사용자 요청 불러오기",
            "현재 조건의 다음 사용자 요청 페이지 불러오기"
        );
        loadMoreButton.setOnClickListener(view -> listener.onLoadMore());
        addView(loadMoreButton, matchWrap());
        retryButton = button("다시 시도", "실패한 사용자 요청 조회 다시 시도");
        retryButton.setOnClickListener(view -> listener.onRetry());
        addView(retryButton, matchWrap());

        detailContainer = verticalGroup();
        addView(detailContainer, matchWrap());
        workflowActions = verticalGroup();
        workflowText = text("처리 상태 변경은 매번 비밀번호와 6자리 추가 인증이 필요합니다.", 15);
        workflowText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        workflowActions.addView(workflowText, matchWrap());
        publicResponseInput = input(
            "사용자에게 보여줄 공개 답변 (선택)",
            "사용자에게 공개되는 처리 답변"
        );
        internalNoteInput = input(
            "관리자 내부 메모 (사용자에게 보이지 않음, 선택)",
            "사용자에게 공개되지 않는 관리자 내부 메모"
        );
        highRiskPassword = sensitiveInput(
            "상태 변경 비밀번호",
            "사용자 요청 상태 변경 비밀번호",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        highRiskTotp = sensitiveInput(
            "상태 변경 6자리 추가 인증",
            "사용자 요청 상태 변경 6자리 추가 인증",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );
        workflowActions.addView(publicResponseInput, matchWrap());
        workflowActions.addView(internalNoteInput, matchWrap());
        workflowActions.addView(highRiskPassword, matchWrap());
        workflowActions.addView(highRiskTotp, matchWrap());
        highRiskConfirm = button(
            "재인증 후 상태 변경",
            "사용자 요청 상태를 재인증 후 한 번 변경"
        );
        highRiskConfirm.setOnClickListener(view -> confirmStatusUpdate());
        workflowActions.addView(highRiskConfirm, matchWrap());
        workflowActions.setVisibility(GONE);
        addView(workflowActions, matchWrap());
    }

    public FilterDraft filterDraft() {
        return new FilterDraft(
            reportIdInput.getText().toString(),
            selectedValue(requestTypeInput, TYPE_VALUES),
            selectedValue(statusInput, STATUS_VALUES)
        );
    }

    public String selectedRequestId() { return selectedRequestId; }

    public void restore(FilterDraft draft, String selectedRequestId) {
        if (draft != null) {
            reportIdInput.setText(draft.reportId());
            selectValue(requestTypeInput, TYPE_VALUES, draft.requestType());
            selectValue(statusInput, STATUS_VALUES, draft.status());
        }
        this.selectedRequestId = selectedRequestId;
        if (selectedRequestId != null) {
            stateText.setText(
                "회전 전에 선택한 사용자 요청: " + selectedRequestId + " · 상세를 다시 조회하세요."
            );
        }
    }

    public void render(AdminReportRequestController.State state) {
        selectedRequestId = selectionAfterRender(selectedRequestId, state);
        if (state.phase() == AdminReportRequestController.Phase.IDLE
            && state.selectedRequestId() == null
            && selectedRequestId != null) {
            stateText.setText(
                "회전 전에 선택한 사용자 요청: " + selectedRequestId
                    + " · 목록을 조회한 뒤 해당 요청의 상세 보기 버튼을 눌러 주세요."
            );
        } else {
            stateText.setText(stateMessage(state));
        }
        retryButton.setVisibility(
            state.phase() == AdminReportRequestController.Phase.ERROR
                || state.phase() == AdminReportRequestController.Phase.UPDATED_DETAIL_STALE
                ? VISIBLE : GONE
        );
        loadMoreButton.setVisibility(state.canLoadMore() ? VISIBLE : GONE);
        boolean mutating = state.phase() == AdminReportRequestController.Phase.MUTATING;
        loadMoreButton.setEnabled(!mutating);
        highRiskConfirm.setEnabled(!mutating);
        publicResponseInput.setEnabled(!mutating);
        internalNoteInput.setEnabled(!mutating);
        highRiskPassword.setEnabled(!mutating);
        highRiskTotp.setEnabled(!mutating);
        for (Button button : mutationButtons) button.setEnabled(!mutating);
        renderList(state);
        renderDetail(state.detail(), mutating);
    }

    static String selectionAfterRender(
        String retainedSelection,
        AdminReportRequestController.State state
    ) {
        return state.selectedRequestId() == null
            ? retainedSelection
            : state.selectedRequestId();
    }

    public void clearSensitiveInputs() {
        highRiskPassword.getText().clear();
        highRiskTotp.getText().clear();
    }

    private void renderList(AdminReportRequestController.State state) {
        listContainer.removeAllViews();
        for (AdminReportRequestModels.Summary item : state.items()) {
            LinearLayout card = card(item.status());
            TextView status = text(statusIcon(item.status()) + " " + statusLabel(item.status()), 18);
            status.setContentDescription("사용자 요청 상태: " + statusLabel(item.status()));
            card.addView(status, matchWrap());
            card.addView(text(
                typeLabel(item.requestType())
                    + "\n신고 ID: " + item.reportId()
                    + "\n요청 ID: " + item.requestId()
                    + "\n상태 version " + item.statusVersion()
                    + "\n접수: " + item.createdAt(),
                15
            ), matchWrap());
            Button detail = button(
                "요청 상세 보기",
                typeLabel(item.requestType()) + " " + statusLabel(item.status()) + " 요청 상세 보기"
            );
            detail.setOnClickListener(view -> listener.onOpenDetail(item.requestId()));
            card.addView(detail, matchWrap());
            listContainer.addView(card, cardParams());
        }
    }

    private void renderDetail(AdminReportRequestModels.Detail detail, boolean mutating) {
        detailContainer.removeAllViews();
        mutationButtons.clear();
        displayedDetail = detail;
        if (!mutating) {
            pendingStatus = null;
            workflowActions.setVisibility(GONE);
            clearSensitiveInputs();
        }
        if (detail == null) return;
        TextView heading = text("사용자 요청 상세", 20);
        heading.setPadding(0, dp(22), 0, dp(6));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) heading.setAccessibilityHeading(true);
        detailContainer.addView(heading, matchWrap());
        AdminReportRequestModels.Summary summary = detail.summary();
        LinearLayout card = card(summary.status());
        card.addView(text(
            statusIcon(summary.status()) + " " + statusLabel(summary.status())
                + " · 상태 version " + summary.statusVersion()
                + "\n" + typeLabel(summary.requestType())
                + "\n요청 ID: " + summary.requestId()
                + "\n신고 ID: " + summary.reportId()
                + "\n요청 내용: " + detail.requestText()
                + "\n최근 갱신: " + summary.updatedAt(),
            16
        ), matchWrap());
        TextView publicResponse = text(
            "사용자에게 보이는 공개 답변\n"
                + (detail.publicResponse() == null ? "아직 없음" : detail.publicResponse()),
            15
        );
        card.addView(publicResponse, matchWrap());
        TextView internalNote = text(
            "관리자만 보는 내부 메모\n"
                + (detail.internalNote() == null ? "아직 없음" : detail.internalNote()),
            15
        );
        card.addView(internalNote, matchWrap());
        for (String nextStatus : summary.allowedNextStatuses()) {
            Button update = button(
                "상태를 " + statusLabel(nextStatus) + "(으)로 변경",
                "현재 version " + summary.statusVersion() + "에서 사용자 요청 상태를 "
                    + statusLabel(nextStatus) + "(으)로 변경"
            );
            update.setOnClickListener(view -> prepareStatus(nextStatus));
            mutationButtons.add(update);
            update.setEnabled(!mutating);
            card.addView(update, matchWrap());
        }
        detailContainer.addView(card, matchWrap());
    }

    private void prepareStatus(String nextStatus) {
        pendingStatus = nextStatus;
        publicResponseInput.setText(displayedDetail.publicResponse());
        internalNoteInput.setText(displayedDetail.internalNote());
        workflowText.setText(
            "선택 작업: " + statusLabel(nextStatus)
                + " · 공개 답변과 내부 메모는 서로 다른 항목이며 자동 재제출하지 않습니다."
        );
        workflowActions.setVisibility(VISIBLE);
        publicResponseInput.requestFocus();
    }

    private void confirmStatusUpdate() {
        if (displayedDetail == null || pendingStatus == null) return;
        char[] password = editableChars(highRiskPassword);
        char[] totp = editableChars(highRiskTotp);
        String publicResponse = publicResponseInput.getText().toString();
        String internalNote = internalNoteInput.getText().toString();
        clearSensitiveInputs();
        try {
            listener.onUpdateStatus(
                displayedDetail,
                pendingStatus,
                publicResponse,
                internalNote,
                password,
                totp
            );
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
        }
    }

    private EditText input(String hint, String description) {
        EditText input = new EditText(getContext());
        input.setHint(hint);
        input.setContentDescription(description);
        input.setSingleLine(false);
        input.setMinHeight(dp(48));
        input.setSaveEnabled(false);
        input.setId(View.NO_ID);
        return input;
    }

    private static char[] editableChars(EditText input) {
        char[] value = new char[input.length()];
        input.getText().getChars(0, value.length, value, 0);
        return value;
    }

    private EditText sensitiveInput(String hint, String description, int inputType) {
        EditText input = input(hint, description);
        input.setInputType(inputType);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setFilterTouchesWhenObscured(true);
        return input;
    }

    private Spinner spinner(String[] labels, String description) {
        Spinner spinner = new Spinner(getContext());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            getContext(),
            android.R.layout.simple_spinner_item,
            labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setContentDescription(description);
        spinner.setMinimumHeight(dp(48));
        spinner.setSaveEnabled(false);
        return spinner;
    }

    private Button button(String label, String description) {
        Button button = new Button(getContext());
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setContentDescription(description);
        return button;
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.BLACK);
        return view;
    }

    private LinearLayout verticalGroup() {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(VERTICAL);
        return group;
    }

    private LinearLayout card(String status) {
        LinearLayout card = verticalGroup();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(statusColor(status));
        background.setCornerRadius(dp(12));
        background.setStroke(dp(2), Color.rgb(55, 55, 55));
        card.setBackground(background);
        return card;
    }

    private LayoutParams cardParams() {
        LayoutParams parameters = matchWrap();
        parameters.setMargins(0, dp(6), 0, dp(6));
        return parameters;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static LayoutParams matchWrap() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static String selectedValue(Spinner spinner, String[] values) {
        int position = spinner.getSelectedItemPosition();
        return position >= 0 && position < values.length ? values[position] : "";
    }

    private static void selectValue(Spinner spinner, String[] values, String value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index].equals(value)) {
                spinner.setSelection(index);
                return;
            }
        }
        spinner.setSelection(0);
    }

    private static String stateMessage(AdminReportRequestController.State state) {
        if (state.message() != null) return state.message();
        return switch (state.phase()) {
            case IDLE -> "조회 전입니다.";
            case LOADING_LIST -> "사용자 요청 목록을 불러오는 중입니다.";
            case LOADING_MORE -> "다음 사용자 요청을 불러오는 중입니다.";
            case LOADING_DETAIL -> "사용자 요청 상세를 불러오는 중입니다.";
            case MUTATING -> "사용자 요청 상태를 변경하는 중입니다.";
            case CONTENT -> "사용자 요청 " + state.items().size() + "건을 표시합니다.";
            case EMPTY -> "조건에 맞는 사용자 요청이 없습니다.";
            case UPDATED_DETAIL_STALE -> "변경 성공, 최신 상세 조회 실패";
            case CONFLICT -> "다른 관리자의 변경과 충돌해 최신 상태를 표시합니다.";
            case ERROR -> "오류: 사용자 요청을 처리하지 못했습니다.";
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "CORRECTION" -> "내용 정정 요청";
            case "DELETE" -> "삭제 요청";
            default -> "알 수 없는 요청";
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "RECEIVED" -> "접수됨";
            case "ACKNOWLEDGED" -> "확인 중";
            case "RESOLVED" -> "처리 완료";
            case "REJECTED" -> "처리 거절";
            default -> "알 수 없는 상태";
        };
    }

    private static String statusIcon(String status) {
        return switch (status) {
            case "RECEIVED" -> "●";
            case "ACKNOWLEDGED" -> "…";
            case "RESOLVED" -> "✓";
            case "REJECTED" -> "!";
            default -> "?";
        };
    }

    private static int statusColor(String status) {
        return switch (status) {
            case "RECEIVED" -> Color.rgb(255, 243, 205);
            case "ACKNOWLEDGED" -> Color.rgb(217, 237, 247);
            case "RESOLVED" -> Color.rgb(220, 242, 220);
            case "REJECTED" -> Color.rgb(250, 220, 220);
            default -> Color.LTGRAY;
        };
    }
}
