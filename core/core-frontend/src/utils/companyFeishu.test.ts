import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  acceptedRunNotice,
  inferExecutionMode,
  inferTargetExecutionMode,
  isRecipientVerified,
  receiveIdTypeFor,
  recipientOptionLabel,
  runStatusLabel,
  verifyStatusLabel
} from './companyFeishu.ts'
import {
  canVisitCompanyPath,
  isCompanyAdminApi,
  requiredCapabilityForPath,
  buildCompanyAdminRoutes
} from './companyNav.ts'

describe('Phase 2F feishu UI helpers', () => {
  it('pairs receiveIdType from explicit recipient type, not id length or prefix', () => {
    assert.equal(receiveIdTypeFor('PERSONAL'), 'user_id')
    assert.equal(receiveIdTypeFor('CHAT'), 'chat_id')
    assert.notEqual(receiveIdTypeFor('PERSONAL'), 'chat_id')
    const personalLookingChat = 'oc_short'
    const longUser = '24d21gc8xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
    assert.equal(receiveIdTypeFor('PERSONAL'), 'user_id')
    assert.equal(receiveIdTypeFor('CHAT'), 'chat_id')
    assert.equal(personalLookingChat.startsWith('oc_'), true)
    assert.equal(longUser.length > personalLookingChat.length, true)
  })

  it('does not disguise unverified recipients as verified', () => {
    assert.equal(isRecipientVerified('UNVERIFIED'), false)
    assert.equal(isRecipientVerified('FAILED'), false)
    assert.equal(isRecipientVerified('VERIFIED'), true)
    assert.equal(verifyStatusLabel('UNVERIFIED'), '未验证')
    assert.equal(verifyStatusLabel(undefined), '未验证')
    const label = recipientOptionLabel({
      displayName: '测试群',
      recipientType: 'CHAT',
      receiveIdType: 'chat_id',
      feishuId: 'oc_36fb1935ef4b79bc11ab80c401f1e741',
      verifyStatus: 'UNVERIFIED'
    })
    assert.match(label, /未验证/)
    assert.doesNotMatch(label, /已验证/)
  })

  it('does not treat accepted/202 as send success', () => {
    const text = acceptedRunNotice({ runId: '7501', status: 'PENDING' })
    assert.match(text, /已受理/)
    assert.match(text, /7501/)
    assert.match(text, /不要当作发送完成/)
    assert.doesNotMatch(text, /发送成功/)
  })

  it('labels dry-run success as simulated, not live send', () => {
    const mode = inferExecutionMode({
      errorSummary: 'dry-run: send skipped',
      screenshotStatus: 'SKIPPED',
      uploadStatus: 'SKIPPED',
      targets: [{ status: 'SUCCEEDED', messageId: null, errorSummary: 'dry-run: send skipped' }]
    })
    assert.equal(mode, 'DRY_RUN')
    assert.equal(runStatusLabel('SUCCEEDED', mode), '模拟执行成功')
    assert.doesNotMatch(runStatusLabel('SUCCEEDED', mode), /真实发送成功/)
    assert.equal(
      runStatusLabel(
        'SUCCEEDED',
        inferTargetExecutionMode({ errorSummary: 'dry-run: send skipped' }, mode)
      ),
      '模拟执行成功'
    )
  })

  it('labels live success only when send evidence exists', () => {
    const byMessage = inferExecutionMode({
      screenshotStatus: 'SUCCEEDED',
      uploadStatus: 'SUCCEEDED',
      targets: [{ status: 'SUCCEEDED', messageId: 'om_live_1' }]
    })
    assert.equal(byMessage, 'LIVE')
    assert.equal(runStatusLabel('SUCCEEDED', byMessage), '真实发送成功')
  })

  it('does not treat historical success without evidence as LIVE', () => {
    const mode = inferExecutionMode({
      screenshotStatus: 'SKIPPED',
      uploadStatus: 'SKIPPED',
      targets: [{ status: 'SUCCEEDED', messageId: null }]
    })
    assert.equal(mode, 'UNKNOWN')
    const text = runStatusLabel('SUCCEEDED', mode)
    assert.equal(text, '执行成功（发送方式未记录）')
    assert.doesNotMatch(text, /真实发送成功/)
    assert.doesNotMatch(text, /模拟执行成功/)
  })

  it('ADMIN feishu route is capability gated', () => {
    assert.equal(requiredCapabilityForPath('/company-admin/feishu'), 'FEISHU_SUBSCRIPTION_MANAGE')
    const hidden = buildCompanyAdminRoutes(['USER_MANAGE'])
    assert.equal(
      hidden[0].children.some((c: { path: string }) => c.path === 'feishu'),
      false
    )
    const shown = buildCompanyAdminRoutes(['USER_MANAGE', 'FEISHU_SUBSCRIPTION_MANAGE'])
    assert.ok(shown[0].children.some((c: { path: string }) => c.path === 'feishu'))
    assert.equal(isCompanyAdminApi('/company/bi/feishu/subscriptions'), true)
    assert.equal(isCompanyAdminApi('/company/bi/admin/users'), true)
    assert.equal(canVisitCompanyPath('/company-admin/feishu', ['DASHBOARD_VIEW']), false)
    assert.equal(canVisitCompanyPath('/company-admin/feishu', ['FEISHU_SUBSCRIPTION_MANAGE']), true)
  })
})
