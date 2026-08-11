const SW_VERSION = "2026-05-31-camera-click-permission";
const CACHE_NAME = `walksafe-assist-${SW_VERSION}`;
const SHELL_ASSETS = ["/", "/manifest.webmanifest", "/icon.svg"];
const API_PATH_PREFIXES = ["/api", "/detect", "/reports", "/uploads", "/navigation", "/speech"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL_ASSETS)));
  self.skipWaiting();
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
    self.skipWaiting();
  }
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") {
    return;
  }

  const requestUrl = new URL(event.request.url);
  const isSameOrigin = requestUrl.origin === self.location.origin;
  const isApiRequest = API_PATH_PREFIXES.some((prefix) => requestUrl.pathname.startsWith(prefix));
  const isStaticAsset =
    isSameOrigin && (requestUrl.pathname.startsWith("/_next/static/") || SHELL_ASSETS.includes(requestUrl.pathname));
  const isNavigation = event.request.mode === "navigate";

  if (isApiRequest) {
    event.respondWith(fetch(event.request, { cache: "no-store" }));
    return;
  }

  if (isNavigation) {
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
