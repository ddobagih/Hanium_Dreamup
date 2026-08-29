package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Generation-bound report screen state. Network execution happens on the caller's worker. */
public final class AdminReportController {
    public enum Phase {
        IDLE,
        LOADING_LIST,
        LOADING_MORE,
        LOADING_DETAIL,
        CONTENT,
        EMPTY,
        ERROR
    }

    public interface Loader {
        AdminReportModels.Page loadPage(AdminReportModels.Filters filters, String cursor) throws Exception;
        AdminReportModels.Detail loadDetail(String reportId) throws Exception;
    }

    public static final class State {
        private final Phase phase;
        private final AdminReportModels.Filters filters;
        private final List<AdminReportModels.Summary> items;
        private final String nextCursor;
        private final String selectedReportId;
        private final AdminReportModels.Detail detail;
        private final String errorMessage;

        private State(
            Phase phase,
            AdminReportModels.Filters filters,
            List<AdminReportModels.Summary> items,
            String nextCursor,
            String selectedReportId,
            AdminReportModels.Detail detail,
            String errorMessage
        ) {
            this.phase = phase;
            this.filters = filters;
            this.items = AdminJava8Collections.copyList(items);
            this.nextCursor = nextCursor;
            this.selectedReportId = selectedReportId;
            this.detail = detail;
            this.errorMessage = errorMessage;
        }

        public Phase phase() { return phase; }
        public AdminReportModels.Filters filters() { return filters; }
        public List<AdminReportModels.Summary> items() { return items; }
        public String nextCursor() { return nextCursor; }
        public String selectedReportId() { return selectedReportId; }
        public AdminReportModels.Detail detail() { return detail; }
        public String errorMessage() { return errorMessage; }
        public boolean canLoadMore() { return nextCursor != null && phase != Phase.LOADING_MORE; }
    }

    public static final class Request {
        private enum Kind { FIRST_PAGE, NEXT_PAGE, DETAIL }

        private final long generation;
        private final Kind kind;
        private final AdminReportModels.Filters filters;
        private final String cursor;
        private final String reportId;

        private Request(
            long generation,
            Kind kind,
            AdminReportModels.Filters filters,
            String cursor,
            String reportId
        ) {
            this.generation = generation;
            this.kind = kind;
            this.filters = filters;
            this.cursor = cursor;
            this.reportId = reportId;
        }
    }

    private final Loader loader;
    private long generation;
    private Request retryRequest;
    private State state;

    public AdminReportController(Loader loader) {
        if (loader == null) throw new IllegalArgumentException("report loader is required");
        this.loader = loader;
        AdminReportModels.Filters empty = new AdminReportModels.Filters(null, null, null, null, null);
        state = new State(Phase.IDLE, empty, AdminJava8Collections.list(), null, null, null, null);
    }

    public synchronized State snapshot() {
        return copy(state);
    }

    public synchronized Request beginFirstPage(AdminReportModels.Filters filters) {
        if (filters == null) throw new IllegalArgumentException("report filters are required");
        Request request = new Request(++generation, Request.Kind.FIRST_PAGE, filters, null, null);
        retryRequest = request;
        state = new State(Phase.LOADING_LIST, filters, AdminJava8Collections.list(), null, null, null, null);
        return request;
    }

    public synchronized Request beginNextPage() {
        if (state.nextCursor == null) throw new IllegalStateException("next report page is unavailable");
        Request request = new Request(
            ++generation,
            Request.Kind.NEXT_PAGE,
            state.filters,
            state.nextCursor,
            state.selectedReportId
        );
        retryRequest = request;
        state = new State(
            Phase.LOADING_MORE,
            state.filters,
            state.items,
            state.nextCursor,
            state.selectedReportId,
            state.detail,
            null
        );
        return request;
    }

    public synchronized Request beginDetail(String reportId) {
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        Request request = new Request(
            ++generation,
            Request.Kind.DETAIL,
            state.filters,
            null,
            safeId
        );
        retryRequest = request;
        state = new State(
            Phase.LOADING_DETAIL,
            state.filters,
            state.items,
            state.nextCursor,
            safeId,
            null,
            null
        );
        return request;
    }

