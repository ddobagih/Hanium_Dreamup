package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.util.Arrays;

/** One-shot high-risk status/package state. Credentials never enter snapshots. */
public final class AdminReportWorkflowController {
    public enum Phase {
        IDLE,
        LOADING,
        SUCCEEDED,
        UPDATED_DETAIL_STALE,
        CONFLICT,
        ERROR,
        PACKAGE_READY,
        SAVED,
        CANCELLED
    }

    @FunctionalInterface
    public interface StatusDispatch {
        boolean markDispatched();
    }

    public static final class StatusDispatchCancelledException extends IOException {
        StatusDispatchCancelledException() {
            super("report status PATCH was cancelled before dispatch");
        }
    }

    public interface Loader {
        AdminReportModels.StatusSnapshot updateStatus(
            String reportId,
            String nextStatus,
            int expectedVersion,
            char[] password,
            char[] totp
        ) throws Exception;
        default AdminReportModels.StatusSnapshot updateStatus(
            String reportId,
            String nextStatus,
            int expectedVersion,
            char[] password,
            char[] totp,
            StatusDispatch dispatch
        ) throws Exception {
            if (dispatch == null || !dispatch.markDispatched()) {
                throw new StatusDispatchCancelledException();
            }
            return updateStatus(reportId, nextStatus, expectedVersion, password, totp);
        }
        AdminDeliveryPackage createPackage(
            AdminDeliveryPackage.Eligibility eligibility,
            char[] password,
            char[] totp
        ) throws Exception;
        AdminReportModels.Detail refreshDetail(String reportId) throws Exception;
    }

    /** Non-sensitive state needed to recover with GET only after a lifecycle fence. */
    public static final class StatusDetailRecovery {
        private final String reportId;
        private final String targetStatus;
        private final int targetStatusVersion;
        private final boolean patchConfirmed;

        private StatusDetailRecovery(
            String reportId,
            String targetStatus,
            int targetStatusVersion,
            boolean patchConfirmed
        ) {
            this.reportId = reportId;
            this.targetStatus = targetStatus;
            this.targetStatusVersion = targetStatusVersion;
            this.patchConfirmed = patchConfirmed;
        }

        public String reportId() { return reportId; }
        public String targetStatus() { return targetStatus; }
        public int targetStatusVersion() { return targetStatusVersion; }
        public boolean patchConfirmed() { return patchConfirmed; }
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
        private enum Kind { STATUS, STATUS_DETAIL_RETRY, PACKAGE }
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
            this.password = password == null ? null : password.clone();
            this.totp = totp == null ? null : totp.clone();
        }

        private synchronized void destroy() {
            if (password != null) Arrays.fill(password, '\0');
            if (totp != null) Arrays.fill(totp, '\0');
        }

