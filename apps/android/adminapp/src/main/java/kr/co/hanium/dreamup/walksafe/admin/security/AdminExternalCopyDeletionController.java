package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Generation-bound list and one-shot record-only mutation state. */
public final class AdminExternalCopyDeletionController {
    public enum Phase {
        IDLE, LOADING, LOADING_MORE, MUTATING, CONTENT, EMPTY, CONFLICT, ERROR
    }

    public interface Loader {
        AdminExternalCopyDeletionModels.Page load(
            AdminExternalCopyDeletionModels.Filter filter,
            String cursor
        ) throws Exception;

        AdminExternalCopyDeletionModels.Item record(
            AdminExternalCopyDeletionModels.EventCommand command
        ) throws Exception;
    }

    public static final class State {
        private final Phase phase;
        private final AdminExternalCopyDeletionModels.Filter filter;
        private final List<AdminExternalCopyDeletionModels.Item> items;
        private final String nextCursor;
        private final String selectedCopyId;
        private final String message;
        private final boolean failedRecordAvailable;

        private State(
            Phase phase,
            AdminExternalCopyDeletionModels.Filter filter,
            List<AdminExternalCopyDeletionModels.Item> items,
            String nextCursor,
            String selectedCopyId,
            String message
        ) {
            this(phase, filter, items, nextCursor, selectedCopyId, message, false);
        }

        private State(
            Phase phase,
            AdminExternalCopyDeletionModels.Filter filter,
            List<AdminExternalCopyDeletionModels.Item> items,
            String nextCursor,
            String selectedCopyId,
            String message,
            boolean failedRecordAvailable
        ) {
            this.phase = phase;
            this.filter = filter;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.nextCursor = nextCursor;
            this.selectedCopyId = selectedCopyId;
            this.message = message;
            this.failedRecordAvailable = failedRecordAvailable;
        }

        public Phase phase() { return phase; }
        public AdminExternalCopyDeletionModels.Filter filter() { return filter; }
        public List<AdminExternalCopyDeletionModels.Item> items() { return items; }
        public String nextCursor() { return nextCursor; }
        public String selectedCopyId() { return selectedCopyId; }
        public String message() { return message; }
        public boolean canLoadMore() {
            return nextCursor != null && phase != Phase.LOADING_MORE && phase != Phase.MUTATING;
        }
        public boolean canRetryRecord() {
            return phase == Phase.ERROR && failedRecordAvailable;
        }
    }

    public static final class Request {
        private enum Kind { FIRST, NEXT, RECORD }

        private final long generation;
        private final Kind kind;
        private final AdminExternalCopyDeletionModels.Filter filter;
        private final String cursor;
        private final AdminExternalCopyDeletionModels.EventCommand command;
        private boolean claimed;

        private Request(
            long generation,
            Kind kind,
            AdminExternalCopyDeletionModels.Filter filter,
            String cursor,
            AdminExternalCopyDeletionModels.EventCommand command
        ) {
            this.generation = generation;
            this.kind = kind;
            this.filter = filter;
            this.cursor = cursor;
            this.command = command;
        }

        private synchronized boolean claim() {
            if (claimed) return false;
            claimed = true;
            return true;
        }
    }

    private final Loader loader;
    private long generation;
    private Request retry;
    private AdminExternalCopyDeletionModels.EventCommand failedRecord;
    private State state = new State(
        Phase.IDLE,
        new AdminExternalCopyDeletionModels.Filter(null),
        Collections.emptyList(),
        null,
        null,
        null
    );

    public AdminExternalCopyDeletionController(Loader loader) {
        if (loader == null) throw new IllegalArgumentException("external-copy loader is required");
        this.loader = loader;
    }

    public synchronized State snapshot() { return copy(state); }

    public synchronized Request beginFirst(AdminExternalCopyDeletionModels.Filter filter) {
        if (filter == null) throw new IllegalArgumentException("external-copy filter is required");
        requireNoMutation();
        Request request = new Request(++generation, Request.Kind.FIRST, filter, null, null);
        retry = request;
        failedRecord = null;
        state = new State(Phase.LOADING, filter, Collections.emptyList(), null, null, null);
        return request;
    }

    public synchronized Request beginNext() {
        requireNoMutation();
        if (state.nextCursor == null || isLoading(state.phase)) {
            throw new IllegalStateException("external-copy next page is unavailable");
        }
        Request request = new Request(
            ++generation, Request.Kind.NEXT, state.filter, state.nextCursor, null
        );
        retry = request;
        failedRecord = null;
        state = new State(
            Phase.LOADING_MORE,
            state.filter,
            state.items,
            state.nextCursor,
            state.selectedCopyId,
            null
        );
        return request;
    }

