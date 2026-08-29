package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Generation-bound incident list/detail state; network work stays on the caller's worker. */
public final class AdminIncidentController {
    public enum Phase { IDLE, LOADING_LIST, LOADING_MORE, LOADING_DETAIL, CONTENT, EMPTY, ERROR }

    public interface Loader {
        AdminIncidentModels.Page loadPage(AdminIncidentModels.Filters filters, String cursor)
            throws Exception;
        AdminIncidentModels.Detail loadDetail(String incidentId) throws Exception;
    }

    public static final class State {
        private final Phase phase;
        private final AdminIncidentModels.Filters filters;
        private final List<AdminIncidentModels.Summary> items;
        private final String nextCursor;
        private final String selectedIncidentId;
        private final AdminIncidentModels.Detail detail;
        private final String errorMessage;

        private State(
            Phase phase,
            AdminIncidentModels.Filters filters,
            List<AdminIncidentModels.Summary> items,
            String nextCursor,
            String selectedIncidentId,
            AdminIncidentModels.Detail detail,
            String errorMessage
        ) {
            this.phase = phase;
            this.filters = filters;
            this.items = AdminJava8Collections.copyList(items);
            this.nextCursor = nextCursor;
            this.selectedIncidentId = selectedIncidentId;
            this.detail = detail;
            this.errorMessage = errorMessage;
        }

        public Phase phase() { return phase; }
        public AdminIncidentModels.Filters filters() { return filters; }
        public List<AdminIncidentModels.Summary> items() { return items; }
        public String nextCursor() { return nextCursor; }
        public String selectedIncidentId() { return selectedIncidentId; }
        public AdminIncidentModels.Detail detail() { return detail; }
        public String errorMessage() { return errorMessage; }
        public boolean canLoadMore() { return nextCursor != null && phase != Phase.LOADING_MORE; }
    }

    public static final class Request {
        private enum Kind { FIRST_PAGE, NEXT_PAGE, DETAIL }

        private final long generation;
        private final Kind kind;
        private final AdminIncidentModels.Filters filters;
        private final String cursor;
        private final String incidentId;

        private Request(
            long generation,
            Kind kind,
            AdminIncidentModels.Filters filters,
            String cursor,
            String incidentId
        ) {
            this.generation = generation;
            this.kind = kind;
            this.filters = filters;
            this.cursor = cursor;
            this.incidentId = incidentId;
        }
    }

    private final Loader loader;
    private long generation;
    private Request retryRequest;
    private State state;

    public AdminIncidentController(Loader loader) {
        if (loader == null) throw new IllegalArgumentException("incident loader is required");
        this.loader = loader;
        state = new State(
            Phase.IDLE,
            new AdminIncidentModels.Filters(null),
            AdminJava8Collections.list(),
            null,
            null,
            null,
            null
        );
    }

    public synchronized State snapshot() { return copy(state); }

    public synchronized Request beginFirstPage(AdminIncidentModels.Filters filters) {
        if (filters == null) throw new IllegalArgumentException("incident filters are required");
        Request request = new Request(++generation, Request.Kind.FIRST_PAGE, filters, null, null);
        retryRequest = request;
        state = new State(
            Phase.LOADING_LIST, filters, AdminJava8Collections.list(), null, null, null, null
        );
        return request;
    }

    public synchronized Request beginNextPage() {
        if (state.nextCursor == null) throw new IllegalStateException("next incident page is unavailable");
        Request request = new Request(
            ++generation, Request.Kind.NEXT_PAGE, state.filters, state.nextCursor,
            state.selectedIncidentId
        );
        retryRequest = request;
        state = new State(
            Phase.LOADING_MORE, state.filters, state.items, state.nextCursor,
            state.selectedIncidentId, state.detail, null
        );
        return request;
    }

    public synchronized Request beginDetail(String incidentId) {
        String safeId = AdminIncidentModels.canonicalUuid(incidentId, "incident_id");
        Request request = new Request(
            ++generation, Request.Kind.DETAIL, state.filters, null, safeId
        );
        retryRequest = request;
        state = new State(
            Phase.LOADING_DETAIL, state.filters, state.items, state.nextCursor,
            safeId, null, null
        );
        return request;
    }

    public synchronized Request beginRetry() {
        if (retryRequest == null || state.phase != Phase.ERROR) {
            throw new IllegalStateException("failed incident request is unavailable");
        }
        Request previous = retryRequest;
        Request request = new Request(
            ++generation, previous.kind, previous.filters, previous.cursor, previous.incidentId
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
            request.incidentId == null ? state.selectedIncidentId : request.incidentId,
            request.kind == Request.Kind.DETAIL ? null : state.detail,
            null
        );
        return request;
    }

    /** Returns false if a newer list/detail request superseded this result. */
    public boolean execute(Request request) {
        if (request == null) throw new IllegalArgumentException("incident request is required");
        try {
            if (request.kind == Request.Kind.DETAIL) {
                return applyDetail(request, loader.loadDetail(request.incidentId));
            }
            return applyPage(request, loader.loadPage(request.filters, request.cursor));
        } catch (Exception error) {
            return applyFailure(request, error instanceof AdminIncidentRepository.NotFoundException);
        }
    }

    public synchronized void invalidate() {
        generation += 1;
        if (state.phase == Phase.LOADING_LIST || state.phase == Phase.LOADING_MORE
            || state.phase == Phase.LOADING_DETAIL) {
            state = new State(
                Phase.ERROR, state.filters, state.items, state.nextCursor,
                state.selectedIncidentId, null,
                "화면이 중단되어 중대 사고 조회를 취소했습니다. 다시 시도해 주세요."
            );
        }
    }

    public synchronized void clearSessionState() {
        generation += 1;
        retryRequest = null;
        state = new State(
            Phase.IDLE,
            new AdminIncidentModels.Filters(null),
            AdminJava8Collections.list(),
            null,
            null,
            null,
            null
        );
    }

    private synchronized boolean applyPage(Request request, AdminIncidentModels.Page page) {
        if (request.generation != generation) return false;
        if (page == null) return applyFailure(request, false);
        List<AdminIncidentModels.Summary> combined = new ArrayList<>();
        if (request.kind == Request.Kind.NEXT_PAGE) combined.addAll(state.items);
        Set<String> ids = new HashSet<>();
        for (AdminIncidentModels.Summary item : combined) ids.add(item.incidentId());
        for (AdminIncidentModels.Summary item : page.items()) {
            if (!ids.add(item.incidentId())) return applyFailure(request, false);
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
            state.selectedIncidentId,
            state.detail,
            null
        );
        retryRequest = null;
        return true;
    }

    private synchronized boolean applyDetail(Request request, AdminIncidentModels.Detail detail) {
        if (request.generation != generation) return false;
        if (detail == null || !request.incidentId.equals(detail.summary().incidentId())) {
            return applyFailure(request, false);
        }
        state = new State(
            Phase.CONTENT, state.filters, state.items, state.nextCursor,
            request.incidentId, detail, null
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
            request.incidentId == null ? state.selectedIncidentId : request.incidentId,
            null,
            notFound
                ? "선택한 중대 사고를 찾을 수 없습니다."
                : "중대 사고 기록을 불러오지 못했습니다. 다시 시도해 주세요."
        );
        return true;
    }

    private static State copy(State value) {
        return new State(
            value.phase, value.filters, value.items, value.nextCursor,
            value.selectedIncidentId, value.detail, value.errorMessage
        );
    }
}
