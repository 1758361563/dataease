import request from '@/config/axios'
import { keepStringId } from '@/utils/companyIds'
import {
  inferExecutionMode,
  inferTargetExecutionMode,
  type FeishuExecutionMode,
  type FeishuReceiveIdType,
  type FeishuRecipientType
} from '@/utils/companyFeishu'

const keep = (value: unknown) => keepStringId(value)

export interface FeishuIntegration {
  configured: boolean
  id?: string
  appId?: string | null
  appSecretRef?: string | null
  status?: string | null
  lastVerifiedAt?: number | null
  hasSecretRef?: boolean
}

export interface FeishuRecipient {
  id: string
  recipientType: FeishuRecipientType
  receiveIdType: FeishuReceiveIdType
  displayName: string
  feishuId: string
  appId: string
  source?: string
  identityStatus?: string
  verifyStatus: string
  status: string
  lastVerifiedAt?: number | null
}

export interface FeishuEmployeeMatch {
  name: string
  userId: string
  departments: string[]
  userIdTail: string
}

export interface FeishuRecipientBody {
  recipientType: FeishuRecipientType
  receiveIdType: FeishuReceiveIdType
  displayName: string
  feishuId: string
  status?: string
}

export interface FeishuSubscription {
  id: string
  name: string
  dashboardId: string
  chartId: string
  chartName?: string
  status: string
  scheduleJson?: string
  freshnessJson?: string | null
  deliveryLane?: string | null
  titleTemplate?: string
  bodyTemplate?: string
  includeDetailLink?: boolean
  fixedFilterJson?: string
  recipientIds?: string[]
}

export interface FeishuSubscriptionBody {
  name: string
  dashboardId: string
  chartId: string
  chartName?: string
  titleTemplate?: string
  bodyTemplate?: string
  includeDetailLink?: boolean
  fixedFilterJson?: string | Record<string, unknown>
  scheduleJson?: Record<string, unknown>
  status?: string
  recipientIds: string[]
}

export interface FeishuDeliveryTarget {
  id: string
  recipientId: string
  displayName?: string
  recipientType?: string
  receiveIdType?: string
  status: string
  executionMode?: string
  messageId?: string | null
  retryCount?: number
  errorSummary?: string | null
  sentAt?: number | null
}

export interface FeishuDeliveryRun {
  id: string
  subscriptionId: string
  triggerType?: string
  status: string
  executionMode?: string
  screenshotStatus?: string
  uploadStatus?: string
  errorSummary?: string | null
  startedAt?: number | null
  finishedAt?: number | null
  createdAt?: number | null
  accepted?: boolean
  runId?: string
  targets?: FeishuDeliveryTarget[]
}

const wrapRecipient = (row: any): FeishuRecipient => ({
  ...row,
  id: keep(row.id)
})

const wrapSubscription = (row: any): FeishuSubscription => ({
  ...row,
  id: keep(row.id),
  dashboardId: keep(row.dashboardId),
  chartId: keep(row.chartId),
  recipientIds: Array.isArray(row.recipientIds) ? row.recipientIds.map(keep) : row.recipientIds
})

const wrapRun = (row: any): FeishuDeliveryRun => {
  const targets = Array.isArray(row.targets)
    ? row.targets.map((item: any) => ({
        ...item,
        id: keep(item.id),
        recipientId: keep(item.recipientId)
      }))
    : row.targets
  const wrapped: FeishuDeliveryRun = {
    ...row,
    id: keep(row.id || row.runId),
    runId: keep(row.runId || row.id),
    subscriptionId: keep(row.subscriptionId),
    targets
  }
  wrapped.executionMode = inferExecutionMode(wrapped)
  if (Array.isArray(wrapped.targets)) {
    wrapped.targets = wrapped.targets.map(item => ({
      ...item,
      executionMode: inferTargetExecutionMode(item, wrapped.executionMode as FeishuExecutionMode)
    }))
  }
  return wrapped
}

export const getFeishuIntegration = (): Promise<FeishuIntegration> =>
  request.get({ url: '/company/bi/feishu/integration' }).then((res: any) => res.data)

export const updateFeishuIntegration = (data: {
  appId: string
  appSecretRef: string
  status?: string
}): Promise<FeishuIntegration> =>
  request.post({ url: '/company/bi/feishu/integration', data }).then((res: any) => res.data)

