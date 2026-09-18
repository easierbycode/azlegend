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

## Watch app

`wear/` contains a standalone Wear OS player (Pixel Watch 5) that downloads the same catalog for
offline listening. See [wear/README.md](wear/README.md) for building and sideloading it.
