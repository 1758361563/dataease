import router from '@/router'
import { useCompanyPermissionStoreWithOut } from '@/store/modules/companyPermission'
import { canVisitCompanyPath } from '@/utils/companyNav'

let refreshing = false

export async function handleCompanyAdminForbidden() {
  if (refreshing) {
    return
  }
  refreshing = true
  const companyStore = useCompanyPermissionStoreWithOut()
  try {
    await companyStore.refreshCapabilities()
  } catch {
    // keep going; 401 is handled elsewhere
  }
  const path = router.currentRoute.value?.path || ''
  if (!canVisitCompanyPath(path, companyStore.capabilities)) {
    window.location.hash = '#/workbranch/index'
    window.location.reload()
    return
  }
  refreshing = false
}
