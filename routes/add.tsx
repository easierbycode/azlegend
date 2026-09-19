import { define } from "../utils.ts";

export default define.page(function AddSong() {
  return (
    <>
      <div class="add-shell">
        <h1>Add a song</h1>
        <p class="lede">
          Paste the address of an audio file. It joins the{" "}
          <strong>Added</strong>{" "}
          album on the site and on the watch the next time it syncs.
        </p>

        <form id="addForm" class="add-form">
          <label>
            Song URL
            <input
              id="urlField"
              name="url"
              type="url"
              required
              placeholder="https://example.com/song.mp3"
              autocomplete="off"
              autocapitalize="off"
              spellcheck={false}
            />
          </label>

          <label>
            Title (optional)
            <input
              id="titleField"
              name="title"
              type="text"
              placeholder="Taken from the file name"
              autocomplete="off"
            />
          </label>

          <label>
            Token
            <input
              id="tokenField"
              name="token"
              type="password"
              required
              autocomplete="current-password"
            />
          </label>
          <p class="add-note">Remembered on this device only.</p>

          <button id="submitButton" type="submit">Add song</button>
        </form>

        <p id="addStatus" class="add-status" role="status" aria-live="polite">
        </p>

        <ul id="addedList" class="added-list"></ul>
      </div>
      <script defer src="/public/app/scripts/add-song.js"></script>
    </>
  );
});
