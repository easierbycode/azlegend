import { define } from "../../../utils.ts";
import { listAddedOrEmpty } from "../../../added.ts";

/**
 * Track list for the "Added" album, in the same shape as the seed albums'
 * JSON files.
 *
 * This must answer 200 with a JSON array in every failure mode. The watch's
 * `CatalogRepository.refresh()` walks every album in the index and gives up on
 * the first bad response, so a 404 or a 500 here would break the whole
 * "Sync catalog" button, not just this album.
 */
export const handler = define.handlers({
  async GET() {
    const tracks = await listAddedOrEmpty();
    return Response.json(tracks, {
      headers: {
        "cache-control": "no-cache, no-store, max-age=0, must-revalidate",
      },
    });
  },
});
