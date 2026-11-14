// audiobookshelf-lite: zero-dependency static server + same-origin pass-through to Audiobookshelf.
// Single user, single instance: credentials live in config.json. This server logs in to Audiobookshelf itself
// and attaches the access token to every forwarded request, so the browser needs no login or tokens.
// Audiobookshelf itself is not modified; requests (incl. Range for audio seeking) are only relayed.
//
//   node server.js                       (uses config.json)
//   ABS_URL=http://127.0.0.1:3335/audiobookshelf PORT=3334 node server.js   (env overrides, e.g. for the mock)
'use strict'
const http = require('http')
const https = require('https')
const fs = require('fs')
const path = require('path')

const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, 'config.json'), 'utf8'))
const TARGET = new URL(process.env.ABS_URL || cfg.absUrl)
const USERNAME = process.env.ABS_USER || cfg.username
const PASSWORD = process.env.ABS_PASS || cfg.password
const PORT = parseInt(process.env.PORT, 10) || cfg.port || 3334
const HOST = process.env.HOST || '0.0.0.0'
const PUBLIC_DIR = path.join(__dirname, 'public')
const PREFIX = '/abs'
const BASE_PATH = TARGET.pathname.replace(/\/+$/, '')
const lib = TARGET.protocol === 'https:' ? https : http
const MAX_TOKEN_AGE_MS = 45 * 60 * 1000 // ABS access tokens last 1h by default

const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.webmanifest': 'application/manifest+json' }

let token = null
let tokenAt = 0
let loginPromise = null

function login() {
  if (loginPromise) return loginPromise
  loginPromise = new Promise((resolve, reject) => {
    const body = JSON.stringify({ username: USERNAME, password: PASSWORD })
    const req = lib.request({ protocol: TARGET.protocol, hostname: TARGET.hostname, port: TARGET.port || undefined, path: BASE_PATH + '/login', method: 'POST', headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), 'x-return-tokens': 'true' } }, (res) => {
      const chunks = []
      res.on('data', (c) => chunks.push(c))
      res.on('end', () => {
        try {
          const j = JSON.parse(Buffer.concat(chunks).toString('utf8'))
          const t = j.user && j.user.accessToken
          if (res.statusCode !== 200 || !t) return reject(new Error('login failed (HTTP ' + res.statusCode + ')'))
          token = t
          tokenAt = Date.now()
          console.log('logged in to Audiobookshelf as', USERNAME)
          resolve(t)
        } catch (e) {
          reject(new Error('login failed: bad response (HTTP ' + res.statusCode + ')'))
        }
      })
    })
    req.on('error', reject)
    req.end(body)
  }).finally(() => {
    loginPromise = null
  })
  return loginPromise
}

async function getToken() {
  if (!token || Date.now() - tokenAt > MAX_TOKEN_AGE_MS) await login()
  return token
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    if (req.method === 'GET' || req.method === 'HEAD') return resolve(null)
    const chunks = []
    let size = 0
    req.on('data', (c) => {
      size += c.length
      if (size > 1024 * 1024) {
        reject(new Error('body too large'))
        req.destroy()
      } else chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

function forward(req, res, body, retried) {
  getToken()
    .then((t) => {
      const headers = Object.assign({}, req.headers, { host: TARGET.host, authorization: 'Bearer ' + t })
      delete headers.origin
      delete headers.referer
      delete headers.cookie
      if (body) headers['content-length'] = body.length
      const up = lib.request({ protocol: TARGET.protocol, hostname: TARGET.hostname, port: TARGET.port || undefined, path: BASE_PATH + req.url.slice(PREFIX.length), method: req.method, headers }, (upRes) => {
        if (upRes.statusCode === 401 && !retried) {
          upRes.resume()
          token = null
          return forward(req, res, body, true)
        }
        res.writeHead(upRes.statusCode, upRes.headers)
        upRes.pipe(res)
      })
      up.on('error', (err) => fail(res, 502, 'Upstream error: ' + err.message))
      res.on('close', () => up.destroy())
      up.end(body || undefined)
    })
    .catch((err) => fail(res, 502, err.message))
}

function fail(res, status, msg) {
  if (!res.headersSent) res.writeHead(status, { 'content-type': 'text/plain' })
  res.end(msg)
}

function serveStatic(req, res) {
  let pathname
  try {
    pathname = decodeURIComponent(new URL(req.url, 'http://x').pathname)
  } catch (e) {
    return fail(res, 400, 'Bad request')
  }
  if (pathname === '/') pathname = '/index.html'
  const file = path.normalize(path.join(PUBLIC_DIR, pathname))
  if (!file.startsWith(PUBLIC_DIR + path.sep)) return fail(res, 403, 'Forbidden')
  fs.readFile(file, (err, data) => {
    if (err) return fail(res, 404, 'Not found')
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream', 'cache-control': 'no-cache' })
    res.end(data)
  })
}

http
  .createServer((req, res) => {
    if (req.url === PREFIX || req.url.startsWith(PREFIX + '/') || req.url.startsWith(PREFIX + '?')) {
      return readBody(req).then((body) => forward(req, res, body, false), (err) => fail(res, 413, err.message))
    }
    serveStatic(req, res)
  })
  .listen(PORT, HOST, () => console.log(`audiobookshelf-lite on http://${HOST}:${PORT}  ->  ${TARGET.href}`))
