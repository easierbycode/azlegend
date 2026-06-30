const VERSION = "azlegend-fresh-v2";
const SHELL_CACHE = VERSION + "-shell";
const MEDIA_CACHE = VERSION + "-media";
const ALBUM_INDEX_URL = "/public/music/albums.json";

const AUDIO_PARTY_ASSETS = [
  "/public/app/images/audio-party/bg-1.png",
  "/public/app/images/audio-party/bg-2.png",
  "/public/app/images/audio-party/bg-3.png",
  "/public/app/images/audio-party/bg-4.png",
  "/public/app/images/audio-party/bg-5.png",
  "/public/app/images/audio-party/hostage-girl-back.png",
  "/public/app/images/audio-party/lights1-yellow-1.png",
  "/public/app/images/audio-party/lights1-yellow-2.png",
  "/public/app/images/audio-party/lights1-yellow-3.png",
  "/public/app/images/audio-party/lights1-yellow-4.png",
  "/public/app/images/audio-party/lights2-blue-1.png",
  "/public/app/images/audio-party/lights2-blue-2.png",
  "/public/app/images/audio-party/lights2-blue-3.png",
  "/public/app/images/audio-party/lights2-blue-4.png",
  "/public/app/images/audio-party/lights3-green-1.png",
  "/public/app/images/audio-party/lights3-green-2.png",
  "/public/app/images/audio-party/lights3-green-3.png",
  "/public/app/images/audio-party/lights3-green-4.png",
  "/public/app/images/audio-party/lights4-purple-1.png",
  "/public/app/images/audio-party/lights4-purple-2.png",
  "/public/app/images/audio-party/lights4-purple-3.png",
  "/public/app/images/audio-party/lights4-purple-4.png",
  "/public/app/images/audio-party/lips.png",
  "/public/app/images/audio-party/nymph.png",
  "/public/app/images/audio-party/star-bg-0.png",
  "/public/app/images/audio-party/star-bg-1.png"
];

const SHELL_ASSETS = [
  "/",
  "/manifest.webmanifest",
  "/public/app/images/icon.svg",
  "/public/app/styles/styles.css",
  "/public/app/scripts/app.js",
  "/public/app/scripts/audio-visualizer.js",
  "/public/libraries/styles/cassette-tape-ui.css",
  "/public/libraries/styles/cassette-tape-ui-blur.css",
  "/public/libraries/font/consolas-webfont.woff",
  "/public/libraries/font/justanotherhand-webfont.woff",
  "/public/libraries/font/ostrich-rounded-webfont.woff",
  "/public/libraries/image/gradient.png",
  "/public/libraries/image/ma-r90-body-skin.png",
  "/public/libraries/image/ma-r90-mask.png",
  "/public/libraries/image/ma-r90-metal-pattern-v2.png",
  "/public/libraries/image/rail-middle-outline.png",
  "/public/libraries/image/spool-metal.png",
  "/public/libraries/image/spool-metal-black.png",
  "/public/libraries/image/tape-guide.png",
  "/public/libraries/image/tape-texture.png",
  ALBUM_INDEX_URL
].concat(AUDIO_PARTY_ASSETS);

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE)
      .then((cache) => Promise.allSettled(SHELL_ASSETS.map((url) => cacheUrl(cache, url))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys
        .filter((key) => key !== SHELL_CACHE && key !== MEDIA_CACHE)
        .map((key) => caches.delete(key))))
      .then(() => purgeOpaqueMedia())
      .then(() => self.clients.claim())
  );
});

// Evict any opaque (no-CORS) audio cached by an earlier build. Such entries
// taint the Web Audio graph and break range reads; dropping only the opaque
// ones forces a CORS-clean re-fetch while keeping same-origin offline tracks.
async function purgeOpaqueMedia() {
  const cache = await caches.open(MEDIA_CACHE);
  const requests = await cache.keys();
  await Promise.all(requests.map(async (request) => {
    const response = await cache.match(request);
    if (response && response.type === "opaque") {
      await cache.delete(request);
    }
  }));
}

