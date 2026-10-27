import type { CompanyCapabilityName } from '../api/company/types'
import { keepStringId } from './companyIds.ts'

export const MANAGEMENT_CAPS: CompanyCapabilityName[] = [
  'USER_MANAGE',
  'GROUP_MANAGE',
  'ACL_MANAGE',
  'AUDIT_VIEW'
]

export function hasCapability(
  capabilities: CompanyCapabilityName[] | undefined,
  name: CompanyCapabilityName
): boolean {
  return !!capabilities?.includes(name)
}

export function isAdminManagementVisible(
  capabilities: CompanyCapabilityName[] | undefined
): boolean {
  return MANAGEMENT_CAPS.some(cap => hasCapability(capabilities, cap))
}

export function requiredCapabilityForPath(path: string): CompanyCapabilityName | null {
  const normalized = path.startsWith('#') ? path.slice(1) : path
  if (normalized.startsWith('/company-admin/users') || normalized === '/company-admin/users') {
    return 'USER_MANAGE'
  }
  if (normalized.startsWith('/company-admin/groups')) {
    return 'GROUP_MANAGE'
  }
  if (normalized.startsWith('/company-admin/permissions')) {
    return 'ACL_MANAGE'
  }
  if (normalized.startsWith('/company-admin/audit')) {
    return 'AUDIT_VIEW'
  }
  if (normalized.startsWith('/company-admin/feishu') || normalized.startsWith('/integrations')) {
    return 'FEISHU_SUBSCRIPTION_MANAGE'
  }
  if (normalized.startsWith('/data/dataset') || normalized.startsWith('/dataset-form')) {
    return 'DATASET_DESIGN'
  }
  if (normalized.startsWith('/data/datasource') || normalized.startsWith('/ds-form')) {
    return 'DATASOURCE_ACCESS'
  }
  if (normalized.startsWith('/sync')) {
    return 'ETL_ACCESS'
  }
  return null
}

export function buildCompanyAdminRoutes(capabilities: CompanyCapabilityName[] | undefined): any[] {
  const children: any[] = []
  if (hasCapability(capabilities, 'USER_MANAGE')) {
    children.push({
      path: 'users',
      name: 'company-users',
      hidden: false,
      inLayout: true,
      component: 'company/admin/users',
      meta: { title: '用户管理' }
    })
  }
  if (hasCapability(capabilities, 'GROUP_MANAGE')) {
    children.push({
      path: 'groups',
      name: 'company-groups',
      hidden: false,
      inLayout: true,
      component: 'company/admin/groups',
      meta: { title: '用户组管理' }
    })
  }
  if (hasCapability(capabilities, 'ACL_MANAGE')) {
    children.push({
      path: 'permissions',
      name: 'company-permissions',
      hidden: false,
      inLayout: true,
      component: 'company/admin/permissions',
      meta: { title: '资源授权' }
    })
  }
  if (hasCapability(capabilities, 'AUDIT_VIEW')) {
    children.push({
      path: 'audit',
      name: 'company-audit',
      hidden: false,
      inLayout: true,
      component: 'company/admin/audit',
      meta: { title: '审计日志' }
    })
  }
  if (!children.length) {
    return []
  }
  return [
    {
      path: '/company-admin',
      name: 'company-admin',
      hidden: false,
      inLayout: true,
      component: 'Layout',
      redirect: `/company-admin/${children[0].path}`,
      meta: { title: '权限管理' },
      children
    }
  ]
}

export function buildIntegrationRoutes(capabilities: CompanyCapabilityName[] | undefined): any[] {
  if (!hasCapability(capabilities, 'FEISHU_SUBSCRIPTION_MANAGE')) return []
  return [
    {
      path: '/integrations',
      name: 'integrations',
      hidden: false,
      inLayout: true,
      component: 'Layout',
      redirect: '/integrations/feishu',
      meta: { title: '集成中心' },
      children: [
        {
          path: 'feishu',
          name: 'company-feishu',
          hidden: false,
          inLayout: true,
          component: 'company/admin/feishu',
          meta: { title: '飞书订阅' }
        },
        {
          path: 'connections',
          name: 'integration-connections',
          hidden: false,
          inLayout: true,
          component: 'company/integrations/connections',
          meta: { title: '应用连接' }
        }
      ]
    },
    {
      path: '/company-admin/feishu',
      name: 'company-feishu-legacy',
      hidden: true,
      redirect: (to: any) => ({
        path:
          to.query?.tab === 'integration' ? '/integrations/connections' : '/integrations/feishu',
        query: to.query
      })
    }
  ]
}

