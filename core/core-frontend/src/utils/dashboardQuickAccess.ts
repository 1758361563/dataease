import { keepStringId } from './companyIds'
import type { BusiTreeNode } from '@/models/tree/TreeNode'

export type DashboardShortcutMode = 'recent' | 'favorite'
export interface DashboardShortcut {
  id: string
  name: string
  folder: string
  favorite: boolean
  viewedAt?: number
  node: BusiTreeNode
}

// Always join history/store records with the currently accessible resource tree.
export function dashboardShortcuts(
  records: Record<string, any>[],
  catalog: BusiTreeNode[],
  mode: DashboardShortcutMode
): DashboardShortcut[] {
  const accessible = new Map<string, { node: BusiTreeNode; folder: string }>()
  const visit = (nodes: BusiTreeNode[], parents: string[] = []) => {
    for (const node of nodes || []) {
      if (node.leaf) {
        if (node.weight !== 0 && node.extraFlag1 !== 0) {
          accessible.set(keepStringId(node.id), { node, folder: parents.join(' / ') })
        }
      } else visit(node.children || [], [...parents, node.name])
    }
  }
  visit(catalog)
  const seen = new Set<string>()
  return (records || []).flatMap(record => {
    if (!['panel', 'dashboard'].includes(record.type)) return []
    const id = keepStringId(mode === 'favorite' ? record.resourceId : record.id)
    const entry = accessible.get(id)
    if (!entry || seen.has(id)) return []
    seen.add(id)
    const time = Number(record.lastEditTime)
    return [
      {
        id,
        name: entry.node.name,
        folder: entry.folder,
        favorite: mode === 'favorite' || !!record.favorite,
        // findRecent maps core_opt_recent.time to lastEditTime; store uses edit time instead.
        viewedAt: mode === 'recent' && Number.isFinite(time) && time > 0 ? time : undefined,
        node: entry.node
      }
    ]
  })
}
