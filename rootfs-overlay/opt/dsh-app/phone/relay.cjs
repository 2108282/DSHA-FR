#!/usr/bin/env node
// dsh-app phone relay: MCP stdio <-> the DSH App's phone bridge. Rewritten by the app at every
// engine start; edits do not survive. Carries no tool definitions (they live in the APK).
'use strict'
const fs = require('fs')
const net = require('net')

const CONFIG = process.argv[2] || '/var/lib/dsh-app/phone/bridge.json'
function say (line) { try { process.stderr.write('[phone-relay] ' + line + '\n') } catch (e) {} }

let cfg
try {
  cfg = JSON.parse(fs.readFileSync(CONFIG, 'utf8'))
} catch (e) {
  say('phone control is not available (' + CONFIG + ': ' + (e.code || e.message) + ')')
  process.exit(1)
}
const targets = []
if (typeof cfg.abstract === 'string' && cfg.abstract) targets.push({ path: '\0' + cfg.abstract, what: 'unix @' + cfg.abstract })
if (Number.isInteger(cfg.tcpPort) && cfg.tcpPort > 0) targets.push({ host: '127.0.0.1', port: cfg.tcpPort, what: 'tcp 127.0.0.1:' + cfg.tcpPort })

process.stdout.on('error', function (e) { process.exit(e.code === 'EPIPE' ? 0 : 1) })

function attempt (i) {
  if (i >= targets.length) { say('phone bridge unreachable'); process.exit(1) }
  const t = targets[i]
  const sock = net.connect(t.port ? { host: t.host, port: t.port } : { path: t.path })
  let up = false
  const timer = setTimeout(function () { sock.destroy(new Error('connect timeout')) }, 3000)
  sock.once('connect', function () {
    up = true
    clearTimeout(timer)
    if (sock.setNoDelay) sock.setNoDelay(true)
    sock.write(JSON.stringify({ dshPhone: 1, token: String(cfg.token || '') }) + '\n')
    process.stdin.pipe(sock)
    sock.pipe(process.stdout)
  })
  sock.on('error', function (e) {
    clearTimeout(timer)
    if (!up) { say(t.what + ': ' + (e.code || e.message)); attempt(i + 1); return }
    say('bridge connection error: ' + (e.code || e.message))
    process.exit(1)
  })
  sock.on('close', function () { if (up) process.exit(0) })
}
attempt(0)
