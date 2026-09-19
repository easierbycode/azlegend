# AZLegendGolden

Fresh 2.3.3 cassette player for AZLegendGolden's tracks.

## Development

```sh
deno install
deno task dev
```

## Production build

```sh
deno task build
deno task start
```

The app is configured for Deno Deploy with the Fresh framework preset in
`deno.json`. Static assets are served from `static/`; existing public URLs such
as `/public/music/albums.json` are preserved.

## Adding songs remotely

`/add` takes the URL of an audio file and adds it to an **Added** album that
shows up in the catalog, in the web player, and on the watch. Nothing is
redeployed: the list lives in Deno KV and `/public/music/albums.json` is now
served by a route that merges it with the seed catalog in `data/albums.json`.

Two things have to be set up once on Deno Deploy before adding works:

1. **Provision a Deno KV database and assign it to the app** (Deploy dashboard →
   Databases → Provision → Deno KV → assign to `azlegend`). Until then the site
   still serves the seed catalog normally — the Added album is simply omitted.
2. **Set `ADD_SONG_SECRET`** as a secret env var. The endpoint fails closed:
   with no secret configured it refuses every write with 503.

```sh
openssl rand -base64 24      # generate the secret
```

The page asks for that secret once and keeps it in `localStorage`. From the
command line:

```sh
curl -X POST https://azlegend.easierbycode.deno.net/api/songs \
  -H 'Content-Type: application/json' \
  -H "X-Add-Token: $ADD_SONG_SECRET" \
  -d '{"url":"https://example.com/song.mp3","title":"Optional title"}'
```

Submitted URLs must be `https`, must not carry credentials, must not resolve to
a private or link-local host (re-checked after redirects), and are checked with
a `HEAD` request for an audio content type and a size under 60 MB. The file name
is always derived server-side and made unique, never taken from the client.

`DELETE /api/songs?url=...` removes one, with the same token.

## Watch app

`wear/` contains a standalone Wear OS player (Pixel Watch 5) that downloads the
same catalog for offline listening. See [wear/README.md](wear/README.md) for
building and sideloading it. It picks up remotely-added songs through the
catalog it already syncs, so adding one needs no new app build.
