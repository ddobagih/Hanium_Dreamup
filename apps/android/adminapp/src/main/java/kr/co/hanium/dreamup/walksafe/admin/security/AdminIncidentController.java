package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Generation-bound incident list/detail/history state; network work stays on the caller's worker. */
public final class AdminIncidentController {
    public enum Phase {
        IDLE, LOADING_LIST, LOADING_MORE, LOADING_DETAIL, LOADING_HISTORY,
        LOADING_HISTORY_MORE, CONTENT, EMPTY, ERROR
    }

    public interface Loader {
        AdminIncidentModels.Page loadPage(AdminIncidentModels.Filters filters, String cursor)
            throws Exception;
        AdminIncidentModels.HistoryPage loadHistory(String incidentId, String cursor)
            throws Exception;
    }

    public static final class State {
        private final Phase phase;
        private final AdminIncidentModels.Filters filters;
        private final List<AdminIncidentModels.Summary> items;
        private final String nextCursor;
        private final String selectedIncidentId;
        private final AdminIncidentModels.Detail detail;
        private final List<AdminIncidentModels.Event> historyItems;
        private final String historyNextCursor;
        private final int historySnapshotRevision;
        private final int historyTotalCount;
        private final String errorMessage;

        private State(
            Phase phase,
            AdminIncidentModels.Filters filters,
            List<AdminIncidentModels.Summary> items,
            String nextCursor,
            String selectedIncidentId,
            AdminIncidentModels.Detail detail,
            List<AdminIncidentModels.Event> historyItems,
            String historyNextCursor,
            int historySnapshotRevision,
            int historyTotalCount,
            String errorMessage
        ) {
            this.phase = phase;
            this.filters = filters;
            this.items = AdminJava8Collections.copyList(items);
            this.nextCursor = nextCursor;
            this.selectedIncidentId = selectedIncidentId;
            this.detail = detail;
            this.historyItems = AdminJava8Collections.copyList(historyItems);
            this.historyNextCursor = historyNextCursor;
            this.historySnapshotRevision = historySnapshotRevision;
            this.historyTotalCount = historyTotalCount;
            this.errorMessage = errorMessage;
        }

        public Phase phase() { return phase; }
        public AdminIncidentModels.Filters filters() { return filters; }
        public List<AdminIncidentModels.Summary> items() { return items; }
        public String nextCursor() { return nextCursor; }
        public String selectedIncidentId() { return selectedIncidentId; }
        public AdminIncidentModels.Detail detail() { return detail; }
        public List<AdminIncidentModels.Event> historyItems() { return historyItems; }
        public String historyNextCursor() { return historyNextCursor; }
        public int historySnapshotRevision() { return historySnapshotRevision; }
        public int historyTotalCount() { return historyTotalCount; }
        public String errorMessage() { return errorMessage; }
        public boolean canLoadMore() { return nextCursor != null && phase != Phase.LOADING_MORE; }
        public boolean canLoadMoreHistory() {
            return detail != null && historyNextCursor != null
                && phase == Phase.CONTENT;
        }
    }

    public static final class Request {
        private enum Kind { FIRST_PAGE, NEXT_PAGE, DETAIL, HISTORY_FIRST, HISTORY_NEXT }

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
        state = emptyState(Phase.IDLE, new AdminIncidentModels.Filters(null), null);
    }

    public synchronized State snapshot() { return copy(state); }

