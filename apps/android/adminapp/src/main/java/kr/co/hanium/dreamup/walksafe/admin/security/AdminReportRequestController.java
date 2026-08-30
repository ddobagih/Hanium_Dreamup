package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Generation-bound list, detail, and one-shot CAS state for user report requests. */
public final class AdminReportRequestController {
    public enum Phase {
        IDLE,
        LOADING_LIST,
        LOADING_MORE,
        LOADING_DETAIL,
        MUTATING,
        CONTENT,
        EMPTY,
        UPDATED_DETAIL_STALE,
        CONFLICT,
        ERROR
    }

    public interface Loader {
        AdminReportRequestModels.Page loadPage(
            AdminReportRequestModels.Filters filters,
            String cursor
        ) throws Exception;

        AdminReportRequestModels.Detail loadDetail(String requestId) throws Exception;

        AdminReportRequestModels.StatusSnapshot updateStatus(
            String requestId,
            String nextStatus,
            int expectedVersion,
            String publicResponse,
            String internalNote,
            char[] password,
            char[] totp
        ) throws Exception;
    }

    public static final class State {
        private final Phase phase;
        private final AdminReportRequestModels.Filters filters;
        private final List<AdminReportRequestModels.Summary> items;
        private final String nextCursor;
        private final String selectedRequestId;
        private final AdminReportRequestModels.Detail detail;
        private final AdminReportRequestModels.StatusSnapshot statusSnapshot;
        private final String message;

        private State(
            Phase phase,
            AdminReportRequestModels.Filters filters,
            List<AdminReportRequestModels.Summary> items,
            String nextCursor,
            String selectedRequestId,
            AdminReportRequestModels.Detail detail,
            String message
        ) {
            this(phase, filters, items, nextCursor, selectedRequestId, detail, null, message);
        }

        private State(
            Phase phase,
            AdminReportRequestModels.Filters filters,
            List<AdminReportRequestModels.Summary> items,
            String nextCursor,
            String selectedRequestId,
            AdminReportRequestModels.Detail detail,
            AdminReportRequestModels.StatusSnapshot statusSnapshot,
            String message
        ) {
            this.phase = phase;
            this.filters = filters;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.nextCursor = nextCursor;
            this.selectedRequestId = selectedRequestId;
            this.detail = detail;
            this.statusSnapshot = statusSnapshot;
            this.message = message;
        }

        public Phase phase() { return phase; }
        public AdminReportRequestModels.Filters filters() { return filters; }
        public List<AdminReportRequestModels.Summary> items() { return items; }
        public String nextCursor() { return nextCursor; }
        public String selectedRequestId() { return selectedRequestId; }
        public AdminReportRequestModels.Detail detail() { return detail; }
        public AdminReportRequestModels.StatusSnapshot statusSnapshot() { return statusSnapshot; }
        public String message() { return message; }
        public boolean canLoadMore() {
            return nextCursor != null && phase != Phase.LOADING_MORE && phase != Phase.MUTATING;
        }
    }

    public static final class Request {
        private enum Kind { FIRST_PAGE, NEXT_PAGE, DETAIL, STATUS }

        private final long generation;
        private final Kind kind;
        private final AdminReportRequestModels.Filters filters;
        private final String cursor;
        private final String requestId;
        private final String nextStatus;
        private final int expectedVersion;
        private final String publicResponse;
        private final String internalNote;
        private final char[] password;
        private final char[] totp;
        private boolean claimed;

        private Request(
            long generation,
            Kind kind,
            AdminReportRequestModels.Filters filters,
            String cursor,
            String requestId,
            String nextStatus,
            int expectedVersion,
            String publicResponse,
            String internalNote,
            char[] password,
            char[] totp
        ) {
            this.generation = generation;
            this.kind = kind;
            this.filters = filters;
            this.cursor = cursor;
            this.requestId = requestId;
            this.nextStatus = nextStatus;
            this.expectedVersion = expectedVersion;
            this.publicResponse = publicResponse;
            this.internalNote = internalNote;
            this.password = password == null ? new char[0] : password.clone();
            this.totp = totp == null ? new char[0] : totp.clone();
        }

        private synchronized boolean claim() {
            if (claimed) return false;
            claimed = true;
            return true;
        }

        private synchronized void destroy() {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
        }

    }

    private final Loader loader;
    private long generation;
    private Request retryRequest;
    private Request activeMutation;
    private State state;

    public AdminReportRequestController(Loader loader) {
        if (loader == null) throw new IllegalArgumentException("report request loader is required");
        this.loader = loader;
        AdminReportRequestModels.Filters empty =
            new AdminReportRequestModels.Filters(null, null, null);
        state = new State(Phase.IDLE, empty, Collections.emptyList(), null, null, null, null);
    }

