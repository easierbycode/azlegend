/**
 * Songs added remotely, stored in Deno KV.
 *
 * Deno Deploy's filesystem is read-only at runtime, so the list cannot live in
 * `static/`. Every read here is written to degrade rather than throw: the watch's
 * `CatalogRepository.refresh()` aborts the whole catalog sync on the first bad
 * response, so a KV hiccup must cost the user the *added* album, never the four
 * seed albums.
 */

/** One added song, shaped exactly like an entry of the site's track-list JSON. */
export interface AddedTrack {
  /** Absolute https URL of the audio file. */
  url: string;
  /** File name for the local copy on a client. Always derived server-side. */
  file: string;
  title: string;
  /**
   * Whether the host allows cross-origin playback. False means the watch can
   * play it but the website cannot; see the CORS note in routes/api/songs.ts.
   */
  cors?: boolean;
  created: string;
}

/** Album that appears in the catalog while at least one song has been added. */
export const ADDED_ALBUM = {
  id: "added",
  title: "Added",
  tracks: "/public/music/added.json",
} as const;

const PREFIX = ["added", "tracks"];

/** Deno KV is only reachable on Deploy once a database is assigned to the app. */
let kvPromise: Promise<Deno.Kv> | undefined;

function openKv(): Promise<Deno.Kv> {
  // Cached, but never cached as a *rejected* promise: a failed open at cold
  // start would otherwise poison every later request for the isolate's life.
  if (!kvPromise) {
    kvPromise = Deno.openKv().catch((error) => {
      kvPromise = undefined;
      throw error;
    });
  }
  return kvPromise;
}

/** Newest last, matching the order tracks were added. */
export async function listAdded(): Promise<AddedTrack[]> {
  const kv = await openKv();
  const tracks: AddedTrack[] = [];
  for await (const entry of kv.list<AddedTrack>({ prefix: PREFIX })) {
    tracks.push(entry.value);
  }
  return tracks.sort((a, b) => a.created.localeCompare(b.created));
}

/** [listAdded] with the failure already handled: an unreachable KV reads as empty. */
export async function listAddedOrEmpty(): Promise<AddedTrack[]> {
  try {
    return await listAdded();
  } catch (error) {
    console.error("added: KV unavailable, serving an empty list", error);
    return [];
  }
}

export async function addTrack(track: AddedTrack): Promise<void> {
  const kv = await openKv();
  await kv.set([...PREFIX, track.created, track.url], track);
}

/** Returns true when a song with that exact URL was found and removed. */
export async function removeTrack(url: string): Promise<boolean> {
  const kv = await openKv();
  for await (const entry of kv.list<AddedTrack>({ prefix: PREFIX })) {
    if (entry.value.url === url) {
      await kv.delete(entry.key);
      return true;
    }
  }
  return false;
}

/**
 * Fixed-window rate limit. Counters carry `expireIn`, so the window keys clean
 * themselves up; that rules out the commutative `sum` mutation, whose type has
 * no `expireIn`. The compare-and-set below can therefore lose an increment when
 * two requests race — it under-counts, never over-counts, which for one
 * person's site is the right way round.
 */
export async function withinRateLimit(
  ip: string,
  limit: number,
  windowMs: number,
): Promise<boolean> {
  const kv = await openKv();
  const key = ["added", "rate", Math.floor(Date.now() / windowMs), ip];
  const current = await kv.get<number>(key);
  const count = (current.value ?? 0) + 1;
  if (count > limit) return false;
  // A lost CAS means a concurrent request already counted; let this one through.
  await kv.atomic()
    .check(current)
    .set(key, count, { expireIn: windowMs * 2 })
    .commit();
  return true;
}
