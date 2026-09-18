import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  canUpgradeExecutionMode,
  desiredExecutionMode,
  inferExecutionMode,
  inferTargetExecutionMode
} from './execution-mode.mjs'

describe('execution-mode', () => {
  it('marks dry-run by stored field or error text, not by SUCCEEDED', () => {
    assert.equal(
      inferExecutionMode(
        { execution_mode: 'DRY_RUN', screenshot_status: 'SUCCEEDED', upload_status: 'SUCCEEDED' },
        [{ status: 'SUCCEEDED', feishu_message_id: 'om_should_not_upgrade' }]
      ),
      'DRY_RUN'
    )
    assert.equal(
      inferExecutionMode(
        { error_summary: 'dry-run: send skipped', screenshot_status: 'SKIPPED', upload_status: 'SKIPPED' },
        [{ status: 'SUCCEEDED', error_summary: 'dry-run: send skipped' }]
      ),
      'DRY_RUN'
    )
  })

  it('marks LIVE from stored field or message id, never from screenshot/upload', () => {
    assert.equal(
      inferExecutionMode({ execution_mode: 'LIVE', screenshot_status: 'FAILED' }, [
        { status: 'FAILED' }
      ]),
      'LIVE'
    )
    assert.equal(
      inferExecutionMode({ screenshot_status: 'SUCCEEDED', upload_status: 'SUCCEEDED' }, [
        { status: 'SUCCEEDED', feishu_message_id: 'om_1' }
      ]),
      'LIVE'
    )
    assert.equal(
      inferExecutionMode({ screenshot_status: 'SUCCEEDED', upload_status: 'SUCCEEDED' }, [
        { status: 'SUCCEEDED' }
      ]),
      'UNKNOWN'
    )
  })

  it('keeps historical success without evidence as UNKNOWN', () => {
    assert.equal(
      inferExecutionMode({ screenshot_status: 'SKIPPED', upload_status: 'SKIPPED', status: 'SUCCEEDED' }, [
        { status: 'SUCCEEDED' }
      ]),
      'UNKNOWN'
    )
    assert.equal(inferTargetExecutionMode({ status: 'SUCCEEDED' }, 'UNKNOWN'), 'UNKNOWN')
    assert.equal(
      inferExecutionMode({ execution_mode: 'UNKNOWN', screenshot_status: 'SUCCEEDED', upload_status: 'SUCCEEDED' }, [
        { status: 'SUCCEEDED' }
      ]),
      'UNKNOWN'
    )
  })

  it('refuses to upgrade a dry-run record to live', () => {
    assert.equal(canUpgradeExecutionMode('DRY_RUN', 'LIVE'), false)
    assert.equal(desiredExecutionMode({ configDryRun: true }), 'DRY_RUN')
    assert.equal(desiredExecutionMode({ dryRun: false, configDryRun: false }), 'LIVE')
  })
})
