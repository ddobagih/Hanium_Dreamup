package kr.co.hanium.dreamup.walksafe.admin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportModels;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportWorkflowController;

/** Vertically reflowing, non-sensitive report list/detail panel. */
public final class AdminReportPanel extends LinearLayout {
    public interface Listener {
        void onApplyFilters(FilterDraft filters);
        void onLoadMore();
        void onOpenDetail(String reportId);
        void onRetry();
        void onRetryWorkflowDetail();
        void onUseInOperations(AdminReportModels.Detail detail);
        void onUpdateStatus(
            AdminReportModels.Detail detail,
            String nextStatus,
            char[] password,
            char[] totp
        );
        void onCreateDeliveryPackage(AdminReportModels.Detail detail, char[] password, char[] totp);
    }

    public static final class FilterDraft {
        private final String reportId;
        private final String status;
        private final String className;
        private final String createdFrom;
        private final String createdTo;

        public FilterDraft(
            String reportId,
            String status,
            String className,
            String createdFrom,
            String createdTo
        ) {
            this.reportId = normalized(reportId);
            this.status = normalized(status);
            this.className = normalized(className);
            this.createdFrom = normalized(createdFrom);
            this.createdTo = normalized(createdTo);
        }

        public String reportId() { return reportId; }
        public String status() { return status; }
        public String className() { return className; }
        public String createdFrom() { return createdFrom; }
        public String createdTo() { return createdTo; }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private static final String[] STATUS_VALUES = {"", "new", "reviewed", "resolved"};
    private static final String[] STATUS_LABELS = {"모든 상태", "새 신고", "검토됨", "처리 완료"};
    private static final String[] CLASS_VALUES = {
        "",
        "damaged_tactile_block",
        "parked_kickboard_bicycle",
        "construction_obstacle",
        "pothole"
    };
    private static final String[] CLASS_LABELS = {
        "모든 유형",
        "점자블록 파손",
        "킥보드·자전거 방치",
        "공사 장애물",
        "도로 파임"
    };

    private final Listener listener;
    private final EditText reportIdInput;
    private final Spinner statusInput;
    private final Spinner classInput;
    private final EditText createdFromInput;
    private final EditText createdToInput;
    private final TextView stateText;
    private final TextView summaryText;
    private final TextView priorityText;
    private final ProgressBar loadingIndicator;
    private final LinearLayout listContainer;
    private final LinearLayout detailContainer;
    private final Button applyButton;
    private final Button loadMoreButton;
    private final Button retryButton;
    private final LinearLayout workflowActions;
    private final EditText highRiskPassword;
    private final EditText highRiskTotp;
    private final Button highRiskConfirm;
    private final TextView workflowText;
    private final List<Button> mutationButtons = new ArrayList<>();
    private final List<Button> navigationButtons = new ArrayList<>();
    private AdminReportModels.Detail displayedDetail;
    private String pendingStatus;
    private boolean pendingPackage;
    private boolean sessionBoundDraftsCleared;
    private boolean reportRetryAvailable;
    private boolean workflowDetailRetryAvailable;
    private boolean workflowBlocksNavigation;
    private String selectedReportId;

