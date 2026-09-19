// Drives /add: posts a song URL to /api/songs and lists what has been added.
// Every value from the server is written with textContent, never innerHTML.
(function () {
  "use strict";

  var TOKEN_KEY = "azlegend.addToken";

  var form = document.getElementById("addForm");
  var urlField = document.getElementById("urlField");
  var titleField = document.getElementById("titleField");
  var tokenField = document.getElementById("tokenField");
  var submitButton = document.getElementById("submitButton");
  var status = document.getElementById("addStatus");
  var list = document.getElementById("addedList");

  function setStatus(message, kind) {
    status.textContent = message || "";
    status.className = "add-status" + (message ? " " + kind : "");
  }

  function storedToken() {
    try {
      return window.localStorage.getItem(TOKEN_KEY) || "";
    } catch (err) {
      return "";
    }
  }

  function rememberToken(token) {
    try {
      window.localStorage.setItem(TOKEN_KEY, token);
    } catch (err) {
      // Private browsing: the field just has to be retyped next time.
    }
  }

  function errorFrom(response, body) {
    if (body && body.error) return body.error;
    if (response.status === 401) return "Wrong token.";
    return "The server answered " + response.status + ".";
  }

  function removeSong(url) {
    return fetch("/api/songs?url=" + encodeURIComponent(url), {
      method: "DELETE",
      headers: { "X-Add-Token": tokenField.value.trim() }
    }).then(function (response) {
      return response.json().catch(function () {
        return null;
      }).then(function (body) {
        if (!response.ok) throw new Error(errorFrom(response, body));
        setStatus("Removed.", "ok");
        return loadAdded();
      });
    }).catch(function (err) {
      setStatus(err.message, "err");
    });
  }

  function renderAdded(tracks) {
    list.textContent = "";
    tracks.forEach(function (track) {
      var item = document.createElement("li");

      var text = document.createElement("div");
      text.className = "t";
      var title = document.createElement("strong");
      title.textContent = track.title || track.file;
      var url = document.createElement("span");
      url.textContent = track.cors === false
        ? "Watch only \u00b7 " + track.url
        : track.url;
      text.appendChild(title);
      text.appendChild(url);

      var remove = document.createElement("button");
      remove.type = "button";
      remove.textContent = "Remove";
      remove.addEventListener("click", function () {
        removeSong(track.url);
      });

      item.appendChild(text);
      item.appendChild(remove);
      list.appendChild(item);
    });
  }

  function loadAdded() {
    return fetch("/api/songs", { headers: { "Accept": "application/json" } })
      .then(function (response) {
        if (!response.ok) throw new Error("could not list");
        return response.json();
      })
      .then(renderAdded)
      .catch(function () {
        list.textContent = "";
      });
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    var token = tokenField.value.trim();
    var url = urlField.value.trim();
    if (!token || !url) return;

    submitButton.disabled = true;
    setStatus("Checking the file…", "ok");

    fetch("/api/songs", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Add-Token": token
      },
      body: JSON.stringify({ url: url, title: titleField.value.trim() })
    }).then(function (response) {
      return response.json().catch(function () {
        return null;
      }).then(function (body) {
        if (!response.ok) throw new Error(errorFrom(response, body));
        rememberToken(token);
        if (body && body.added === false) {
          setStatus("That song was already added.", "ok");
        } else {
          var noCors = body && body.track && body.track.cors === false;
          setStatus(
            noCors
              ? "Added. That host blocks browser playback, so it plays on the watch but not here."
              : "Added. Sync the watch to pick it up.",
            "ok"
          );
          urlField.value = "";
          titleField.value = "";
        }
        return loadAdded();
      });
    }).catch(function (err) {
      setStatus(err.message, "err");
    }).finally(function () {
      submitButton.disabled = false;
    });
  });

  tokenField.value = storedToken();
  loadAdded();
})();
