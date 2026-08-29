package kr.co.hanium.dreamup.walksafe.admin;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminAuditController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminAuditModels;

/** Allowlisted audit event panel with stable cursor paging. */
public final class AdminAuditPanel extends LinearLayout {
    public interface Listener {
        void onLoad(String eventType, String actorId);
        void onLoadMore();
        void onRetry();
    }

    private static final String[] TYPE_VALUES = {
        "", "SECURITY", "READ", "STATUS", "REVIEW", "EXPORT", "DELIVERY"
    };
    private static final String[] TYPE_LABELS = {
        "모든 감사 유형", "보안", "조회", "상태", "검수", "제출본 생성", "수동 전달"
    };
    private final Listener listener;
    private final Spinner typeInput;
    private final EditText actorInput;
    private final TextView stateText;
    private final LinearLayout events;
    private final Button more;
    private final Button retry;

    public AdminAuditPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        setPadding(0, dp(28), 0, dp(16));
        TextView heading = text("관리자 감사 기록", 21);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) heading.setAccessibilityHeading(true);
        addView(heading, matchWrap());
        addView(text("허용된 사건·행위·결과·행위자·리소스·시각·상관 ID만 표시합니다.", 15), matchWrap());
        typeInput = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, TYPE_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeInput.setAdapter(adapter);
        typeInput.setMinimumHeight(dp(48));
        typeInput.setContentDescription("감사 사건 유형 필터");
        typeInput.setSaveEnabled(false);
        addView(typeInput, matchWrap());
        actorInput = new EditText(context);
        actorInput.setHint("관리자 ID (선택)");
        actorInput.setMinHeight(dp(48));
        actorInput.setSingleLine(false);
        actorInput.setSaveEnabled(false);
        actorInput.setId(View.NO_ID);
        actorInput.setContentDescription("감사 기록 관리자 ID 필터");
        addView(actorInput, matchWrap());
        Button load = button("감사 기록 조회");
        load.setOnClickListener(view -> listener.onLoad(eventType(), actorId()));
        addView(load, matchWrap());
        stateText = text("조회 전입니다.", 16);
        stateText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(stateText, matchWrap());
        events = new LinearLayout(context);
        events.setOrientation(VERTICAL);
        addView(events, matchWrap());
        more = button("다음 감사 기록 불러오기");
        more.setOnClickListener(view -> listener.onLoadMore());
        addView(more, matchWrap());
        retry = button("감사 기록 다시 시도");
        retry.setOnClickListener(view -> listener.onRetry());
        addView(retry, matchWrap());
    }

    public String eventType() {
        int position = typeInput.getSelectedItemPosition();
        return position >= 0 && position < TYPE_VALUES.length ? TYPE_VALUES[position] : "";
    }

    public String actorId() { return actorInput.getText().toString().trim(); }

    public void restore(String eventType, String actorId) {
        for (int index = 0; index < TYPE_VALUES.length; index++) {
            if (TYPE_VALUES[index].equals(eventType)) typeInput.setSelection(index);
        }
        actorInput.setText(actorId == null ? "" : actorId);
    }

    public void render(AdminAuditController.State state) {
        stateText.setText(switch (state.phase()) {
            case IDLE -> "조회 전입니다.";
            case LOADING -> "감사 기록을 불러오는 중입니다.";
            case LOADING_MORE -> "다음 감사 기록을 불러오는 중입니다.";
            case CONTENT -> "감사 기록 " + state.items().size() + "건을 표시합니다.";
            case EMPTY -> "조건에 맞는 감사 기록이 없습니다.";
            case ERROR -> "오류: " + state.errorMessage();
        });
        retry.setVisibility(state.phase() == AdminAuditController.Phase.ERROR ? VISIBLE : GONE);
        more.setVisibility(state.canLoadMore() ? VISIBLE : GONE);
        events.removeAllViews();
        for (AdminAuditModels.Event item : state.items()) {
            TextView card = text(
                typeIcon(item.eventType()) + " " + typeLabel(item.eventType())
                    + " · " + outcomeLabel(item.outcome())
                    + "\n행위: " + item.action()
                    + "\n행위자: " + (item.actorId() == null ? "시스템" : item.actorId())
                    + "\n대상: " + item.resourceType() + " / " + item.resourceId()
                    + "\n시각: " + item.occurredAt()
                    + (item.correlationId() == null ? "" : "\n상관 ID: " + item.correlationId()),
                15
            );
            card.setPadding(dp(12), dp(12), dp(12), dp(12));
            card.setBackgroundColor(Color.rgb(242, 242, 242));
            LayoutParams params = matchWrap();
            params.setMargins(0, dp(5), 0, dp(5));
            events.addView(card, params);
        }
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

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static LayoutParams matchWrap() {
        return new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static String typeIcon(String type) {
        return switch (type) {
            case "SECURITY" -> "🔒";
            case "READ" -> "◉";
            case "STATUS" -> "↔";
            case "REVIEW" -> "✓";
            case "EXPORT" -> "▣";
            case "DELIVERY" -> "→";
            default -> "•";
        };
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "SECURITY" -> "보안";
            case "READ" -> "조회";
            case "STATUS" -> "상태";
            case "REVIEW" -> "검수";
            case "EXPORT" -> "제출본 생성";
            case "DELIVERY" -> "수동 전달";
            default -> "기타";
        };
    }

    private static String outcomeLabel(String outcome) {
        return switch (outcome) {
            case "SUCCEEDED" -> "성공";
            case "DENIED" -> "거부됨";
            case "ERROR" -> "오류";
            default -> "알 수 없음";
        };
    }
}
