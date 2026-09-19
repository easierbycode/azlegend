/**
 * Validation for song URLs submitted to `/api/songs`. Pure functions, no KV and
 * no network, so `deno test songurl_test.ts` covers the whole decision table.
 */

export const MAX_URL_LENGTH = 512;
export const MAX_TITLE_LENGTH = 120;
export const MAX_ENTRIES = 200;
/** The E2E track is 40.4 MB; a watch has no size limit of its own, so cap here. */
export const MAX_BYTES = 60 * 1024 * 1024;

const DENIED_HOSTS = new Set([
  "localhost",
  "0.0.0.0",
  "metadata.google.internal",
]);

const DENIED_SUFFIXES = [".localhost", ".internal", ".local"];

/**
 * Hosts a public site must never be talked into fetching: loopback, the RFC1918
 * ranges, and the link-local address that serves cloud instance credentials.
 */
export function isPrivateHost(hostname: string): boolean {
  // A trailing dot makes an FQDN that resolvers treat identically ("localhost." is
  // localhost), but that neither the deny set nor the suffix check would match.
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, "").replace(
    /\.$/,
    "",
  );
  if (DENIED_HOSTS.has(host)) return true;
  if (DENIED_SUFFIXES.some((suffix) => host.endsWith(suffix))) return true;

  const v4 = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    if ([a, b, Number(v4[3]), Number(v4[4])].some((n) => n > 255)) return true;
    if (a === 0 || a === 10 || a === 127) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 169 && b === 254) return true;
    if (a >= 224) return true;
    return false;
  }

  // IPv6 loopback, unique-local (fc00::/7) and link-local (fe80::/10).
  if (host === "::1" || host === "::") return true;
  return /^f[cd][0-9a-f]{2}:/.test(host) || /^fe[89ab][0-9a-f]:/.test(host);
}

export type UrlRejection =
  | "empty"
  | "too-long"
  | "unparseable"
  | "not-https"
  | "has-credentials"
  | "private-host";

/** Parses and vets a submitted URL. Returns the rejection reason, or the URL. */
export function checkUrl(raw: string): { url: URL } | { reason: UrlRejection } {
  const trimmed = raw.trim();
  if (!trimmed) return { reason: "empty" };
  if (trimmed.length > MAX_URL_LENGTH) return { reason: "too-long" };

  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return { reason: "unparseable" };
  }
  if (url.protocol !== "https:") return { reason: "not-https" };
  if (url.username || url.password) return { reason: "has-credentials" };
  if (isPrivateHost(url.hostname)) return { reason: "private-host" };
  return { url };
}

export const REJECTION_MESSAGES: Record<UrlRejection, string> = {
  "empty": "Paste the address of an audio file.",
  "too-long": `That URL is longer than ${MAX_URL_LENGTH} characters.`,
  "unparseable": "That is not a URL.",
  "not-https": "Only https:// addresses are accepted.",
  "has-credentials": "Remove the username and password from the URL.",
  "private-host": "That host is not reachable from the public internet.",
};

/**
 * Local file name for a track, derived from the URL path. Never trust a
 * client-supplied name: it ends up as a path segment on the watch's disk.
 */
export function fileNameFromUrl(url: URL): string {
  const last = url.pathname.split("/").filter(Boolean).pop() ?? "";
  const decoded = (() => {
    try {
      return decodeURIComponent(last);
    } catch {
      return last;
    }
  })();
  const cleaned = decoded.replace(/[^A-Za-z0-9._-]+/g, "_").replace(/^\.+/, "");
  if (!cleaned) return "track.mp3";
  return cleaned.length > 120 ? cleaned.slice(-120) : cleaned;
}

/**
 * Strips control characters, bidi/zero-width formatting characters and angle
 * brackets, then collapses whitespace.
 *
 * Deliberately not a character-class regex: `deno fmt` rewrites `\uXXXX`
 * escapes inside one into the literal (invisible) characters, which is both
 * unreadable and easy to break on the next edit.
 */
export function cleanTitle(raw: string | undefined): string {
  if (!raw) return "";
  let stripped = "";
  for (const char of raw) {
    const code = char.codePointAt(0) ?? 0;
    const isControl = code < 0x20 || (code >= 0x7f && code <= 0x9f);
    const isFormatting = code >= 0x200b && code <= 0x200f;
    if (isControl || isFormatting) continue;
    if (char === "<" || char === ">") continue;
    stripped += char;
  }
  const collapsed = stripped.replace(/\s+/g, " ").trim();
  return collapsed.slice(0, MAX_TITLE_LENGTH);
}

/**
 * Appends a short digest of the URL to the file name, so the name is a function of
 * the URL and nothing else.
 *
 * Without this, a track's identity on the watch is (albumId, fileName) with the URL
 * playing no part: re-uploading a song under a new URL with the same basename would
 * resolve to the file already on disk, and the watch would keep playing the old
 * audio forever without ever re-downloading.
 */
export async function fileNameForUrl(url: URL): Promise<string> {
  const base = fileNameFromUrl(url);
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(url.href),
  );
  const hex = Array.from(new Uint8Array(digest).slice(0, 4))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  const dot = base.lastIndexOf(".");
  const stem = dot > 0 ? base.slice(0, dot) : base;
  const ext = dot > 0 ? base.slice(dot) : "";
  return `${stem}-${hex}${ext}`;
}

/**
 * Makes a file name unique against [taken] by suffixing the stem: "a.mp3",
 * then "a-2.mp3", "a-3.mp3".
 *
 * This matters more than it looks. The watch builds `Track.id` as
 * "<albumId>/<file>", and its album list is keyed by that id, so two songs
 * whose URLs end in the same basename would collide into one id and crash the
 * track list. It is also the local file name, so a collision would have the two
 * downloads overwrite each other.
 */
export function uniqueFileName(name: string, taken: Iterable<string>): string {
  const used = new Set(taken);
  if (!used.has(name)) return name;
  const dot = name.lastIndexOf(".");
  const stem = dot > 0 ? name.slice(0, dot) : name;
  const ext = dot > 0 ? name.slice(dot) : "";
  for (let n = 2;; n++) {
    const candidate = `${stem}-${n}${ext}`;
    if (!used.has(candidate)) return candidate;
  }
}

/** "Sol_Invicto_-_Initium_ft_Zach_Hill.mp3" -> "Sol Invicto - Initium ft Zach Hill". */
export function titleFromFileName(file: string): string {
  const stem = file.replace(/\.[A-Za-z0-9]{1,5}$/, "");
  const words = stem.replace(/[_+]+/g, " ").replace(/\s+/g, " ").trim();
  return words || file;
}

/** A content type that plausibly is audio; some CDNs serve mp3 as octet-stream. */
export function isAudioContentType(contentType: string | null): boolean {
  if (!contentType) return false;
  const type = contentType.split(";")[0].trim().toLowerCase();
  return type.startsWith("audio/") || type === "application/octet-stream" ||
    type === "video/mp4";
}
