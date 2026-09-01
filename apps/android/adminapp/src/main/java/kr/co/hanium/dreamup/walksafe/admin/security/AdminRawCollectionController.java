package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.Arrays;
import java.util.List;

/** Generation-bound state for the bounded, non-paginated raw quarantine list. */
public final class AdminRawCollectionController {
    public enum Phase { IDLE, LOADING, CONTENT, EMPTY, ERROR }

    public interface Loader {
        AdminRawCollectionModels.Page load(char[] password, char[] totp) throws Exception;
    }

    public static final class State {
        private final Phase phase;
        private final List<AdminRawCollectionModels.Summary> items;
        private final String selectedCollectionId;
        private final String errorMessage;

        private State(
            Phase phase,
            List<AdminRawCollectionModels.Summary> items,
            String selectedCollectionId,
            String errorMessage
        ) {
            this.phase = phase;
            this.items = AdminJava8Collections.copyList(items);
            this.selectedCollectionId = selectedCollectionId;
            this.errorMessage = errorMessage;
        }

        public Phase phase() { return phase; }
        public List<AdminRawCollectionModels.Summary> items() { return items; }
        public String selectedCollectionId() { return selectedCollectionId; }
        public String errorMessage() { return errorMessage; }

        public AdminRawCollectionModels.Summary selected() {
            if (selectedCollectionId == null) return null;
            for (AdminRawCollectionModels.Summary item : items) {
                if (selectedCollectionId.equals(item.collectionId())) return item;
            }
            return null;
        }
    }

    public static final class Request {
        private final long generation;
        private final char[] password;
        private final char[] totp;
        private boolean consumed;

        private Request(long generation, char[] password, char[] totp) {
            this.generation = generation;
            this.password = password == null ? null : password.clone();
            this.totp = totp == null ? null : totp.clone();
        }

        private synchronized void claim() {
            if (consumed) throw new IllegalStateException("raw collection request was already used");
            consumed = true;
        }

        private void clear() {
            if (password != null) Arrays.fill(password, '\0');
            if (totp != null) Arrays.fill(totp, '\0');
        }
    }

    private final Loader loader;
    private long generation;
    private boolean retryAvailable;
    private State state = empty(Phase.IDLE, null, null);

    public AdminRawCollectionController(Loader loader) {
        if (loader == null) throw new IllegalArgumentException("raw collection loader is required");
        this.loader = loader;
    }

    public synchronized State snapshot() {
        return new State(
            state.phase, state.items, state.selectedCollectionId, state.errorMessage
        );
    }

    public synchronized Request beginLoad(char[] password, char[] totp) {
        try {
            Request request = new Request(++generation, password, totp);
            retryAvailable = true;
            state = new State(
                Phase.LOADING, state.items, state.selectedCollectionId, null
            );
            return request;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totp != null) Arrays.fill(totp, '\0');
        }
    }

    public synchronized Request beginRetry(char[] password, char[] totp) {
        try {
            if (state.phase != Phase.ERROR || !retryAvailable) {
                throw new IllegalStateException("failed raw collection request is unavailable");
            }
            return beginLoad(password, totp);
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (totp != null) Arrays.fill(totp, '\0');
        }
    }

    public synchronized void select(String collectionId) {
        String safeId = AdminRawCollectionModels.canonicalUuid(collectionId, "collection_id");
        boolean found = false;
        for (AdminRawCollectionModels.Summary item : state.items) {
            if (safeId.equals(item.collectionId())) {
                found = true;
                break;
            }
        }
        if (!found) throw new IllegalArgumentException("raw collection is not in the current list");
        state = new State(state.phase, state.items, safeId, state.errorMessage);
    }

    public synchronized void restoreSelection(String collectionId) {
        if (collectionId == null) return;
        String safeId = AdminRawCollectionModels.canonicalUuid(collectionId, "collection_id");
        state = new State(state.phase, state.items, safeId, state.errorMessage);
    }

    public boolean execute(Request request) {
        if (request == null) throw new IllegalArgumentException("raw collection request is required");
        request.claim();
        try {
            return apply(request, loader.load(request.password, request.totp));
        } catch (Exception error) {
            return fail(request);
        } finally {
            request.clear();
        }
    }

    public synchronized void requireRefresh(String message) {
        generation += 1;
        retryAvailable = true;
        state = new State(
            Phase.ERROR,
            state.items,
            state.selectedCollectionId,
            message == null || message.trim().isEmpty()
                ? "최신 검역 목록을 새 재인증으로 다시 조회해 주세요."
                : message
        );
    }

    public synchronized void invalidate() {
        generation += 1;
        if (state.phase == Phase.LOADING) {
            retryAvailable = true;
            state = new State(
                Phase.ERROR,
                state.items,
                state.selectedCollectionId,
                "화면이 중단되어 검역 목록 조회를 취소했습니다. 다시 시도해 주세요."
            );
        }
    }

    public synchronized void clearSessionState() {
        generation += 1;
        retryAvailable = false;
        state = empty(Phase.IDLE, null, null);
    }

    private synchronized boolean apply(
        Request request,
        AdminRawCollectionModels.Page page
    ) {
        if (request.generation != generation) return false;
        if (page == null) return fail(request);
        String selection = null;
        for (AdminRawCollectionModels.Summary item : page.items()) {
            if (item.collectionId().equals(state.selectedCollectionId)) {
                selection = item.collectionId();
                break;
            }
        }
        retryAvailable = false;
        state = new State(
            page.items().isEmpty() ? Phase.EMPTY : Phase.CONTENT,
            page.items(),
            selection,
            null
        );
        return true;
    }

    private synchronized boolean fail(Request request) {
        if (request.generation != generation) return false;
        retryAvailable = true;
        state = new State(
            Phase.ERROR,
            state.items,
            state.selectedCollectionId,
            "검역 목록을 불러오지 못했습니다. 자동 재시도하지 않습니다."
        );
        return true;
    }

    private static State empty(Phase phase, String selectedId, String error) {
        return new State(phase, AdminJava8Collections.list(), selectedId, error);
    }
}