    public AdminReportPanel(Context context, Listener listener) {
        super(context);
        if (listener == null) throw new IllegalArgumentException("report panel listener is required");
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(0, dp(24), 0, dp(16));

        TextView heading = text("1. 업무 요약과 신고 선택", 21);
        markAccessibilityHeading(heading);
        addView(heading, matchWrap());
        addView(text(
            "먼저 '새 신고'를 확인하세요. 목록은 현재 불러온 범위만 요약하며, 상세에서 검수와 제출 준비로 이어갈 수 있습니다.",
            15
        ), matchWrap());

        summaryText = text("현재 불러온 목록: 아직 조회하지 않음", 18);
        summaryText.setPadding(0, dp(14), 0, dp(2));
        markAccessibilityHeading(summaryText);
        addView(summaryText, matchWrap());
        priorityText = text("우선 업무: 신고를 조회하세요.", 15);
        priorityText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(priorityText, matchWrap());

        reportIdInput = input("정확한 신고 UUID (선택)");
        reportIdInput.setContentDescription("신고 UUID 필터");
        addView(reportIdInput, matchWrap());
        statusInput = spinner(STATUS_LABELS, "신고 상태 필터");
        classInput = spinner(CLASS_LABELS, "신고 유형 필터");
        addView(statusInput, matchWrap());
        addView(classInput, matchWrap());
        createdFromInput = input("시작 시각 (예: 2026-08-29T00:00:00Z, 선택)");
        createdToInput = input("종료 시각 (예: 2026-08-29T23:59:59Z, 선택)");
        createdFromInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        createdToInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        createdFromInput.setContentDescription("신고 생성 시작시각 필터");
        createdToInput.setContentDescription("신고 생성 종료시각 필터");
        addView(createdFromInput, matchWrap());
        addView(createdToInput, matchWrap());

        applyButton = button("조건으로 신고 조회", "현재 조건으로 신고 목록 조회");
        applyButton.setOnClickListener(view -> listener.onApplyFilters(filterDraft()));
        addView(applyButton, matchWrap());

        stateText = text("조회 전입니다.", 16);
        stateText.setPadding(0, dp(12), 0, dp(8));
        stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(stateText, matchWrap());
        loadingIndicator = new ProgressBar(context);
        loadingIndicator.setIndeterminate(true);
        loadingIndicator.setContentDescription("신고 정보를 불러오는 중");
        loadingIndicator.setVisibility(GONE);
        addView(loadingIndicator, wrapCentered());

        listContainer = verticalGroup();
        addView(listContainer, matchWrap());
        loadMoreButton = button("다음 신고 불러오기", "현재 조건의 다음 신고 페이지 불러오기");
        loadMoreButton.setOnClickListener(view -> listener.onLoadMore());
        addView(loadMoreButton, matchWrap());
        retryButton = button("다시 시도", "실패한 신고 조회 다시 시도");
        retryButton.setOnClickListener(view -> {
            if (workflowDetailRetryAvailable) listener.onRetryWorkflowDetail();
            else listener.onRetry();
        });
        addView(retryButton, matchWrap());

        detailContainer = verticalGroup();
        addView(detailContainer, matchWrap());
        workflowActions = verticalGroup();
        workflowText = text("상태 변경과 제출본 생성은 매번 비밀번호와 6자리 추가 인증이 필요합니다.", 15);
        workflowText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        workflowActions.addView(workflowText, matchWrap());
        highRiskPassword = sensitiveInput(
            "고위험 작업 비밀번호",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        highRiskTotp = sensitiveInput(
            "고위험 작업 6자리 추가 인증",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
        );
        workflowActions.addView(highRiskPassword, matchWrap());
        workflowActions.addView(highRiskTotp, matchWrap());
        highRiskConfirm = button("선택한 고위험 작업 재인증 후 실행", "고위험 관리자 작업 재인증 후 한 번 실행");
        highRiskConfirm.setOnClickListener(view -> confirmHighRiskAction());
        workflowActions.addView(highRiskConfirm, matchWrap());
        workflowActions.setVisibility(GONE);
        addView(workflowActions, matchWrap());
        TextView originalBoundary = text(
            "원본 사진은 이 패널에서 열지 않습니다. 일회용 목적 결속 grant와 고위험 재인증이 완결된 전용 흐름만 허용됩니다.",
            14
        );
        originalBoundary.setPadding(0, dp(16), 0, 0);
        addView(originalBoundary, matchWrap());
    }

    public FilterDraft filterDraft() {
        return new FilterDraft(
            reportIdInput.getText().toString(),
            selectedValue(statusInput, STATUS_VALUES),
            selectedValue(classInput, CLASS_VALUES),
            createdFromInput.getText().toString(),
            createdToInput.getText().toString()
        );
    }

    public String selectedReportId() {
        return selectedReportId;
    }

    public void restore(FilterDraft draft, String selectedReportId) {
        if (draft != null) {
            reportIdInput.setText(draft.reportId());
            selectValue(statusInput, STATUS_VALUES, draft.status());
            selectValue(classInput, CLASS_VALUES, draft.className());
            createdFromInput.setText(draft.createdFrom());
            createdToInput.setText(draft.createdTo());
        }
        this.selectedReportId = selectedReportId;
        if (selectedReportId != null) {
            stateText.setText("회전 전에 선택한 신고: " + selectedReportId + " · 다시 로그인한 뒤 상세를 조회하세요.");
        }
    }

    public void render(AdminReportController.State state) {
        selectedReportId = state.selectedReportId();
        stateText.setText(stateMessage(state));
        summaryText.setText(workSummary(state.items()));
        priorityText.setText(priorityMessage(state.items()));
        loadingIndicator.setVisibility(isLoading(state.phase()) ? VISIBLE : GONE);
        reportRetryAvailable = state.phase() == AdminReportController.Phase.ERROR;
        loadMoreButton.setVisibility(state.canLoadMore() ? VISIBLE : GONE);
        loadMoreButton.setEnabled(state.phase() != AdminReportController.Phase.LOADING_MORE);
        navigationButtons.clear();
        renderList(state);
        renderDetail(state.detail());
        renderRetryAction();
        applyWorkflowNavigationState();
    }

    public void renderWorkflow(AdminReportWorkflowController.State state) {
        boolean detailRecovery =
            state.phase() == AdminReportWorkflowController.Phase.UPDATED_DETAIL_STALE;
        if (sessionBoundDraftsCleared && !detailRecovery) {
            workflowActions.setVisibility(GONE);
            return;
        }
        if (detailRecovery) sessionBoundDraftsCleared = false;
        if (state.message() != null) workflowText.setText(state.message());
        if (state.phase() != AdminReportWorkflowController.Phase.IDLE) {
            workflowActions.setVisibility(VISIBLE);
        }
        boolean loading = state.phase() == AdminReportWorkflowController.Phase.LOADING;
        workflowDetailRetryAvailable = detailRecovery;
        workflowBlocksNavigation = loading || workflowDetailRetryAvailable;
        renderRetryAction();
        applyWorkflowNavigationState();
    }

    public void clearHighRiskInputs() {
        highRiskPassword.getText().clear();
        highRiskTotp.getText().clear();
    }

    public void clearSessionBoundDrafts() {
        sessionBoundDraftsCleared = true;
        pendingStatus = null;
        pendingPackage = false;
        workflowDetailRetryAvailable = false;
        workflowBlocksNavigation = false;
        clearHighRiskInputs();
        workflowActions.setVisibility(GONE);
        renderRetryAction();
        applyWorkflowNavigationState();
    }

    private void renderList(AdminReportController.State state) {
        listContainer.removeAllViews();
        for (AdminReportModels.Summary item : state.items()) {
            LinearLayout card = verticalGroup();
            card.setPadding(dp(16), dp(14), dp(16), dp(14));
            LayoutParams cardParams = matchWrap();
            cardParams.setMargins(0, dp(6), 0, dp(6));
            card.setBackground(cardBackground(statusColor(item.status())));
            TextView status = text(statusIcon(item.status()) + " " + statusLabel(item.status()), 18);
            status.setContentDescription("상태: " + statusLabel(item.status()));
            card.addView(status, matchWrap());
            card.addView(text(
                classLabel(item.className())
                    + "\n위치 품질: " + locationLabel(item.locationQuality())
                    + " · 신뢰도 " + Math.round(item.confidence() * 100d) + "%"
                    + "\n중복 근거 " + item.duplicateCount() + "건"
                    + "\n접수: " + item.createdAt(),
                15
            ), matchWrap());
            Button detail = button(
                "상세 보기",
                statusLabel(item.status()) + " " + classLabel(item.className()) + " 신고 상세 보기"
            );
            detail.setOnClickListener(view -> listener.onOpenDetail(item.id()));
            navigationButtons.add(detail);
            card.addView(detail, matchWrap());
            listContainer.addView(card, cardParams);
        }
    }

    private void renderDetail(AdminReportModels.Detail detail) {
        detailContainer.removeAllViews();
        mutationButtons.clear();
        displayedDetail = detail;
        pendingStatus = null;
        pendingPackage = false;
        workflowActions.setVisibility(GONE);
        clearHighRiskInputs();
        if (detail == null) return;
        TextView heading = text("선택 신고 상세", 20);
        heading.setPadding(0, dp(22), 0, dp(6));
        markAccessibilityHeading(heading);
        detailContainer.addView(heading, matchWrap());
        AdminReportModels.Summary summary = detail.summary();
        detailContainer.addView(text(
            "처리 순서\n1) 상세 확인  →  2) 검수 폼에 연결·결정 기록  →  "
                + "3) 승인 후 제출본 저장  →  4) 앱 밖 수동 제출  →  5) 접수번호 기록",
            15
        ), matchWrap());
        LinearLayout card = verticalGroup();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(cardBackground(statusColor(summary.status())));
        card.addView(text(
            statusIcon(summary.status()) + " " + statusLabel(summary.status())
                + " · 상태 version " + summary.statusVersion()
                + "\n" + classLabel(summary.className())
                + "\n신고 ID: " + summary.id()
                + "\n위치 품질: " + locationLabel(summary.locationQuality())
                + " · 신뢰도 " + Math.round(summary.confidence() * 100d) + "%"
                + "\n촬영: " + summary.capturedAt()
                + "\n최근 갱신: " + summary.updatedAt(),
            16
        ), matchWrap());
        card.addView(text(reviewText(detail.review()), 15), matchWrap());
        card.addView(text(deliveryText(detail.delivery()), 15), matchWrap());
        for (String nextStatus : detail.allowedNextStatuses()) {
            Button statusButton = button(
                "상태를 " + statusLabel(nextStatus) + "(으)로 변경",
                "현재 version " + summary.statusVersion() + "에서 상태를 " + statusLabel(nextStatus) + "(으)로 변경"
            );
            statusButton.setOnClickListener(view -> prepareStatus(nextStatus));
            mutationButtons.add(statusButton);
            card.addView(statusButton, matchWrap());
        }
        if (canCreatePackage(detail.review())) {
            Button packageButton = button(
                "기관 수동 제출용 ZIP 생성",
                "승인된 신고의 기관 수동 제출용 ZIP을 생성하고 저장 위치 선택"
            );
            packageButton.setOnClickListener(view -> preparePackage());
            mutationButtons.add(packageButton);
            card.addView(packageButton, matchWrap());
        }
        Button use = button(
            "검수·수동 제출 준비로 이어가기",
            "선택 신고를 아래 검수 결정과 수동 기관 제출 기록 폼에 연결"
        );
        use.setOnClickListener(view -> listener.onUseInOperations(detail));
        navigationButtons.add(use);
        card.addView(use, matchWrap());
        detailContainer.addView(card, matchWrap());
    }

    private void renderRetryAction() {
        retryButton.setText(workflowDetailRetryAvailable ? "최신 신고 상세 다시 조회" : "다시 시도");
        retryButton.setContentDescription(
            workflowDetailRetryAvailable
                ? "상태 변경 결과 확인을 위한 최신 신고 상세만 다시 조회"
                : "실패한 신고 조회 다시 시도"
        );
        retryButton.setVisibility(
            reportRetryAvailable || workflowDetailRetryAvailable ? VISIBLE : GONE
        );
    }

    private void applyWorkflowNavigationState() {
        boolean enabled = !workflowBlocksNavigation;
        boolean mutationEnabled = enabled;
        reportIdInput.setEnabled(enabled);
        statusInput.setEnabled(enabled);
        classInput.setEnabled(enabled);
        createdFromInput.setEnabled(enabled);
        createdToInput.setEnabled(enabled);
        applyButton.setEnabled(enabled);
        retryButton.setEnabled(enabled || workflowDetailRetryAvailable);
        if (loadMoreButton.getVisibility() == VISIBLE) loadMoreButton.setEnabled(enabled);
        for (Button button : navigationButtons) button.setEnabled(enabled);
        highRiskPassword.setEnabled(mutationEnabled);
        highRiskTotp.setEnabled(mutationEnabled);
        highRiskConfirm.setEnabled(mutationEnabled);
        for (Button button : mutationButtons) button.setEnabled(mutationEnabled);
    }

    private static String stateMessage(AdminReportController.State state) {
        return switch (state.phase()) {
            case IDLE -> "조회 전입니다. 필요하면 조건을 선택한 뒤 신고를 조회하세요.";
            case LOADING_LIST -> "신고 목록을 불러오는 중입니다…";
            case LOADING_MORE -> "다음 신고를 불러오는 중입니다… 현재 " + state.items().size() + "건";
            case LOADING_DETAIL -> "선택한 신고 상세를 불러오는 중입니다…";
            case CONTENT -> "신고 " + state.items().size() + "건을 표시합니다."
                + (state.detail() == null ? "" : " 선택 신고 상세도 표시합니다.");
            case EMPTY -> "조건에 맞는 신고가 없습니다. 조건을 줄이거나 '모든 상태'로 다시 조회하세요.";
            case ERROR -> "신고를 불러오지 못했습니다. 서버 상태를 확인한 뒤 '다시 시도'를 누르세요. "
                + state.errorMessage();
        };
    }

    private static String workSummary(List<AdminReportModels.Summary> items) {
        int fresh = 0;
        int reviewed = 0;
        int resolved = 0;
        for (AdminReportModels.Summary item : items) {
            if ("new".equals(item.status())) fresh += 1;
            else if ("reviewed".equals(item.status())) reviewed += 1;
            else if ("resolved".equals(item.status())) resolved += 1;
        }
        return "현재 불러온 목록 " + items.size() + "건 · 새 신고 " + fresh
            + "건 · 검토됨 " + reviewed + "건 · 처리 완료 " + resolved + "건";
    }

    private static String priorityMessage(List<AdminReportModels.Summary> items) {
        int fresh = 0;
        int reviewed = 0;
        for (AdminReportModels.Summary item : items) {
            if ("new".equals(item.status())) fresh += 1;
            else if ("reviewed".equals(item.status())) reviewed += 1;
        }
        if (fresh > 0) return "우선 업무: 새 신고 " + fresh + "건의 상세와 중복 가능성을 먼저 확인하세요.";
        if (reviewed > 0) return "다음 업무: 검토된 신고 " + reviewed + "건의 수동 제출·접수 기록을 확인하세요.";
        if (items.isEmpty()) return "우선 업무: 신고를 조회하세요.";
        return "현재 불러온 목록에 새 검토 대상이 없습니다.";
    }

    private static boolean isLoading(AdminReportController.Phase phase) {
        return phase == AdminReportController.Phase.LOADING_LIST
            || phase == AdminReportController.Phase.LOADING_MORE
            || phase == AdminReportController.Phase.LOADING_DETAIL;
    }

    private static String reviewText(AdminReportModels.ReviewSummary review) {
        if (review == null) return "\n현재 검수: 아직 기록 없음";
        return "\n현재 검수: " + review.decision() + " · revision " + review.revision()
            + "\n사용자 공개 사유: "
            + (review.userVisibleReason() == null ? "없음" : review.userVisibleReason())
            + "\n위치 " + done(review.locationReviewed())
            + " · 사진 " + done(review.photoReviewed())
            + " · 개인정보 " + done(review.privacyReviewed())
            + (review.duplicateOfReportId() == null ? "" : "\n중복 대상: " + review.duplicateOfReportId())
            + "\n결정시각: " + review.decidedAt();
    }

    private static String deliveryText(AdminReportModels.DeliverySummary delivery) {
        if (delivery == null) return "\n현재 수동전달 기록: 아직 기록 없음";
        return "\n현재 수동전달 기록: " + delivery.status() + " · revision " + delivery.revision()
            + "\n제출본 revision: " + delivery.packageRevision()
            + "\n외부 접수번호 " + present(delivery.externalReceiptPresent())
            + " · 증빙 " + present(delivery.evidencePresent())
            + "\n관찰시각: " + delivery.observedAt()
            + "\n기록시각: " + delivery.recordedAt();
    }

    private void prepareStatus(String nextStatus) {
        sessionBoundDraftsCleared = false;
        pendingStatus = nextStatus;
        pendingPackage = false;
        workflowText.setText("선택 작업: 상태를 " + statusLabel(nextStatus) + "(으)로 변경 · 자동 재제출 없음");
        workflowActions.setVisibility(VISIBLE);
        highRiskPassword.requestFocus();
    }

    private void preparePackage() {
        sessionBoundDraftsCleared = false;
        pendingStatus = null;
        pendingPackage = true;
        workflowText.setText("선택 작업: ZIP 생성 · 저장과 실제 기관 전달 기록은 별도 사건입니다.");
        workflowActions.setVisibility(VISIBLE);
        highRiskPassword.requestFocus();
    }

    private void confirmHighRiskAction() {
        if (displayedDetail == null || (!pendingPackage && pendingStatus == null)) return;
        char[] password = editableChars(highRiskPassword);
        char[] totp = editableChars(highRiskTotp);
        clearHighRiskInputs();
        try {
            if (pendingPackage) {
                listener.onCreateDeliveryPackage(displayedDetail, password, totp);
            } else {
                listener.onUpdateStatus(displayedDetail, pendingStatus, password, totp);
            }
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
        }
    }

    private static char[] editableChars(EditText input) {
        char[] value = new char[input.length()];
        input.getText().getChars(0, value.length, value, 0);
        return value;
    }

    private EditText sensitiveInput(String hint, int inputType) {
        EditText input = input(hint);
        input.setInputType(inputType);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setFilterTouchesWhenObscured(true);
        return input;
    }

    private static boolean canCreatePackage(AdminReportModels.ReviewSummary review) {
        return review != null
            && "APPROVED".equals(review.decision())
            && review.locationReviewed()
            && review.photoReviewed()
            && review.privacyReviewed()
            && review.duplicateOfReportId() == null;
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
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.BLACK);
        return view;
    }

