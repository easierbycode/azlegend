# data/

Server-side data that is **not** served as a static file.

`albums.json` is the seed catalog. It deliberately lives here rather than in
`static/public/music/`, because `main.ts` registers `staticFiles()` before
`fsRoutes()`: a file at `static/public/music/albums.json` would be served
directly and would silently shadow `routes/public/music/albums.json.ts`, which
merges this seed list with the songs added at runtime. Do not move it back.
