import { defineStore } from 'pinia'
import { store } from '../index'
import { fetchCompanyMe } from '@/api/company/me'
import type { CompanyAccountRole, CompanyCapabilityName, CompanyId } from '@/api/company/types'
import { hasCapability as capHas, isAdminManagementVisible } from '@/utils/companyNav'
import { keepStringId } from '@/utils/companyIds'

interface CompanyPermissionState {
  userId: CompanyId
  username: string
  displayName: string
  accountRole: CompanyAccountRole | ''
  capabilities: CompanyCapabilityName[]
  loaded: boolean
}

export const companyPermissionStore = defineStore('companyPermission', {
  state: (): CompanyPermissionState => ({
    userId: '',
    username: '',
    displayName: '',
    accountRole: '',
    capabilities: [],
    loaded: false
  }),
  getters: {
    currentRole(): CompanyAccountRole | '' {
      return this.accountRole
    },
    isAdminManagementVisible(): boolean {
      return isAdminManagementVisible(this.capabilities)
    }
  },
  actions: {
    hasCapability(name: CompanyCapabilityName): boolean {
      return capHas(this.capabilities, name)
    },
    async refreshCapabilities(): Promise<void> {
      const me = await fetchCompanyMe()
      this.userId = keepStringId(me.userId)
      this.username = me.username
      this.displayName = me.displayName
      this.accountRole = me.accountRole
      this.capabilities = Array.isArray(me.capabilities) ? me.capabilities : []
      this.loaded = true
    },
    clear() {
      this.userId = ''
      this.username = ''
      this.displayName = ''
      this.accountRole = ''
      this.capabilities = []
      this.loaded = false
    }
  }
})

export const useCompanyPermissionStoreWithOut = () => {
  return companyPermissionStore(store)
}
