import { define } from "../utils.ts";

export default define.page(function App({ Component }) {
  return (
    <html lang="en">
      <head>
        <meta charSet="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <meta name="theme-color" content="#050508" />
        <title>AZLegendGolden</title>
        <link
          rel="icon"
          href="/public/app/images/icon.svg"
          type="image/svg+xml"
        />
        <link rel="manifest" href="/manifest.webmanifest" />
        <link
          rel="stylesheet"
          href="/public/libraries/styles/cassette-tape-ui.css"
        />
        <link
          rel="stylesheet"
          href="/public/libraries/styles/cassette-tape-ui-blur.css"
        />
        <link rel="stylesheet" href="/public/app/styles/styles.css" />
      </head>
      <body>
        <Component />
        <script defer src="/public/app/scripts/audio-visualizer.js">
        </script>
        <script defer src="/public/app/scripts/app.js"></script>
      </body>
    </html>
  );
});
