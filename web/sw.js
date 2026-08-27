// Service worker: makes the tanpura work offline once it has been opened.
//
// Cache-first for the app shell, because none of it changes without a new
// deployment and a practice session should never be interrupted by a flaky
// connection. Bump CACHE_VERSION on every deploy to roll users forward.

const CACHE_VERSION = 'tanpura-v1';

const SHELL = [
  './',
  './index.html',
  './style.css',
  './manifest.webmanifest',
  './tanpura-worklet.js',
  './src/app.js',
  './src/engine.js',
  './src/instrument.js',
  './src/ui.js',
  './icons/icon.svg',
  './icons/icon-192.png',
  './icons/icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE_VERSION)
      // Individually, so one missing file cannot fail the whole install.
      .then((cache) => Promise.allSettled(SHELL.map((url) => cache.add(url))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  // Only handle our own origin; a user's local audio file is a blob: URL and
  // never reaches here anyway.
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) {
        // Refresh in the background so the next launch is up to date.
        event.waitUntil(
          fetch(request)
            .then((response) => {
              if (response && response.ok) {
                return caches.open(CACHE_VERSION).then((c) => c.put(request, response));
              }
              return undefined;
            })
            .catch(() => undefined),
        );
        return cached;
      }
      return fetch(request)
        .then((response) => {
          if (response && response.ok) {
            const copy = response.clone();
            event.waitUntil(caches.open(CACHE_VERSION).then((c) => c.put(request, copy)));
          }
          return response;
        })
        .catch(() => caches.match('./index.html'));
    }),
  );
});
