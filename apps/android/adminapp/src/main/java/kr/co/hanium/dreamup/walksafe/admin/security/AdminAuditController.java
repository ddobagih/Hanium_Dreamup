package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Generation-bound, cursor-stable audit list state. */
public final class AdminAuditController {
    public enum Phase { IDLE, LOADING, LOADING_MORE, CONTENT, EMPTY, ERROR }

    public interface Loader {
        AdminAuditModels.Page load(AdminAuditModels.Filters filters, String cursor) throws Exception;
    }

    public static final class State {
        private final Phase phase;
        private final AdminAuditModels.Filters filters;
        private final List<AdminAuditModels.Event> items;
        private final String nextCursor;
        private final String errorMessage;

        private State(
            Phase phase,
            AdminAuditModels.Filters filters,
            List<AdminAuditModels.Event> items,
            String nextCursor,
            String errorMessage
        ) {
            this.phase = phase;
            this.filters = filters;
            this.items = AdminJava8Collections.copyList(items);
            this.nextCursor = nextCursor;
            this.errorMessage = errorMessage;
        }

        public Phase phase() { return phase; }
        public AdminAuditModels.Filters filters() { return filters; }
        public List<AdminAuditModels.Event> items() { return items; }
        public String nextCursor() { return nextCursor; }
        public String errorMessage() { return errorMessage; }
        public boolean canLoadMore() { return nextCursor != null && phase != Phase.LOADING_MORE; }
    }

    public static final class Request {
        private final long generation;
        private final AdminAuditModels.Filters filters;
        private final String cursor;

        private Request(long generation, AdminAuditModels.Filters filters, String cursor) {
            this.generation = generation;
            this.filters = filters;
            this.cursor = cursor;
        }
    }

    private final Loader loader;
    private long generation;
    private Request retry;
    private State state = new State(
        Phase.IDLE,
        new AdminAuditModels.Filters(null, null),
        AdminJava8Collections.list(),
        null,
        null
    );

    public AdminAuditController(Loader loader) {
        if (loader == null) throw new IllegalArgumentException("audit loader is required");
        this.loader = loader;
    }

    public synchronized State snapshot() { return copy(state); }

    public synchronized Request begin(AdminAuditModels.Filters filters) {
        Request request = new Request(++generation, filters, null);
        retry = request;
        state = new State(Phase.LOADING, filters, AdminJava8Collections.list(), null, null);
        return request;
    }

    public synchronized Request beginNext() {
        if (state.nextCursor == null) throw new IllegalStateException("audit next page is unavailable");
        Request request = new Request(++generation, state.filters, state.nextCursor);
        retry = request;
        state = new State(Phase.LOADING_MORE, state.filters, state.items, state.nextCursor, null);
        return request;
    }

    public synchronized Request beginRetry() {
        if (retry == null || state.phase != Phase.ERROR) throw new IllegalStateException("audit retry is unavailable");
        Request request = new Request(++generation, retry.filters, retry.cursor);
        retry = request;
        state = new State(
            request.cursor == null ? Phase.LOADING : Phase.LOADING_MORE,
            request.filters,
            request.cursor == null ? AdminJava8Collections.list() : state.items,
            state.nextCursor,
            null
        );
        return request;
    }

    public boolean execute(Request request) {
        try {
            return apply(request, loader.load(request.filters, request.cursor));
        } catch (Exception error) {
            return fail(request);
        }
    }

    public synchronized void invalidate() { generation += 1; }

    public synchronized void clearSessionState() {
        generation += 1;
        retry = null;
        state = new State(
            Phase.IDLE,
            new AdminAuditModels.Filters(null, null),
            AdminJava8Collections.list(),
            null,
            null
        );
    }

    private synchronized boolean apply(Request request, AdminAuditModels.Page page) {
        if (request.generation != generation) return false;
        List<AdminAuditModels.Event> combined = new ArrayList<>();
        if (request.cursor != null) combined.addAll(state.items);
        Set<String> keys = new HashSet<>();
        for (AdminAuditModels.Event item : combined) keys.add(item.eventType() + ":" + item.eventId());
        for (AdminAuditModels.Event item : page.items()) {
            if (!keys.add(item.eventType() + ":" + item.eventId())) return fail(request);
            combined.add(item);
        }
        if (request.cursor != null && request.cursor.equals(page.nextCursor())) return fail(request);
        state = new State(
            combined.isEmpty() ? Phase.EMPTY : Phase.CONTENT,
            request.filters,
            combined,
            page.nextCursor(),
            null
        );
        retry = null;
        return true;
    }

    private synchronized boolean fail(Request request) {
        if (request.generation != generation) return false;
        state = new State(
            Phase.ERROR,
            state.filters,
            state.items,
            state.nextCursor,
            "감사 기록을 불러오지 못했습니다. 다시 시도해 주세요."
        );
        return true;
    }

    private static State copy(State value) {
        return new State(value.phase, value.filters, value.items, value.nextCursor, value.errorMessage);
    }
}