    private EditText input(String hint) {
        EditText input = new EditText(getContext());
        input.setHint(hint);
        input.setSingleLine(false);
        input.setMinHeight(dp(48));
        input.setSaveEnabled(false);
        input.setId(View.NO_ID);
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

    private LinearLayout verticalGroup() {
        LinearLayout group = new LinearLayout(getContext());
        group.setOrientation(VERTICAL);
        return group;
    }

    private GradientDrawable cardBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(2), Color.rgb(55, 55, 55));
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static LayoutParams matchWrap() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LayoutParams wrapCentered() {
        LayoutParams parameters = new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        parameters.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        return parameters;
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

    private static int statusColor(String status) {
        return switch (status) {
            case "new" -> Color.rgb(255, 243, 205);
            case "reviewed" -> Color.rgb(217, 237, 247);
            case "resolved" -> Color.rgb(220, 242, 220);
            default -> Color.LTGRAY;
        };
    }

    private static String statusIcon(String status) {
        return switch (status) {
            case "new" -> "●";
            case "reviewed" -> "✓";
            case "resolved" -> "★";
            default -> "?";
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "new" -> "새 신고";
            case "reviewed" -> "검토됨";
            case "resolved" -> "처리 완료";
            default -> "알 수 없는 상태";
        };
    }

    private static String classLabel(String value) {
        return switch (value) {
            case "damaged_tactile_block" -> "점자블록 파손";
            case "parked_kickboard_bicycle" -> "킥보드·자전거 방치";
            case "construction_obstacle" -> "공사 장애물";
            case "pothole" -> "도로 파임";
            default -> value.toLowerCase(Locale.ROOT);
        };
    }

    private static String locationLabel(String value) {
        return switch (value) {
            case "missing" -> "위치 없음";
            case "low" -> "낮음";
            case "medium" -> "보통";
            case "high" -> "높음";
            default -> "알 수 없음";
        };
    }

    private static String done(boolean value) { return value ? "확인됨" : "미확인"; }
    private static String present(boolean value) { return value ? "있음" : "없음"; }
}
