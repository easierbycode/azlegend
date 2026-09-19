import { define } from "../../../utils.ts";
import seedAlbums from "../../../data/albums.json" with { type: "json" };
import { ADDED_ALBUM, listAddedOrEmpty } from "../../../added.ts";

/**
 * The album index, served dynamically so remotely-added songs appear without a
 * redeploy. The seed list lives in `data/` rather than `static/` on purpose —
 * see data/README.md.
 *
 * The "Added" album is omitted while the list is empty: the watch shows every
 * album in the index, and an empty one is just a dead row with a Download
 * button that does nothing.
 */
export const handler = define.handlers({
  async GET() {
    const added = await listAddedOrEmpty();
    const albums = added.length > 0 ? [...seedAlbums, ADDED_ALBUM] : seedAlbums;
    return Response.json(albums, {
      headers: {
        // Matches what the static file used to send, so Deploy's CDN keeps
        // bypassing and the watch never syncs a stale index.
        "cache-control": "no-cache, no-store, max-age=0, must-revalidate",
      },
    });
  },
});
