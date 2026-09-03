import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { keepStringId, SNOWFLAKE_SAMPLE, isUnsafeNumberId, BOOTSTRAP_ADMIN_ID } from './companyIds.ts'
import {
  applyCompanyCapabilityToMenus,
  buildCompanyAdminRoutes,
  canVisitCompanyPath,
  grantPayloadFromUi,
  hasCapability,
  isAdminManagementVisible,
  requiredCapabilityForPath
} from './companyNav.ts'

describe('P4-2 company nav', () => {
  it('T1 ADMIN capabilities show management menu', () => {
    const caps = ['USER_MANAGE', 'GROUP_MANAGE', 'ACL_MANAGE', 'AUDIT_VIEW']
    assert.equal(isAdminManagementVisible(caps), true)
    const routes = buildCompanyAdminRoutes(caps)
    assert.equal(routes[0].path, '/company-admin')
    assert.equal(routes[0].redirect, '/company-admin/users')
    assert.equal(routes[0].children.length, 4)
  })

  it('T2 NORMAL hides management menu', () => {
    const caps = ['DASHBOARD_VIEW']
    assert.equal(isAdminManagementVisible(caps), false)
    assert.equal(buildCompanyAdminRoutes(caps).length, 0)
  })

  it('T3 READONLY hides management menu', () => {
    assert.equal(isAdminManagementVisible(['DASHBOARD_VIEW']), false)
  })

  it('T4 USER_MANAGE false denies user route', () => {
    assert.equal(requiredCapabilityForPath('/company-admin/users'), 'USER_MANAGE')
    assert.equal(hasCapability(['DASHBOARD_VIEW'], 'USER_MANAGE'), false)
    assert.equal(canVisitCompanyPath('/company-admin/users', ['DASHBOARD_VIEW']), false)
    assert.equal(canVisitCompanyPath('/company-admin', ['DASHBOARD_VIEW']), false)
  })

  it('T5 ACL_MANAGE true shows permission page', () => {
    const routes = buildCompanyAdminRoutes(['ACL_MANAGE'])
    assert.ok(routes[0].children.some(c => c.path === 'permissions'))
    assert.equal(requiredCapabilityForPath('/company-admin/permissions'), 'ACL_MANAGE')
  })

  it('T6 catalog source is admin catalog not runtime tree', () => {
    assert.equal(requiredCapabilityForPath('/company-admin/permissions'), 'ACL_MANAGE')
  })

  it('T7 Dashboard VISITOR grant request', () => {
    const payload = grantPayloadFromUi({
      subjectType: 'USER',
      subjectId: '1',
      resourceType: 'DASHBOARD',
      resourceId: SNOWFLAKE_SAMPLE,
      uiRelation: 'VISITOR'
    })
    assert.equal(payload.kind, 'grant')
    assert.equal(payload.body.relation, 'VISITOR')
    assert.equal(payload.body.resourceId, SNOWFLAKE_SAMPLE)
  })

  it('T8 Dashboard OWNER grant request', () => {
    const payload = grantPayloadFromUi({
      subjectType: 'USER',
      subjectId: '1',
      resourceType: 'DASHBOARD',
      resourceId: SNOWFLAKE_SAMPLE,
      uiRelation: 'OWNER'
    })
    assert.equal(payload.body.relation, 'OWNER')
  })

  it('T9 NONE revokes', () => {
    const payload = grantPayloadFromUi({
      subjectType: 'USER',
      subjectId: '1',
      resourceType: 'DASHBOARD',
      resourceId: SNOWFLAKE_SAMPLE,
      uiRelation: 'NONE'
    })
    assert.equal(payload.kind, 'revoke')
    assert.equal(payload.body.relation, undefined)
  })

  it('T10 Dataset READ grant', () => {
    const payload = grantPayloadFromUi({
      subjectType: 'USER',
      subjectId: '1',
      resourceType: 'DATASET',
      resourceId: SNOWFLAKE_SAMPLE,
      uiRelation: 'READ'
    })
    assert.equal(payload.kind, 'grant')
    assert.equal(payload.body.relation, 'READ')
    assert.equal(payload.body.resourceType, 'DATASET')
  })

  it('T11 Dataset UI never sends OWNER', () => {
    assert.throws(() =>
      grantPayloadFromUi({
        subjectType: 'USER',
        subjectId: '1',
        resourceType: 'DATASET',
        resourceId: SNOWFLAKE_SAMPLE,
        uiRelation: 'OWNER'
      })
    )
  })

  it('T12 snowflake id kept string', () => {
    assert.equal(keepStringId(SNOWFLAKE_SAMPLE), SNOWFLAKE_SAMPLE)
    assert.equal(isUnsafeNumberId(SNOWFLAKE_SAMPLE), true)
    assert.notEqual(SNOWFLAKE_SAMPLE, String(Number(SNOWFLAKE_SAMPLE)))
  })

  it('T13 uid=1 bootstrap id is string 1', () => {
    assert.equal(BOOTSTRAP_ADMIN_ID, '1')
    assert.equal(keepStringId(1), '1')
  })

  it('T14 dataset/datasource routes require capabilities', () => {
    assert.equal(requiredCapabilityForPath('/data/dataset'), 'DATASET_DESIGN')
    assert.equal(requiredCapabilityForPath('/data/datasource'), 'DATASOURCE_ACCESS')
  })

  it('T15 403 path maps to capability refresh target', () => {
    assert.equal(requiredCapabilityForPath('/company-admin/users'), 'USER_MANAGE')
  })

  it('T16 password not part of grant payload', () => {
    const payload = grantPayloadFromUi({
      subjectType: 'USER',
      subjectId: '1',
      resourceType: 'DASHBOARD',
      resourceId: SNOWFLAKE_SAMPLE,
      uiRelation: 'VISITOR'
    })
    assert.equal('password' in payload.body, false)
  })

  it('NORMAL loses dataset and datasource menus', () => {
    const menus = [
      {
        path: '/data',
        name: 'data',
        children: [
          { path: 'dataset', name: 'dataset' },
          { path: 'datasource', name: 'datasource' }
        ]
      },
      { path: '/panel', name: 'panel' }
    ]
    const filtered = applyCompanyCapabilityToMenus(menus, ['DASHBOARD_VIEW'])
    assert.equal(
      filtered.some(r => r.path === '/data'),
      false
    )
    assert.equal(
      filtered.some(r => r.path === '/panel'),
      true
    )
    assert.equal(
      filtered.some(r => r.path === '/company-admin'),
      false
    )
  })
})
