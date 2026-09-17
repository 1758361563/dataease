import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { inferExecutionMode, inferTargetExecutionMode } from './execution-mode.mjs'

describe('execution-mode', () => {
  it('marks dry-run by error text, not by SUCCEEDED', () => {
    assert.equal(
      inferExecutionMode(
        { error_summary: 'dry-run: send skipped', screenshot_status: 'SKIPPED', upload_status: 'SKIPPED' },
        [{ status: 'SUCCEEDED', error_summary: 'dry-run: send skipped' }]
      ),
      'DRY_RUN'
    )
  })

  it('marks LIVE from message id or completed screenshot pipeline', () => {
    assert.equal(
      inferExecutionMode({ screenshot_status: 'SUCCEEDED', upload_status: 'SUCCEEDED' }, [
        { status: 'SUCCEEDED', feishu_message_id: 'om_1' }
      ]),
      'LIVE'
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
  })
})