    public synchronized Request beginRetry() {
        requireNoMutation();
        if (retry == null || state.phase != Phase.ERROR || retry.kind == Request.Kind.RECORD) {
            throw new IllegalStateException("external-copy read retry is unavailable");
        }
        Request previous = retry;
        Request request = new Request(
            ++generation, previous.kind, previous.filter, previous.cursor, null
        );
        retry = request;
        state = new State(
            request.cursor == null ? Phase.LOADING : Phase.LOADING_MORE,
            request.filter,
            request.cursor == null ? Collections.emptyList() : state.items,
            state.nextCursor,
            state.selectedCopyId,
            null
        );
        return request;
    }

    public synchronized Request beginRecord(
        AdminExternalCopyDeletionModels.Item selected,
        String nextState,
        String observedAt,
        String institutionReference,
        String evidenceSha256
    ) {
        requireNoMutation();
        if (selected == null || !containsExact(state.items, selected)) {
            throw new IllegalStateException("current external-copy item is unavailable");
        }
        if (!selected.allowedNextStates().contains(nextState)) {
            throw new IllegalArgumentException("external-copy transition is invalid");
        }
        AdminExternalCopyDeletionModels.EventCommand command =
            new AdminExternalCopyDeletionModels.EventCommand(
                selected.requestId(),
                selected.copyId(),
                nextState,
                selected.revision(),
                UUID.randomUUID().toString(),
                observedAt,
                institutionReference,
                evidenceSha256
            );
        Request request = new Request(
            ++generation, Request.Kind.RECORD, state.filter, null, command
        );
        retry = null;
        failedRecord = null;
        state = new State(
            Phase.MUTATING,
            state.filter,
            state.items,
            state.nextCursor,
            selected.copyId(),
            "앱 밖에서 이미 수행한 사실을 기록하고 있습니다."
        );
        return request;
    }

    public synchronized Request beginRecordRetry() {
        requireNoMutation();
        if (state.phase != Phase.ERROR || failedRecord == null) {
            throw new IllegalStateException("external-copy record retry is unavailable");
        }
        Request request = new Request(
            ++generation, Request.Kind.RECORD, state.filter, null, failedRecord
        );
        state = new State(
            Phase.MUTATING,
            state.filter,
            state.items,
            state.nextCursor,
            failedRecord.copyId(),
            "같은 사실·같은 멱등키로 기록 결과를 다시 확인하고 있습니다."
        );
        return request;
    }

    public boolean execute(Request request) {
        if (request == null || !request.claim() || !isCurrent(request)) return false;
        try {
            return switch (request.kind) {
                case FIRST, NEXT -> applyPage(
                    request,
                    loader.load(request.filter, request.cursor)
                );
                case RECORD -> applyRecorded(request, loader.record(request.command));
            };
        } catch (AdminReportRepository.ExternalCopyConflictException conflict) {
            return applyConflict(request, conflict.latest().latest());
        } catch (Exception error) {
            return applyFailure(request);
        }
    }

    public synchronized void select(String copyId) {
        String safeId = AdminExternalCopyDeletionModels.canonicalUuid(copyId, "copy_id");
        if (find(state.items, safeId) == null) {
            throw new IllegalArgumentException("external-copy selection is unavailable");
        }
        state = new State(
            state.phase, state.filter, state.items, state.nextCursor, safeId, state.message
        );
    }

    public synchronized void invalidate() {
        generation += 1;
        if (isLoading(state.phase) || state.phase == Phase.MUTATING) {
            state = new State(
                Phase.ERROR,
                state.filter,
                state.items,
                state.nextCursor,
                state.selectedCopyId,
                "화면이 중단되어 결과를 적용하지 않았습니다. 목록을 다시 조회해 주세요."
            );
            retry = null;
            failedRecord = null;
        }
    }

    public synchronized void clearSessionState() {
        generation += 1;
        retry = null;
        failedRecord = null;
        state = new State(
            Phase.IDLE,
            new AdminExternalCopyDeletionModels.Filter(null),
            Collections.emptyList(),
            null,
            null,
            null
        );
    }

    private synchronized boolean applyPage(
        Request request,
        AdminExternalCopyDeletionModels.Page page
    ) {
        if (!isCurrentLocked(request)) return false;
        if (page == null) return applyFailureLocked(request);
        List<AdminExternalCopyDeletionModels.Item> combined = new ArrayList<>();
        if (request.kind == Request.Kind.NEXT) combined.addAll(state.items);
        Set<String> ids = new HashSet<>();
        for (AdminExternalCopyDeletionModels.Item item : combined) ids.add(item.copyId());
        for (AdminExternalCopyDeletionModels.Item item : page.items()) {
            if (!ids.add(item.copyId())) return applyFailureLocked(request);
            combined.add(item);
        }
        if (request.cursor != null && request.cursor.equals(page.nextCursor())) {
            return applyFailureLocked(request);
        }
        state = new State(
            combined.isEmpty() ? Phase.EMPTY : Phase.CONTENT,
            request.filter,
            combined,
            page.nextCursor(),
            find(combined, state.selectedCopyId) == null ? null : state.selectedCopyId,
            null
        );
        retry = null;
        return true;
    }