self.addEventListener("message", (event) => {
  if (!event.data || event.data.type !== "CACHE_AUDIO") {
    return;
  }

  const replyPort = event.ports && event.ports[0];
  event.waitUntil(
    cacheAudioFromAlbums()
      .then((result) => {
        if (replyPort) {
          replyPort.postMessage(result);
        }
      })
      .catch((error) => {
        if (replyPort) {
          replyPort.postMessage({ error: error.message || "Audio cache failed" });
        }
      })
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;

  if (request.method !== "GET") {
    return;
  }

  if (request.headers.has("range")) {
    event.respondWith(handleRangeRequest(request));
    return;
  }

  const url = new URL(request.url);

  if (request.mode === "navigate") {
    event.respondWith(networkFirst(request, "/"));
    return;
  }

  if (url.pathname.endsWith(".mp3")) {
    event.respondWith(cacheFirst(request, MEDIA_CACHE));
    return;
  }

  if (url.pathname.endsWith(".json")) {
    event.respondWith(networkFirst(request));
    return;
  }

  if (isStaticAsset(url.pathname)) {
    event.respondWith(cacheFirst(request, SHELL_CACHE));
  }
});

function isStaticAsset(pathname) {
  return /\.(css|js|png|svg|webmanifest|woff|woff2|ttf|eot)$/i.test(pathname);
}

async function cacheUrl(cache, url) {
  const request = new Request(url, { cache: "reload" });
  const response = await fetch(request);
  if (response.ok || response.type === "opaque") {
    await cache.put(url, response);
  }
}

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request.url);
  // Skip opaque cached entries: an opaque (no-CORS) response taints the Web
  // Audio graph and yields silent playback, so re-fetch it CORS-clean instead.
  if (cached && cached.type !== "opaque") {
    return cached;
  }

  const response = await fetch(request);
  if (response.ok) {
    cache.put(request.url, response.clone());
  }
  return response;
}

async function networkFirst(request, fallbackUrl) {
  const cache = await caches.open(SHELL_CACHE);
  try {
    const response = await fetch(request);
    if (response.ok) {
      cache.put(request.url, response.clone());
    }
    return response;
  } catch (error) {
    const cached = await cache.match(request.url);
    if (cached) {
      return cached;
    }
    if (fallbackUrl) {
      return cache.match(fallbackUrl);
    }
    throw error;
  }
}

async function readJson(url) {
  const cache = await caches.open(SHELL_CACHE);
  let response;

  try {
    response = await fetch(url, { cache: "reload" });
    if (response.ok) {
      cache.put(url, response.clone());
    }
  } catch (error) {
    response = await cache.match(url);
  }

  if (!response || !response.ok) {
    throw new Error("Could not read " + url);
  }

  return response.json();
}

async function cacheAudioFromAlbums() {
  const albumIndex = await readJson(ALBUM_INDEX_URL);
  const urls = new Set([ALBUM_INDEX_URL]);

  for (const album of albumIndex) {
    urls.add(album.tracks);
    const tracks = await readJson(album.tracks);
    for (const track of tracks) {
      if (track.url) {
        urls.add(track.url);
      }
    }
  }

  const mediaCache = await caches.open(MEDIA_CACHE);
  const shellCache = await caches.open(SHELL_CACHE);
  let cached = 0;
  let failed = 0;

  for (const url of urls) {
    const targetCache = url.endsWith(".mp3") ? mediaCache : shellCache;
    const existing = await targetCache.match(url);
    if (existing) {
      cached += 1;
      continue;
    }

    try {
      const response = await fetch(url, { cache: "reload" });
      if (!response.ok) {
        failed += 1;
        continue;
      }
      await targetCache.put(url, response);
      cached += 1;
    } catch (error) {
      failed += 1;
    }
  }

  return { cached, failed };
}

async function handleRangeRequest(request) {
  const cached = await caches.match(request.url);

  // An opaque cached body is unreadable (arrayBuffer() throws) and would also
  // taint Web Audio, so go to the network (CORS mode) instead.
  if (!cached || cached.type === "opaque") {
    return fetch(request);
  }

  const range = request.headers.get("range");
  const match = /^bytes=(\d*)-(\d*)$/.exec(range || "");

  if (!match) {
    return cached;
  }

  const buffer = await cached.arrayBuffer();
  const size = buffer.byteLength;
  const start = match[1] ? Number(match[1]) : 0;
  const end = match[2] ? Number(match[2]) : size - 1;
  const safeStart = Math.min(Math.max(start, 0), size - 1);
  const safeEnd = Math.min(Math.max(end, safeStart), size - 1);
  const chunk = buffer.slice(safeStart, safeEnd + 1);
  const headers = new Headers(cached.headers);

  headers.set("Accept-Ranges", "bytes");
  headers.set("Content-Length", String(chunk.byteLength));
  headers.set("Content-Range", "bytes " + safeStart + "-" + safeEnd + "/" + size);
  headers.set("Content-Type", cached.headers.get("Content-Type") || "audio/mpeg");

  return new Response(chunk, {
    status: 206,
    statusText: "Partial Content",
    headers
  });
}
