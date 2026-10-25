package kr.co.hanium.dreamup.walksafe.admin.security;

/** Atomic lifecycle authority for admitting one request and retaining its cancellation. */
interface AdminRequestAdmission {
    interface Registration extends AutoCloseable { @Override void close(); }

    /** start must only register/enqueue, never wait for network I/O. */
    Registration dispatch(Runnable cancel, Runnable start);

    AdminRequestAdmission UNBOUND = (cancel, start) -> { start.run(); return () -> {}; };
}