    public synchronized Request beginRetry() {
        if (retryRequest == null || state.phase != Phase.ERROR) {
            throw new IllegalStateException("failed report request is unavailable");
        }
        Request previous = retryRequest;
        Request request = new Request(
            ++generation,
            previous.kind,
            previous.filters,
            previous.cursor,
            previous.reportId
        );
        retryRequest = request;
        Phase loading = switch (request.kind) {
            case FIRST_PAGE -> Phase.LOADING_LIST;
            case NEXT_PAGE -> Phase.LOADING_MORE;
            case DETAIL -> Phase.LOADING_DETAIL;
        };
        state = new State(
            loading,
            state.filters,
            request.kind == Request.Kind.FIRST_PAGE ? AdminJava8Collections.list() : state.items,
            state.nextCursor,
            request.reportId == null ? state.selectedReportId : request.reportId,
            request.kind == Request.Kind.DETAIL ? null : state.detail,
            null
        );
        return request;
    }

    /** Returns false when a newer request has superseded this result. */
    public boolean execute(Request request) {
        if (request == null) throw new IllegalArgumentException("report request is required");
        try {
            if (request.kind == Request.Kind.DETAIL) {
                AdminReportModels.Detail detail = loader.loadDetail(request.reportId);
                return applyDetail(request, detail);
            }
            AdminReportModels.Page page = loader.loadPage(request.filters, request.cursor);
            return applyPage(request, page);
        } catch (Exception error) {
            return applyFailure(request, error instanceof AdminReportRepository.NotFoundException);
        }
    }

    public synchronized void invalidate() {
        generation += 1;
        if (state.phase == Phase.LOADING_LIST
            || state.phase == Phase.LOADING_MORE
            || state.phase == Phase.LOADING_DETAIL) {
            state = new State(
                Phase.ERROR,
                state.filters,
                state.items,
                state.nextCursor,
                state.selectedReportId,
                null,
                "화면이 중단되어 조회를 취소했습니다. 다시 시도해 주세요."
            );
        }
    }

    public synchronized void clearSessionState() {
        generation += 1;
        retryRequest = null;
        AdminReportModels.Filters empty =
            new AdminReportModels.Filters(null, null, null, null, null);
        state = new State(
            Phase.IDLE,
            empty,
            AdminJava8Collections.list(),
            null,
            null,
            null,
            null
        );
    }

    public synchronized void replaceDetail(AdminReportModels.Detail detail) {
        if (detail == null) throw new IllegalArgumentException("report detail is required");
        generation += 1;
        state = new State(
            Phase.CONTENT,
            state.filters,
            state.items,
            state.nextCursor,
            detail.summary().id(),
            detail,
            null
        );
        retryRequest = null;
    }

    private synchronized boolean applyPage(Request request, AdminReportModels.Page page) {
        if (request.generation != generation) return false;
        if (page == null) return applyFailure(request, false);
        List<AdminReportModels.Summary> combined = new ArrayList<>();
        if (request.kind == Request.Kind.NEXT_PAGE) combined.addAll(state.items);
        Set<String> ids = new HashSet<>();
        for (AdminReportModels.Summary item : combined) ids.add(item.id());
        for (AdminReportModels.Summary item : page.items()) {
            if (!ids.add(item.id())) return applyFailure(request, false);
            combined.add(item);
        }
        if (request.cursor != null && request.cursor.equals(page.nextCursor())) {
            return applyFailure(request, false);
        }
        state = new State(
            combined.isEmpty() ? Phase.EMPTY : Phase.CONTENT,
            request.filters,
            combined,
            page.nextCursor(),
            state.selectedReportId,
            state.detail,
            null
        );
        retryRequest = null;
        return true;
    }

    private synchronized boolean applyDetail(Request request, AdminReportModels.Detail detail) {
        if (request.generation != generation) return false;
        if (detail == null || !request.reportId.equals(detail.summary().id())) {
            return applyFailure(request, false);
        }
        state = new State(
            Phase.CONTENT,
            state.filters,
            state.items,
            state.nextCursor,
            request.reportId,
            detail,
            null
        );
        retryRequest = null;
        return true;
    }

    private synchronized boolean applyFailure(Request request, boolean notFound) {
        if (request.generation != generation) return false;
        state = new State(
            Phase.ERROR,
            state.filters,
            state.items,
            state.nextCursor,
            request.reportId == null ? state.selectedReportId : request.reportId,
            null,
            notFound
                ? "선택한 신고를 찾을 수 없습니다."
                : "신고 정보를 불러오지 못했습니다. 서버 상태를 확인한 뒤 다시 시도해 주세요."
        );
        return true;
    }

    private static State copy(State value) {
        return new State(
            value.phase,
            value.filters,
            value.items,
            value.nextCursor,
            value.selectedReportId,
            value.detail,
            value.errorMessage
        );
    }
}
