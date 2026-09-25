// Caches the static, account-agnostic shell assets (CSS/JS/logo/manifest)
// cache-first - required by Chrome/Android for install eligibility. Doesn't
// touch any other route (/inbox, /inbox/dismissed, /settings, etc.) - those
// are per-account and dynamic, so cache-first there risks serving stale or
// wrong-account content. See foodie's CLAUDE.md "Service worker caching
// (PWA)" section if that kind of offline support is ever wanted here too.
//
// Bump CACHE_NAME whenever a file in STATIC_ASSETS changes, so browsers
// detect the update and repopulate the cache with the new files.
const CACHE_NAME = 'schoolio-static-v18';

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

// Once-a-day digest notification (see WebPush.kt/InboxRoutes.kt's
// internalNotifyRoutes) - the payload is the plain JSON encrypted server-side
// (RFC 8291), already decrypted by the browser by the time this handler
// runs. event.data can legitimately be missing (a push with no payload is
// valid per spec, even though this app never sends one that way) - falls
// back to generic text rather than throwing, since a failed push handler
// leaves the OS with no notification shown at all.
self.addEventListener('push', (event) => {
  let payload = { title: 'Schoolio', body: "There's something new.", url: '/inbox' };
  try {
    if (event.data) payload = { ...payload, ...event.data.json() };
  } catch (e) {
    // Not JSON for some reason - the generic fallback above still shows
    // *something* rather than silently dropping the notification.
  }
  event.waitUntil(
    self.registration.showNotification(payload.title, {
      body: payload.body,
      icon: '/logo.svg',
      badge: '/logo.svg',
      data: { url: payload.url }
    })
  );
});

// Focuses an already-open /inbox tab rather than always opening a new one -
// checks every open window client for one already on this origin, since
// matchAll's own url filter only does exact-URL matching, not "same app,
// different path".
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const targetUrl = (event.notification.data && event.notification.data.url) || '/inbox';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      const origin = self.location.origin;
      const existing = clients.find((client) => client.url.startsWith(origin));
      if (existing) {
        existing.navigate(targetUrl);
        return existing.focus();
      }
      return self.clients.openWindow(targetUrl);
    })
  );
});
