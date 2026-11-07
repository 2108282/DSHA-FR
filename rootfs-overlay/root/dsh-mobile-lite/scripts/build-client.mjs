import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { dirname, join, posix } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(fileURLToPath(new URL('../package.json', import.meta.url)))
const buildDir = join(root, '.client-build')
const outputPath = join(root, 'lib', 'client.js')

async function collectSources(dir, { rel = '' } = {}) {
  const sources = new Map()
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const abs = join(dir, entry.name)
    const relPath = rel ? `${rel}/${entry.name}` : entry.name
    if (entry.isDirectory()) {
      for (const [k, v] of await collectSources(abs, { rel: relPath })) {
        sources.set(k, v)
      }
    } else if (entry.isFile() && entry.name.endsWith('.js')) {
      sources.set(
        relPath,
        (await readFile(abs, 'utf8')).replace(/\n?\/\/# sourceMappingURL=.*$/u, ''),
      )
    }
  }
  return sources
}

const sources = await collectSources(buildDir)

const REQUIRE_RE = /require\("(\.[^"]+\.js)"\)/g
const resolveChild = (parent, rel) => {
  const joined = posix.join(posix.dirname(parent), rel)
  const normalized = posix.normalize(joined)
  return normalized === '.' ? '' : normalized
}

const visited = new Set()
const order = []
const visit = (file) => {
  if (visited.has(file)) return
  visited.add(file)
  const src = sources.get(file)
  if (!src) throw new Error(`client module not found for require: ${file}`)
  for (const match of src.matchAll(REQUIRE_RE)) {
    visit(resolveChild(file, match[1]))
  }
  order.push(file)
}
visit('index.js')

const modules = order
  .map((file) => {
    const src = sources.get(file).replace(REQUIRE_RE, (m, rel) => `require("./${resolveChild(file, rel)}")`)
    return `__modules[${JSON.stringify(file)}] = function (require, module, exports) {\n${src}\n};`
  })
  .join('\n')

const wrapped = [
  'window.__ModuleLoader__.load({ id: "dsh-mobile-lite", factory: (require) => {',
  'var __modules = {};',
  modules,
  'var __cache = {};',
  'function __localRequire(id) {',
  '  if (id.charCodeAt(0) !== 46) return require(id);',
  '  id = id.slice(2);',
  '  var cached = __cache[id];',
  '  if (cached) return cached.exports;',
  '  var module = { exports: {} };',
  '  __cache[id] = module;',
  '  __modules[id](__localRequire, module, module.exports);',
  '  return module.exports;',
  '}',
  'var module = { exports: {} };',
  '__modules["index.js"](__localRequire, module, module.exports);',
  'return module.exports; } });',
  '',
].join('\n')

await mkdir(dirname(outputPath), { recursive: true })
await writeFile(outputPath, wrapped)
await rm(buildDir, { recursive: true, force: true })
console.log(`client bundle written: ${outputPath} (${order.length} modules inlined)`)
