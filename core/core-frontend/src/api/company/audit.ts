import request from '@/config/axios'
import type { CompanyAuditPage, CompanyId } from './types'

export const queryCompanyAudit = (params: {
  operatorUserId?: CompanyId
  actionType?: string
  targetType?: string
  fromTime?: number
  toTime?: number
  page?: number
  pageSize?: number
}): Promise<CompanyAuditPage> =>
  request.get({ url: '/company/bi/admin/audit', params }).then((res: any) => res.data)
