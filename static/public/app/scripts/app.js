(function () {
  "use strict";

  var ALBUM_INDEX_URL = "/public/music/albums.json";

  var state = {
    albums: [],
    selectedAlbumIndex: 0,
    currentAlbumIndex: -1,
    currentTrackIndex: -1,
    seekDragging: false
  };

  var elements = {
    albumTabs: document.getElementById("albumTabs"),
    trackList: document.getElementById("trackList"),
    audio: document.getElementById("audioPlayer"),
    playerStatus: document.getElementById("playerStatus"),
    offlineButton: document.getElementById("offlineButton"),
    offlineStatus: document.getElementById("offlineStatus"),
    albumTitle: document.getElementById("albumTitle"),
    trackTitle: document.getElementById("trackTitle"),
    tapeLabel: document.getElementById("tapeLabel"),
    tapeStage: document.getElementById("tapeStage"),
    tapeShell: document.getElementById("tapeShell"),
    prevButton: document.getElementById("prevButton"),
    playButton: document.getElementById("playButton"),
    stopButton: document.getElementById("stopButton"),
    nextButton: document.getElementById("nextButton"),
    seekControl: document.getElementById("seekControl"),
    currentTime: document.getElementById("currentTime"),
    duration: document.getElementById("duration")
  };

  var visualizer = new window.AudioPartyVisualizer(document.getElementById("visualizer"), {
    assetBase: "/public/app/images/audio-party"
  });

  function titleize(value) {
    return String(value || "")
      .replace(/\.[^.]+$/, "")
      .replace(/[_-]+/g, " ")
      .replace(/\s+/g, " ")
      .trim()
      .split(" ")
      .map(function (part) {
        if (/^\d+$/.test(part)) {
          return part;
        }
        return part.charAt(0).toUpperCase() + part.slice(1).toLowerCase();
      })
      .join(" ");
  }

  function formatTime(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) {
      return "0:00";
    }

    var minutes = Math.floor(seconds / 60);
    var rest = Math.floor(seconds % 60);
    return minutes + ":" + String(rest).padStart(2, "0");
  }

  function normalizeTrack(track) {
    var url = track.url || "";
    var fileName = track.file || url.split("/").pop() || "";
    return {
      title: track.title || titleize(fileName),
      file: fileName,
      url: url
    };
  }

  function setStatus(message) {
    elements.playerStatus.textContent = message || "";
  }

  function setOfflineStatus(message) {
    elements.offlineStatus.textContent = message || "";
  }

  function currentAlbum() {
    return state.albums[state.currentAlbumIndex] || null;
  }

  function currentTrack() {
    var album = currentAlbum();
    if (!album) {
      return null;
    }
    return album.tracks[state.currentTrackIndex] || null;
  }

  function selectedAlbum() {
    return state.albums[state.selectedAlbumIndex] || null;
  }

  function setTapePlaying(isPlaying) {
    elements.tapeShell.classList.toggle("playing", isPlaying);
    elements.tapeShell.classList.toggle("stopped", !isPlaying);
    elements.playButton.textContent = isPlaying ? "Pause" : "Play";
  }

  function updateNowPlaying() {
    var album = currentAlbum() || selectedAlbum();
    var track = currentTrack();

    elements.albumTitle.textContent = album ? album.title : "Album";
    elements.trackTitle.textContent = track ? track.title : "No track selected";
    elements.tapeLabel.textContent = album ? album.title : "Golden";

    document.title = track ? track.title + " - Golden" : "Golden - AZ Legend";
    renderTrackList();
  }

  function updateProgress() {
    var audio = elements.audio;
    var duration = Number.isFinite(audio.duration) ? audio.duration : 0;

    if (!state.seekDragging) {
      elements.seekControl.value = duration ? Math.round((audio.currentTime / duration) * 1000) : 0;
    }

    elements.currentTime.textContent = formatTime(audio.currentTime);
    elements.duration.textContent = formatTime(duration);
  }

  function startPlayback(status) {
    var settled = false;
    var playPromise = elements.audio.play();
    var fallback = window.setTimeout(function () {
      if (settled) {
        return;
      }

      if (elements.audio.paused) {
        setTapePlaying(false);
        setStatus("Tap Play to start");
      } else {
        setTapePlaying(true);
        setStatus(status || "Playing");
      }
      renderTrackList();
    }, 1200);

    if (!playPromise || typeof playPromise.then !== "function") {
      window.clearTimeout(fallback);
      setTapePlaying(!elements.audio.paused);
      setStatus(elements.audio.paused ? "Tap Play to start" : (status || "Playing"));
      renderTrackList();
      return Promise.resolve();
    }

    return playPromise
      .then(function () {
        settled = true;
        window.clearTimeout(fallback);
        setTapePlaying(true);
        setStatus(status || "Playing");
        renderTrackList();
      })
      .catch(function () {
        settled = true;
        window.clearTimeout(fallback);
        setTapePlaying(false);
        setStatus("Tap Play to start");
        renderTrackList();
      });
  }

  function renderAlbumTabs() {
    elements.albumTabs.replaceChildren();

    state.albums.forEach(function (album, index) {
      var button = document.createElement("button");
      button.className = "album-tab";
      button.type = "button";
      button.id = "album-tab-" + album.id;
      button.setAttribute("role", "tab");
      button.setAttribute("aria-selected", index === state.selectedAlbumIndex ? "true" : "false");
      button.textContent = album.title;
      button.addEventListener("click", function () {
        selectAlbum(index);
      });
      elements.albumTabs.appendChild(button);
    });
  }

  function renderTrackList() {
    var album = selectedAlbum();
    elements.trackList.replaceChildren();

    if (!album) {
      return;
    }

    album.tracks.forEach(function (track, index) {
      var item = document.createElement("li");
      var button = document.createElement("button");
      var title = document.createElement("span");
      var badge = document.createElement("span");
      var isActive = state.currentAlbumIndex === state.selectedAlbumIndex && state.currentTrackIndex === index;

      button.className = "track-button" + (isActive ? " is-active" : "");
      button.type = "button";
      button.setAttribute("aria-current", isActive ? "true" : "false");

      title.className = "track-title";
      title.textContent = track.title;
      badge.className = "track-badge";
      badge.textContent = isActive && !elements.audio.paused ? "Playing" : "MP3";

      button.appendChild(title);
      button.appendChild(badge);
      button.addEventListener("click", function () {
        playTrack(state.selectedAlbumIndex, index);
      });

      item.appendChild(button);
      elements.trackList.appendChild(item);
    });
  }

  function selectAlbum(index) {
    state.selectedAlbumIndex = index;
    renderAlbumTabs();
    renderTrackList();

    var album = selectedAlbum();
    if (album && state.currentAlbumIndex < 0) {
      elements.albumTitle.textContent = album.title;
      elements.tapeLabel.textContent = album.title;
    }
  }

  function playTrack(albumIndex, trackIndex) {
    var album = state.albums[albumIndex];
    var track = album && album.tracks[trackIndex];
    if (!track) {
      return Promise.resolve();
    }

    state.currentAlbumIndex = albumIndex;
    state.currentTrackIndex = trackIndex;
    state.selectedAlbumIndex = albumIndex;
    renderAlbumTabs();
    updateNowPlaying();
    setStatus("Loading");

    if (elements.audio.getAttribute("src") !== track.url) {
      elements.audio.src = track.url;
      elements.audio.load();
    }

    return visualizer.connect(elements.audio)
      .then(function () {
        return startPlayback("Playing");
      });
  }

  function togglePlayback() {
    var track = currentTrack();

    if (!track) {
      var album = selectedAlbum();
      if (album && album.tracks.length) {
        return playTrack(state.selectedAlbumIndex, 0);
      }
      return Promise.resolve();
    }

    if (elements.audio.paused) {
      return visualizer.connect(elements.audio)
        .then(function () {
          return startPlayback("Playing");
        });
    }

    elements.audio.pause();
    return Promise.resolve();
  }

  function stopTrack() {
    elements.audio.pause();
    elements.audio.currentTime = 0;
    setTapePlaying(false);
    setStatus(currentTrack() ? "Stopped" : "Choose a song");
    updateProgress();
    renderTrackList();
  }

  function playNeighbor(direction) {
    var albumIndex = state.currentAlbumIndex >= 0 ? state.currentAlbumIndex : state.selectedAlbumIndex;
    var album = state.albums[albumIndex];
    if (!album || !album.tracks.length) {
      return Promise.resolve();
    }

    var trackIndex = state.currentTrackIndex;
    if (trackIndex < 0 || state.currentAlbumIndex !== albumIndex) {
      trackIndex = direction > 0 ? 0 : album.tracks.length - 1;
    } else {
      trackIndex = (trackIndex + direction + album.tracks.length) % album.tracks.length;
    }

    return playTrack(albumIndex, trackIndex);
  }

  function fitTape() {
    var width = elements.tapeStage.clientWidth;
    var scale = Math.min(1, width / 480);
    elements.tapeShell.style.transform = "scale(" + scale + ")";
    elements.tapeStage.style.height = Math.round(300 * scale) + "px";
  }

  function sendServiceWorkerMessage(message) {
    return navigator.serviceWorker.ready.then(function (registration) {
      var worker = registration.active || navigator.serviceWorker.controller;
      if (!worker) {
        throw new Error("No active service worker");
      }

      return new Promise(function (resolve, reject) {
        var channel = new MessageChannel();
        var timeout = window.setTimeout(function () {
          reject(new Error("Service worker timed out"));
        }, 120000);

        channel.port1.onmessage = function (event) {
          window.clearTimeout(timeout);
          if (event.data && event.data.error) {
            reject(new Error(event.data.error));
          } else {
            resolve(event.data || {});
          }
        };

        worker.postMessage(message, [channel.port2]);
      });
    });
  }

  function cacheOfflineAudio() {
    if (!("serviceWorker" in navigator) || window.location.protocol === "file:") {
      setOfflineStatus("Use http");
      return;
    }

    elements.offlineButton.disabled = true;
    setOfflineStatus("Saving");

    sendServiceWorkerMessage({ type: "CACHE_AUDIO" })
      .then(function (result) {
        var failed = result.failed || 0;
        var cached = result.cached || 0;
        setOfflineStatus(failed ? "Saved " + cached + ", " + failed + " failed" : "Saved " + cached);
      })
      .catch(function () {
        setOfflineStatus("Save failed");
      })
      .finally(function () {
        elements.offlineButton.disabled = false;
      });
  }

  function registerServiceWorker() {
    if (!("serviceWorker" in navigator) || window.location.protocol === "file:") {
      elements.offlineButton.disabled = true;
      setOfflineStatus("Use http");
      return;
    }

    navigator.serviceWorker.register("/service-worker.js")
      .then(function () {
        setOfflineStatus(navigator.onLine ? "Ready" : "Offline");
      })
      .catch(function () {
        elements.offlineButton.disabled = true;
        setOfflineStatus("No PWA");
      });
  }

  function bindEvents() {
    elements.playButton.addEventListener("click", togglePlayback);
    elements.stopButton.addEventListener("click", stopTrack);
    elements.prevButton.addEventListener("click", function () {
      if (elements.audio.currentTime > 4) {
        elements.audio.currentTime = 0;
        updateProgress();
        return;
      }
      playNeighbor(-1);
    });
    elements.nextButton.addEventListener("click", function () {
      playNeighbor(1);
    });
    elements.offlineButton.addEventListener("click", cacheOfflineAudio);

    elements.tapeShell.addEventListener("click", togglePlayback);
    elements.tapeShell.addEventListener("keydown", function (event) {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        togglePlayback();
      }
    });

    elements.seekControl.addEventListener("input", function () {
      state.seekDragging = true;
      var duration = Number.isFinite(elements.audio.duration) ? elements.audio.duration : 0;
      if (duration) {
        elements.currentTime.textContent = formatTime((Number(elements.seekControl.value) / 1000) * duration);
      }
    });

    elements.seekControl.addEventListener("change", function () {
      var duration = Number.isFinite(elements.audio.duration) ? elements.audio.duration : 0;
      if (duration) {
        elements.audio.currentTime = (Number(elements.seekControl.value) / 1000) * duration;
      }
      state.seekDragging = false;
      updateProgress();
    });

    elements.audio.addEventListener("play", function () {
      setTapePlaying(true);
      setStatus("Playing");
      renderTrackList();
    });
    elements.audio.addEventListener("pause", function () {
      setTapePlaying(false);
      if (currentTrack()) {
        setStatus("Paused");
      }
      renderTrackList();
    });
    elements.audio.addEventListener("ended", function () {
      playNeighbor(1);
    });
    elements.audio.addEventListener("timeupdate", updateProgress);
    elements.audio.addEventListener("durationchange", updateProgress);

    window.addEventListener("resize", fitTape);
    window.addEventListener("online", function () {
      setOfflineStatus("Ready");
    });
    window.addEventListener("offline", function () {
      setOfflineStatus("Offline");
    });
  }

  function loadAlbums() {
    return fetch(ALBUM_INDEX_URL)
      .then(function (response) {
        if (!response.ok) {
          throw new Error("Album index failed");
        }
        return response.json();
      })
      .then(function (albumIndex) {
        return Promise.all(albumIndex.map(function (album) {
          return fetch(album.tracks)
            .then(function (response) {
              if (!response.ok) {
                throw new Error(album.title + " failed");
              }
              return response.json();
            })
            .then(function (tracks) {
              return {
                id: album.id,
                title: album.title,
                tracksUrl: album.tracks,
                tracks: tracks.map(normalizeTrack)
              };
            });
        }));
      })
      .then(function (albums) {
        state.albums = albums;
        renderAlbumTabs();
        renderTrackList();
        if (albums[0]) {
          elements.albumTitle.textContent = albums[0].title;
          elements.tapeLabel.textContent = albums[0].title;
        }
        setStatus("Choose a song");
      })
      .catch(function () {
        setStatus("Could not load albums");
      });
  }

  function init() {
    bindEvents();
    fitTape();
    visualizer.load();
    loadAlbums();
    registerServiceWorker();
  }

  init();
})();
