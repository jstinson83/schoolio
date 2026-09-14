// Caches the static, account-agnostic shell assets (CSS/JS/logo/manifest)
// cache-first - required by Chrome/Android for install eligibility. Doesn't
// touch any other route (/inbox, /inbox/dismissed, /settings, etc.) - those
// are per-account and dynamic, so cache-first there risks serving stale or
// wrong-account content. See foodie's CLAUDE.md "Service worker caching
// (PWA)" section if that kind of offline support is ever wanted here too.
//
// Bump CACHE_NAME whenever a file in STATIC_ASSETS changes, so browsers
// detect the update and repopulate the cache with the new files.
const CACHE_NAME = 'schoolio-static-v10';

const STATIC_ASSETS = [
  '/css/base.css',
  '/app.js',
  '/manifest.json',
  '/logo.svg',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((names) =>
      Promise.all(
        names.filter((name) => name !== CACHE_NAME).map((name) => caches.delete(name))
      )
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;
  const url = new URL(event.request.url);
  if (!STATIC_ASSETS.includes(url.pathname)) return;

  event.respondWith(
    caches.match(event.request).then((cached) => cached || fetch(event.request))
  );
});
