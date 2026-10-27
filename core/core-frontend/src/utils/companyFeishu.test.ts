import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  acceptedRunNotice,
  DATA_UPDATE_UNCHECKED,
  dataUpdateStatusLabel,
  inferExecutionMode,
  inferTargetExecutionMode,
  isRecipientVerified,
  identityStatusLabel,
  employeeMatchLabel,
  receiveIdTypeFor,
  recipientOptionLabel,
  runStatusLabel,
  verifyStatusLabel
} from './companyFeishu.ts'
import {
  canVisitCompanyPath,
  isCompanyAdminApi,
  requiredCapabilityForPath,
  buildCompanyAdminRoutes,
  buildIntegrationRoutes
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
    assert.equal(verifyStatusLabel('UNVERIFIED'), '发送未验证')
    assert.equal(verifyStatusLabel(undefined), '发送未验证')
    assert.equal(identityStatusLabel('CONFIRMED'), '身份已确认')
    assert.equal(identityStatusLabel('UNCONFIRMED'), '身份未确认')
    const label = recipientOptionLabel({
      displayName: '测试群',
      recipientType: 'CHAT',
      receiveIdType: 'chat_id',
      feishuId: 'oc_36fb1935ef4b79bc11ab80c401f1e741',
      identityStatus: 'UNCONFIRMED',
      verifyStatus: 'UNVERIFIED'
    })
    assert.match(label, /发送未验证/)
    assert.match(label, /身份未确认/)
    assert.doesNotMatch(label, /发送已验证/)
    assert.doesNotMatch(label, /身份已确认/)
  })

  it('keeps same-name employees distinguishable and does not treat identity as send success', () => {
    const one = employeeMatchLabel({ name: '张三', departments: ['销售'], userIdTail: 'sale' })
    const two = employeeMatchLabel({ name: '张三', departments: ['财务'], userIdTail: '_fin' })
    assert.notEqual(one, two)
    assert.match(one, /销售/)
    assert.match(two, /财务/)
    const confirmedUnsent = recipientOptionLabel({
      displayName: '张三',
      recipientType: 'PERSONAL',
      receiveIdType: 'user_id',
      feishuId: 'uid_fin',
      identityStatus: 'CONFIRMED',
      verifyStatus: 'UNVERIFIED'
    })
    assert.match(confirmedUnsent, /身份已确认/)
    assert.match(confirmedUnsent, /发送未验证/)
    assert.doesNotMatch(confirmedUnsent, /发送已验证/)
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
    assert.equal(runStatusLabel('SUCCEEDED', mode), '未发送（历史记录）')
    assert.doesNotMatch(runStatusLabel('SUCCEEDED', mode), /发送成功/)
    assert.equal(
      runStatusLabel(
        'SUCCEEDED',
        inferTargetExecutionMode({ errorSummary: 'dry-run: send skipped' }, mode)
      ),
      '未发送（历史记录）'
    )
  })

  it('labels live success only when send evidence exists', () => {
    const byMessage = inferExecutionMode({
      screenshotStatus: 'SUCCEEDED',
      uploadStatus: 'SUCCEEDED',
      targets: [{ status: 'SUCCEEDED', messageId: 'om_live_1' }]
    })
    assert.equal(byMessage, 'LIVE')
    assert.equal(runStatusLabel('SUCCEEDED', byMessage), '发送成功')
  })

  it('does not treat screenshot/upload success as live send', () => {
    const mode = inferExecutionMode({
      screenshotStatus: 'SUCCEEDED',
      uploadStatus: 'SUCCEEDED',
      targets: [{ status: 'SUCCEEDED', messageId: null }]
    })
    assert.equal(mode, 'UNKNOWN')
    assert.equal(runStatusLabel('SUCCEEDED', mode), '执行成功（发送方式未记录）')
  })

  it('keeps explicit DRY_RUN even if a messageId is present', () => {
    const mode = inferExecutionMode({
      executionMode: 'DRY_RUN',
      screenshotStatus: 'SUCCEEDED',
      uploadStatus: 'SUCCEEDED',
      targets: [{ status: 'SUCCEEDED', messageId: 'om_should_not_upgrade' }]
    })
    assert.equal(mode, 'DRY_RUN')
    assert.equal(runStatusLabel('SUCCEEDED', mode), '未发送（历史记录）')
  })

  it('shows live mixed recipients separately', () => {
    const runMode = inferExecutionMode({
      executionMode: 'LIVE',
      status: 'FAILED',
      targets: [
        { status: 'SUCCEEDED', messageId: 'om_ok' },
        { status: 'FAILED', messageId: null, errorSummary: 'unreachable' }
      ]
    })
    assert.equal(runMode, 'LIVE')
    assert.equal(runStatusLabel('FAILED', runMode), '发送失败')
    assert.equal(
      runStatusLabel(
        'SUCCEEDED',
        inferTargetExecutionMode({ status: 'SUCCEEDED', messageId: 'om_ok' }, runMode)
      ),
      '发送成功'
    )
    assert.equal(
      runStatusLabel(
        'FAILED',
        inferTargetExecutionMode({ status: 'FAILED', errorSummary: 'unreachable' }, runMode)
      ),
      '发送失败'
    )
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
    assert.doesNotMatch(text, /发送成功/)
    assert.doesNotMatch(text, /未发送（历史记录）/)
  })

  it('shows unchecked data status until an ETL task is linked', () => {
    assert.equal(dataUpdateStatusLabel(null), DATA_UPDATE_UNCHECKED)
    assert.equal(dataUpdateStatusLabel('{"requireEtl":true}'), DATA_UPDATE_UNCHECKED)
    assert.match(dataUpdateStatusLabel('{"etlTasks":["销售订单数据"]}'), /未约定业务日/)
    assert.match(
      dataUpdateStatusLabel('{"etlTasks":["销售订单数据"],"businessDate":"2026-09-21"}'),
      /2026-09-21/
    )
    assert.match(
      dataUpdateStatusLabel('{"etlTasks":["销售订单数据"],"businessDate":"2026-09-21"}'),
      /不发送旧图/
    )
  })

  it('ADMIN feishu route is capability gated', () => {
    assert.equal(requiredCapabilityForPath('/company-admin/feishu'), 'FEISHU_SUBSCRIPTION_MANAGE')
    const hidden = buildCompanyAdminRoutes(['USER_MANAGE'])
    assert.equal(
      hidden[0].children.some((c: { path: string }) => c.path === 'feishu'),
      false
    )
    const shown = buildIntegrationRoutes(['USER_MANAGE', 'FEISHU_SUBSCRIPTION_MANAGE'])
    assert.ok(shown[0].children.some((c: { path: string }) => c.path === 'feishu'))
    assert.equal(isCompanyAdminApi('/company/bi/feishu/subscriptions'), true)
    assert.equal(isCompanyAdminApi('/company/bi/feishu/employees/search'), true)
    assert.equal(isCompanyAdminApi('/company/bi/admin/users'), true)
    assert.equal(canVisitCompanyPath('/company-admin/feishu', ['DASHBOARD_VIEW']), false)
    assert.equal(canVisitCompanyPath('/company-admin/feishu', ['FEISHU_SUBSCRIPTION_MANAGE']), true)
    assert.equal(canVisitCompanyPath('/integrations/connections', ['DASHBOARD_VIEW']), false)
    assert.equal(
      canVisitCompanyPath('/integrations/connections', ['FEISHU_SUBSCRIPTION_MANAGE']),
      true
    )
  })
})
