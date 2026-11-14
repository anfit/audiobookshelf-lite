// Minimal fake Audiobookshelf for testing the lite client + proxy on localhost. Test credentials only.
// Mimics: POST /login, GET /api/{libraries,me,me/items-in-progress,libraries/:id/items,libraries/:id/search,items/:id/cover},
// POST /api/items/:id/play, /api/session/:id/{sync,close}, GET /public/session/:id/track/:n (WAV with Range support).
// Everything lives under /audiobookshelf like the real instance. /api/* requires "Authorization: Bearer <token>".
'use strict'
const http = require('http')
const PORT = parseInt(process.env.MOCK_PORT, 10) || 3335
const BASE = '/audiobookshelf'
const TEST_USER = 'test', TEST_PASS = 'test', TOKEN = 'mock-access-token'
const log = []

function wav(seconds, freq) {
  const rate = 8000, n = rate * seconds, buf = Buffer.alloc(44 + n * 2)
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + n * 2, 4); buf.write('WAVEfmt ', 8); buf.writeUInt32LE(16, 16)
  buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22); buf.writeUInt32LE(rate, 24); buf.writeUInt32LE(rate * 2, 28)
  buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34); buf.write('data', 36); buf.writeUInt32LE(n * 2, 40)
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin((2 * Math.PI * freq * i) / rate) * 6000), 44 + i * 2)
  return buf
}
const TRACKS = [wav(30, 440), wav(30, 660)]
// 1x1 PNG
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')

const items = [
  { id: 'item-1', libraryId: 'lib-1', media: { duration: 60, metadata: { title: 'Mock Book One', authorName: 'A. Tester' } } },
  { id: 'item-2', libraryId: 'lib-1', media: { duration: 60, metadata: { title: 'Another Story', authorName: 'B. Writer' } } }
]
const progress = {}

function json(res, code, obj) { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)) }
function body(req) { return new Promise((r) => { const c = []; req.on('data', (d) => c.push(d)); req.on('end', () => { try { r(JSON.parse(Buffer.concat(c).toString() || '{}')) } catch (e) { r({}) } }) }) }

http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x'), p = u.pathname
  if (p === '/__log') return json(res, 200, log)
  if (!p.startsWith(BASE + '/')) { res.writeHead(404); return res.end('not found') }
  const r = p.slice(BASE.length)
  log.push(req.method + ' ' + r + (req.headers.authorization ? ' [auth]' : ' [noauth]'))

  if (r === '/login' && req.method === 'POST') {
    const b = await body(req)
    if (b.username !== TEST_USER || b.password !== TEST_PASS) return json(res, 401, { error: 'bad credentials' })
    return json(res, 200, { user: { id: 'u1', username: TEST_USER, accessToken: TOKEN, refreshToken: req.headers['x-return-tokens'] === 'true' ? 'mock-refresh' : null, mediaProgress: Object.values(progress) } })
  }
  const m = r.match(/^\/public\/session\/([^/]+)\/track\/(\d+)$/)
  if (m) { // public route: no auth, Range supported
    const data = TRACKS[parseInt(m[2], 10) - 1]; if (!data) { res.writeHead(404); return res.end() }
    const range = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '')
    if (range) {
      const s = range[1] ? parseInt(range[1], 10) : 0, e = range[2] ? Math.min(parseInt(range[2], 10), data.length - 1) : data.length - 1
      res.writeHead(206, { 'content-type': 'audio/wav', 'accept-ranges': 'bytes', 'content-range': `bytes ${s}-${e}/${data.length}`, 'content-length': e - s + 1 })
      return res.end(data.subarray(s, e + 1))
    }
    res.writeHead(200, { 'content-type': 'audio/wav', 'accept-ranges': 'bytes', 'content-length': data.length })
    return res.end(data)
  }
  if (r.startsWith('/api/') && req.headers.authorization !== 'Bearer ' + TOKEN) return json(res, 401, { error: 'unauthorized' })

  if (r === '/api/libraries') return json(res, 200, { libraries: [{ id: 'lib-1', name: 'Mock Books', mediaType: 'book' }] })
  if (r === '/api/me') return json(res, 200, { id: 'u1', mediaProgress: Object.values(progress) })
  if (r === '/api/me/items-in-progress') return json(res, 200, { libraryItems: items.filter((i) => progress[i.id] && !progress[i.id].isFinished) })
  if (r === '/api/libraries/lib-1/items') return json(res, 200, { results: items, total: items.length, page: 0 })
  if (r === '/api/libraries/lib-1/search') { const q = (u.searchParams.get('q') || '').toLowerCase(); return json(res, 200, { book: items.filter((i) => i.media.metadata.title.toLowerCase().includes(q)).map((libraryItem) => ({ libraryItem })) }) }
  if (/^\/api\/items\/[^/]+\/cover$/.test(r)) { res.writeHead(200, { 'content-type': 'image/png' }); return res.end(PNG) }
  const play = r.match(/^\/api\/items\/([^/]+)\/play$/)
  if (play && req.method === 'POST') {
    const b = await body(req), sid = 'sess-' + play[1]
    const cur = (progress[play[1]] && progress[play[1]].currentTime) || 0
    log.push('PLAY body mimes=' + JSON.stringify(b.supportedMimeTypes))
    return json(res, 200, { id: sid, libraryItemId: play[1], duration: 60, currentTime: cur, startTime: cur, displayTitle: 'x', chapters: [{ id: 0, start: 0, end: 20, title: 'Intro' }, { id: 1, start: 20, end: 40, title: 'Middle' }, { id: 2, start: 40, end: 60, title: 'End' }], audioTracks: [{ index: 1, startOffset: 0, duration: 30, contentUrl: `/public/session/${sid}/track/1`, mimeType: 'audio/wav' }, { index: 2, startOffset: 30, duration: 30, contentUrl: `/public/session/${sid}/track/2`, mimeType: 'audio/wav' }] })
  }
  const sess = r.match(/^\/api\/session\/sess-([^/]+)\/(sync|close)$/)
  if (sess && req.method === 'POST') {
    const b = await body(req); const id = sess[1]
    progress[id] = { libraryItemId: id, currentTime: b.currentTime, progress: (b.currentTime || 0) / 60, isFinished: false }
    log.push(`${sess[2].toUpperCase()} ${id} t=${Math.round(b.currentTime)} listened=${b.timeListened}`)
    return json(res, 200, { success: true })
  }
  json(res, 404, { error: 'mock: unhandled ' + r })
}).listen(PORT, '127.0.0.1', () => console.log('mock ABS on http://127.0.0.1:' + PORT + BASE))
