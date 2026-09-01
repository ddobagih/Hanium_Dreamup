package kr.co.hanium.dreamup.walksafe.admin.security;

import java.util.Arrays;

/** One-shot high-risk status/package state. Credentials never enter snapshots. */
public final class AdminReportWorkflowController {
    public enum Phase { IDLE, LOADING, SUCCEEDED, CONFLICT, ERROR, PACKAGE_READY, SAVED, CANCELLED }

    public interface Loader {
        AdminReportModels.StatusSnapshot updateStatus(
            String reportId,
            String nextStatus,
            int expectedVersion,
            char[] password,
            char[] totp
        ) throws Exception;
        AdminDeliveryPackage createPackage(
            AdminDeliveryPackage.Eligibility eligibility,
            char[] password,
            char[] totp
        ) throws Exception;
        AdminReportModels.Detail refreshDetail(String reportId) throws Exception;
    }

    public static final class State {
        private final Phase phase;
        private final String reportId;
        private final String message;
        private final AdminReportModels.Detail refreshedDetail;

        private State(
            Phase phase,
            String reportId,
            String message,
            AdminReportModels.Detail refreshedDetail
        ) {
            this.phase = phase;
            this.reportId = reportId;
            this.message = message;
            this.refreshedDetail = refreshedDetail;
        }

        public Phase phase() { return phase; }
        public String reportId() { return reportId; }
        public String message() { return message; }
        public AdminReportModels.Detail refreshedDetail() { return refreshedDetail; }
    }

    public static final class Request {
        private enum Kind { STATUS, PACKAGE }
        private final long generation;
        private final Kind kind;
        private final String reportId;
        private final String nextStatus;
        private final int expectedVersion;
        private final AdminDeliveryPackage.Eligibility packageEligibility;
        private final char[] password;
        private final char[] totp;
        private boolean claimed;

        private Request(
            long generation,
            Kind kind,
            String reportId,
            String nextStatus,
            int expectedVersion,
            AdminDeliveryPackage.Eligibility packageEligibility,
            char[] password,
            char[] totp
        ) {
            this.generation = generation;
            this.kind = kind;
            this.reportId = reportId;
            this.nextStatus = nextStatus;
            this.expectedVersion = expectedVersion;
            this.packageEligibility = packageEligibility;
            this.password = password.clone();
            this.totp = totp.clone();
        }

        private synchronized void destroy() {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
        }

        private synchronized char[] passwordCopy() { return password.clone(); }
        private synchronized char[] totpCopy() { return totp.clone(); }
        private synchronized boolean claim() {
            if (claimed) return false;
            claimed = true;
            return true;
        }
    }

    private final Loader loader;
    private long generation;
    private State state = new State(Phase.IDLE, null, null, null);
    private AdminDeliveryPackage packageValue;
    private Request activeRequest;

    public AdminReportWorkflowController(Loader loader) {
        if (loader == null) throw new IllegalArgumentException("workflow loader is required");
        this.loader = loader;
    }

    public synchronized State snapshot() {
        return new State(state.phase, state.reportId, state.message, state.refreshedDetail);
    }

    public synchronized long generationToken() { return generation; }

    public synchronized Request beginStatus(
        String reportId,
        String nextStatus,
        int expectedVersion,
        char[] password,
        char[] totp
    ) {
        requireNoMutationInFlight();
        requireCredentials(password, totp);
        clearPackage();
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        Request request = new Request(
            ++generation, Request.Kind.STATUS, safeId, nextStatus, expectedVersion, null, password, totp
        );
        replaceActiveRequest(request);
        state = new State(Phase.LOADING, safeId, "상태 변경을 재인증하고 있습니다.", null);
        return request;
    }

    public synchronized Request beginPackage(
        AdminReportModels.Detail detail,
        char[] password,
        char[] totp
    ) {
        requireNoMutationInFlight();
        requireCredentials(password, totp);
        clearPackage();
        AdminDeliveryPackage.Eligibility eligibility =
            AdminDeliveryPackage.Eligibility.fromDetail(detail);
        String safeId = eligibility.reportId();
        Request request = new Request(
            ++generation, Request.Kind.PACKAGE, safeId, null, 0, eligibility, password, totp
        );
        replaceActiveRequest(request);
        state = new State(Phase.LOADING, safeId, "제출본 생성을 재인증하고 있습니다.", null);
        return request;
    }

