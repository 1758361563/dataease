import request from '@/config/axios'
import type {
  CompanyId,
  CompanyAccountRole,
  CompanyUser,
  CompanyUserCreateBody,
  CompanyUserUpdateBody
} from './types'

export const listCompanyUsers = (): Promise<CompanyUser[]> =>
  request.get({ url: '/company/bi/admin/users' }).then((res: any) => res.data || [])

export const getCompanyUser = (id: CompanyId): Promise<CompanyUser> =>
  request.get({ url: `/company/bi/admin/users/${id}` }).then((res: any) => res.data)

export const createCompanyUser = (data: CompanyUserCreateBody): Promise<CompanyUser> =>
  request.post({ url: '/company/bi/admin/users', data }).then((res: any) => res.data)

export const updateCompanyUser = (
  id: CompanyId,
  data: CompanyUserUpdateBody
): Promise<CompanyUser> =>
  request.post({ url: `/company/bi/admin/users/${id}`, data }).then((res: any) => res.data)

export const changeCompanyUserRole = (
  id: CompanyId,
  accountRole: CompanyAccountRole
): Promise<CompanyUser> =>
  request
    .post({ url: `/company/bi/admin/users/${id}/role`, data: { accountRole } })
    .then((res: any) => res.data)

export const enableCompanyUser = (id: CompanyId): Promise<CompanyUser> =>
  request.post({ url: `/company/bi/admin/users/${id}/enable` }).then((res: any) => res.data)

export const disableCompanyUser = (id: CompanyId): Promise<CompanyUser> =>
  request.post({ url: `/company/bi/admin/users/${id}/disable` }).then((res: any) => res.data)

export const resetCompanyUserPassword = (id: CompanyId, password: string): Promise<void> =>
  request
    .post({ url: `/company/bi/admin/users/${id}/resetPassword`, data: { password } })
    .then(() => undefined)
