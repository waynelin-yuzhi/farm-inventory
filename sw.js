// 简易 Service Worker：缓存应用外壳，加快加载并支持离线打开界面。
// 数据读写仍需联网（Supabase）。修改静态文件后请提升 CACHE 版本号。
const CACHE = "farm-inv-v1";
const SHELL = [
  "./",
  "./index.html",
  "./css/styles.css",
  "./manifest.webmanifest",
  "./js/app.js",
  "./js/router.js",
  "./js/ui.js",
  "./js/db.js",
  "./js/scanner.js",
  "./js/supabase.js",
  "./js/config.js",
  "./js/views/dashboard.js",
  "./js/views/checkout.js",
  "./js/views/purchase.js",
  "./js/views/products.js",
  "./js/views/suppliers.js",
];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  // 仅对同源 GET 走缓存；跨域（Supabase / CDN）直连网络。
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  e.respondWith(
    caches.match(e.request).then((hit) =>
      hit ||
      fetch(e.request).then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
        return res;
      }).catch(() => caches.match("./index.html"))
    )
  );
});
