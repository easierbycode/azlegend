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
