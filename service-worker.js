/* PaySaathi PWA service worker. Bump APP_VERSION for every app release. */
const APP_VERSION = "1.1.4";
const CACHE_NAME = "paysaathi-shell-" + APP_VERSION;
const BASE_URL = new URL("./", self.location.href);
const HOME_URL = new URL("./index.html", BASE_URL).href;
const DASHBOARD_URL = new URL("./dashboard.html", BASE_URL).href;
const CORE_FILES = [
  new URL("./", BASE_URL).href,
  HOME_URL,
  DASHBOARD_URL,
  new URL("./login.html", BASE_URL).href,
  new URL("./manifest.webmanifest", BASE_URL).href,
  new URL("./assets/paysaathi-app.svg", BASE_URL).href,
  new URL("./release.json", BASE_URL).href,
  new URL("./kyc.html", BASE_URL).href,
  new URL("./admin-kyc.html", BASE_URL).href,
  new URL("./kyc.js", BASE_URL).href,
  new URL("./kyc-gate.js", BASE_URL).href
];

self.addEventListener("install", event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await Promise.all(CORE_FILES.map(async url => {
      try {
        const response = await fetch(url, {cache:"reload"});
        if (response.ok) await cache.put(url, response);
      } catch (_) {}
    }));
  })());
  // Activate the new app shell immediately so installed phones receive updates automatically.
  self.skipWaiting();
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(key => key.startsWith("paysaathi-shell-") && key !== CACHE_NAME).map(key => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener("message", event => {
  if (event.data && event.data.type === "PWAY_SKIP_WAITING") self.skipWaiting();
});

self.addEventListener("fetch", event => {
  const request = event.request;
  if (request.method !== "GET" || request.mode !== "navigate") return;
  const requestUrl = new URL(request.url);
  if (requestUrl.origin !== self.location.origin || !requestUrl.pathname.startsWith(BASE_URL.pathname)) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    try {
      const response = await fetch(request);
      if (response && response.ok) await cache.put(requestUrl.href, response.clone());
      return response;
    } catch (_) {
      const exact = await cache.match(requestUrl.href);
      if (exact) return exact;
      const isDashboard = requestUrl.pathname.endsWith("/dashboard.html");
      return (await cache.match(isDashboard ? DASHBOARD_URL : HOME_URL)) || Response.error();
    }
  })());
});