export const testFeishuIntegration = (): Promise<{
  ok: boolean
  checked?: string
  feishuCalled?: boolean
}> => request.post({ url: '/company/bi/feishu/test' }).then((res: any) => res.data)

export const listFeishuRecipients = (): Promise<FeishuRecipient[]> =>
  request
    .get({ url: '/company/bi/feishu/recipients' })
    .then((res: any) => (res.data || []).map(wrapRecipient))

export const createFeishuRecipient = (data: FeishuRecipientBody): Promise<FeishuRecipient> =>
  request
    .post({ url: '/company/bi/feishu/recipients', data })
    .then((res: any) => wrapRecipient(res.data))

export const updateFeishuRecipient = (
  id: string,
  data: FeishuRecipientBody
): Promise<FeishuRecipient> =>
  request
    .post({ url: `/company/bi/feishu/recipients/${id}`, data })
    .then((res: any) => wrapRecipient(res.data))

export const enableFeishuRecipient = (id: string): Promise<FeishuRecipient> =>
  request
    .post({ url: `/company/bi/feishu/recipients/${id}/enable` })
    .then((res: any) => wrapRecipient(res.data))

export const disableFeishuRecipient = (id: string): Promise<FeishuRecipient> =>
  request
    .post({ url: `/company/bi/feishu/recipients/${id}/disable` })
    .then((res: any) => wrapRecipient(res.data))

export const searchFeishuEmployees = (query: string): Promise<FeishuEmployeeMatch[]> =>
  request
    .get({ url: '/company/bi/feishu/employees/search', params: { query } })
    .then((res: any) => res.data?.matches || [])

export const confirmFeishuEmployee = (
  userId: string
): Promise<
  FeishuRecipient & {
    created?: boolean
    identityJustConfirmed?: boolean
    sendVerified?: boolean
  }
> =>
  request
    .post({ url: '/company/bi/feishu/employees/confirm', data: { userId } })
    .then((res: any) => wrapRecipient(res.data))

export const listFeishuSubscriptions = (): Promise<FeishuSubscription[]> =>
  request
    .get({ url: '/company/bi/feishu/subscriptions' })
    .then((res: any) => (res.data || []).map(wrapSubscription))

export const getFeishuSubscription = (id: string): Promise<FeishuSubscription> =>
  request
    .get({ url: `/company/bi/feishu/subscriptions/${id}` })
    .then((res: any) => wrapSubscription(res.data))

export const createFeishuSubscription = (
  data: FeishuSubscriptionBody
): Promise<FeishuSubscription> =>
  request
    .post({ url: '/company/bi/feishu/subscriptions', data })
    .then((res: any) => wrapSubscription(res.data))

export const updateFeishuSubscription = (
  id: string,
  data: FeishuSubscriptionBody
): Promise<FeishuSubscription> =>
  request
    .post({ url: `/company/bi/feishu/subscriptions/${id}`, data })
    .then((res: any) => wrapSubscription(res.data))

export const enableFeishuSubscription = (id: string): Promise<FeishuSubscription> =>
  request
    .post({ url: `/company/bi/feishu/subscriptions/${id}/enable` })
    .then((res: any) => wrapSubscription(res.data))

export const disableFeishuSubscription = (id: string): Promise<FeishuSubscription> =>
  request
    .post({ url: `/company/bi/feishu/subscriptions/${id}/disable` })
    .then((res: any) => wrapSubscription(res.data))

export const runFeishuSubscription = (
  id: string,
  data: { dryRun?: boolean } = { dryRun: true }
): Promise<FeishuDeliveryRun> =>
  request
    .post({ url: `/company/bi/feishu/subscriptions/${id}/run`, data })
    .then((res: any) => wrapRun(res.data))

export const listFeishuRuns = (id: string): Promise<FeishuDeliveryRun[]> =>
  request
    .get({ url: `/company/bi/feishu/subscriptions/${id}/runs` })
    .then((res: any) => (res.data || []).map(wrapRun))

export const getFeishuRun = (id: string, runId: string): Promise<FeishuDeliveryRun> =>
  request
    .get({ url: `/company/bi/feishu/subscriptions/${id}/runs/${runId}` })
    .then((res: any) => wrapRun(res.data))
