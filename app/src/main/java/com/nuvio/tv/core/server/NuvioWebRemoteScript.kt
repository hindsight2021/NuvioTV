package com.nuvio.tv.core.server

/**
 * Embedded vanilla JavaScript for the Nuvio+ Zero-Install Mobile Remote & Second Screen PWA.
 *
 * All dynamic strings avoid unescaped Kotlin raw-string template interpolation so compilation
 * never collides with `$` expressions in JavaScript.
 */
object NuvioWebRemoteScript {

    val JS: String = """
(function () {
  'use strict';

  var TOKEN_KEY = 'nuvio_api_token';
  var STATUS_MS = 1500;
  var TELEMETRY_MS = 1000;
  var SPARK_MAX = 60;

  var state = {
    token: null,
    activeTab: 'remote',
    statusTimer: null,
    telemetryTimer: null,
    status: null,
    sparkThroughput: [],
    sparkBuffer: [],
    liveChannels: [],
    recognition: null,
    listening: false
  };

  function el(id) { return document.getElementById(id); }
  function qs(sel, root) { return (root || document).querySelector(sel); }
  function qsa(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function clear(node) { while (node && node.firstChild) node.removeChild(node.firstChild); }

  function make(tag, attrs, children) {
    var n = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        if (k === 'class') n.className = attrs[k];
        else if (k === 'text') n.textContent = attrs[k];
        else if (k.indexOf('on') === 0 && typeof attrs[k] === 'function') n.addEventListener(k.slice(2), attrs[k]);
        else if (attrs[k] !== null && attrs[k] !== undefined) n.setAttribute(k, attrs[k]);
      });
    }
    if (typeof children === 'string') {
      n.textContent = children;
    } else if (Array.isArray(children)) {
      children.forEach(function (c) {
        if (c == null) return;
        n.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
      });
    }
    return n;
  }

  function errText(err) {
    return (err && err.message) ? err.message : String(err || 'error');
  }

  function fmtMs(ms) {
    if (ms == null || isNaN(ms) || ms < 0) return '0:00';
    var total = Math.floor(ms / 1000);
    var h = Math.floor(total / 3600);
    var m = Math.floor((total % 3600) / 60);
    var s = total % 60;
    var mm = (m < 10 && h > 0 ? '0' : '') + m;
    var ss = (s < 10 ? '0' : '') + s;
    return h > 0 ? (h + ':' + mm + ':' + ss) : (mm + ':' + ss);
  }

  function fmtBytes(b) {
    if (b == null || isNaN(b)) return '0 B';
    var units = ['B', 'KB', 'MB', 'GB'];
    var i = 0; var v = b;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return (i === 0 ? Math.round(v) : v.toFixed(1)) + ' ' + units[i];
  }

  function loadToken() {
    try {
      var stored = localStorage.getItem(TOKEN_KEY);
      if (stored) state.token = stored;
    } catch (e) { /* ignore */ }
  }

  function saveToken(t) {
    state.token = t;
    try {
      if (t) localStorage.setItem(TOKEN_KEY, t);
      else localStorage.removeItem(TOKEN_KEY);
    } catch (e) { /* ignore */ }
  }

  function toast(msg, isError) {
    var t = el('toast');
    if (!t) return;
    t.textContent = msg;
    t.className = 'show' + (isError ? ' error' : '');
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.className = ''; }, 2600);
  }

  function api(method, path, body) {
    var headers = { 'Accept': 'application/json' };
    if (state.token) headers['Authorization'] = 'Bearer ' + state.token;
    var opts = { method: method, headers: headers };
    if (body !== undefined && body !== null) {
      headers['Content-Type'] = 'application/json';
      opts.body = JSON.stringify(body);
    }
    return fetch(path, opts).then(function (res) {
      var ct = res.headers.get('content-type') || '';
      var parser = ct.indexOf('application/json') >= 0 ? res.json() : res.text();
      return parser.then(function (data) {
        if (!res.ok) {
          var msg = (data && (data.error || data.message)) || ('HTTP ' + res.status);
          if (res.status === 401) {
            saveToken(null);
            showPairView('Session expired — please re-pair');
          }
          throw new Error(msg);
        }
        return data;
      });
    });
  }

  function get(path) { return api('GET', path, null); }
  function post(path, body) { return api('POST', path, body || {}); }
  function del(path) { return api('DELETE', path, null); }

  function pairWithPin(pin) {
    return fetch('/api/v1/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: JSON.stringify({ pin: pin })
    }).then(function (res) {
      return res.json().then(function (data) {
        if (!res.ok) throw new Error((data && data.error) || 'Invalid PIN');
        if (!data || !data.token) throw new Error('Missing token');
        saveToken(data.token);
        return data;
      });
    });
  }

  function showPairView(msg) {
    stopStatusPolling();
    stopTelemetryPolling();
    var p = el('pairView'); var a = el('appView');
    if (p) p.classList.remove('hidden');
    if (a) a.classList.add('hidden');
    var pm = el('pair-msg');
    if (pm) pm.textContent = msg || '';
  }

  function showAppView(deviceLabel) {
    var p = el('pairView'); var a = el('appView');
    if (p) p.classList.add('hidden');
    if (a) a.classList.remove('hidden');
    var dot = el('conn-dot'); var dev = el('conn-device');
    if (dot) dot.classList.add('on');
    if (dev) dev.textContent = deviceLabel || 'Connected to Nuvio+';
    startStatusPolling();
    loadLiveChannels();
  }

  function switchTab(name) {
    state.activeTab = name;
    qsa('nav#tabs button').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-tab') === name);
    });
    qsa('.panel').forEach(function (p) {
      p.classList.toggle('active', p.getAttribute('data-tab') === name);
    });
    if (name === 'nerd') {
      startTelemetryPolling();
      loadCurrentStreams();
    } else {
      stopTelemetryPolling();
    }
    if (name === 'queue') renderQueue();
    if (name === 'remote') renderStatus();
  }

  function startStatusPolling() {
    stopStatusPolling();
    pollStatus();
    state.statusTimer = setInterval(pollStatus, STATUS_MS);
  }

  function stopStatusPolling() {
    if (state.statusTimer) { clearInterval(state.statusTimer); state.statusTimer = null; }
  }

  function pollStatus() {
    if (!state.token) return;
    get('/api/v1/status').then(function (data) {
      state.status = data || {};
      var dev = el('conn-device');
      if (dev && data.deviceIp) dev.textContent = (data.app || 'Nuvio+') + ' · ' + data.deviceIp;
      renderStatus();
      if (state.activeTab === 'queue') renderQueue();
    }).catch(function () { /* keep quiet during transient polls */ });
  }

  function syncTrackSelect(selectEl, tracks, selectedIndex, prefix, emptyLabel) {
    if (!selectEl) return;
    var sig = (tracks || []).map(function (t) { return (t.index != null ? t.index : '') + ':' + (t.label || t.language || ''); }).join('|') + '@' + selectedIndex;
    if (selectEl._sig === sig) return;
    selectEl._sig = sig;
    clear(selectEl);
    if (!tracks || !tracks.length) {
      selectEl.appendChild(make('option', { value: '-1' }, emptyLabel));
      return;
    }
    if (prefix === 'Subtitles') {
      selectEl.appendChild(make('option', { value: '-1' }, 'Subtitles: Off'));
    }
    tracks.forEach(function (tr) {
      var idx = tr.index != null ? tr.index : 0;
      var label = tr.label || tr.language || ('Track ' + (idx + 1));
      var opt = make('option', { value: String(idx) }, prefix + ': ' + label);
      if (idx === selectedIndex) opt.selected = true;
      selectEl.appendChild(opt);
    });
  }

  function renderStatus() {
    var s = state.status || {};
    var pb = s.playback || {};
    var active = !!pb.isActive;
    var title = active ? (pb.title || pb.contentName || 'Playing') : 'Nothing playing';
    var epCode = (pb.season != null && pb.episode != null) ? ('S' + pb.season + 'E' + pb.episode) : '';
    var sub = [pb.contentName, epCode, pb.episodeTitle, pb.streamName].filter(Boolean).join(' · ') || 'Ready on TV';
    var stateTxt = !active ? 'idle' : (pb.isBuffering ? 'buffering' : (pb.isPlaying ? 'playing' : 'paused'));

    if (el('np-title')) el('np-title').textContent = title;
    if (el('np-sub')) el('np-sub').textContent = sub;
    if (el('np-state')) el('np-state').textContent = stateTxt;

    var pos = pb.positionMs || 0;
    var dur = pb.durationMs || 0;
    var scrub = el('scrub');
    if (scrub && !scrub._dragging) {
      scrub.max = String(dur > 0 ? dur : 1000);
      scrub.value = String(pos);
    }
    if (el('scrub-pos')) el('scrub-pos').textContent = fmtMs(pos);
    if (el('scrub-dur')) el('scrub-dur').textContent = fmtMs(dur);
    if (el('speed-label')) el('speed-label').textContent = (pb.playbackSpeed || 1) + 'x';

    syncTrackSelect(el('audio-select'), pb.audioTracks || [], pb.selectedAudioTrackIndex != null ? pb.selectedAudioTrackIndex : -1, 'Audio', 'Audio: Default');
    syncTrackSelect(el('subtitle-select'), pb.subtitleTracks || [], pb.selectedSubtitleTrackIndex != null ? pb.selectedSubtitleTrackIndex : -1, 'Subtitles', 'Subtitles: Off');
  }

  function bindRemoteControls() {
    var scrub = el('scrub');
    if (scrub) {
      scrub.addEventListener('input', function () {
        scrub._dragging = true;
        if (el('scrub-pos')) el('scrub-pos').textContent = fmtMs(Number(scrub.value));
      });
      scrub.addEventListener('change', function () {
        scrub._dragging = false;
        post('/api/v1/playback/seek', { positionMs: Number(scrub.value) }).catch(function (e) { toast(errText(e), true); });
      });
    }

    qsa('[data-playback]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var action = btn.getAttribute('data-playback');
        if (action === 'rw') {
          post('/api/v1/playback/seek', { deltaMs: -10000 }).catch(function (e) { toast(errText(e), true); });
          return;
        }
        if (action === 'ff') {
          post('/api/v1/playback/seek', { deltaMs: 30000 }).catch(function (e) { toast(errText(e), true); });
          return;
        }
        var body = {};
        if (action === 'speed') {
          var speeds = [0.5, 0.75, 1.0, 1.25, 1.5, 2.0];
          var cur = (state.status && state.status.playback && state.status.playback.playbackSpeed) || 1.0;
          var idx = speeds.indexOf(cur);
          body.speed = speeds[(idx + 1) % speeds.length];
        }
        post('/api/v1/playback/' + action, body).then(pollStatus).catch(function (e) { toast(errText(e), true); });
      });
    });

    var audioSel = el('audio-select');
    if (audioSel) {
      audioSel.addEventListener('change', function () {
        post('/api/v1/playback/audio_track', { index: Number(audioSel.value) }).catch(function (e) { toast(errText(e), true); });
      });
    }
    var subSel = el('subtitle-select');
    if (subSel) {
      subSel.addEventListener('change', function () {
        var idx = Number(subSel.value);
        if (idx < 0) post('/api/v1/playback/subtitles/disable', {}).catch(function (e) { toast(errText(e), true); });
        else post('/api/v1/playback/subtitle_track', { index: idx }).catch(function (e) { toast(errText(e), true); });
      });
    }

    qsa('[data-dpad]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        post('/api/v1/navigation/dpad', { key: btn.getAttribute('data-dpad') }).catch(function (e) { toast(errText(e), true); });
      });
    });

    var modeTrack = el('mode-trackpad');
    var modeDpad = el('mode-dpad');
    if (modeTrack && modeDpad) {
      modeTrack.addEventListener('click', function () {
        modeTrack.classList.add('on'); modeDpad.classList.remove('on');
        el('trackpad-wrap').classList.remove('hidden');
        el('dpad-wrap').classList.add('hidden');
      });
      modeDpad.addEventListener('click', function () {
        modeDpad.classList.add('on'); modeTrack.classList.remove('on');
        el('dpad-wrap').classList.remove('hidden');
        el('trackpad-wrap').classList.add('hidden');
      });
    }

    var pad = el('trackpad');
    if (pad) {
      var sx = 0, sy = 0, active = false;
      function begin(x, y) { sx = x; sy = y; active = true; }
      function finish(x, y) {
        if (!active) return;
        active = false;
        var dx = x - sx; var dy = y - sy;
        if (Math.abs(dx) < 36 && Math.abs(dy) < 36) {
          post('/api/v1/navigation/dpad', { key: 'SELECT' }).catch(function (e) { toast(errText(e), true); });
          return;
        }
        var k = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'RIGHT' : 'LEFT') : (dy > 0 ? 'DOWN' : 'UP');
        post('/api/v1/navigation/dpad', { key: k }).catch(function (e) { toast(errText(e), true); });
      }
      pad.addEventListener('touchstart', function (e) { var t = e.changedTouches[0]; begin(t.clientX, t.clientY); }, { passive: true });
      pad.addEventListener('touchend', function (e) { var t = e.changedTouches[0]; finish(t.clientX, t.clientY); }, { passive: true });
      pad.addEventListener('pointerdown', function (e) { if (e.pointerType !== 'touch') begin(e.clientX, e.clientY); });
      pad.addEventListener('pointerup', function (e) { if (e.pointerType !== 'touch') finish(e.clientX, e.clientY); });
    }

    var ti = el('text-input');
    var ts = el('text-search');
    var ta = el('text-ai');
    function sendTvSearch() {
      var q = ti ? ti.value.trim() : '';
      if (!q) return;
      post('/api/v1/navigation/search', { query: q }).then(function () { toast('Sent search to TV'); }).catch(function (e) { toast(errText(e), true); });
    }
    function sendTvAi() {
      var q = ti ? ti.value.trim() : '';
      if (!q) return;
      post('/api/v1/ai/command', { prompt: q }).then(function (r) { toast((r && r.message) || 'AI command sent'); }).catch(function (e) { toast(errText(e), true); });
    }
    if (ts) ts.addEventListener('click', sendTvSearch);
    if (ta) ta.addEventListener('click', sendTvAi);
    if (ti) ti.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); sendTvSearch(); } });

    qsa('[data-screen]').forEach(function (chip) {
      chip.addEventListener('click', function () {
        post('/api/v1/navigation/open', { screen: chip.getAttribute('data-screen') }).catch(function (e) { toast(errText(e), true); });
      });
    });

    if (el('livetv-refresh')) el('livetv-refresh').addEventListener('click', loadLiveChannels);
  }

  function loadLiveChannels() {
    if (!state.token) return;
    get('/api/v1/livetv/channels').then(function (res) {
      state.liveChannels = (res && res.channels) || [];
      renderLiveChannels();
    }).catch(function () { /* silent */ });
  }

  function renderLiveChannels() {
    var host = el('livetv-list');
    if (!host) return;
    clear(host);
    if (!state.liveChannels.length) {
      host.appendChild(make('div', { class: 'muted' }, 'No active channels'));
      return;
    }
    state.liveChannels.forEach(function (ch) {
      var label = (ch.number ? ('CH ' + ch.number + ' · ') : '') + (ch.name || ch.id) + (ch.nowPlaying ? (' — ' + ch.nowPlaying) : '');
      var btn = make('button', {
        class: 'chip' + (ch.isAiNetwork ? ' active' : ''),
        type: 'button',
        onclick: function () {
          post('/api/v1/livetv/tune', { channelId: ch.id, startFromBeginning: false })
            .then(function (r) { toast((r && r.message) || ('Tuned ' + ch.name)); })
            .catch(function (e) { toast(errText(e), true); });
        }
      }, label);
      host.appendChild(btn);
    });
  }

  function renderQueue() {
    var list = el('queue-list');
    if (!list) return;
    clear(list);
    var qObj = (state.status && state.status.queue) || {};
    var items = Array.isArray(qObj.items) ? qObj.items : [];
    var curIdx = qObj.currentIndex != null ? qObj.currentIndex : -1;
    if (el('queue-count')) el('queue-count').textContent = items.length + ' items';

    if (!items.length) {
      list.appendChild(make('div', { class: 'muted' }, 'Queue is empty. Add shows from Browse or launch an AI mood below.'));
      return;
    }

    items.forEach(function (item, idx) {
      var row = make('div', {
        class: 'queue-row' + (idx === curIdx ? ' current' : ''),
        draggable: 'true',
        'data-index': String(idx)
      });
      var ep = (item.season != null && item.episode != null) ? (' · S' + item.season + 'E' + item.episode) : '';
      var labelText = (item.seriesTitle ? (item.seriesTitle + ' — ') : '') + (item.title || 'Untitled') + ep;
      row.appendChild(make('span', { class: 'queue-handle', title: 'Drag to reorder' }, '\u2630'));
      row.appendChild(make('span', { class: 'queue-num' }, String(idx + 1)));
      row.appendChild(make('span', { class: 'queue-title' }, labelText));

      var upBtn = make('button', { class: 'sm', type: 'button', onclick: function () { moveQueueItem(idx, idx - 1); } }, '\u25B2');
      var downBtn = make('button', { class: 'sm', type: 'button', onclick: function () { moveQueueItem(idx, idx + 1); } }, '\u25BC');
      var delBtn = make('button', { class: 'sm', type: 'button', onclick: function () { removeQueueItem(idx); } }, '\u2715');
      if (idx === 0) upBtn.disabled = true;
      if (idx === items.length - 1) downBtn.disabled = true;
      row.appendChild(upBtn);
      row.appendChild(downBtn);
      row.appendChild(delBtn);
      list.appendChild(row);
    });
  }

  function moveQueueItem(fromIndex, toIndex) {
    if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0) return;
    post('/api/v1/queue/reorder', { fromIndex: fromIndex, toIndex: toIndex })
      .then(pollStatus)
      .catch(function (e) { toast('Reorder failed: ' + errText(e), true); });
  }

  function removeQueueItem(index) {
    post('/api/v1/queue/remove', { index: index })
      .then(pollStatus)
      .catch(function (e) { toast('Remove failed: ' + errText(e), true); });
  }

  function bindQueueControls() {
    var list = el('queue-list');
    if (list) {
      var dragFrom = -1;
      list.addEventListener('dragstart', function (e) {
        var row = e.target.closest ? e.target.closest('.queue-row') : null;
        if (!row) return;
        dragFrom = parseInt(row.getAttribute('data-index'), 10);
        row.classList.add('dragging');
      });
      list.addEventListener('dragover', function (e) {
        e.preventDefault();
        var row = e.target.closest ? e.target.closest('.queue-row') : null;
        qsa('.queue-row', list).forEach(function (r) { r.classList.remove('drag-over'); });
        if (row) row.classList.add('drag-over');
      });
      list.addEventListener('drop', function (e) {
        e.preventDefault();
        var row = e.target.closest ? e.target.closest('.queue-row') : null;
        qsa('.queue-row', list).forEach(function (r) { r.classList.remove('drag-over', 'dragging'); });
        if (row && dragFrom >= 0) {
          var toIdx = parseInt(row.getAttribute('data-index'), 10);
          if (!isNaN(toIdx) && toIdx !== dragFrom) moveQueueItem(dragFrom, toIdx);
        }
        dragFrom = -1;
      });

      var touchFrom = -1;
      list.addEventListener('pointerdown', function (e) {
        var handle = e.target.closest ? e.target.closest('.queue-handle') : null;
        if (!handle) return;
        var row = handle.closest('.queue-row');
        if (!row) return;
        touchFrom = parseInt(row.getAttribute('data-index'), 10);
        row.classList.add('dragging');
      });
      list.addEventListener('pointermove', function (e) {
        if (touchFrom < 0) return;
        var over = document.elementFromPoint(e.clientX, e.clientY);
        var row = over && over.closest ? over.closest('.queue-row') : null;
        qsa('.queue-row', list).forEach(function (r) { r.classList.remove('drag-over'); });
        if (row) row.classList.add('drag-over');
      });
      list.addEventListener('pointerup', function (e) {
        if (touchFrom < 0) return;
        var over = document.elementFromPoint(e.clientX, e.clientY);
        var row = over && over.closest ? over.closest('.queue-row') : null;
        qsa('.queue-row', list).forEach(function (r) { r.classList.remove('drag-over', 'dragging'); });
        if (row) {
          var toIdx = parseInt(row.getAttribute('data-index'), 10);
          if (!isNaN(toIdx) && toIdx !== touchFrom) moveQueueItem(touchFrom, toIdx);
        }
        touchFrom = -1;
      });
      list.addEventListener('pointercancel', function () {
        touchFrom = -1;
        qsa('.queue-row', list).forEach(function (r) { r.classList.remove('drag-over', 'dragging'); });
      });
    }

    if (el('queue-advance')) {
      el('queue-advance').addEventListener('click', function () {
        post('/api/v1/queue/advance', {}).then(function () { toast('Playing next item'); pollStatus(); }).catch(function (e) { toast(errText(e), true); });
      });
    }
    if (el('queue-clear')) {
      el('queue-clear').addEventListener('click', function () {
        del('/api/v1/queue').then(function () { toast('Queue cleared'); pollStatus(); }).catch(function (e) { toast(errText(e), true); });
      });
    }

    qsa('.mood-chip').forEach(function (chip) {
      chip.addEventListener('click', function () {
        var moodId = chip.getAttribute('data-mood');
        post('/api/v1/channels/curated', { moodId: moodId })
          .then(function (r) { toast((r && r.message) || 'Mood started'); pollStatus(); })
          .catch(function (e) { toast(errText(e), true); });
      });
    });

    var tf = el('thematic-form');
    if (tf) {
      tf.addEventListener('submit', function (e) {
        e.preventDefault();
        var inp = el('thematic-prompt');
        var prompt = inp ? inp.value.trim() : '';
        if (!prompt) return;
        post('/api/v1/channels/thematic', { prompt: prompt })
          .then(function (r) { toast((r && r.message) || 'Thematic channel started'); if (inp) inp.value = ''; pollStatus(); })
          .catch(function (e2) { toast(errText(e2), true); });
      });
    }
  }

  function bindBrowseTab() {
    var form = el('browse-form');
    var results = el('browse-results');
    var streams = el('browse-streams');

    function fetchStreamsFor(item) {
      if (!streams) return;
      clear(streams);
      streams.appendChild(make('div', { class: 'muted' }, 'Fetching streams for ' + (item.name || item.title) + '...'));
      var qUrl = '/api/v1/streams/browse?type=' + encodeURIComponent(item.type || 'movie') +
        '&id=' + encodeURIComponent(item.id || '') +
        '&title=' + encodeURIComponent(item.name || item.title || '');
      get(qUrl).then(function (res) {
        clear(streams);
        var list = (res && res.streams) || [];
        if (!list.length) {
          streams.appendChild(make('div', { class: 'muted' }, 'No streams found from enabled addons.'));
          return;
        }
        list.forEach(function (st) {
          var card = make('div', { class: 'stream-card' });
          var head = make('div', { class: 'stream-head' }, [
            make('div', { class: 'stream-title' }, st.label || st.title || st.name || 'Stream'),
            make('span', { class: 'badge' }, st.addonName || 'Addon')
          ]);
          card.appendChild(head);
          if (st.description) card.appendChild(make('div', { class: 'muted' }, st.description));

          var actions = make('div', { class: 'stream-actions' });
          var titleStr = item.name || item.title || st.title || 'Stream';
          actions.appendChild(make('button', {
            class: 'primary sm',
            type: 'button',
            onclick: function () {
              post('/api/v1/play/stream', { streamUrl: st.url, title: titleStr, headers: st.headers || {} })
                .then(function () { toast('Launched on TV'); })
                .catch(function (e) { toast(errText(e), true); });
            }
          }, 'Play TV'));
          actions.appendChild(make('button', {
            class: 'sm',
            type: 'button',
            onclick: function () {
              post('/api/v1/queue/play-next', { contentId: item.id, title: titleStr, mediaType: item.type || 'movie', thumbnail: item.poster || null })
                .then(function () { toast('Queued Next'); pollStatus(); })
                .catch(function (e) { toast(errText(e), true); });
            }
          }, 'Play Next'));
          actions.appendChild(make('button', {
            class: 'sm',
            type: 'button',
            onclick: function () {
              post('/api/v1/queue', { contentId: item.id, title: titleStr, mediaType: item.type || 'movie', thumbnail: item.poster || null })
                .then(function () { toast('Added to Queue'); pollStatus(); })
                .catch(function (e) { toast(errText(e), true); });
            }
          }, '+ Queue'));
          card.appendChild(actions);
          streams.appendChild(card);
        });
      }).catch(function (e) {
        clear(streams);
        streams.appendChild(make('div', { class: 'muted' }, 'Error: ' + errText(e)));
      });
    }

    if (form) {
      form.addEventListener('submit', function (e) {
        e.preventDefault();
        var q = (el('browse-query') && el('browse-query').value || '').trim();
        if (!q || !results) return;
        clear(results);
        results.appendChild(make('div', { class: 'muted' }, 'Searching addons...'));
        get('/api/v1/search?q=' + encodeURIComponent(q)).then(function (res) {
          clear(results);
          var items = (res && res.items) || [];
          if (!items.length) {
            results.appendChild(make('div', { class: 'muted' }, 'No catalog matches found.'));
            return;
          }
          items.forEach(function (item) {
            var meta = [(item.type || '').toUpperCase(), item.releaseInfo, item.catalogName].filter(Boolean).join(' · ');
            var btn = make('button', {
              class: 'browse-item',
              type: 'button',
              onclick: function () { fetchStreamsFor(item); }
            }, [
              make('span', { class: 'bt' }, item.name || item.title || item.id),
              make('span', { class: 'bm' }, meta || item.id)
            ]);
            results.appendChild(btn);
          });
        }).catch(function (err) {
          clear(results);
          toast('Search failed: ' + errText(err), true);
        });
      });
    }
  }

  function bindVoiceTab() {
    var log = el('voice-log');
    var input = el('voice-input');
    var send = el('voice-send');
    var btn = el('voice-btn');

    function appendBubble(txt, who) {
      if (!log) return;
      log.appendChild(make('div', { class: 'voice-bubble voice-' + who }, txt));
      log.scrollTop = log.scrollHeight;
    }

    function sendPrompt(prompt) {
      var p = (prompt || '').trim();
      if (!p) return;
      appendBubble(p, 'user');
      if (input) input.value = '';
      post('/api/v1/ai/command', { prompt: p })
        .then(function (res) {
          var msg = (res && (res.message || (res.data && res.data.reply))) || 'Executed';
          appendBubble(msg, 'ai');
          pollStatus();
        })
        .catch(function (err) { appendBubble('Error: ' + errText(err), 'ai'); });
    }

    if (send) send.addEventListener('click', function () { sendPrompt(input ? input.value : ''); });
    if (input) input.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); sendPrompt(input.value); } });

    qsa('.voice-chip').forEach(function (chip) {
      chip.addEventListener('click', function () { sendPrompt(chip.getAttribute('data-prompt')); });
    });

    if (btn) {
      var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
      var rec = null;
      if (SR) {
        rec = new SR();
        rec.lang = 'en-US';
        rec.interimResults = false;
        rec.continuous = false;
        rec.onresult = function (event) {
          var text = '';
          for (var i = event.resultIndex; i < event.results.length; i++) {
            text += event.results[i][0].transcript;
          }
          if (text) sendPrompt(text);
        };
        rec.onerror = function (event) {
          toast('Mic error: ' + (event.error || 'unavailable'), true);
          state.listening = false;
          btn.classList.remove('listening');
        };
        rec.onend = function () {
          state.listening = false;
          btn.classList.remove('listening');
        };
      }

      btn.addEventListener('click', function () {
        if (!rec) {
          toast('Browser SpeechRecognition requires HTTPS/localhost; type below instead', true);
          if (input) input.focus();
          return;
        }
        if (state.listening) {
          try { rec.stop(); } catch (ignore) {}
          state.listening = false;
          btn.classList.remove('listening');
          return;
        }
        try {
          rec.start();
          state.listening = true;
          btn.classList.add('listening');
        } catch (e) {
          toast('Voice error: ' + errText(e), true);
        }
      });
    }
  }

  function startTelemetryPolling() {
    stopTelemetryPolling();
    pollTelemetry();
    state.telemetryTimer = setInterval(pollTelemetry, TELEMETRY_MS);
  }

  function stopTelemetryPolling() {
    if (state.telemetryTimer) { clearInterval(state.telemetryTimer); state.telemetryTimer = null; }
  }

  function pollTelemetry() {
    if (state.activeTab !== 'nerd' || !state.token) return;
    get('/api/v1/telemetry').then(renderTelemetry).catch(function () { /* quiet */ });
  }

  function renderTelemetry(t) {
    var cards = el('nerd-cards');
    if (!cards || !t) return;
    clear(cards);
    var res = (t.width && t.height) ? (t.width + 'x' + t.height) : '--';
    var bpsMbps = t.videoBitrateBps > 0 ? (t.videoBitrateBps / 1000000).toFixed(2) + ' Mbps' : '--';
    var pre = t.speculativePreWarm || {};
    var preStr = (pre.state || 'IDLE') +
      (pre.targetLabel ? (' · ' + pre.targetLabel) : '') +
      (pre.cachedBytes > 0 ? (' (' + fmtBytes(pre.cachedBytes) + ')') : '');

    var kpis = [
      ['Player Engine', t.engine || '--'],
      ['Video Codec / Res', (t.videoCodec || '--') + ' · ' + res],
      ['Frame Rate', t.frameRateFps > 0 ? (Number(t.frameRateFps).toFixed(3) + ' fps') : '--'],
      ['Dropped Frames', String(t.droppedFrames != null ? t.droppedFrames : 0)],
      ['Audio Track', (t.audioCodec || '--') + (t.audioChannels > 0 ? (' · ' + t.audioChannels + 'ch') : '')],
      ['Video Bitrate', bpsMbps],
      ['Buffer Ahead', fmtMs(t.totalBufferAheadMs || 0)],
      ['Throughput', t.networkThroughputMbps > 0 ? (Number(t.networkThroughputMbps).toFixed(2) + ' Mbps') : '--'],
      ['VOD Disk Cache', (t.vodCacheMaxMb > 0) ? (t.vodCacheUsedMb + ' / ' + t.vodCacheMaxMb + ' MB') : 'Off'],
      ['Next-Ep Pre-Warm', preStr]
    ];

    kpis.forEach(function (kv) {
      cards.appendChild(make('div', { class: 'kpi' }, [
        make('div', { class: 'kpi-label' }, kv[0]),
        make('div', { class: 'kpi-value' }, kv[1])
      ]));
    });

    if (t.networkThroughputMbps != null) {
      state.sparkThroughput.push(Number(t.networkThroughputMbps) || 0);
      if (state.sparkThroughput.length > SPARK_MAX) state.sparkThroughput.shift();
    }
    if (t.totalBufferAheadMs != null) {
      state.sparkBuffer.push((Number(t.totalBufferAheadMs) || 0) / 1000);
      if (state.sparkBuffer.length > SPARK_MAX) state.sparkBuffer.shift();
    }
    drawSparkline();
  }

  function drawSparkline() {
    var canvas = el('nerd-spark');
    if (!canvas || !canvas.getContext) return;
    var ctx = canvas.getContext('2d');
    var w = canvas.width; var h = canvas.height;
    ctx.clearRect(0, 0, w, h);
    var series = [
      { data: state.sparkThroughput, color: '#22C55E' },
      { data: state.sparkBuffer, color: '#22D3EE' }
    ];
    series.forEach(function (s) {
      if (!s.data.length) return;
      var max = Math.max.apply(null, s.data.concat([1]));
      ctx.beginPath();
      ctx.strokeStyle = s.color;
      ctx.lineWidth = 2;
      s.data.forEach(function (val, i) {
        var x = (i / Math.max(s.data.length - 1, 1)) * w;
        var y = h - (val / max) * (h - 8) - 4;
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();
    });
  }

  function loadCurrentStreams() {
    if (!state.token) return;
    get('/api/v1/streams/current').then(function (res) {
      renderHotSwap((res && res.streams) || []);
    }).catch(function () { /* quiet */ });
  }

  function renderHotSwap(streams) {
    var host = el('hotswap-list');
    if (!host) return;
    clear(host);
    if (!streams.length) {
      host.appendChild(make('div', { class: 'muted' }, 'No alternate streams cached for current playback.'));
      return;
    }
    streams.forEach(function (st) {
      var row = make('div', { class: 'stream-card' });
      row.appendChild(make('div', { class: 'stream-head' }, [
        make('div', { class: 'stream-title' }, st.label || st.title || st.name || 'Stream'),
        make('span', { class: 'badge' }, st.isCurrent ? 'CURRENT' : (st.addonName || 'Switch'))
      ]));
      var swapBtn = make('button', {
        class: st.isCurrent ? 'sm' : 'cyan sm',
        type: 'button',
        disabled: st.isCurrent ? 'disabled' : null,
        onclick: function () {
          post('/api/v1/playback/hotswap', { streamUrl: st.url, title: st.label || st.title || 'Stream', headers: st.headers || {} })
            .then(function () { toast('Hot-swapping stream at exact timestamp'); loadCurrentStreams(); })
            .catch(function (e) { toast('Hot-swap failed: ' + errText(e), true); });
        }
      }, st.isCurrent ? 'On Air Now' : 'Hot-Swap Here');
      row.appendChild(swapBtn);
      host.appendChild(row);
    });
  }

  function bindNerdTab() {
    if (el('hotswap-refresh')) el('hotswap-refresh').addEventListener('click', loadCurrentStreams);
  }

  function boot() {
    var search = new URLSearchParams(window.location.search);
    var qrPin = search.get('pin');
    var urlToken = search.get('token') || search.get('key');

    if (urlToken) {
      saveToken(urlToken);
      try { window.history.replaceState({}, '', window.location.pathname); } catch (e) {}
    } else if (qrPin) {
      pairWithPin(qrPin).then(function (res) {
        try { window.history.replaceState({}, '', window.location.pathname); } catch (e) {}
        showAppView(res.device || 'Connected');
      }).catch(function () {
        showPairView('Auto-pairing PIN expired — enter current PIN');
      });
      return;
    }

    loadToken();
    if (!state.token) {
      showPairView('');
      return;
    }
    get('/api/v1/status').then(function (data) {
      state.status = data;
      showAppView((data && data.app ? data.app : 'Nuvio+') + (data && data.deviceIp ? (' · ' + data.deviceIp) : ''));
      renderStatus();
    }).catch(function () {
      showPairView('Pair with PIN on your TV');
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    if (el('pair-btn')) {
      el('pair-btn').addEventListener('click', function () {
        var pin = (el('pin-input') && el('pin-input').value || '').trim();
        if (!pin) return;
        pairWithPin(pin).then(function (res) {
          showAppView(res.device || 'Connected');
        }).catch(function (e) {
          if (el('pair-msg')) el('pair-msg').textContent = errText(e);
        });
      });
    }
    if (el('pin-input')) {
      el('pin-input').addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && el('pair-btn')) el('pair-btn').click();
      });
    }
    if (el('unpair-btn')) {
      el('unpair-btn').addEventListener('click', function () {
        saveToken(null);
        showPairView('Unpaired from TV');
      });
    }

    qsa('nav#tabs button').forEach(function (btn) {
      btn.addEventListener('click', function () {
        switchTab(btn.getAttribute('data-tab'));
      });
    });

    bindRemoteControls();
    bindQueueControls();
    bindBrowseTab();
    bindVoiceTab();
    bindNerdTab();
    boot();
  });
})();
""".trimIndent()
}