    private synchronized boolean applyRecorded(
        Request request,
        AdminExternalCopyDeletionModels.Item recorded
    ) {
        if (!isCurrentLocked(request) || recorded == null
            || !request.command.requestId().equals(recorded.requestId())
            || !request.command.copyId().equals(recorded.copyId())) {
            return isCurrentLocked(request) ? applyFailureLocked(request) : false;
        }
        state = new State(
            Phase.CONTENT,
            state.filter,
            replace(state.items, recorded),
            state.nextCursor,
            recorded.copyId(),
            "외부 전송 없이 앱 밖에서 수행한 사실만 기록했습니다."
        );
        failedRecord = null;
        return true;
    }

    private synchronized boolean applyConflict(
        Request request,
        AdminExternalCopyDeletionModels.Item latest
    ) {
        if (!isCurrentLocked(request) || request.kind != Request.Kind.RECORD || latest == null
            || !request.command.requestId().equals(latest.requestId())
            || !request.command.copyId().equals(latest.copyId())) {
            return false;
        }
        state = new State(
            Phase.CONFLICT,
            state.filter,
            replace(state.items, latest),
            state.nextCursor,
            latest.copyId(),
            "다른 관리자가 먼저 기록했습니다. 최신 상태를 반영했으며 자동 재전송하지 않았습니다."
        );
        failedRecord = null;
        return true;
    }

    private synchronized boolean applyFailure(Request request) {
        return applyFailureLocked(request);
    }

    private boolean applyFailureLocked(Request request) {
        if (!isCurrentLocked(request)) return false;
        boolean mutation = request.kind == Request.Kind.RECORD;
        state = new State(
            Phase.ERROR,
            state.filter,
            state.items,
            state.nextCursor,
            state.selectedCopyId,
            mutation
                ? "사실 기록 결과를 확인하지 못했습니다. 자동 재전송하지 않습니다. 같은 사실·멱등키로 명시적으로 다시 확인하거나 목록을 새로 조회해 주세요."
                : "외부기관 보관본 목록을 불러오지 못했습니다. 다시 시도해 주세요.",
            mutation
        );
        if (mutation) retry = null;
        if (mutation) failedRecord = request.command;
        return true;
    }

    private synchronized boolean isCurrent(Request request) {
        return isCurrentLocked(request);
    }

    private boolean isCurrentLocked(Request request) {
        return request.generation == generation;
    }

    private void requireNoMutation() {
        if (state.phase == Phase.MUTATING) {
            throw new IllegalStateException("external-copy record is already in flight");
        }
    }

    private static boolean isLoading(Phase phase) {
        return phase == Phase.LOADING || phase == Phase.LOADING_MORE;
    }

    private static boolean containsExact(
        List<AdminExternalCopyDeletionModels.Item> items,
        AdminExternalCopyDeletionModels.Item expected
    ) {
        AdminExternalCopyDeletionModels.Item current = find(items, expected.copyId());
        return current != null
            && current.requestId().equals(expected.requestId())
            && current.state().equals(expected.state())
            && current.revision() == expected.revision();
    }

    private static AdminExternalCopyDeletionModels.Item find(
        List<AdminExternalCopyDeletionModels.Item> items,
        String copyId
    ) {
        if (copyId == null) return null;
        for (AdminExternalCopyDeletionModels.Item item : items) {
            if (copyId.equals(item.copyId())) return item;
        }
        return null;
    }

    private static List<AdminExternalCopyDeletionModels.Item> replace(
        List<AdminExternalCopyDeletionModels.Item> items,
        AdminExternalCopyDeletionModels.Item replacement
    ) {
        List<AdminExternalCopyDeletionModels.Item> updated = new ArrayList<>(items.size());
        boolean replaced = false;
        for (AdminExternalCopyDeletionModels.Item item : items) {
            if (item.copyId().equals(replacement.copyId())) {
                updated.add(replacement);
                replaced = true;
            } else {
                updated.add(item);
            }
        }
        if (!replaced) updated.add(replacement);
        return updated;
    }

    private static State copy(State value) {
        return new State(
            value.phase,
            value.filter,
            value.items,
            value.nextCursor,
            value.selectedCopyId,
            value.message,
            value.failedRecordAvailable
        );
    }
}
