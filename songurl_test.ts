import { assert, assertEquals, assertFalse } from "jsr:@std/assert@1";
import {
  checkUrl,
  cleanTitle,
  fileNameForUrl,
  fileNameFromUrl,
  isAudioContentType,
  isPrivateHost,
  titleFromFileName,
  uniqueFileName,
} from "./songurl.ts";

const E2E_URL =
  "https://res.cloudinary.com/dujip8nqb/video/upload/v1770804101/Sol_Invicto_-_Initium_ft_Zach_Hill_Death_Grips_cdav0i.mp3";

Deno.test("the end-to-end Cloudinary URL is accepted verbatim", () => {
  const result = checkUrl(E2E_URL);
  assert("url" in result);
  assertEquals(result.url.href, E2E_URL);
});

Deno.test("only https is accepted", () => {
  for (const url of ["http://example.com/a.mp3", "file:///etc/passwd"]) {
    const result = checkUrl(url);
    assert("reason" in result, `${url} should be rejected`);
  }
  assertEquals(
    (checkUrl("http://example.com/a.mp3") as { reason: string }).reason,
    "not-https",
  );
});

Deno.test("credentials in the URL are rejected", () => {
  const result = checkUrl("https://user:pw@example.com/a.mp3");
  assert("reason" in result);
  assertEquals(result.reason, "has-credentials");
});

Deno.test("private and loopback hosts are rejected", () => {
  const blocked = [
    "localhost",
    "127.0.0.1",
    "10.0.0.5",
    "172.16.3.1",
    "172.31.255.255",
    "192.168.1.1",
    "169.254.169.254",
    "0.0.0.0",
    "::1",
    "fd00::1",
    "fe80::1",
    "metadata.google.internal",
    "router.local",
    // A trailing dot is an FQDN that resolves to exactly the same host.
    "localhost.",
    "metadata.google.internal.",
    "router.local.",
    "127.0.0.1.",
  ];
  for (const host of blocked) {
    assert(isPrivateHost(host), `${host} should be private`);
  }

  const allowed = [
    "res.cloudinary.com",
    "172.32.0.1",
    "8.8.8.8",
    "example.com",
  ];
  for (const host of allowed) {
    assertFalse(isPrivateHost(host), `${host} should be public`);
  }
});

Deno.test("empty and oversized URLs are rejected", () => {
  assertEquals((checkUrl("   ") as { reason: string }).reason, "empty");
  const long = "https://example.com/" + "a".repeat(600) + ".mp3";
  assertEquals((checkUrl(long) as { reason: string }).reason, "too-long");
});

Deno.test("file names come from the URL path and stay path-safe", () => {
  assertEquals(
    fileNameFromUrl(new URL(E2E_URL)),
    "Sol_Invicto_-_Initium_ft_Zach_Hill_Death_Grips_cdav0i.mp3",
  );
  assertEquals(
    fileNameFromUrl(new URL("https://x.com/a/b/My%20Song.mp3?dl=1#x")),
    "My_Song.mp3",
  );
  assertEquals(fileNameFromUrl(new URL("https://x.com/")), "track.mp3");
});

Deno.test("file names can never traverse out of their directory", () => {
  const hostile = [
    "https://x.com/a/..%2f..%2fetc",
    "https://x.com/%2e%2e%2f%2e%2e%2fpasswd",
    "https://x.com/a/%00evil.mp3",
    "https://x.com/a/....//b.mp3",
  ];
  for (const raw of hostile) {
    const name = fileNameFromUrl(new URL(raw));
    assertFalse(name.includes("/"), `${name} has a separator`);
    assertFalse(name.includes("\\"), `${name} has a separator`);
    assertFalse(name.startsWith("."), `${name} starts with a dot`);
    assert(/^[A-Za-z0-9._-]+$/.test(name), `${name} has unexpected characters`);
  }
});

Deno.test("titles are derived from the file name when none is given", () => {
  assertEquals(
    titleFromFileName(
      "Sol_Invicto_-_Initium_ft_Zach_Hill_Death_Grips_cdav0i.mp3",
    ),
    "Sol Invicto - Initium ft Zach Hill Death Grips cdav0i",
  );
  assertEquals(titleFromFileName("track_05.mp3"), "track 05");
});

Deno.test("titles are stripped of control characters and angle brackets", () => {
  const noisy = "  a" + String.fromCharCode(0) + "b  <script>  ";
  assertEquals(cleanTitle(noisy), "ab script");
  assertEquals(cleanTitle(undefined), "");
  assertEquals(cleanTitle("x".repeat(200)).length, 120);
});

Deno.test("content types that count as audio", () => {
  assert(isAudioContentType("audio/mpeg"));
  assert(isAudioContentType("audio/mpeg; charset=utf-8"));
  assert(isAudioContentType("application/octet-stream"));
  assertFalse(isAudioContentType("text/html"));
  assertFalse(isAudioContentType(null));
});

Deno.test("file names are made unique so track ids never collide", () => {
  assertEquals(uniqueFileName("a.mp3", []), "a.mp3");
  assertEquals(uniqueFileName("a.mp3", ["a.mp3"]), "a-2.mp3");
  assertEquals(uniqueFileName("a.mp3", ["a.mp3", "a-2.mp3"]), "a-3.mp3");
  assertEquals(uniqueFileName("noext", ["noext"]), "noext-2");
});

Deno.test("trailing-dot hosts cannot smuggle past the deny list", async () => {
  for (
    const raw of [
      "https://localhost./a.mp3",
      "https://metadata.google.internal./a",
    ]
  ) {
    const result = checkUrl(raw);
    assert("reason" in result, `${raw} should be rejected`);
    assertEquals(result.reason, "private-host");
  }
  await Promise.resolve();
});

Deno.test("file names are a function of the URL, not of list position", async () => {
  const a = new URL("https://cdn.example.com/v1/beat.mp3");
  const b = new URL("https://cdn.example.com/v2/beat.mp3");

  const nameA = await fileNameForUrl(a);
  const nameB = await fileNameForUrl(b);

  // Same basename, different URL: the names must differ, or a watch that already
  // downloaded the first would keep playing it instead of fetching the second.
  assertEquals(nameA.startsWith("beat-"), true);
  assert(nameA !== nameB, `${nameA} should differ from ${nameB}`);
  assert(/^beat-[0-9a-f]{8}\.mp3$/.test(nameA), `${nameA} has the wrong shape`);

  // And it is stable: the same URL always yields the same name.
  assertEquals(await fileNameForUrl(new URL(a.href)), nameA);
});
