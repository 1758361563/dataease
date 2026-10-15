const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')
const assert = require('node:assert/strict')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const base = path.resolve(__dirname, '../src/views/chart/components/editor/drag-item')
const filename = path.join(base, 'useFieldNameTooltip.ts')
const mounts = [],
  unmounts = [],
  listeners = new Map()
const fakeDocument = {
  addEventListener(event, listener, capture) {
    assert.equal(capture, true)
    if (!listeners.has(event)) listeners.set(event, new Set())
    listeners.get(event).add(listener)
  },
  removeEventListener(event, listener, capture) {
    assert.equal(capture, true)
    listeners.get(event).delete(listener)
  }
}
const loaded = new Module(filename, module)
loaded.require = name =>
  name === 'vue'
    ? {
        ref: value => ({ value }),
        onMounted: callback => mounts.push(callback),
        onBeforeUnmount: callback => unmounts.push(callback)
      }
    : require(name)
// Run the real hook with a deterministic document; no browser globals are required.
const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
// Use a factory scope rather than changing the process-global document.
const factory = new Function('require', 'module', 'exports', 'document', code)
factory(loaded.require, loaded, loaded.exports, fakeDocument)
const first = loaded.exports.useFieldNameTooltip(),
  second = loaded.exports.useFieldNameTooltip()
let closes = 0,
  hides = 0
for (const tooltip of [first, second])
  tooltip.fieldNameTooltip.value = { onClose: () => closes++, hide: () => hides++ }
mounts.forEach(callback => callback())
for (const event of ['pointerdown', 'mousedown', 'dragstart']) {
  first.resumeFieldNameTooltip({ buttons: 0 })
  second.resumeFieldNameTooltip({ buttons: 0 })
  listeners.get(event).forEach(listener => listener())
  assert.equal(first.fieldNameTooltipBlocked.value, true)
  assert.equal(second.fieldNameTooltipBlocked.value, true)
  first.resumeFieldNameTooltip({ buttons: 1 })
  assert.equal(
    first.fieldNameTooltipBlocked.value,
    true,
    'moving across another field while dragging must not reopen hints'
  )
}
assert.equal(closes, 6, 'pending opens must be cancelled for both fields')
assert.equal(hides, 6, 'visible hints must close immediately for both fields')
first.resumeFieldNameTooltip({ buttons: 0 })
assert.equal(first.fieldNameTooltipBlocked.value, false)
first.dismissFieldNameTooltip()
assert.equal(first.fieldNameTooltipBlocked.value, true)
unmounts.forEach(callback => callback())
assert.ok(
  [...listeners.values()].every(set => set.size === 0),
  'unmount must remove global interaction listeners'
)
for (const name of ['QuotaItem.vue', 'DimensionItem.vue']) {
  const file = path.join(base, name),
    { descriptor } = parse(fs.readFileSync(file, 'utf8'))
  compileScript(descriptor, { id: `hint-${name}` })
  assert.deepEqual(
    compileTemplate({ source: descriptor.template.content, filename: file, id: `hint-${name}` })
      .errors,
    []
  )
  const tooltip = descriptor.template.content.match(
    /<el-tooltip\s+ref="fieldNameTooltip"[\s\S]*?>/
  )[0]
  assert.ok(tooltip.includes(':enterable="false"'))
  assert.ok(tooltip.includes(':hide-after="0"'))
  assert.ok(tooltip.includes(':show-after="350"'))
  assert.ok(tooltip.includes("pointerEvents: 'none'"))
  assert.ok(tooltip.includes(':disabled="fieldNameTooltipBlocked"'))
  assert.ok(descriptor.template.content.includes('@mouseleave="dismissFieldNameTooltip"'))
}
console.log(
  'Field name tooltips: click-through configuration, immediate dismissal, pending-open cancellation, drag suppression, re-hover recovery and listener cleanup passed'
)
