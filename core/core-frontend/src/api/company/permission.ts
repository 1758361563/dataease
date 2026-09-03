import request from '@/config/axios'
import type { CompanyAclMutation, CompanyAdminCatalog, CompanyDirectAcl, CompanyId } from './types'

export const fetchAdminCatalog = (): Promise<CompanyAdminCatalog> =>
  request.get({ url: '/company/bi/admin/permissions/catalog' }).then((res: any) => res.data)

export const fetchDirectGrants = (
  subjectType: 'USER' | 'GROUP',
  subjectId: CompanyId
): Promise<CompanyDirectAcl[]> =>
  request
    .get({ url: '/company/bi/admin/permissions/grants', params: { subjectType, subjectId } })
    .then((res: any) => res.data || [])

export const grantCompanyAcl = (data: CompanyAclMutation): Promise<any> =>
  request.post({ url: '/company/bi/admin/permissions/grant', data }).then((res: any) => res.data)

export const revokeCompanyAcl = (data: Omit<CompanyAclMutation, 'relation'>): Promise<any> =>
  request.post({ url: '/company/bi/admin/permissions/revoke', data }).then((res: any) => res.data)
