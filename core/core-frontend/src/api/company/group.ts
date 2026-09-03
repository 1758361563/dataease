import request from '@/config/axios'
import type { CompanyGroup, CompanyGroupMember, CompanyId, CompanyUserStatus } from './types'

export const listCompanyGroups = (): Promise<CompanyGroup[]> =>
  request.get({ url: '/company/bi/admin/groups' }).then((res: any) => res.data || [])

export const createCompanyGroup = (data: {
  name: string
  description?: string
  status?: CompanyUserStatus
}): Promise<CompanyGroup> =>
  request.post({ url: '/company/bi/admin/groups', data }).then((res: any) => res.data)

export const updateCompanyGroup = (
  id: CompanyId,
  data: { name: string; description?: string; status?: CompanyUserStatus }
): Promise<CompanyGroup> =>
  request.post({ url: `/company/bi/admin/groups/${id}`, data }).then((res: any) => res.data)

export const listCompanyGroupMembers = (id: CompanyId): Promise<CompanyGroupMember[]> =>
  request.get({ url: `/company/bi/admin/groups/${id}/members` }).then((res: any) => res.data || [])

export const addCompanyGroupMember = (id: CompanyId, userId: CompanyId): Promise<void> =>
  request
    .post({ url: `/company/bi/admin/groups/${id}/members`, data: { userId } })
    .then(() => undefined)

export const removeCompanyGroupMember = (id: CompanyId, userId: CompanyId): Promise<void> =>
  request.delete({ url: `/company/bi/admin/groups/${id}/members/${userId}` }).then(() => undefined)