export function canVisitCompanyPath(
  path: string,
  capabilities: CompanyCapabilityName[] | undefined
): boolean {
  const normalized = path.startsWith('#') ? path.slice(1) : path
  if (normalized === '/company-admin' || normalized === '/company-admin/') {
    return isAdminManagementVisible(capabilities)
  }
  const needed = requiredCapabilityForPath(normalized)
  if (!needed) {
    return true
  }
  return hasCapability(capabilities, needed)
}

function menuName(route: any): string {
  return String(route?.name || '')
}

function menuPath(route: any): string {
  return String(route?.path || '')
}

function isDatasetMenu(route: any): boolean {
  return (
    menuName(route) === 'dataset' ||
    menuPath(route) === 'dataset' ||
    menuPath(route).includes('dataset-form')
  )
}

function isDatasourceMenu(route: any): boolean {
  return (
    menuName(route) === 'datasource' ||
    menuPath(route) === 'datasource' ||
    menuPath(route).includes('ds-form') ||
    menuName(route) === 'datasource-form'
  )
}

function isEtlMenu(route: any): boolean {
  return (
    menuName(route) === 'sync' || menuPath(route).includes('/sync') || menuPath(route) === 'sync'
  )
}

export function filterMenusByCapability(
  routes: any[] | undefined,
  capabilities: CompanyCapabilityName[] | undefined
): any[] {
  if (!routes?.length) {
    return []
  }
  const next: any[] = []
  for (const route of routes) {
    if (isDatasetMenu(route) && !hasCapability(capabilities, 'DATASET_DESIGN')) {
      continue
    }
    if (isDatasourceMenu(route) && !hasCapability(capabilities, 'DATASOURCE_ACCESS')) {
      continue
    }
    if (isEtlMenu(route) && !hasCapability(capabilities, 'ETL_ACCESS')) {
      continue
    }
    const copy = { ...route }
    if (Array.isArray(route.children) && route.children.length) {
      copy.children = filterMenusByCapability(route.children, capabilities)
      if (
        (menuName(route) === 'data' || menuPath(route) === '/data') &&
        (!copy.children || copy.children.length === 0)
      ) {
        continue
      }
    }
    next.push(copy)
  }
  return next
}

export function applyCompanyCapabilityToMenus(
  backendMenus: any[] | undefined,
  capabilities: CompanyCapabilityName[] | undefined
): any[] {
  return [
    ...filterMenusByCapability(backendMenus || [], capabilities),
    ...buildCompanyAdminRoutes(capabilities),
    ...buildIntegrationRoutes(capabilities)
  ]
}

export function dashboardRelationOptions() {
  return [
    { value: 'NONE', label: '无权限' },
    { value: 'VISITOR', label: '访问者' },
    { value: 'OWNER', label: '所有者' }
  ]
}

export function datasetRelationOptions() {
  return [
    { value: 'NONE', label: '无权限' },
    { value: 'READ', label: '读取' }
  ]
}

export function grantPayloadFromUi(params: {
  subjectType: 'USER' | 'GROUP'
  subjectId: string
  resourceType: 'DASHBOARD' | 'DASHBOARD_FOLDER' | 'DATASET'
  resourceId: unknown
  uiRelation: string
}): { kind: 'grant' | 'revoke'; body: Record<string, string> } {
  const resourceId = keepStringId(params.resourceId)
  const subjectId = keepStringId(params.subjectId)
  if (params.resourceType === 'DATASET') {
    if (params.uiRelation === 'OWNER') {
      throw new Error('Dataset UI must not send OWNER')
    }
    if (params.uiRelation === 'NONE' || !params.uiRelation) {
      return {
        kind: 'revoke',
        body: {
          subjectType: params.subjectType,
          subjectId,
          resourceType: 'DATASET',
          resourceId
        }
      }
    }
    return {
      kind: 'grant',
      body: {
        subjectType: params.subjectType,
        subjectId,
        resourceType: 'DATASET',
        resourceId,
        relation: 'READ'
      }
    }
  }
  if (params.uiRelation === 'NONE' || !params.uiRelation) {
    return {
      kind: 'revoke',
      body: {
        subjectType: params.subjectType,
        subjectId,
        resourceType: params.resourceType,
        resourceId
      }
    }
  }
  return {
    kind: 'grant',
    body: {
      subjectType: params.subjectType,
      subjectId,
      resourceType: params.resourceType,
      resourceId,
      relation: params.uiRelation
    }
  }
}

export function isCompanyAdminApi(url?: string): boolean {
  return !!url && (url.includes('/company/bi/admin/') || url.includes('/company/bi/feishu'))
}

export const ROLE_LABELS: Record<string, string> = {
  ADMIN: '管理员',
  NORMAL: '普通用户',
  READONLY: '只读用户'
}

export const STATUS_LABELS: Record<string, string> = {
  ENABLED: '启用',
  DISABLED: '禁用'
}
