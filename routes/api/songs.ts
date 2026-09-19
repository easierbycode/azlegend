import { define } from "../../utils.ts";
import {
  type AddedTrack,
  addTrack,
  listAdded,
  removeTrack,
  withinRateLimit,
} from "../../added.ts";
import {
  checkUrl,
  cleanTitle,
  fileNameForUrl,
  fileNameFromUrl,
  isAudioContentType,
  MAX_BYTES,
  MAX_ENTRIES,
  REJECTION_MESSAGES,
  titleFromFileName,
  uniqueFileName,
} from "../../songurl.ts";

const NO_STORE = { "cache-control": "no-store" } as const;

const RATE_LIMIT = 10;
const RATE_WINDOW_MS = 60_000;
const HEAD_TIMEOUT_MS = 5_000;

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: NO_STORE });
}

/** Constant-time compare, so the token check leaks no length or prefix. */
function secretsMatch(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/**
 * Fails closed: with no secret configured the endpoint refuses to write at all,
 * rather than standing open on a public domain.
 */
function authorize(req: Request): Response | null {
  const secret = Deno.env.get("ADD_SONG_SECRET");
  if (!secret) {
    return json({
      error:
        "This site has no ADD_SONG_SECRET configured, so adding songs is disabled.",
    }, 503);
  }
  const offered = req.headers.get("x-add-token") ?? "";
  if (!secretsMatch(offered, secret)) {
    return json({ error: "Wrong or missing token." }, 401);
  }
  return null;
}

function clientIp(req: Request): string {
  const forwarded = req.headers.get("x-forwarded-for");
  return forwarded?.split(",")[0].trim() || "unknown";
}

/**
 * Checks the URL really points at audio before it is stored. Redirects are
 * followed, so the final host is re-vetted: an open redirector would otherwise
 * walk straight past the private-host check.
 */
async function inspect(
  url: URL,
  origin: string,
): Promise<
  { ok: true; finalUrl: URL; cors: boolean } | { ok: false; error: string }
> {
  let response: Response;
  try {
    response = await fetch(url, {
      method: "HEAD",
      redirect: "follow",
      // Sent so the response reveals whether the host allows cross-origin playback.
      headers: { origin },
      signal: AbortSignal.timeout(HEAD_TIMEOUT_MS),
    });
  } catch {
    return { ok: false, error: "Could not reach that URL." };
  }
  // A HEAD body is empty, but an undrained stream still holds a connection.
  await response.body?.cancel();

  if (!response.ok) {
    return { ok: false, error: `That URL answered HTTP ${response.status}.` };
  }

  const finalUrl = new URL(response.url || url.href);
  const recheck = checkUrl(finalUrl.href);
  if ("reason" in recheck) {
    return { ok: false, error: "That URL redirects somewhere unreachable." };
  }

  const contentType = response.headers.get("content-type");
  // A 405 on HEAD is common enough that a missing type alone is not a reject;
  // an explicit *non-audio* type is.
  if (contentType && !isAudioContentType(contentType)) {
    return { ok: false, error: `That URL serves ${contentType}, not audio.` };
  }

  const length = Number(response.headers.get("content-length") ?? "0");
  if (length > MAX_BYTES) {
    const mb = Math.round(length / (1024 * 1024));
    return { ok: false, error: `That file is ${mb} MB; the limit is 60 MB.` };
  }

  // The watch fetches with a plain HttpURLConnection, but the web player's
  // <audio crossOrigin="anonymous"> turns a missing header into a fatal media
  // error. Recorded rather than rejected: the song is still perfectly playable
  // on the watch, and a host can start sending the header at any time.
  const allowOrigin = response.headers.get("access-control-allow-origin");
  const cors = allowOrigin === "*" || allowOrigin === origin;

  return { ok: true, finalUrl: recheck.url, cors };
}

export const handler = define.handlers({
  /** Public: the same list the catalog serves, for the add page to render. */
  async GET() {
    try {
      return json(await listAdded());
    } catch {
      return json({ error: "Song storage is unavailable." }, 503);
    }
  },

  async POST(ctx) {
    const denied = authorize(ctx.req);
    if (denied) return denied;

    let body: { url?: string; title?: string };
    try {
      body = await ctx.req.json();
    } catch {
      return json({ error: "Expected a JSON body." }, 400);
    }

    const checked = checkUrl(body.url ?? "");
    if ("reason" in checked) {
      return json({ error: REJECTION_MESSAGES[checked.reason] }, 400);
    }

    let existing: AddedTrack[];
    try {
      if (
        !await withinRateLimit(clientIp(ctx.req), RATE_LIMIT, RATE_WINDOW_MS)
      ) {
        return json({ error: "Too many songs at once. Wait a minute." }, 429);
      }
      existing = await listAdded();
    } catch {
      return json({ error: "Song storage is unavailable." }, 503);
    }

    if (existing.length >= MAX_ENTRIES) {
      return json({
        error: `The list is full at ${MAX_ENTRIES} songs. Remove one first.`,
      }, 409);
    }

    const inspected = await inspect(checked.url, new URL(ctx.req.url).origin);
    if (!inspected.ok) return json({ error: inspected.error }, 400);

    // Store what the server will actually serve: the post-redirect URL.
    const url = inspected.finalUrl.href;
    const already = existing.find((track) => track.url === url);
    if (already) return json({ track: already, added: false });

    // The digest makes the name a function of the URL, so re-adding a different
    // song with the same basename can never resolve to a file already on a watch.
    // uniqueFileName stays as a belt-and-braces guard against a digest collision.
    const file = uniqueFileName(
      await fileNameForUrl(inspected.finalUrl),
      existing.map((track) => track.file),
    );
    const track: AddedTrack = {
      url,
      file,
      title: cleanTitle(body.title) ||
        titleFromFileName(fileNameFromUrl(inspected.finalUrl)),
      cors: inspected.cors,
      created: new Date().toISOString(),
    };

    try {
      await addTrack(track);
    } catch {
      return json({ error: "Song storage is unavailable." }, 503);
    }
    return json({ track, added: true }, 201);
  },

  async DELETE(ctx) {
    const denied = authorize(ctx.req);
    if (denied) return denied;

    const url = new URL(ctx.req.url).searchParams.get("url");
    if (!url) return json({ error: "Pass ?url= the song to remove." }, 400);

    try {
      const removed = await removeTrack(url);
      return removed
        ? json({ removed: true })
        : json({ error: "No song with that URL." }, 404);
    } catch {
      return json({ error: "Song storage is unavailable." }, 503);
    }
  },
});
