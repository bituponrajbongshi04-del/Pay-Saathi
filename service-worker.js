/* PaySaathi PWA service worker. Bump APP_VERSION for every app release. */
const APP_VERSION = "1.0.1";
const CACHE_NAME = "paysaathi-shell-" + APP_VERSION;
const BASE_URL = new URL("./", self.location.href);
const CORE_FILES = [
  new URL("./", BASE_URL).href,
  new URL("./index.html", BASE_URL).href,
  new URL("./manifest.webmanifest", BASE_URL).href,
  new URL("./assets/paysaathi-app.svg", BASE_URL).href,
  new URL("./release.json", BASE_URL).href
];

self.addEventListener("install", event => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    await Promise.all(CORE_FILES.map(async url => {
      try { const response = await fetch(url, {cache:"reload"}); if (response.ok) await cache.put(url, response); } catch (_) {}
    }));
  })());
  // Deliberately do not skipWaiting: the app asks the user before applying updates.
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
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || !url.pathname.startsWith(BASE_URL.pathname)) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE_NAME);
    try {
      const response = await fetch(request);
      if (response && response.ok) await cache.put(new URL(request.url).href, response.clone());
      return response;
    } catch (_) {
      return (await cache.match(new URL(request.url).href)) || (await cache.match(new URL("./index.html", BASE_URL).href));
    }
  })());
});
