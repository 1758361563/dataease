import request from '@/config/axios'
import type { CompanyMe } from './types'

export const fetchCompanyMe = (): Promise<CompanyMe> =>
  request.get({ url: '/company/bi/me' }).then((res: any) => res.data)
