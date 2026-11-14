// ABS Lite client. Plain ES5-style JS, no framework, no build step. Auth is handled by server.js (same-origin /abs proxy).
(function () {
  'use strict'
  var API = '/abs'
  var PAGE = 40
  var SPEEDS = [1, 1.25, 1.5, 1.75, 2, 0.75]
  var SLEEPS = [0, 15, 30, 60]
  var $ = function (id) { return document.getElementById(id) }
  var audio = new Audio()
  audio.preload = 'auto'

  var S = {
    libId: null, page: 0, total: 0, query: '', progress: {},
    sess: null, tracks: [], ti: 0, chapters: [], duration: 0, item: null,
    listened: 0, drag: false, speedIdx: 0, sleepIdx: 0, sleepTimer: null, pendingSeek: null
  }

  function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v) } catch (e) { return null } }

  function toast(msg) {
    var t = $('toast'); t.textContent = msg; t.style.display = 'block'
    clearTimeout(toast._t); toast._t = setTimeout(function () { t.style.display = 'none' }, 4000)
  }

  function api(method, path, body, keepalive) {
    var opts = { method: method, headers: {}, keepalive: !!keepalive }
    if (body) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body) }
    return fetch(API + path, opts).then(function (r) {
      if (!r.ok) return r.text().then(function (t) { throw new Error(method + ' ' + path + ' -> ' + r.status + ' ' + t.slice(0, 120)) })
      return r.text().then(function (t) { try { return JSON.parse(t) } catch (e) { return null } })
    })
  }

  function fmt(s) {
    s = Math.max(0, Math.floor(s || 0))
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
    return (h ? h + ':' + (m < 10 ? '0' : '') + m : m) + ':' + (sec < 10 ? '0' : '') + sec
  }
  function coverUrl(id, w) { return API + '/api/items/' + id + '/cover?width=' + (w || 200) + '&format=jpeg' }

  // ---------- library / list ----------
  function meta(it) { var m = (it.media && it.media.metadata) || {}; return { title: m.title || '(untitled)', author: m.authorName || '' } }

  function renderItems(ul, items, append) {
    if (!append) ul.innerHTML = ''
    items.forEach(function (it) {
      var m = meta(it), p = S.progress[it.id], pct = p ? Math.round((p.progress || 0) * 100) : 0
      var li = document.createElement('li')
      var img = document.createElement('img'); img.loading = 'lazy'; img.alt = ''; img.src = coverUrl(it.id, 120)
      var box = document.createElement('div'); box.className = 'meta'
      var t = document.createElement('div'); t.className = 't'; t.textContent = m.title
      var a = document.createElement('div'); a.className = 'muted'; a.textContent = m.author
      box.appendChild(t); box.appendChild(a)
      if (pct > 0) { var pr = document.createElement('div'); pr.className = 'prog'; pr.innerHTML = '<i style="width:' + pct + '%"></i>'; box.appendChild(pr) }
      li.appendChild(img); li.appendChild(box)
      li.onclick = function () { play(it.id, m.title, m.author) }
      ul.appendChild(li)
    })
  }

  function loadProgress() {
    return api('GET', '/api/me').then(function (me) {
      S.progress = {}
      ;((me && me.mediaProgress) || []).forEach(function (p) { if (!p.episodeId) S.progress[p.libraryItemId] = p })
    }).catch(function () {})
  }

  function loadContinue() {
    return api('GET', '/api/me/items-in-progress').then(function (r) {
      var items = ((r && r.libraryItems) || []).filter(function (i) { return !S.libId || i.libraryId === S.libId })
      $('contH').hidden = !items.length
      renderItems($('contList'), items, false)
    }).catch(function () { $('contH').hidden = true })
  }

  function loadPage(reset) {
    if (reset) { S.page = 0; S.total = 0 }
    var q = S.query
    if (q) {
      return api('GET', '/api/libraries/' + S.libId + '/search?limit=30&q=' + encodeURIComponent(q)).then(function (r) {
        var items = ((r && r.book) || []).map(function (b) { return b.libraryItem })
        $('allH').textContent = 'Results'; renderItems($('allList'), items, false); $('more').hidden = true
      })
    }
    $('allH').textContent = 'Library'
    return api('GET', '/api/libraries/' + S.libId + '/items?limit=' + PAGE + '&page=' + S.page + '&sort=media.metadata.title&minified=1').then(function (r) {
      var results = (r && r.results) || []
      S.total = (r && r.total) || results.length
      renderItems($('allList'), results, S.page > 0)
      $('more').hidden = (S.page + 1) * PAGE >= S.total
    })
  }

  function initHome() {
    return api('GET', '/api/libraries').then(function (r) {
      var libs = ((r && r.libraries) || []).filter(function (l) { return l.mediaType === 'book' })
      if (!libs.length) throw new Error('No book libraries found')
      var sel = $('libSel'); sel.innerHTML = ''
      libs.forEach(function (l) { var o = document.createElement('option'); o.value = l.id; o.textContent = l.name; sel.appendChild(o) })
      var saved = store('lib'); if (saved && libs.some(function (l) { return l.id === saved })) sel.value = saved
      S.libId = sel.value
      return loadProgress().then(function () { return Promise.all([loadContinue(), loadPage(true)]) })
    }).catch(function (e) { toast('Could not load library: ' + e.message) })
  }

  $('libSel').onchange = function () { S.libId = this.value; store('lib', S.libId); loadContinue(); loadPage(true) }
  $('more').onclick = function () { S.page++; loadPage(false) }
  var st; $('search').oninput = function () { var v = this.value.trim(); clearTimeout(st); st = setTimeout(function () { S.query = v; loadPage(true) }, 350) }

  // ---------- playback ----------
  function curTrack() { return S.tracks[S.ti] }
  function globalTime() { var t = curTrack(); return t ? (t.startOffset || 0) + (audio.currentTime || 0) : 0 }

  function loadTrack(i, offset, autoplay) {
    var t = S.tracks[i]; if (!t) return
    S.ti = i; S.pendingSeek = offset || 0
    audio.src = API + t.contentUrl
    audio.playbackRate = SPEEDS[S.speedIdx]
    if (autoplay) { var pr = audio.play(); if (pr && pr.catch) pr.catch(function () { toast('Tap play to start') }) }
  }
  audio.addEventListener('loadedmetadata', function () {
    if (S.pendingSeek) { try { audio.currentTime = S.pendingSeek } catch (e) {} }
    S.pendingSeek = null
  })

  function seekTo(t) {
    t = Math.max(0, Math.min(S.duration || t, t))
    var i = 0
    for (var k = 0; k < S.tracks.length; k++) if ((S.tracks[k].startOffset || 0) <= t) i = k
    var wasPlaying = !audio.paused
    if (i !== S.ti) loadTrack(i, t - (S.tracks[i].startOffset || 0), wasPlaying)
    else audio.currentTime = t - (S.tracks[i].startOffset || 0)
    updateUI()
  }

  function chapterIndex(t) {
    var idx = -1
    for (var i = 0; i < S.chapters.length; i++) if (S.chapters[i].start <= t + 0.01) idx = i
    return idx
  }

  function updateUI() {
    var t = globalTime(), d = S.duration || 0
    if (!S.drag) $('seek').value = d ? Math.round(t / d * 1000) : 0
    $('cur').textContent = fmt(t); $('left').textContent = '-' + fmt((d - t) / audio.playbackRate)
    var ci = chapterIndex(t)
    $('chapTitle').textContent = ci >= 0 ? S.chapters[ci].title : ''
    if (ci >= 0 && $('chapSel').value !== String(ci)) $('chapSel').value = String(ci)
    var icon = audio.paused ? '▶' : '❚❚'
    $('play').textContent = icon; $('miniPlay').textContent = icon
  }

  function setupMediaSession(title, author, id) {
    if (!('mediaSession' in navigator)) return
    try {
      navigator.mediaSession.metadata = new MediaMetadata({ title: title, artist: author, artwork: [{ src: location.origin + coverUrl(id, 300), sizes: '300x300', type: 'image/jpeg' }] })
      navigator.mediaSession.setActionHandler('play', function () { audio.play() })
      navigator.mediaSession.setActionHandler('pause', function () { audio.pause() })
      navigator.mediaSession.setActionHandler('seekbackward', function () { seekTo(globalTime() - 10) })
      navigator.mediaSession.setActionHandler('seekforward', function () { seekTo(globalTime() + 30) })
      navigator.mediaSession.setActionHandler('previoustrack', function () { chapterStep(-1) })
      navigator.mediaSession.setActionHandler('nexttrack', function () { chapterStep(1) })
    } catch (e) {}
  }

  function showPlayer(show) { $('player').hidden = !show; $('home').hidden = show }

  function play(id, title, author) {
    if (S.item && S.item.id === id && S.sess) { showPlayer(true); return }
    closeSession(false)
    var mimes = ['audio/mpeg', 'audio/mp4', 'audio/aac', 'audio/ogg', 'audio/flac', 'audio/x-m4a', 'audio/webm', 'audio/wav'].filter(function (m) { return audio.canPlayType(m) })
    toast('Loading...')
    api('POST', '/api/items/' + id + '/play', { deviceInfo: { clientName: 'ABS Lite', deviceId: 'abs-lite-web' }, forceDirectPlay: true, forceTranscode: false, supportedMimeTypes: mimes, mediaPlayer: 'html5' }).then(function (s) {
      S.sess = s; S.item = { id: id, title: title, author: author }
      S.tracks = (s.audioTracks || []).slice().sort(function (a, b) { return (a.index || 0) - (b.index || 0) })
      S.chapters = s.chapters || []; S.duration = s.duration || 0; S.listened = 0
      $('title').textContent = title; $('author').textContent = author; $('miniTitle').textContent = title
      var c = $('cover'); c.hidden = false; c.src = coverUrl(id, 400)
      var sel = $('chapSel'); sel.innerHTML = ''; sel.hidden = !S.chapters.length
      S.chapters.forEach(function (ch, i) { var o = document.createElement('option'); o.value = String(i); o.textContent = ch.title; sel.appendChild(o) })
      var start = s.currentTime || s.startTime || 0, ti = 0
      for (var k = 0; k < S.tracks.length; k++) if ((S.tracks[k].startOffset || 0) <= start) ti = k
      loadTrack(ti, start - (S.tracks[ti].startOffset || 0), true)
      setupMediaSession(title, author, id)
      $('mini').hidden = false; showPlayer(true); updateUI()
    }).catch(function (e) { toast('Play failed: ' + e.message) })
  }

  function syncBody() { return { currentTime: globalTime(), timeListened: Math.round(S.listened), duration: S.duration } }
  function sync(done) {
    if (!S.sess) return
    var b = syncBody(), n = b.timeListened
    api('POST', '/api/session/' + S.sess.id + '/sync', b).then(function () { S.listened = Math.max(0, S.listened - n); if (done) done() }).catch(function () {})
  }
  function closeSession(keepalive) {
    if (!S.sess) return
    var id = S.sess.id, b = syncBody()
    api('POST', '/api/session/' + id + '/close', b, keepalive).catch(function () {})
    audio.pause(); S.sess = null; S.item = null; $('mini').hidden = true
  }

  setInterval(function () { if (S.sess && !audio.paused) S.listened += 1 }, 1000)
  setInterval(function () { if (S.sess && !audio.paused) sync() }, 15000)
  window.addEventListener('pagehide', function () { if (S.sess) { var b = syncBody(); api('POST', '/api/session/' + S.sess.id + '/sync', b, true).catch(function () {}) } })

  audio.addEventListener('timeupdate', updateUI)
  audio.addEventListener('play', updateUI)
  audio.addEventListener('pause', function () { updateUI(); sync() })
  audio.addEventListener('ended', function () {
    if (S.ti < S.tracks.length - 1) loadTrack(S.ti + 1, 0, true)
    else { sync(); updateUI() }
  })
  audio.addEventListener('error', function () { if (S.sess) toast('Audio error (code ' + (audio.error && audio.error.code) + ')') })

  function togglePlay() { if (audio.paused) { var p = audio.play(); if (p && p.catch) p.catch(function () {}) } else audio.pause() }
  function chapterStep(dir) {
    var t = globalTime(), ci = chapterIndex(t)
    if (dir < 0) seekTo(ci >= 0 && t - S.chapters[ci].start > 3 ? S.chapters[ci].start : (S.chapters[ci - 1] || { start: 0 }).start)
    else if (S.chapters[ci + 1]) seekTo(S.chapters[ci + 1].start)
  }

  $('play').onclick = togglePlay; $('miniPlay').onclick = function (e) { e.stopPropagation(); togglePlay() }
  $('b30').onclick = function () { seekTo(globalTime() - 30) }; $('b10').onclick = function () { seekTo(globalTime() - 10) }
  $('f10').onclick = function () { seekTo(globalTime() + 10) }; $('f30').onclick = function () { seekTo(globalTime() + 30) }
  $('prevCh').onclick = function () { chapterStep(-1) }; $('nextCh').onclick = function () { chapterStep(1) }
  $('chapSel').onchange = function () { var ch = S.chapters[parseInt(this.value, 10)]; if (ch) seekTo(ch.start) }
  $('seek').oninput = function () { S.drag = true; $('cur').textContent = fmt(this.value / 1000 * S.duration) }
  $('seek').onchange = function () { S.drag = false; seekTo(this.value / 1000 * S.duration) }
  $('back').onclick = function () { showPlayer(false); loadProgress().then(loadContinue) }
  $('mini').onclick = function () { if (S.sess) showPlayer(true) }
  $('speed').onclick = function () { S.speedIdx = (S.speedIdx + 1) % SPEEDS.length; audio.playbackRate = SPEEDS[S.speedIdx]; $('speed').textContent = SPEEDS[S.speedIdx] + 'x'; store('speed', String(S.speedIdx)) }
  $('sleep').onclick = function () {
    S.sleepIdx = (S.sleepIdx + 1) % SLEEPS.length; clearTimeout(S.sleepTimer)
    var m = SLEEPS[S.sleepIdx]; $('sleep').textContent = 'Sleep: ' + (m ? m + 'm' : 'off')
    if (m) S.sleepTimer = setTimeout(function () { audio.pause(); S.sleepIdx = 0; $('sleep').textContent = 'Sleep: off' }, m * 60000)
  }

  var sp = parseInt(store('speed') || '0', 10); if (sp >= 0 && sp < SPEEDS.length) { S.speedIdx = sp; $('speed').textContent = SPEEDS[sp] + 'x' }
  initHome()
})()
