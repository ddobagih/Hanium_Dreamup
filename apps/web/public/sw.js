// This worker provides an opt-in offline app shell, not offline detection/report/navigation.
// API-like paths always use the network without cache fallback to avoid replaying stale safety data.
importScripts("/sw-version.js");
const SOURCE_COMMIT = self.WALKSAFE_SW_SOURCE_COMMIT;
if (typeof SOURCE_COMMIT !== "string" || !/^[0-9a-f]{40}$/.test(SOURCE_COMMIT)) {
  throw new Error("WalkSafe service-worker source identity is unavailable");
}
const SW_VERSION = `source-${SOURCE_COMMIT}`;
const CACHE_NAME = `walksafe-assist-${SW_VERSION}`;
const SHELL_ASSETS = ["/manifest.webmanifest", "/icon.svg", "/icons/icon-192.png", "/icons/icon-512.png"];
const API_PATH_PREFIXES = ["/api", "/detect", "/reports", "/uploads", "/navigation", "/speech"];

async function cacheCompleteShell() {
  const cache = await caches.open(CACHE_NAME);
  const rootResponse = await fetch("/", { cache: "reload" });
  if (!rootResponse.ok) throw new Error("root shell fetch failed");
  const html = await rootResponse.clone().text();
  await cache.put("/", rootResponse);
  const nextAssets = [...html.matchAll(/(?:src|href)=["']([^"']*\/_next\/static\/[^"']+)["']/g)]
    .map((match) => new URL(match[1], self.location.origin))
    .filter((url) => url.origin === self.location.origin)
    .map((url) => `${url.pathname}${url.search}`);
  await cache.addAll([...new Set([...SHELL_ASSETS, ...nextAssets])]);
}

self.addEventListener("install", (event) => {
  event.waitUntil(cacheCompleteShell());
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key.startsWith("walksafe-assist-") && key !== CACHE_NAME).map((key) => caches.delete(key))))
      .then(() => self.clients.claim())
      .then(() => self.clients.matchAll({ type: "window" }))
      .then((clients) => clients.forEach((client) => client.postMessage({ type: "SW_VERSION", version: SW_VERSION })))
  );
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "GET_VERSION") {
    event.ports?.[0]?.postMessage({ type: "SW_VERSION", version: SW_VERSION });
    return;
  }
  if (event.data?.type === "SKIP_WAITING") {
    event.waitUntil(self.skipWaiting());
    return;
  }
  if (event.data?.type === "CACHE_LOADED_STATIC_ASSETS") {
    const rawUrls = Array.isArray(event.data.urls) ? event.data.urls.slice(0, 256) : [];
    const staticUrls = rawUrls
      .filter((value) => typeof value === "string")
      .flatMap((value) => {
        try {
          return [new URL(value, self.location.origin)];
        } catch {
          return [];
        }
      })
      .filter((url) => url.origin === self.location.origin && url.pathname.startsWith("/_next/static/"))
      .map((url) => `${url.pathname}${url.search}`);
    event.waitUntil(
      caches
        .open(CACHE_NAME)
        .then(async (cache) => {
          const uniqueUrls = [...new Set(staticUrls)];
          const results = await Promise.all(
            uniqueUrls.map(async (url) => {
              const existing = await cache.match(url);
              if (existing) return true;
              const response = await fetch(url);
              if (!response.ok) return false;
              await cache.put(url, response);
              return true;
            })
          );
          const cached = results.filter(Boolean).length;
          event.ports?.[0]?.postMessage({ ok: cached === uniqueUrls.length && cached > 0, cached, expected: uniqueUrls.length });
        })
        .catch(() => event.ports?.[0]?.postMessage({ ok: false, cached: 0 }))
    );
  }
});

self.addEventListener("fetch", (event) => {
  // POST/other mutations are intentionally left to the page and are never queued by this worker.
  if (event.request.method !== "GET") {
    return;
  }

  const requestUrl = new URL(event.request.url);
  const isSameOrigin = requestUrl.origin === self.location.origin;
  const isApiRequest = API_PATH_PREFIXES.some((prefix) => requestUrl.pathname.startsWith(prefix));
  const isStaticAsset =
    isSameOrigin && (requestUrl.pathname.startsWith("/_next/static/") || SHELL_ASSETS.includes(requestUrl.pathname));
  const isRootShellRequest = isSameOrigin && requestUrl.pathname === "/";
  const isNavigation = event.request.mode === "navigate";

  if (isApiRequest) {
    event.respondWith(fetch(event.request, { cache: "no-store" }));
    return;
  }

  if (isNavigation) {
    event.respondWith(fetch(event.request).catch(() => caches.match("/")));
    return;
  }

  if (isRootShellRequest) {
    event.respondWith(fetch(event.request).catch(() => caches.match("/")));
    return;
  }

  if (!isStaticAsset) {
    event.respondWith(fetch(event.request));
    return;
  }

  event.respondWith(
    fetch(event.request)
      .then((response) => {
        if (response.ok) {
          const responseClone = response.clone();
          event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.put(event.request, responseClone)));
        }
        return response;
      })
      .catch(() => caches.match(event.request))
  );
});