    public synchronized State snapshot() {
        return copy(state);
    }

    public synchronized Request beginFirstPage(AdminReportRequestModels.Filters filters) {
        if (filters == null) throw new IllegalArgumentException("report request filters are required");
        requireNoMutationInFlight();
        Request request = request(
            ++generation, Request.Kind.FIRST_PAGE, filters, null, null,
            null, 0, null, null, null, null
        );
        retryRequest = request;
        state = new State(
            Phase.LOADING_LIST, filters, Collections.emptyList(), null, null, null, null
        );
        return request;
    }

    public synchronized Request beginNextPage() {
        requireNoMutationInFlight();
        if (state.nextCursor == null || isReadLoading(state.phase)) {
            throw new IllegalStateException("next report request page is unavailable");
        }
        Request request = request(
            ++generation, Request.Kind.NEXT_PAGE, state.filters, state.nextCursor,
            state.selectedRequestId, null, 0, null, null, null, null
        );
        retryRequest = request;
        state = new State(
            Phase.LOADING_MORE,
            state.filters,
            state.items,
            state.nextCursor,
            state.selectedRequestId,
            state.detail,
            null
        );
        return request;
    }

    public synchronized Request beginDetail(String requestId) {
        requireNoMutationInFlight();
        String safeId = AdminReportRequestModels.canonicalUuid(requestId, "request_id");
        Request request = request(
            ++generation, Request.Kind.DETAIL, state.filters, null, safeId,
            null, 0, null, null, null, null
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
        requireNoMutationInFlight();
        if (retryRequest == null
            || (state.phase != Phase.ERROR && state.phase != Phase.UPDATED_DETAIL_STALE)
            || retryRequest.kind == Request.Kind.STATUS) {
            throw new IllegalStateException("failed report request read is unavailable");
        }
        Request previous = retryRequest;
        Request request = request(
            ++generation,
            previous.kind,
            previous.filters,
            previous.cursor,
            previous.requestId,
            null,
            0,
            null,
            null,
            null,
            null
        );
        retryRequest = request;
        Phase loading = switch (request.kind) {
            case FIRST_PAGE -> Phase.LOADING_LIST;
            case NEXT_PAGE -> Phase.LOADING_MORE;
            case DETAIL -> Phase.LOADING_DETAIL;
            case STATUS -> throw new IllegalStateException("status updates are never replayed");
        };
        state = new State(
            loading,
            request.filters,
            request.kind == Request.Kind.FIRST_PAGE ? Collections.emptyList() : state.items,
            state.nextCursor,
            request.requestId == null ? state.selectedRequestId : request.requestId,
            request.kind == Request.Kind.DETAIL ? null : state.detail,
            null
        );
        return request;
    }

    public synchronized Request beginStatusUpdate(
        String requestId,
        String nextStatus,
        int expectedVersion,
        String publicResponse,
        String internalNote,
        char[] password,
        char[] totp
    ) {
        requireNoMutationInFlight();
        requireCredentials(password, totp);
        String safeId = AdminReportRequestModels.canonicalUuid(requestId, "request_id");
        if (state.detail == null || !safeId.equals(state.detail.summary().requestId())) {
            throw new IllegalStateException("selected report request detail is unavailable");
        }
        if (expectedVersion != state.detail.summary().statusVersion()
            || !state.detail.summary().allowedNextStatuses().contains(nextStatus)) {
            throw new IllegalArgumentException("report request transition or version is invalid");
        }
        Request request = request(
            ++generation,
            Request.Kind.STATUS,
            state.filters,
            null,
            safeId,
            nextStatus,
            expectedVersion,
            AdminReportRequestModels.optionalResponse(publicResponse, "public_response"),
            AdminReportRequestModels.optionalResponse(internalNote, "internal_note"),
            password,
            totp
        );
        activeMutation = request;
        retryRequest = null;
        state = new State(
            Phase.MUTATING,
            state.filters,
            state.items,
            state.nextCursor,
            safeId,
            state.detail,
            "상태 변경을 재인증하고 있습니다."
        );
        return request;
    }

    /** Returns false when the request was already used or superseded. */
    public boolean execute(Request request) {
        if (request == null || !request.claim()) return false;
        try {
            if (!isCurrent(request)) return false;
            return switch (request.kind) {
                case FIRST_PAGE, NEXT_PAGE -> applyPage(
                    request,
                    loader.loadPage(request.filters, request.cursor)
                );
                case DETAIL -> applyDetail(request, loader.loadDetail(request.requestId));
                case STATUS -> executeStatus(request);
            };
        } catch (AdminReportRepository.ReportRequestConflictException conflict) {
            return refreshAfterConflict(request);
        } catch (Exception error) {
            return applyFailure(
                request,
                error instanceof AdminReportRepository.ReportRequestNotFoundException
            );
        } finally {
            request.destroy();
            clearActiveMutation(request);
        }
    }

    public synchronized void invalidate() {
        generation += 1;
        boolean mutationWasInFlight = state.phase == Phase.MUTATING;
        if (activeMutation != null) activeMutation.destroy();
        activeMutation = null;
        if (isReadLoading(state.phase) || state.phase == Phase.MUTATING) {
            if (mutationWasInFlight && state.selectedRequestId != null) {
                retryRequest = detailRetry(state.selectedRequestId);
            }
            state = new State(
                Phase.ERROR,
                state.filters,
                state.items,
                state.nextCursor,
                state.selectedRequestId,
                null,
                "화면이 중단되어 요청을 취소했습니다. 다시 조회해 주세요."
            );
        }
    }

    public synchronized void clearSessionState() {
        generation += 1;
        if (activeMutation != null) activeMutation.destroy();
        if (retryRequest != null && retryRequest != activeMutation) retryRequest.destroy();
        activeMutation = null;
        retryRequest = null;
        AdminReportRequestModels.Filters empty =
            new AdminReportRequestModels.Filters(null, null, null);
        state = new State(
            Phase.IDLE,
            empty,
            Collections.emptyList(),
            null,
            null,
            null,
            null
        );
    }

    private boolean executeStatus(Request request) throws Exception {
        char[] password = request.password.clone();
        char[] totp = request.totp.clone();
        AdminReportRequestModels.StatusSnapshot updated;
        try {
            updated = loader.updateStatus(
                request.requestId,
                request.nextStatus,
                request.expectedVersion,
                request.publicResponse,
                request.internalNote,
                password,
                totp
            );
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
        }
        try {
            AdminReportRequestModels.Detail refreshed = loader.loadDetail(request.requestId);
            if (!updated.requestId().equals(refreshed.summary().requestId())
                || !updated.reportId().equals(refreshed.summary().reportId())
                || updated.statusVersion() != refreshed.summary().statusVersion()
                || !updated.status().equals(refreshed.summary().status())) {
                throw new IllegalStateException("updated report request detail is mismatched");
            }
            return applyMutation(
                request,
                Phase.CONTENT,
                "요청 상태를 변경하고 최신 상세를 확인했습니다.",
                refreshed,
                updated
            );
        } catch (Exception refreshFailure) {
            return applyPatchSuccessWithoutDetail(request, updated);
        }
    }

    private boolean refreshAfterConflict(Request request) {
        if (request.kind != Request.Kind.STATUS || !isCurrent(request)) return false;
        try {
            AdminReportRequestModels.Detail refreshed = loader.loadDetail(request.requestId);
            return applyMutation(
                request,
                Phase.CONFLICT,
                "다른 관리자가 먼저 처리했습니다. 최신 상태를 한 번 조회했으며 자동 재제출하지 않았습니다.",
                refreshed,
                null
            );
        } catch (Exception refreshFailure) {
            return applyFailure(request, false);
        }
    }

    private synchronized boolean applyPage(
        Request request,
        AdminReportRequestModels.Page page
    ) {
        if (!isCurrentLocked(request)) return false;
        if (page == null) return applyFailureLocked(request, false);
        List<AdminReportRequestModels.Summary> combined = new ArrayList<>();
        if (request.kind == Request.Kind.NEXT_PAGE) combined.addAll(state.items);
        Set<String> ids = new HashSet<>();
        for (AdminReportRequestModels.Summary item : combined) ids.add(item.requestId());
        for (AdminReportRequestModels.Summary item : page.items()) {
            if (!ids.add(item.requestId())) return applyFailureLocked(request, false);
            combined.add(item);
        }
        if (request.cursor != null && request.cursor.equals(page.nextCursor())) {
            return applyFailureLocked(request, false);
        }
        state = new State(
            combined.isEmpty() ? Phase.EMPTY : Phase.CONTENT,
            request.filters,
            combined,
            page.nextCursor(),
            state.selectedRequestId,
            state.detail,
            null
        );
        retryRequest = null;
        return true;
    }

    private synchronized boolean applyDetail(
        Request request,
        AdminReportRequestModels.Detail detail
    ) {
        if (!isCurrentLocked(request)) return false;
        if (detail == null || !request.requestId.equals(detail.summary().requestId())) {
            return applyFailureLocked(request, false);
        }
        state = new State(
            Phase.CONTENT,
            state.filters,
            state.items,
            state.nextCursor,
            request.requestId,
            detail,
            null
        );
        retryRequest = null;
        return true;
    }

    private synchronized boolean applyMutation(
        Request request,
        Phase phase,
        String message,
        AdminReportRequestModels.Detail detail,
        AdminReportRequestModels.StatusSnapshot statusSnapshot
    ) {
        if (!isCurrentLocked(request)) return false;
        if (detail == null || !request.requestId.equals(detail.summary().requestId())) {
            return applyFailureLocked(request, false);
        }
        state = new State(
            phase, state.filters, replaceSummary(state.items, detail.summary()), state.nextCursor, request.requestId,
            detail, statusSnapshot, message
        );
        return true;
    }

    private synchronized boolean applyPatchSuccessWithoutDetail(
        Request request,
        AdminReportRequestModels.StatusSnapshot updated
    ) {
        if (!isCurrentLocked(request)) return false;
        retryRequest = detailRetry(request.requestId);
        state = new State(
            Phase.UPDATED_DETAIL_STALE,
            state.filters,
            Collections.emptyList(),
            null,
            request.requestId,
            null,
            updated,
            "상태 변경은 성공했습니다. 최신 상세 조회에 실패했으며 PATCH를 자동 재전송하지 않습니다."
        );
        return true;
    }

    private synchronized boolean applyFailure(Request request, boolean notFound) {
        return applyFailureLocked(request, notFound);
    }

    private boolean applyFailureLocked(Request request, boolean notFound) {
        if (!isCurrentLocked(request)) return false;
        state = new State(
            Phase.ERROR,
            state.filters,
            state.items,
            state.nextCursor,
            request.requestId == null ? state.selectedRequestId : request.requestId,
            null,
            notFound
                ? "선택한 사용자 요청을 찾을 수 없습니다."
                : request.kind == Request.Kind.STATUS
                    ? "요청 상태를 변경하지 못했습니다. 최신 상세를 다시 조회해 주세요."
                    : "사용자 요청을 불러오지 못했습니다. 다시 시도해 주세요."
        );
        if (request.kind == Request.Kind.STATUS) retryRequest = detailRetry(request.requestId);
        return true;
    }

    private synchronized boolean isCurrent(Request request) {
        return isCurrentLocked(request);
    }

    private boolean isCurrentLocked(Request request) {
        return request.generation == generation;
    }

    private synchronized void clearActiveMutation(Request request) {
        if (activeMutation == request) activeMutation = null;
    }

    private void requireNoMutationInFlight() {
        if (state.phase == Phase.MUTATING) {
            throw new IllegalStateException("report request mutation is already in flight");
        }
    }

    private static boolean isReadLoading(Phase phase) {
        return phase == Phase.LOADING_LIST
            || phase == Phase.LOADING_MORE
            || phase == Phase.LOADING_DETAIL;
    }

    private static void requireCredentials(char[] password, char[] totp) {
        if (password == null || password.length < 12 || password.length > 256
            || totp == null || totp.length != 6) {
            throw new IllegalArgumentException("password and six-digit TOTP are required");
        }
        for (char value : totp) if (value < '0' || value > '9') {
            throw new IllegalArgumentException("six-digit TOTP is required");
        }
    }

    private static Request request(
        long generation,
        Request.Kind kind,
        AdminReportRequestModels.Filters filters,
        String cursor,
        String requestId,
        String nextStatus,
        int expectedVersion,
        String publicResponse,
        String internalNote,
        char[] password,
        char[] totp
    ) {
        return new Request(
            generation,
            kind,
            filters,
            cursor,
            requestId,
            nextStatus,
            expectedVersion,
            publicResponse,
            internalNote,
            password,
            totp
        );
    }

    private Request detailRetry(String requestId) {
        return request(
            generation,
            Request.Kind.DETAIL,
            state.filters,
            null,
            requestId,
            null,
            0,
            null,
            null,
            null,
            null
        );
    }

    private static State copy(State value) {
        return new State(
            value.phase,
            value.filters,
            value.items,
            value.nextCursor,
            value.selectedRequestId,
            value.detail,
            value.statusSnapshot,
            value.message
        );
    }

    private static List<AdminReportRequestModels.Summary> replaceSummary(
        List<AdminReportRequestModels.Summary> items,
        AdminReportRequestModels.Summary replacement
    ) {
        List<AdminReportRequestModels.Summary> updated = new ArrayList<>(items.size());
        for (AdminReportRequestModels.Summary item : items) {
            if (item.requestId().equals(replacement.requestId())) {
                updated.add(replacement);
            } else {
                updated.add(item);
            }
        }
        return updated;
    }
}
