import { useT } from '../../i18n'
import type { PluginApplicationsProps, PluginAppsNoticeProps } from './PluginApplications.types'
import s from './PluginApplications.module.css'

/** Приложения одного плагина. Вход и доступность инструментов показаны отдельно и в IDE, и на телефоне. */
export const PluginApplications = ({ group, pendingAppId, onConnect }: PluginApplicationsProps) => {
  const t = useT().pluginApps

  if (!group) return null

  return (
    <div className={s.apps}>
      {group.error && <p className={s.error} role="alert">{group.error}</p>}
      {group.apps.map((app) => (
        <div key={app.id} className={s.app}>
          <span className={s.name}>{app.name}</span>
          <span className={s.note}>
            {app.accessible === undefined ? t.accessUnknown : app.accessible ? t.accessible : t.unavailable}
            {' · '}
            {app.enabled === undefined ? t.enabledUnknown : app.enabled ? t.enabled : t.disabled}
            {' · '}
            {app.callable === undefined ? t.toolsUnknown : app.callable ? t.toolsReady : t.toolsUnavailable}
          </span>
          <span className={app.needsAuth ? s.error : s.note}>{app.needsAuth ? t.needsAuth : t.authUnknown}</span>
          {app.reason && <span className={s.error}>
            {app.reason === 'NO_ACTIVE_WORKSPACE' ? t.noWorkspace
              : app.reason === 'NOT_CONFIGURED_FOR_WORKSPACE' ? t.workspaceBlocked
                : app.reason === 'disabled_by_admin' ? t.adminBlocked
                  : app.reason === 'plan_not_eligible' ? t.planBlocked
                    : app.reason === 'required_app_unavailable' ? t.requiredUnavailable
                : app.reason === 'unresolved-template' ? t.unresolved : app.reason}
          </span>}
          {onConnect ? (
            <button type="button" className={s.button} disabled={!app.canConnect || !!pendingAppId}
              onClick={() => onConnect(app.id)}>
              {pendingAppId === app.id ? t.opening : t.connect}
            </button>
          ) : app.canConnect ? <span className={s.note}>{t.atDesk}</span> : null}
          {!app.canConnect && <span className={s.note}>{t.noAddress}</span>}
        </div>
      ))}
    </div>
  )
}

/** Обновление после браузера не объявляет вход успешным: Codex сообщает только состояние инструментов. */
export const PluginAppsNotice = ({ state, onRefresh, onCancel }: PluginAppsNoticeProps) => {
  const t = useT().pluginApps
  const busy = state?.phase === 'loading' || state?.phase === 'opening'
  const waiting = state?.phase === 'waiting' || state?.phase === 'opening'

  return (
    <div className={s.apps} aria-live="polite">
      {(!state || busy || state.phase === 'stale') && <span className={s.note}>{t.loading}</span>}
      {state?.phase === 'waiting' && <span className={s.note}>{t.waiting}</span>}
      {state?.phase === 'cancelled' && <span className={s.note}>{t.cancelled}</span>}
      {state?.phase === 'error' && <span className={s.error} role="alert">{state.error}</span>}
      {onRefresh && <button type="button" className={s.button} disabled={busy} onClick={onRefresh}>{t.check}</button>}
      {waiting && onCancel && <button type="button" className={s.button} onClick={onCancel}>{t.cancel}</button>}
    </div>
  )
}
