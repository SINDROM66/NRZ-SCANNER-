// NSSF Member Data Capture - Service Worker
const CACHE_NAME = 'nssf-capture-v1';

self.addEventListener('install', (e) => {
    console.log('[SW] Service Worker Installed');
    self.skipWaiting();
});

self.addEventListener('activate', (e) => {
    console.log('[SW] Service Worker Activated');
    return self.clients.claim();
});

self.addEventListener('fetch', (e) => {
    // Default fetch handler
});
