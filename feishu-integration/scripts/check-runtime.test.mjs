import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { checkRuntime, loadRuntime } from './check-runtime.mjs'

test('Docker COPY list includes the complete worker dependency graph', () => {
  const root = fileURLToPath(new URL('../../', import.meta.url))
  const dockerfile = fs.readFileSync(path.join(root, 'deploy/companybi/Dockerfile'), 'utf8')
  const stage = dockerfile.split('AS integration')[1].split('# Stage 4:')[0]
  const files = [...stage.matchAll(/feishu-integration\/src\/[^\s\\]+\.mjs/g)].map(match => match[0])
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'feishu-runtime-'))
  try {
    for (const file of files) fs.copyFileSync(path.join(root, file), path.join(temporary, path.basename(file)))
    assert.ok(checkRuntime(path.join(temporary, 'embedded-worker.mjs')) > 10)
    fs.unlinkSync(path.join(temporary, 'subscription-content.mjs'))
    assert.throws(() => checkRuntime(path.join(temporary, 'embedded-worker.mjs')), /Missing runtime module:.*subscription-content\.mjs/)
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true })
  }
})


test('Node links dynamic branches and rejects invalid exports without executing worker code', async () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'feishu-link-'))
  try {
    const entry = path.join(temporary, 'entry.mjs')
    fs.writeFileSync(entry, "throw new Error('must not execute'); await import('./branch.mjs')")
    fs.writeFileSync(path.join(temporary, 'branch.mjs'), "import { value } from './value.mjs'; export { value }")
    fs.writeFileSync(path.join(temporary, 'value.mjs'), 'export const value = 1')
    assert.equal((await loadRuntime(entry)).length, 3)
    fs.writeFileSync(path.join(temporary, 'value.mjs'), 'export const wrong = 1')
    await assert.rejects(loadRuntime(entry), /does not provide an export named/)
    fs.unlinkSync(path.join(temporary, 'branch.mjs'))
    await assert.rejects(loadRuntime(entry), /Missing runtime module/)
    fs.writeFileSync(entry, "await import('./' + name)")
    await assert.rejects(loadRuntime(entry), /Non-literal dynamic import/)
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true })
  }
})
