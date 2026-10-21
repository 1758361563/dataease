const fs = require('node:fs'),
  path = require('node:path'),
  Module = require('node:module')
const assert = require('node:assert/strict'),
  ts = require('typescript'),
  less = require('less')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const file = path.resolve(__dirname, '../src/custom/company-bi/utils/dashboardUiPalette.ts')
const mod = new Module(file)
mod.paths = module.paths
mod._compile(
  ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS }
  }).outputText,
  file
)
const { dashboardUiColor } = mod.exports
for (const value of ['#000', '#000000', '#1F2329', 'rgba(31, 35, 41, 1)']) {
  assert.equal(dashboardUiColor(value, 'muted'), 'var(--bi-text-muted, #7b879b)')
  assert.equal(dashboardUiColor(value, 'text'), 'var(--bi-text-primary, #37465f)')
}
assert.equal(dashboardUiColor('rgba(245,245,245,1)', 'surface'), 'var(--bi-bg-subtle, #f3f5f8)')
assert.equal(dashboardUiColor('#ffffffff', 'surface'), 'var(--bi-bg-surface, #ffffff)')
for (const custom of ['#7c3aed', '#fff', 'rgba(31,35,41,0.4)', 'red'])
  assert.equal(dashboardUiColor(custom, 'text'), custom)
assert.equal(dashboardUiColor('#131C42', 'surface'), '#131C42')
async function main() {
  for (const relative of [
    'custom-component/de-tabs/Component.vue',
    'custom-component/de-tabs/DeCustomTab.vue'
  ]) {
    const filename = path.resolve(__dirname, '../src', relative),
      source = fs.readFileSync(filename, 'utf8')
    const { descriptor, errors } = parse(source, { filename })
    assert.deepEqual(errors, [])
    const script = compileScript(descriptor, { id: 'dashboard-palette' })
    const template = compileTemplate({
      source: descriptor.template.content,
      filename,
      id: 'dashboard-palette',
      compilerOptions: { bindingMetadata: script.bindings }
    })
    assert.deepEqual(template.errors, [])
  }
  const entry = path.resolve(__dirname, '../src/custom/company-bi/index.less')
  const variables = path.resolve(__dirname, '../src/style/variable.less').replace(/\\/g, '/')
  const css = (
    await less.render(`@import (reference) "${variables}";\n` + fs.readFileSync(entry, 'utf8'), {
      filename: entry,
      javascriptEnabled: true
    })
  ).css
  assert.match(css, /background: var\(--bi-bg-subtle\)/)
  assert.match(css, /color: var\(--de-font-color, var\(--bi-text-muted\)\)/)
  const density = fs.readFileSync(
    path.resolve(__dirname, '../src/custom/company-bi/style/dashboard-density.less'),
    'utf8'
  )
  assert.doesNotMatch(density, /font-size: 16px !important|--ed-input-height: 36px !important/)
  console.log(
    'Dashboard neutral palette, preserved custom/dark colors, Tab Vue compilation and full company Less layer passed'
  )
}
main().catch(error => {
  console.error(error)
  process.exit(1)
})
