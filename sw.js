/**
 * sw.js - NSSF Offline MRZ Scanner Service Worker v8
 * Multi-layer cache-busting and 100% offline asset caching
 */

const CACHE_NAME = 'nssf-mrz-v8';
const ASSETS_TO_CACHE = [
    './',
    './index.html',
    './manifest.json',
    './js/ug-id-parser.js?v=20260819_v8',
    './js/app.js?v=20260819_v8',
    'https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js'
];

// Install Event - Force immediate activation
self.addEventListener('install', event => {
    console.log('[ServiceWorker] Installing version:', CACHE_NAME);
    self.skipWaiting(); // Force active immediately without waiting for browser restart
    event.waitUntil(
        caches.open(CACHE_NAME).then(cache => {
            console.log('[ServiceWorker] Caching core PWA assets');
            return cache.addAll(ASSETS_TO_CACHE);
        })
    );
});

// Activate Event - Clean up stale old caches and take control of all open tabs
self.addEventListener('activate', event => {
    console.log('[ServiceWorker] Activating version:', CACHE_NAME);
    event.waitUntil(
        caches.keys().then(cacheNames => {
            return Promise.all(
                cacheNames.map(name => {
                    if (name !== CACHE_NAME) {
                        console.log('[ServiceWorker] Deleting old cache:', name);
                        return caches.delete(name);
                    }
                })
            );
        }).then(() => {
            console.log('[ServiceWorker] Taking control of clients via clients.claim()');
            return self.clients.claim();
        })
    );
});

// Fetch Event - Stale-while-revalidate strategy for maximum speed and offline operation
self.addEventListener('fetch', event => {
    if (event.request.method !== 'GET' || !event.request.url.startsWith('http')) {
        return;
    }

    event.respondWith(
        caches.match(event.request).then(cachedResponse => {
            const fetchPromise = fetch(event.request).then(networkResponse => {
                if (networkResponse && networkResponse.status === 200 && networkResponse.type === 'basic') {
                    const responseToCache = networkResponse.clone();
                    caches.open(CACHE_NAME).then(cache => {
                        cache.put(event.request, responseToCache);
                    });
                }
                return networkResponse;
            }).catch(err => {
                console.log('[ServiceWorker] Fetch failed, returning offline fallback:', err);
            });

            return cachedResponse || fetchPromise;
        })
    );
});
