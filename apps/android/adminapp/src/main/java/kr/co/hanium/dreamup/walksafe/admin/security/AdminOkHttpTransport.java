package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
        .cache(null)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();

    private AdminOkHttpTransport() {}

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
        if (Thread.currentThread().isInterrupted()) throw new IOException(cancelledMessage);
        Request.Builder request = new Request.Builder().url(url);
        headers.forEach(request::header);
        request.method(method, body == null ? null : RequestBody.create(body, null));
        Call call = CLIENT.newCall(request.build());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Result> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        call.enqueue(new Callback() {
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
        });
        try {
            completed.await();
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
