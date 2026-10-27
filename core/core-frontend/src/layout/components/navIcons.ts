import type { Component } from 'vue'
import dataset from '@/assets/svg/scent/table-2.svg'
import datasource from '@/assets/svg/scent/database.svg'
import users from '@/assets/svg/scent/user-round.svg'
import groups from '@/assets/svg/scent/users-round.svg'
import permissions from '@/assets/svg/scent/shield-check.svg'
import audit from '@/assets/svg/scent/scroll-text.svg'
import feishu from '@/assets/svg/scent/send.svg'

const icons: Record<string, Component> = {
  dataset,
  datasource,
  users,
  groups,
  permissions,
  audit,
  feishu,
  integrations: feishu,
  connections: datasource
}

export const resourceNavIcon = (path: string) => icons[path.split('/').filter(Boolean).pop() || '']