    public synchronized Request beginFirstPage(AdminIncidentModels.Filters filters) {
        if (filters == null) throw new IllegalArgumentException("incident filters are required");
        Request request = new Request(++generation, Request.Kind.FIRST_PAGE, filters, null, null);
        retryRequest = request;
        state = emptyState(Phase.LOADING_LIST, filters, null);
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
            state.selectedIncidentId, state.detail, state.historyItems, state.historyNextCursor,
            state.historySnapshotRevision, state.historyTotalCount, null
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
            safeId, null, AdminJava8Collections.list(), null, 0, 0, null
        );
        return request;
    }

    public synchronized Request beginNextHistoryPage() {
        if (state.detail == null || state.historyNextCursor == null) {
            throw new IllegalStateException("next incident history page is unavailable");
        }
        Request request = new Request(
            ++generation, Request.Kind.HISTORY_NEXT, state.filters,
            state.historyNextCursor, state.selectedIncidentId
        );
        retryRequest = request;
        state = new State(
            Phase.LOADING_HISTORY_MORE, state.filters, state.items, state.nextCursor,
            state.selectedIncidentId, state.detail, state.historyItems, state.historyNextCursor,
            state.historySnapshotRevision, state.historyTotalCount, null
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
            case HISTORY_FIRST -> Phase.LOADING_HISTORY;
            case HISTORY_NEXT -> Phase.LOADING_HISTORY_MORE;
        };
        state = new State(
            loading, state.filters,
            request.kind == Request.Kind.FIRST_PAGE ? AdminJava8Collections.list() : state.items,
            state.nextCursor,
            request.incidentId == null ? state.selectedIncidentId : request.incidentId,
            request.kind == Request.Kind.DETAIL ? null : state.detail,
            request.kind == Request.Kind.DETAIL ? AdminJava8Collections.list() : state.historyItems,
            request.kind == Request.Kind.DETAIL ? null : state.historyNextCursor,
            request.kind == Request.Kind.DETAIL ? 0 : state.historySnapshotRevision,
            request.kind == Request.Kind.DETAIL ? 0 : state.historyTotalCount,
            null
        );
        return request;
    }

    /** Returns false if a newer list/detail/history request superseded this result. */
    public boolean execute(Request request) {
        if (request == null) throw new IllegalArgumentException("incident request is required");
        try {
            return switch (request.kind) {
                case DETAIL -> applyDetail(
                    request,
                    loader.loadHistory(request.incidentId, null)
                );
                case HISTORY_FIRST, HISTORY_NEXT -> applyHistory(
                    request, loader.loadHistory(request.incidentId, request.cursor)
                );
                case FIRST_PAGE, NEXT_PAGE -> applyPage(
                    request, loader.loadPage(request.filters, request.cursor)
                );
            };
        } catch (AdminIncidentRepository.NotFoundException error) {
            return applyNotFound(request);
        } catch (AdminIncidentRepository.HistoryCursorException error) {
            return applyHistoryCursorFailure(request);
        } catch (Exception error) {
            return applyFailure(request);
        }
    }

    public synchronized void invalidate() {
        generation += 1;
        if (isLoading(state.phase)) {
            state = new State(
                Phase.ERROR, state.filters, state.items, state.nextCursor,
                state.selectedIncidentId, state.detail, state.historyItems,
                state.historyNextCursor, state.historySnapshotRevision, state.historyTotalCount,
                "화면이 중단되어 중대 사고 조회를 취소했습니다. 다시 시도해 주세요."
            );
        }
    }

    public synchronized void clearSessionState() {
        generation += 1;
        retryRequest = null;
        state = emptyState(Phase.IDLE, new AdminIncidentModels.Filters(null), null);
    }

    private synchronized boolean applyPage(Request request, AdminIncidentModels.Page page) {
        if (request.generation != generation) return false;
        if (page == null) return applyFailure(request);
        List<AdminIncidentModels.Summary> combined = new ArrayList<>();
        if (request.kind == Request.Kind.NEXT_PAGE) combined.addAll(state.items);
        Set<String> ids = new HashSet<>();
        for (AdminIncidentModels.Summary item : combined) ids.add(item.incidentId());
        for (AdminIncidentModels.Summary item : page.items()) {
            if (!ids.add(item.incidentId())) return applyFailure(request);
            combined.add(item);
        }
        if (request.cursor != null && request.cursor.equals(page.nextCursor())) {
            return applyFailure(request);
        }
        state = new State(
            combined.isEmpty() ? Phase.EMPTY : Phase.CONTENT,
            request.filters, combined, page.nextCursor(), state.selectedIncidentId,
            state.detail, state.historyItems, state.historyNextCursor,
            state.historySnapshotRevision, state.historyTotalCount, null
        );
        retryRequest = null;
        return true;
    }

    private synchronized boolean applyDetail(
        Request request,
        AdminIncidentModels.HistoryPage history
    ) throws IOException {
        if (request.generation != generation) return false;
        if (history == null) {
            return applyFailure(request);
        }
        return applyHistoryResult(
            request, AdminIncidentModels.detailFromHistory(history), history, true
        );
    }

    private synchronized boolean applyHistory(
        Request request,
        AdminIncidentModels.HistoryPage history
    ) throws IOException {
        if (request.generation != generation) return false;
        if (state.detail == null || history == null) return applyFailure(request);
        return applyHistoryResult(
            request,
            state.detail,
            history,
            request.kind == Request.Kind.HISTORY_FIRST
        );
    }

    private boolean applyHistoryResult(
        Request request,
        AdminIncidentModels.Detail detail,
        AdminIncidentModels.HistoryPage page,
        boolean replace
    ) throws IOException {
        if (!request.incidentId.equals(page.incident().incidentId())) {
            throw new IOException("incident history resource binding is invalid");
        }
        List<AdminIncidentModels.Event> combined = new ArrayList<>();
        if (!replace) {
            if (state.historySnapshotRevision != page.snapshotRevision()
                || state.historyTotalCount != page.totalCount()
                || !sameSummary(state.detail.summary(), page.incident())
                || !state.detail.allowedNextStates().equals(page.allowedNextStates())) {
                throw new IOException("incident history snapshot changed between pages");
            }
            combined.addAll(state.historyItems);
        }
        Set<String> eventIds = new HashSet<>();
        for (AdminIncidentModels.Event item : combined) eventIds.add(item.eventId());
        AdminIncidentModels.Event previous = combined.isEmpty()
            ? null : combined.get(combined.size() - 1);
        for (AdminIncidentModels.Event item : page.items()) {
            int expectedRevision = previous == null ? 1 : previous.revision() + 1;
            if (item.revision() != expectedRevision || !eventIds.add(item.eventId())
                || (previous != null && !previous.nextState().equals(item.previousState()))) {
                throw new IOException("incident history pages are not contiguous");
            }
            combined.add(item);
            previous = item;
        }
        if (request.cursor != null && request.cursor.equals(page.nextCursor())) {
            throw new IOException("incident history cursor made no progress");
        }
        if (combined.size() > page.totalCount()
            || (page.nextCursor() == null && combined.size() != page.totalCount())
            || (page.nextCursor() != null && combined.size() >= page.totalCount())) {
            throw new IOException("incident history loaded count is inconsistent");
        }
        AdminIncidentModels.Detail bound = AdminIncidentModels.bindDetailToHistory(detail, page);
        state = new State(
            Phase.CONTENT, state.filters, state.items, state.nextCursor,
            request.incidentId, bound, combined, page.nextCursor(),
            page.snapshotRevision(), page.totalCount(), null
        );
        retryRequest = null;
        return true;
    }

    private synchronized boolean applyFailure(Request request) {
        if (request.generation != generation) return false;
        state = new State(
            Phase.ERROR, state.filters, state.items, state.nextCursor,
            request.incidentId == null ? state.selectedIncidentId : request.incidentId,
            state.detail, state.historyItems, state.historyNextCursor,
            state.historySnapshotRevision, state.historyTotalCount,
            "중대 사고 기록을 불러오지 못했습니다. 다시 시도해 주세요."
        );
        return true;
    }

    private synchronized boolean applyHistoryCursorFailure(Request request) {
        if (request.generation != generation) return false;
        retryRequest = new Request(
            request.generation,
            request.kind == Request.Kind.DETAIL
                ? Request.Kind.DETAIL : Request.Kind.HISTORY_FIRST,
            state.filters, null,
            request.incidentId
        );
        state = new State(
            Phase.ERROR, state.filters, state.items, state.nextCursor,
            state.selectedIncidentId, state.detail, state.historyItems,
            state.historyNextCursor, state.historySnapshotRevision, state.historyTotalCount,
            "이력 페이지 기준이 만료되었습니다. 기존 이력은 유지했으며 첫 페이지부터 다시 조회해 주세요."
        );
        return true;
    }

    private synchronized boolean applyNotFound(Request request) {
        if (request.generation != generation) return false;
        retryRequest = null;
        state = new State(
            state.items.isEmpty() ? Phase.EMPTY : Phase.CONTENT,
            state.filters, state.items, state.nextCursor, null, null,
            AdminJava8Collections.list(), null, 0, 0,
            "선택한 중대 사고가 더 이상 존재하지 않아 선택을 해제했습니다."
        );
        return true;
    }

    private static boolean sameSummary(
        AdminIncidentModels.Summary left,
        AdminIncidentModels.Summary right
    ) {
        return left.incidentId().equals(right.incidentId())
            && left.severity().equals(right.severity())
            && left.status().equals(right.status())
            && left.statusVersion() == right.statusVersion()
            && left.reasonCode().equals(right.reasonCode())
            && left.summary().equals(right.summary())
            && left.startedAt().equals(right.startedAt())
            && left.detectedAt().equals(right.detectedAt())
            && left.updatedAt().equals(right.updatedAt());
    }

    private static boolean isLoading(Phase phase) {
        return phase == Phase.LOADING_LIST || phase == Phase.LOADING_MORE
            || phase == Phase.LOADING_DETAIL || phase == Phase.LOADING_HISTORY
            || phase == Phase.LOADING_HISTORY_MORE;
    }

    private static State emptyState(
        Phase phase,
        AdminIncidentModels.Filters filters,
        String errorMessage
    ) {
        return new State(
            phase, filters, AdminJava8Collections.list(), null, null, null,
            AdminJava8Collections.list(), null, 0, 0, errorMessage
        );
    }

    private static State copy(State value) {
        return new State(
            value.phase, value.filters, value.items, value.nextCursor,
            value.selectedIncidentId, value.detail, value.historyItems,
            value.historyNextCursor, value.historySnapshotRevision,
            value.historyTotalCount, value.errorMessage
        );
    }
}
