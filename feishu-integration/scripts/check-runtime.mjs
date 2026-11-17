import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import vm from 'node:vm'

// Inspect the packaged import graph without starting the worker or its scheduler.
export function checkRuntime(entry) {
  const visited = new Set()
  function visit(filename, importer) {
    const resolved = path.resolve(filename)
    if (visited.has(resolved)) return
    if (!fs.existsSync(resolved)) {
      throw new Error(`Missing runtime module: ${resolved}${importer ? ` (imported by ${importer})` : ''}`)
    }
    visited.add(resolved)
    const source = fs.readFileSync(resolved, 'utf8')
    const imports = /(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s*)['"](\.[^'"]+)['"]/g
    for (const match of source.matchAll(imports)) {
      visit(path.resolve(path.dirname(resolved), match[1]), resolved)
    }
  }
  visit(entry)
  return visited.size
}

// Link modules with Node's ESM parser, without evaluating worker/database/scheduler code.
export async function loadRuntime(entry) {
  const modules = new Map(), external = new Map(), dynamicExternal = []
  function load(filename) {
    filename = path.resolve(filename)
    if (modules.has(filename)) return modules.get(filename)
    if (!fs.existsSync(filename)) throw new Error('Missing runtime module: ' + filename)
    const source = fs.readFileSync(filename, 'utf8')
    const mod = new vm.SourceTextModule(source, { identifier: filename })
    modules.set(filename, mod)
    for (const specifier of mod.dependencySpecifiers) {
      if (specifier.startsWith('.')) load(path.resolve(path.dirname(filename), specifier))
    }
    // All runtime dynamic imports currently use literal relative specifiers.
    for (const match of source.matchAll(/\bimport\s*\(\s*(['"])([^'"]+)\1\s*\)/g)) {
      if (match[2].startsWith('.')) load(path.resolve(path.dirname(filename), match[2]))
      else dynamicExternal.push([match[2], mod])
    }
    const dynamicCount = [...source.matchAll(/\bimport\s*\(/g)].length
    const literalCount = [...source.matchAll(/\bimport\s*\(\s*(['"])([^'"]+)\1\s*\)/g)].length
    if (dynamicCount !== literalCount) throw new Error('Non-literal dynamic import requires explicit packaging support: ' + filename)
    return mod
  }
  load(entry)
  const linker = async (specifier, parent) => {
    if (specifier.startsWith('.')) return load(path.resolve(path.dirname(parent.identifier), specifier))
    const resolved = import.meta.resolve(specifier, pathToFileURL(parent.identifier).href)
    if (!external.has(resolved)) {
      external.set(resolved, (async () => {
        const namespace = await import(resolved)
        return new vm.SyntheticModule(Object.keys(namespace), function () {
          for (const key of Object.keys(namespace)) this.setExport(key, namespace[key])
        })
      })())
    }
    return external.get(resolved)
  }
  for (const [specifier, parent] of dynamicExternal) await linker(specifier, parent)
  for (const mod of modules.values()) if (mod.status === 'unlinked') await mod.link(linker)
  return [...modules.keys()].map(filename => path.relative(path.dirname(path.resolve(entry)), filename)).sort()
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const entry = process.argv[2]
  if (!entry) throw new Error('Usage: node check-runtime.mjs <entry.mjs>')
  const files = await loadRuntime(entry)
  console.log(`Runtime module graph linked: ${files.length} modules\n${files.join('\n')}`)
}