        private synchronized char[] passwordCopy() { return password.clone(); }
        private synchronized char[] totpCopy() { return totp.clone(); }
        private synchronized boolean claim() {
            if (claimed) return false;
            claimed = true;
            return true;
        }
    }

    private enum StatusStage {
        NONE,
        NOT_DISPATCHED,
        PATCH_DISPATCHED,
        PATCH_CONFIRMED
    }

    private final Loader loader;
    private long generation;
    private State state = new State(Phase.IDLE, null, null, null);
    private AdminDeliveryPackage packageValue;
    private Request activeRequest;
    private StatusStage statusStage = StatusStage.NONE;
    private String confirmedStatus;
    private int confirmedStatusVersion;

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
        clearStatusRecovery();
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        Request request = new Request(
            ++generation, Request.Kind.STATUS, safeId, nextStatus, expectedVersion, null, password, totp
        );
        replaceActiveRequest(request);
        statusStage = StatusStage.NOT_DISPATCHED;
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
        clearStatusRecovery();
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

    public synchronized Request beginStatusDetailRetry() {
        requireNoMutationInFlight();
        if (state.phase != Phase.UPDATED_DETAIL_STALE || state.reportId == null) {
            throw new IllegalStateException("stale report status detail is unavailable");
        }
        Request request = new Request(
            ++generation,
            Request.Kind.STATUS_DETAIL_RETRY,
            state.reportId,
            null,
            0,
            null,
            null,
            null
        );
        replaceActiveRequest(request);
        state = new State(
            Phase.LOADING,
            request.reportId,
            statusStage == StatusStage.PATCH_CONFIRMED
                ? "상태 변경 성공 응답과 일치하는지 PATCH 없이 최신 상세만 다시 조회하고 있습니다."
                : "상태 변경 결과를 확정하기 위해 PATCH 없이 최신 상세만 다시 조회하고 있습니다.",
            null
        );
        return request;
    }

    public synchronized String statusDetailRecoveryReportId() {
        StatusDetailRecovery recovery = statusDetailRecovery();
        return recovery == null ? null : recovery.reportId;
    }

    public synchronized void restoreStatusDetailRecovery(String reportId) {
        restoreStatusDetailRecovery(reportId, null, 0, false);
    }

    public synchronized StatusDetailRecovery statusDetailRecovery() {
        boolean recoverableState = state.phase == Phase.UPDATED_DETAIL_STALE
            || (state.phase == Phase.LOADING
                && activeRequest != null
                && (activeRequest.kind == Request.Kind.STATUS
                    || activeRequest.kind == Request.Kind.STATUS_DETAIL_RETRY));
        if (!recoverableState || statusStage == StatusStage.NONE
            || statusStage == StatusStage.NOT_DISPATCHED) {
            return null;
        }
        return new StatusDetailRecovery(
            state.reportId,
            confirmedStatus,
            confirmedStatusVersion,
            statusStage == StatusStage.PATCH_CONFIRMED
        );
    }

    public synchronized void restoreStatusDetailRecovery(
        String reportId,
        String targetStatus,
        int targetStatusVersion,
        boolean patchConfirmed
    ) {
        String safeId = AdminReportModels.canonicalUuid(reportId, "report_id");
        if (patchConfirmed
            && (!isReportStatus(targetStatus) || targetStatusVersion < 1)) {
            throw new IllegalArgumentException("confirmed report status recovery is invalid");
        }
        if (!patchConfirmed && (targetStatus != null || targetStatusVersion != 0)) {
            throw new IllegalArgumentException("unconfirmed report status recovery is invalid");
        }
        generation += 1;
        destroyActiveRequest();
        clearPackage();
        statusStage = patchConfirmed
            ? StatusStage.PATCH_CONFIRMED
            : StatusStage.PATCH_DISPATCHED;
        confirmedStatus = targetStatus;
        confirmedStatusVersion = targetStatusVersion;
        state = new State(
            Phase.UPDATED_DETAIL_STALE,
            safeId,
            patchConfirmed
                ? "이전 상태 변경 성공 응답을 복원했습니다. PATCH 없이 목표 상태와 version을 다시 확인해 주세요."
                : "이전 상태 변경 결과를 확정할 수 없습니다. PATCH 없이 최신 상세만 다시 조회해 주세요.",
            null
        );
    }

    public synchronized void suspendForLifecycle() {
        StatusDetailRecovery recovery = statusDetailRecovery();
        generation += 1;
        destroyActiveRequest();
        clearPackage();
        if (recovery == null) {
            clearStatusRecovery();
            state = new State(Phase.IDLE, null, null, null);
        } else {
            state = new State(
                Phase.UPDATED_DETAIL_STALE,
                recovery.reportId,
                recovery.patchConfirmed
                    ? "화면이 중단됐지만 상태 변경 성공 응답은 확인했습니다. PATCH 없이 목표 상태와 version만 다시 확인해 주세요."
                    : "화면이 중단되어 상태 변경 결과를 확정할 수 없습니다. PATCH 없이 최신 상세만 다시 조회해 주세요.",
                null
            );
        }
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
            if (request.kind == Request.Kind.STATUS_DETAIL_RETRY) {
                try {
                    AdminReportModels.Detail detail = loader.refreshDetail(request.reportId);
                    return applyRecoveredStatusDetail(request, detail);
                } catch (Exception refreshFailure) {
                    return markUpdatedDetailStale(request);
                }
            }
            return executeStatus(request);
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
        clearStatusRecovery();
        state = new State(Phase.IDLE, null, null, null);
    }

    public synchronized void clearSessionState() {
        generation += 1;
        destroyActiveRequest();
        clearPackage();
        clearStatusRecovery();
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
        clearStatusRecovery();
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
        clearStatusRecovery();
        state = new State(phase, request.reportId, message, detail);
        return true;
    }

    private synchronized boolean fail(Request request, String message) {
        if (request.generation != generation) return false;
        clearStatusRecovery();
        state = new State(Phase.ERROR, request.reportId, message, null);
        return true;
    }

    private synchronized boolean markUpdatedDetailStale(Request request) {
        if (request.generation != generation) return false;
        state = new State(
            Phase.UPDATED_DETAIL_STALE,
            request.reportId,
            statusStage == StatusStage.PATCH_CONFIRMED
                ? "상태 변경은 성공했습니다. 최신 상세 조회에 실패했으며 PATCH를 자동 재전송하지 않습니다."
                : "상태 변경 응답을 확인하지 못했습니다. PATCH를 재전송하지 않고 최신 상세만 조회해 주세요.",
            null
        );
        return true;
    }

    private boolean executeStatus(Request request) {
        char[] password = request.passwordCopy();
        char[] totp = request.totpCopy();
        try {
            try {
                AdminReportModels.StatusSnapshot updated = loader.updateStatus(
                    request.reportId,
                    request.nextStatus,
                    request.expectedVersion,
                    password,
                    totp,
                    () -> markStatusDispatched(request)
                );
                if (!validStatusResponse(request, updated)) {
                    return markUpdatedDetailStale(request);
                }
                if (!markStatusConfirmed(request, updated)) return false;
            } catch (StatusDispatchCancelledException cancelled) {
                return false;
            } catch (AdminReportRepository.StatusConflictException conflict) {
                return refreshAfterStatusConflict(request);
            } catch (Exception responseFailure) {
                return applyStatusFailure(request);
            }
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(totp, '\0');
        }
        try {
            return applyRecoveredStatusDetail(
                request,
                loader.refreshDetail(request.reportId)
            );
        } catch (Exception refreshFailure) {
            return markUpdatedDetailStale(request);
        }
    }

    private synchronized boolean applyStatusFailure(Request request) {
        if (request.generation != generation) return false;
        if (statusStage == StatusStage.NOT_DISPATCHED) {
            clearStatusRecovery();
            state = new State(
                Phase.ERROR,
                request.reportId,
                "상태 변경을 전송하지 못했습니다. 재인증 후 다시 시도해 주세요.",
                null
            );
            return true;
        }
        return markUpdatedDetailStale(request);
    }

    private boolean refreshAfterStatusConflict(Request request) {
        try {
            return applyStatus(
                request,
                Phase.CONFLICT,
                "다른 관리자가 먼저 상태를 변경했습니다. 최신 상세를 반영했으며 자동 재제출하지 않았습니다.",
                loader.refreshDetail(request.reportId)
            );
        } catch (Exception refreshFailure) {
            return fail(request, "상태 충돌 후 최신 상세를 불러오지 못했습니다. 다시 조회해 주세요.");
        }
    }

    private synchronized boolean markStatusDispatched(Request request) {
        if (!isCurrent(request) || activeRequest != request
            || statusStage != StatusStage.NOT_DISPATCHED) {
            return false;
        }
        statusStage = StatusStage.PATCH_DISPATCHED;
        return true;
    }

    private synchronized boolean markStatusConfirmed(
        Request request,
        AdminReportModels.StatusSnapshot updated
    ) {
        if (!isCurrent(request) || activeRequest != request
            || statusStage != StatusStage.PATCH_DISPATCHED) {
            return false;
        }
        confirmedStatus = updated.status();
        confirmedStatusVersion = updated.statusVersion();
        statusStage = StatusStage.PATCH_CONFIRMED;
        return true;
    }

    private synchronized boolean applyRecoveredStatusDetail(
        Request request,
        AdminReportModels.Detail detail
    ) {
        if (request.generation != generation) return false;
        if (statusStage != StatusStage.PATCH_CONFIRMED) {
            clearStatusRecovery();
            state = new State(
                Phase.CONFLICT,
                request.reportId,
                "최신 상세를 반영했지만 보존한 PATCH의 성공 응답이 없어 이 요청의 성공으로 확정하지 않았습니다. PATCH를 재전송하지 않았습니다.",
                detail
            );
            return true;
        }
        if (confirmedStatus.equals(detail.summary().status())
            && confirmedStatusVersion == detail.summary().statusVersion()) {
            clearStatusRecovery();
            state = new State(
                Phase.SUCCEEDED,
                request.reportId,
                "PATCH를 다시 보내지 않고 성공 응답과 일치하는 최신 상세를 확인했습니다.",
                detail
            );
            return true;
        }
        if (detail.summary().statusVersion() < confirmedStatusVersion) {
            state = new State(
                Phase.UPDATED_DETAIL_STALE,
                request.reportId,
                "최신 상세가 확인된 상태 변경 version보다 오래됐습니다. PATCH 없이 다시 조회해 주세요.",
                detail
            );
            return true;
        }
        clearStatusRecovery();
        state = new State(
            Phase.CONFLICT,
            request.reportId,
            "상태 변경 성공 응답과 최신 상세가 다릅니다. 이후 변경을 반영했으며 PATCH를 재전송하지 않았습니다.",
            detail
        );
        return true;
    }

    private static boolean validStatusResponse(
        Request request,
        AdminReportModels.StatusSnapshot updated
    ) {
        return updated != null
            && request.reportId.equals(updated.id())
            && request.nextStatus.equals(updated.status())
            && request.expectedVersion < Integer.MAX_VALUE
            && updated.statusVersion() == request.expectedVersion + 1;
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

    private void clearStatusRecovery() {
        statusStage = StatusStage.NONE;
        confirmedStatus = null;
        confirmedStatusVersion = 0;
    }

    private static boolean isReportStatus(String value) {
        return "new".equals(value) || "reviewed".equals(value) || "resolved".equals(value);
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
