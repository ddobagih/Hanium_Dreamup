package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Shared bounded OkHttp transport for administrator API clients that require PATCH. */
final class AdminOkHttpTransport {
    private static final int CONNECT_TIMEOUT_SECONDS = 8;
    private static final int READ_TIMEOUT_SECONDS = 12;
    private static final int REQUEST_TIMEOUT_SECONDS = CONNECT_TIMEOUT_SECONDS + READ_TIMEOUT_SECONDS;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();

    private AdminOkHttpTransport() {}

    static final class RequestOwner {
        private final java.util.Set<Call> calls = new java.util.HashSet<>();
        private boolean cancelled;
        synchronized void register(Call call) throws IOException {
            if (cancelled) throw new IOException("administrator client is closed");
            calls.add(call);
        }
        synchronized void completed(Call call) { calls.remove(call); }
        void cancelInFlight() {
            java.util.List<Call> pending;
            synchronized (this) {
                // Controller close is terminal. A request racing registration cannot reopen it.
                cancelled = true;
                pending = new java.util.ArrayList<>(calls);
            }
            for (Call call : pending) call.cancel();
        }
    }

    static Result execute(
        String method,
        String url,
        Map<String, String> headers,
        byte[] body,
        int maximumResponseBytes,
        String cancelledMessage,
        String noProgressMessage,
        String tooLargeMessage
    ) throws IOException {
        return execute(new RequestOwner(), method, url, headers, body, maximumResponseBytes,
            cancelledMessage, noProgressMessage, tooLargeMessage);
    }

    static Result execute(RequestOwner owner, String method, String url,
        Map<String, String> headers, byte[] body, int maximumResponseBytes,
        String cancelledMessage, String noProgressMessage, String tooLargeMessage) throws IOException {
        return execute(owner, null, method, url, headers, body, maximumResponseBytes,
            cancelledMessage, noProgressMessage, tooLargeMessage);
    }

    static Result execute(RequestOwner owner, AdminOperationsApi.SessionContext session,
        String method, String url, Map<String, String> headers, byte[] body, int maximumResponseBytes,
        String cancelledMessage, String noProgressMessage, String tooLargeMessage) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException(cancelledMessage);
        Request.Builder request = new Request.Builder().url(url);
        headers.forEach(request::header);
        request.method(method, body == null ? null : RequestBody.create(body, null));
        Call call = CLIENT.newCall(request.build());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Result> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        owner.register(call);
        AdminRequestAdmission.Registration registration = null;
        try {
            Callback callback = new Callback() {
                @Override
                public void onFailure(Call ignored, IOException error) {
                    failure.set(error);
                    completed.countDown();
                }

                @Override
                public void onResponse(Call ignored, okhttp3.Response response) {
                    try (response) {
                        if (call.isCanceled()) throw new IOException(cancelledMessage);
                        byte[] responseBytes = response.body() == null
                            ? new byte[0]
                            : readBounded(
                                response.body().byteStream(),
                                call,
                                maximumResponseBytes,
                                cancelledMessage,
                                noProgressMessage,
                                tooLargeMessage
                            );
                        Map<String, String> responseHeaders = new LinkedHashMap<>();
                        response.headers().toMultimap().forEach((name, values) -> {
                            if (name != null && values != null && values.size() == 1) {
                                responseHeaders.put(name, values.get(0));
                            }
                        });
                        result.set(new Result(response.code(), responseBytes, responseHeaders));
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        completed.countDown();
                    }
                }
            };
            if (session == null) call.enqueue(callback);
            else registration = session.dispatch(call::cancel, () -> call.enqueue(callback));
            // Only admission/enqueue owns the lifecycle monitor; response waiting never does.
            try {
                if (!completed.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    call.cancel();
                    throw new SocketTimeoutException("administrator API request timed out; server outcome unknown");
                }
            } catch (InterruptedException error) {
                call.cancel();
                Thread.currentThread().interrupt();
                throw new IOException(cancelledMessage, error);
            }
            Throwable error = failure.get();
            if (error instanceof IOException ioError) throw ioError;
            if (error instanceof RuntimeException runtimeError) throw runtimeError;
            if (error instanceof Error fatalError) throw fatalError;
            Result completedResult = result.get();
            if (completedResult == null) {
                throw new IOException("administrator response did not complete");
            }
            return completedResult;
        } finally {
            if (registration != null) registration.close();
            owner.completed(call);
        }
    }

    private static byte[] readBounded(
        InputStream input,
        Call call,
        int maximumResponseBytes,
        String cancelledMessage,
        String noProgressMessage,
        String tooLargeMessage
    ) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4_096];
            int zeroReads = 0;
            try {
                while (true) {
                    if (call.isCanceled()) throw new IOException(cancelledMessage);
                    int read = input.read(buffer);
                    if (read < 0) break;
                    if (read == 0) {
                        if (++zeroReads > 3) throw new IOException(noProgressMessage);
                        continue;
                    }
                    zeroReads = 0;
                    if (output.size() + read > maximumResponseBytes) {
                        throw new IOException(tooLargeMessage);
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
        }
    }

    static final class Result {
        final int statusCode;
        final byte[] body;
        final Map<String, String> headers;

        Result(int statusCode, byte[] body, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body == null ? new byte[0] : body.clone();
            this.headers = AdminJava8Collections.copyMap(headers);
        }

        String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
            }
            return null;
        }
    }
}