    public boolean execute(Request request) {
        if (request == null || !request.claim()) return false;
        try {
            if (!isCurrent(request)) return false;
            if (request.kind == Request.Kind.PACKAGE) {
                char[] password = request.passwordCopy();
                char[] totp = request.totpCopy();
                try {
                    AdminDeliveryPackage created = loader.createPackage(
                        request.packageEligibility,
                        password,
                        totp
                    );
                    return applyPackage(request, created);
                } finally {
                    Arrays.fill(password, '\0');
                    Arrays.fill(totp, '\0');
                }
            }
            char[] password = request.passwordCopy();
            char[] totp = request.totpCopy();
            try {
                loader.updateStatus(
                    request.reportId,
                    request.nextStatus,
                    request.expectedVersion,
                    password,
                    totp
                );
            } finally {
                Arrays.fill(password, '\0');
                Arrays.fill(totp, '\0');
            }
            AdminReportModels.Detail detail = loader.refreshDetail(request.reportId);
            return applyStatus(request, Phase.SUCCEEDED, "상태를 변경하고 최신 상세를 확인했습니다.", detail);
        } catch (AdminReportRepository.StatusConflictException conflict) {
            try {
                AdminReportModels.Detail detail = loader.refreshDetail(request.reportId);
                return applyStatus(
                    request,
                    Phase.CONFLICT,
                    "다른 관리자가 먼저 상태를 변경했습니다. 최신 상세를 반영했으며 자동 재제출하지 않았습니다.",
                    detail
                );
            } catch (Exception refreshFailure) {
                return fail(request, "상태 충돌 후 최신 상세를 불러오지 못했습니다. 다시 조회해 주세요.");
            }
        } catch (Exception error) {
            return fail(request, "고위험 작업을 완료하지 못했습니다. 재인증 후 다시 시도해 주세요.");
        } finally {
            request.destroy();
            clearActiveRequest(request);
        }
    }

    public synchronized AdminDeliveryPackage consumePackage() {
        if (state.phase != Phase.PACKAGE_READY || packageValue == null) return null;
        AdminDeliveryPackage result = packageValue;
        packageValue = null;
        return result;
    }

    public synchronized boolean markSaved(long expectedGeneration, int revision) {
        if (expectedGeneration != generation || state.phase != Phase.PACKAGE_READY) return false;
        state = new State(
            Phase.SAVED,
            state.reportId,
            "제출본 revision " + revision + "을 사용자가 선택한 문서에 검증하여 저장했습니다. 기관 전달은 아직 기록되지 않았습니다.",
            null
        );
        return true;
    }

    public synchronized boolean markSaveFailed(long expectedGeneration, boolean cancelled) {
        if (expectedGeneration != generation || state.phase != Phase.PACKAGE_READY) return false;
        clearPackage();
        state = new State(
            cancelled ? Phase.CANCELLED : Phase.ERROR,
            state.reportId,
            cancelled
                ? "문서 저장을 취소했습니다. 제출본 바이트를 폐기했습니다."
                : "문서 저장 검증에 실패해 생성 문서 삭제를 시도하고 제출본 바이트를 폐기했습니다.",
            null
        );
        return true;
    }

    public synchronized void invalidate() {
        generation += 1;
        destroyActiveRequest();
        clearPackage();
        state = new State(Phase.IDLE, null, null, null);
    }

    public synchronized void clearSessionState() {
        generation += 1;
        destroyActiveRequest();
        clearPackage();
        state = new State(Phase.IDLE, null, null, null);
    }

    private synchronized void clearActiveRequest(Request request) {
        if (activeRequest == request) activeRequest = null;
    }

    private synchronized void replaceActiveRequest(Request request) {
        if (activeRequest != null && activeRequest != request) activeRequest.destroy();
        activeRequest = request;
    }

    private void destroyActiveRequest() {
        if (activeRequest != null) activeRequest.destroy();
        activeRequest = null;
    }

    private synchronized boolean applyPackage(Request request, AdminDeliveryPackage created) {
        if (request.generation != generation) {
            created.destroy();
            return false;
        }
        packageValue = created;
        state = new State(
            Phase.PACKAGE_READY,
            request.reportId,
            "제출본이 생성됐습니다. 저장 위치를 직접 선택해 주세요. 기관으로 자동 전송되지 않습니다.",
            null
        );
        return true;
    }

    private synchronized boolean applyStatus(
        Request request,
        Phase phase,
        String message,
        AdminReportModels.Detail detail
    ) {
        if (request.generation != generation) return false;
        state = new State(phase, request.reportId, message, detail);
        return true;
    }

    private synchronized boolean fail(Request request, String message) {
        if (request.generation != generation) return false;
        state = new State(Phase.ERROR, request.reportId, message, null);
        return true;
    }

    private synchronized boolean isCurrent(Request request) {
        return request.generation == generation && state.phase == Phase.LOADING;
    }

    private void requireNoMutationInFlight() {
        if (state.phase == Phase.LOADING) {
            throw new IllegalStateException("administrator report mutation is already in flight");
        }
    }

    private void clearPackage() {
        if (packageValue != null) packageValue.destroy();
        packageValue = null;
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
}
