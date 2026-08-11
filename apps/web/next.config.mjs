export function parseAllowedDevOrigins(value = "") {
  return value
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      if (!entry.includes("://")) return entry;
      try {
        return new URL(entry).hostname;
      } catch {
        throw new Error("WALKSAFE_ALLOWED_DEV_ORIGINS contains an invalid URL");
      }
    });
}

export function parseNextDistDirectory(value = "") {
  const normalized = value.trim();
  if (!normalized) return ".next";
  if (!/^\.next-[A-Za-z0-9._-]+$/.test(normalized)) {
    throw new Error("WALKSAFE_NEXT_DIST_DIR must be a safe .next-* directory name");
  }
  return normalized;
}

export function walksafeSecurityHeaders(production = process.env.NODE_ENV === "production") {
  const scriptPolicy = production
    ? "script-src 'self' 'unsafe-inline'"
    : "script-src 'self' 'unsafe-inline' 'unsafe-eval'";
  const contentSecurityPolicy = [
    "default-src 'self'",
    scriptPolicy,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' blob: data:",
    "media-src 'self' blob:",
    "connect-src 'self'",
    "font-src 'self' data:",
    "worker-src 'self' blob:",
    "manifest-src 'self'",
    "form-action 'self'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
    "object-src 'none'"
  ].join("; ");
  const headers = [
    { key: "Content-Security-Policy", value: contentSecurityPolicy },
    { key: "X-Content-Type-Options", value: "nosniff" },
    { key: "Referrer-Policy", value: "no-referrer" },
    { key: "Permissions-Policy", value: "camera=(self), microphone=(self), geolocation=(self)" }
  ];
  if (production) {
    headers.push({ key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" });
  }
  return headers;
}

const allowedDevOrigins = parseAllowedDevOrigins(process.env.WALKSAFE_ALLOWED_DEV_ORIGINS);
const sourceCommit = (process.env.WALKSAFE_SOURCE_COMMIT ?? "").trim().toLowerCase();
if (sourceCommit && !/^[0-9a-f]{40}$/.test(sourceCommit)) {
  throw new Error("WALKSAFE_SOURCE_COMMIT must be a full lowercase Git commit SHA");
}
// Runtime state lives outside the project; do not let dynamic file paths package source, tests, or local logs.
const runtimeTraceExcludes = [
  "./app/**/*",
  "./lib/**/*",
  "./types/**/*",
  "./tests/**/*",
  "./public/**/*",
  "./walksafe-field-logs/**/*",
  "./walksafe-test-logs/**/*",
  "./.env*",
  "./*.md",
  "./eslint.config.mjs",
  "./next.config.mjs",
  "./package-lock.json",
  "./quality-requirements.lock",
  "./quality-requirements.txt",
  "./proxy.ts",
  "./legacy-runtime-boundary.ts",
  "./tsconfig*.json",
  "./tsconfig.tsbuildinfo"
];
const nextConfig = {
  reactStrictMode: true,
  output: "standalone",
  distDir: parseNextDistDirectory(process.env.WALKSAFE_NEXT_DIST_DIR),
  outputFileTracingExcludes: { "/*": runtimeTraceExcludes },
  async headers() {
    return [{ source: "/:path*", headers: walksafeSecurityHeaders() }];
  },
  ...(sourceCommit ? { generateBuildId: async () => sourceCommit } : {}),
  ...(allowedDevOrigins.length > 0 ? { allowedDevOrigins } : {})
};

export default nextConfig;
