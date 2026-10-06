import type { PluginAppsInfo } from '../../protocol'

export type PluginApplicationsProps = {
  group: PluginAppsInfo['plugins'][number] | undefined
  pendingAppId?: string
  onConnect?: (app: string) => void
}

export type PluginAppsNoticeProps = {
  state: PluginAppsInfo | null
  onRefresh?: () => void
  onCancel?: () => void
}
