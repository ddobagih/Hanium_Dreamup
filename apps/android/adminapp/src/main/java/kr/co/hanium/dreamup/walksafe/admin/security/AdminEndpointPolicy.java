package kr.co.hanium.dreamup.walksafe.admin.security;

import java.net.URI;
import java.util.Locale;

public final class AdminEndpointPolicy {
    private AdminEndpointPolicy() {}

    public static String approvedOriginOrNull(String raw, boolean debugBuild) {
        if (raw == null) return null;
        String value = raw.trim().replaceAll("/+$", "");
        if (value.isEmpty()) return null;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) return null;
            String port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return "https://" + uri.getHost().toLowerCase(Locale.ROOT) + port;
            }
            boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost());
            if (debugBuild && loopback && "http".equalsIgnoreCase(uri.getScheme())) {
                return "http://" + uri.getHost().toLowerCase(Locale.ROOT) + port;
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
