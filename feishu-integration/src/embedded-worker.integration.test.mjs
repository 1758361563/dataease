import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createCipheriv, randomBytes } from 'node:crypto'
import { once } from 'node:events'
import readline from 'node:readline'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import mysql from 'mysql2/promise'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
test('product executor: boot, lease, full send workflow, schedule, history isolation and shutdown', {
  skip: !process.env.BI_WORKER_TEST_DB_PORT, timeout: 95000
}, async () => {
  // Run only against the explicitly created, isolated test database.
  const database = `companybi_integration_test_${process.pid}`
  const connection = await mysql.createConnection({ host: '127.0.0.1', port: Number(process.env.BI_WORKER_TEST_DB_PORT),
    user: 'root', password: 'integration-test-only', multipleStatements: true,
    supportBigNumbers: true, bigNumberStrings: true })
  const children = []
  let diagnostics = ''
  try {
    await connection.query(`CREATE DATABASE \`${database}\``)
    await connection.changeUser({ database })
    for (const file of ['company_feishu_app_config', 'company_feishu_recipient', 'company_feishu_subscription',
      'company_feishu_subscription_recipient', 'company_feishu_delivery_run', 'company_feishu_delivery_target', 'company_integration_connection']) {
      await connection.query(fs.readFileSync(path.resolve(root, '../core/core-backend/src/main/resources/db/company', file + '.sql'), 'utf8'))
    }
    const [columns] = await connection.query("SHOW COLUMNS FROM company_feishu_subscription LIKE 'delivery_lane'")
    if (!columns.length) await connection.query("ALTER TABLE company_feishu_subscription ADD COLUMN delivery_lane VARCHAR(16) NOT NULL DEFAULT 'TEST'")
    const config = { appId: 'cli_worker_fixture', runtimeEnabled: true, screenshotUser: 'fixture-capture' }
    const key = randomBytes(32), iv = randomBytes(12)
    const cipher = createCipheriv('aes-256-gcm', key, iv); cipher.setAAD(Buffer.from('FEISHU:default'))
    const encrypted = Buffer.concat([cipher.update(JSON.stringify({ appSecret: 'fixture-not-a-real-secret', screenshotPassword: 'fixture-password' })), cipher.final(), cipher.getAuthTag()])
    await connection.query(`INSERT INTO company_integration_connection VALUES (?,?,?,?,?,?)`,
      ['FEISHU', 'default', JSON.stringify(config), `v1.${iv.toString('base64')}.${encrypted.toString('base64')}`, Date.now(), 1])
    await connection.query(`INSERT INTO company_feishu_app_config (id,app_id,app_secret_ref,status,created_at,updated_at)
      VALUES (1,?,'BI_MANAGED_FEISHU_SECRET','ENABLED',1,1)`, [config.appId])
    await connection.query(`INSERT INTO company_feishu_recipient
      (id,recipient_type,receive_id_type,display_name,feishu_id,app_id,source,identity_status,verify_status,status,created_at,updated_at)
      VALUES (7500000000000000123,'PERSONAL','user_id','Fixture','fixture_user',?,'CONTACT_LOOKUP','CONFIRMED','UNVERIFIED','ENABLED',1,1)`, [config.appId])
    await connection.query(`INSERT INTO company_feishu_subscription
      (id,name,dashboard_id,chart_id,status,created_at,updated_at,created_by,delivery_lane)
      VALUES (7500000000000000456,'Fixture','123','456','ENABLED',1,1,1,'PROD')`)
    await connection.query(`INSERT INTO company_feishu_subscription_recipient (id,subscription_id,recipient_id,created_at)
      VALUES (1,7500000000000000456,7500000000000000123,1)`)
    const env = { ...process.env, COMPANY_BI_EMBEDDED_WORKER: 'true', COMPANY_BI_INTEGRATION_KEY: key.toString('base64'),
      DE_MYSQL_HOST: '127.0.0.1', DE_MYSQL_PORT: process.env.BI_WORKER_TEST_DB_PORT, DE_MYSQL_USER: 'root',
      DE_MYSQL_PASSWORD: 'integration-test-only', DE_MYSQL_DATABASE: database, DE_MYSQL_TRANSPORT: 'tcp',
      FEISHU_TMP_DIR: path.join(root, 'tmp/embedded-integration-test'), FEISHU_LOG_DIR: path.join(root, 'tmp/embedded-integration-test/log') }
    const start = () => {
      const child = spawn(process.execPath, ['--experimental-loader', pathToFileURL(path.join(root, 'src/embedded-worker-test-loader.mjs')).href, 'src/embedded-worker.mjs'], { cwd: root, env, windowsHide: true,
        stdio: ['pipe', 'pipe', 'pipe'] })
      child.stderr.on('data', chunk => { diagnostics = (diagnostics + chunk.toString()).slice(-6000) }); children.push(child); return child
    }
    const child = start()
    const lines = readline.createInterface({ input: child.stdout })
    const responses = []; lines.on('line', line => responses.push(JSON.parse(line)))
    const wait = async predicate => {
      const until = Date.now() + 15000
      while (Date.now() < until) { const value = responses.find(predicate); if (value) return value; await new Promise(r => setTimeout(r, 20)) }
      throw new Error('worker protocol response timed out: ' + diagnostics)
    }
    await wait(r => r.ready)
    const duplicate = start(); const duplicateExit = once(duplicate, 'exit')
    assert.notEqual((await duplicateExit)[0], 0, 'second worker must refuse to acquire the same metadata database')
    child.stdin.write(JSON.stringify({ id: 'start', method: 'start', args: { subscriptionId: '7500000000000000456', operatorUserId: '7500000000000000122', dryRun: false } }) + '\n')
    const accepted = (await wait(r => r.id === 'start')).result
    assert.equal(accepted.accepted, true)
    let run
    for (let i = 0; i < 100; i++) {
      const [rows] = await connection.query('SELECT * FROM company_feishu_delivery_run WHERE id=?', [accepted.runId]); run = rows[0]
      if (run.status === 'SUCCEEDED') break
      await new Promise(r => setTimeout(r, 30))
    }
    assert.equal(run.subscription_id, '7500000000000000456', 'snowflake IDs retain exact precision')
    assert.equal(run.operator_user_id, '7500000000000000122', 'audit user IDs retain exact precision across Java/Node RPC')
    assert.equal(run.execution_mode, 'LIVE'); assert.equal(run.status, 'SUCCEEDED')
    assert.equal(run.screenshot_status, 'SUCCEEDED'); assert.equal(run.upload_status, 'SUCCEEDED')
    const [targets] = await connection.query('SELECT * FROM company_feishu_delivery_target WHERE run_id=?', [accepted.runId])
    assert.equal(targets[0].recipient_id, '7500000000000000123'); assert.equal(targets[0].feishu_message_id, 'fixture-message-1')
    // Historical records without delivery must never be upgraded into new sends.
    await connection.query(`INSERT INTO company_feishu_delivery_run
      (id,subscription_id,trigger_type,planned_at,operator_user_id,status,execution_mode,created_at)
      VALUES (7500000000000000789,7500000000000000456,'MANUAL',2,1,'PENDING','DRY_RUN',1)`)
    let recovered
    for (let i = 0; i < 350; i++) {
      const [rows] = await connection.query('SELECT status,error_summary FROM company_feishu_delivery_run WHERE id=7500000000000000789')
      recovered = rows[0]; if (recovered.status === 'SKIPPED') break
      await new Promise(r => setTimeout(r, 100))
    }
    assert.equal(recovered.status, 'SKIPPED')
    assert.match(recovered.error_summary, /历史未投递记录/)
    await connection.query('UPDATE company_feishu_subscription SET schedule_json=?', [JSON.stringify({kind:'daily',time:'00:00',autoRun:true})])
    let scheduled;
    for (let i = 0; i < 350; i++) {
      const [rows] = await connection.query("SELECT status,execution_mode FROM company_feishu_delivery_run WHERE trigger_type='SCHEDULED'");
      if (rows[0]?.status === 'SUCCEEDED') { scheduled=rows[0]; break; }
      await new Promise(r => setTimeout(r,100));
    }
    assert.equal(scheduled?.execution_mode, 'LIVE', 'scheduling uses the same product send workflow without a global test switch')
    await connection.query('UPDATE company_integration_connection SET config_json=?', [JSON.stringify({ ...config, runtimeEnabled: false })])
    child.stdin.write(JSON.stringify({ id: 'disabled', method: 'search', args: { query: 'fixture' } }) + '\n')
    assert.ok((await wait(r => r.id === 'disabled')).error)
    const exited = once(child, 'exit'); child.stdin.end(); assert.equal((await exited)[0], 0)
  } finally {
    for (const child of children) if (child.exitCode === null) child.kill()
    await connection.end()
  }
})
