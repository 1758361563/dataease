const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const { baseParse, NodeTypes } = require('@vue/compiler-dom')

const file = path.resolve(__dirname, '../src/views/chart/components/editor/drag-item/QuotaItem.vue')
const { descriptor } = parse(fs.readFileSync(file, 'utf8'))
const ast = baseParse(descriptor.template.content)
const roots = ast.children.filter(node => node.type === NodeTypes.ELEMENT)
assert.equal(
  roots.length,
  1,
  'Metric drag attributes must fall through to one DOM root, not a fragment'
)
assert.equal(roots[0].tag, 'span')
assert.ok(
  roots[0].props.some(prop => prop.name === 'class' && prop.value?.content === 'item-style')
)
assert.ok(
  roots[0].children.some(node => node.type === NodeTypes.ELEMENT && node.tag === 'el-dialog'),
  'Percentage dialog must remain inside the metric component root'
)
compileScript(descriptor, { id: 'quota-drag-regression' })
assert.deepEqual(
  compileTemplate({
    source: descriptor.template.content,
    filename: file,
    id: 'quota-drag-regression'
  }).errors,
  []
)
console.log(
  'Metric dragging: single DOM root retains Sortable attributes and percentage dialog; Vue compilation passed'
)
