export type CompanyId = string

export type CompanyAccountRole = 'ADMIN' | 'NORMAL' | 'READONLY'
export type CompanyUserStatus = 'ENABLED' | 'DISABLED'
export type CompanyCapabilityName =
  | 'DASHBOARD_VIEW'
  | 'DASHBOARD_EDIT'
  | 'DASHBOARD_EXPORT'
  | 'DASHBOARD_GRANT'
  | 'DATASET_DESIGN'
  | 'DATASOURCE_ACCESS'
  | 'ETL_ACCESS'
  | 'USER_MANAGE'
  | 'GROUP_MANAGE'
  | 'ACL_MANAGE'
  | 'AUDIT_VIEW'

export interface CompanyMe {
  userId: CompanyId
  username: string
  displayName: string
  accountRole: CompanyAccountRole
  capabilities: CompanyCapabilityName[]
}

export interface CompanyUser {
  id: CompanyId
  username: string
  displayName: string
  accountRole: CompanyAccountRole
  status: CompanyUserStatus
  createdAt?: number
  updatedAt?: number
  lastLoginAt?: number
  groupIds?: CompanyId[]
}

export interface CompanyUserCreateBody {
  username: string
  displayName: string
  accountRole: CompanyAccountRole
  status: CompanyUserStatus
  password: string
}

export interface CompanyUserUpdateBody {
  username?: string
  displayName?: string
  accountRole?: CompanyAccountRole
}

export interface CompanyGroup {
  id: CompanyId
  name: string
  description?: string
  status: CompanyUserStatus
  memberCount: number
  createdAt?: number
  updatedAt?: number
}

export interface CompanyGroupMember {
  userId: CompanyId
  username: string
  displayName: string
  accountRole: CompanyAccountRole
  status: CompanyUserStatus
}

export interface CompanyCatalogNode {
  id: CompanyId
  name: string
  type: 'DASHBOARD' | 'DASHBOARD_FOLDER' | 'DATASET' | 'DATASET_FOLDER'
  pid: CompanyId
  leaf: boolean
  children?: CompanyCatalogNode[]
}

export interface CompanyAdminCatalog {
  dashboards: CompanyCatalogNode[]
  datasets: CompanyCatalogNode[]
}

export interface CompanyDirectAcl {
  resourceType: string
  resourceId: CompanyId
  relation: string
  name?: string
}

export interface CompanyAclMutation {
  subjectType: 'USER' | 'GROUP'
  subjectId: CompanyId
  resourceType: 'DASHBOARD' | 'DASHBOARD_FOLDER' | 'DATASET'
  resourceId: CompanyId
  relation?: 'VISITOR' | 'OWNER' | 'READ'
}

export interface CompanyAuditRecord {
  id: CompanyId
  operatorUserId: CompanyId
  actionType: string
  targetType?: string
  targetId?: CompanyId
  beforeJson?: string
  afterJson?: string
  ip?: string
  requestId?: string
  createdAt: number
}

export interface CompanyAuditPage {
  total: number
  page: number
  pageSize: number
  records: CompanyAuditRecord[]
}
