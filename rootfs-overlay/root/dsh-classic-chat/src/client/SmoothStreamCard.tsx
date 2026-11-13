/**
 * The smooth-stream plugin configuration card, rendered inside the Web
 * Settings "plugin configuration" page. Preferences are staged until the user
 * saves — the same shape as the Host-shipped cards, hand-drawn because the
 * Host cards' chrome is not exported for reuse.
 */

import { useState, useSyncExternalStore } from 'react'
import type { InjectFace, PropsLocale, PropsRuntime } from '@deepseek-ai/dsh-client-ui-slots'
import type {} from '@deepseek-ai/dsh-client-ui-settings-plugins/client'
import { IconChevronDownOutlineRegular, IconRefreshOutlineRegular } from '@deepseek-ai/dsh-client-ui-primitives'
import type { SmoothStreamCardController, SmoothStreamCardFace } from './smooth-stream-card-controller.ts'
import { zh } from './locales.ts'
import css from './SmoothStreamCard.module.css'

/** Props the renderer binds for the smooth-stream card. */
export type SmoothStreamCardProps =
  Partial<PropsRuntime<'settings.plugin.item'>>
  & Partial<PropsLocale<'settings.smoothStream'>>
  & Partial<InjectFace<SmoothStreamCardFace>>
  & {
    cardController?: SmoothStreamCardController
    defaultOpen?: boolean
  }

/** Render the smooth-stream card independently of the core settings namespace allowlist. */
export function SmoothStreamCard(props: SmoothStreamCardProps) {
  const { defaultOpen = false, cardController } = props
  const [open, setOpen] = useState(defaultOpen)
  const cardFace = cardController ? cardController.inject() : undefined
  const state = typeof props.useSmoothStreamCard === 'function'
    ? props.useSmoothStreamCard(snapshot => snapshot)
    : (cardController ? useSyncExternalStore(cardController.subscribe, () => cardController.getSnapshot()) : {
      status: 'ready' as const,
      writable: true,
      dirty: false,
      saving: false,
      failed: false,
      enabled: true,
      controlScroll: true,
      motionPreference: 'auto' as const,
      thinkAutoExpand: false,
      logarithmicFade: true,
      fastFold: true,
      fastPipeline: true,
      keepStreamOnToolCall: true,
      debugEnabled: false,
      debugTuning: {
        revealScale: 1,
        queuePressure: 0.8,
        maxRevealCps: 360,
        springStiffness: 140,
        springDamping: 24,
        springMass: 1,
        runwayPx: 32,
        reserveResponseMs: 180,
        backpressureMinScale: 0.5,
      },
      debugAvailable: false,
      version: undefined,
      installation: 'unmanaged' as const,
      canUpgrade: false,
      upgrading: false,
      upgradeFailed: false,
      restartRequired: false,
    })
  const t = typeof props.t === 'function' ? props.t : ((key: string) => (zh as Record<string, string>)[key] ?? key)
  const edit = props.edit ?? (patch => { cardFace?.edit(patch) })
  const save = props.save ?? (() => { cardFace?.save() })
  const discard = props.discard ?? (() => { cardFace?.discard() })
  const reload = props.reload ?? (() => { cardFace?.reload() })
  const upgrade = props.upgrade ?? (() => { cardFace?.upgrade() })
  const blocked = !state.dirty || state.saving || state.status !== 'ready'
  const versionLabel = state.version === undefined
    ? null
    : t(state.installation === 'development' ? 'developmentVersion' : 'version')
      .replace('{version}', state.version)

  return (
    <li className={open ? `${css.card} ${css.cardOpen}` : css.card}>
      <button
        type="button"
        className={css.header}
        aria-expanded={open}
        onClick={() => { setOpen(!open) }}
      >
        <span className={css.headText}>
          <span className={css.name}>{t('title')}</span>
          <span className={css.description}>{t('description')}</span>
        </span>
        {versionLabel === null ? null : <span className={css.version}>{versionLabel}</span>}
        {state.dirty ? <span className={css.pending}>{t('unsaved')}</span> : null}
        <IconChevronDownOutlineRegular className={open ? `${css.chevron} ${css.chevronOpen}` : css.chevron} />
      </button>
      {open
        ? (
          <div className={css.body}>
            {state.status === 'loading' ? <p className={css.readOnly} role="status">{t('loading')}</p> : null}
            {state.status === 'unavailable' ? (
              <div className={css.failure}>
                <p className={css.readOnly} role="status">{t('unavailable')}</p>
                <button type="button" className={css.discard} onClick={reload}>{t('retry')}</button>
              </div>
            ) : null}
            {state.status === 'ready' ? (
              <>
                {!state.writable ? <p className={css.readOnly} role="status">{t('readOnly')}</p> : null}
                <label className={css.field}>
                  <span className={css.fieldHead}>
                    <span className={css.label}>{t('enabled')}</span>
                    <input
                      type="checkbox"
                      className={css.toggle}
                      checked={state.enabled}
                      disabled={!state.writable || state.saving}
                      onChange={(event) => { edit({ enabled: event.target.checked }) }}
                    />
                  </span>
                  <span className={css.hint}>{t('enabledHint')}</span>
                </label>
                <label className={state.enabled ? css.field : `${css.field} ${css.fieldDisabled}`}>
                  <span className={css.fieldHead}>
                    <span className={css.label}>{t('controlScroll')}</span>
                    <input
                      type="checkbox"
                      className={css.toggle}
                      checked={state.controlScroll}
                      disabled={!state.writable || state.saving || !state.enabled}
                      onChange={(event) => { edit({ controlScroll: event.target.checked }) }}
                    />
                  </span>
                  <span className={css.hint}>{t('controlScrollHint')}</span>
                </label>
                <label className={state.enabled ? css.field : `${css.field} ${css.fieldDisabled}`}>
                  <span className={css.fieldHead}>
                    <span className={css.label}>{t('fastFold')}</span>
                    <input
                      type="checkbox"
                      className={css.toggle}
                      checked={state.fastFold}
                      disabled={!state.writable || state.saving || !state.enabled}
                      onChange={(event) => { edit({ fastFold: event.target.checked }) }}
                    />
                  </span>
                  <span className={css.hint}>{t('fastFoldHint')}</span>
                </label>
                <label className={state.enabled ? css.field : `${css.field} ${css.fieldDisabled}`}>
                  <span className={css.fieldHead}>
                    <span className={css.label}>{t('fastPipeline')}</span>
                    <input
                      type="checkbox"
                      className={css.toggle}
                      checked={state.fastPipeline}
                      disabled={!state.writable || state.saving || !state.enabled}
                      onChange={(event) => { edit({ fastPipeline: event.target.checked }) }}
                    />
                  </span>
                  <span className={css.hint}>{t('fastPipelineHint')}</span>
                </label>
                <label className={state.enabled ? css.field : `${css.field} ${css.fieldDisabled}`}>
                  <span className={css.fieldHead}>
                    <span className={css.label}>{t('logarithmicFade')}</span>
                    <input
                      type="checkbox"
                      className={css.toggle}
                      checked={state.logarithmicFade}
                      disabled={!state.writable || state.saving || !state.enabled}
                      onChange={(event) => { edit({ logarithmicFade: event.target.checked }) }}
                    />
                  </span>
                  <span className={css.hint}>{t('logarithmicFadeHint')}</span>
                </label>
                {/* Motion preference is a radio group, so this row is a div:
                    nesting the choice labels inside a field <label> would be
                    illegal HTML (label within label) and browsers route the
                    inner click away, so React never sees onChange. */}
                <div className={state.enabled ? css.field : `${css.field} ${css.fieldDisabled}`}>
                  <span className={css.fieldHead}>
                    <span className={css.label}>{t('motionPreference')}</span>
                  </span>
                  <span className={css.hint}>{t('motionPreferenceHint')}</span>
                  <span className={css.choiceRow} role="radiogroup" aria-label={t('motionPreference')}>
                    {([
                      ['auto', 'motionAuto', 'motionAutoHint'],
                      ['force-smooth', 'motionForceSmooth', 'motionForceSmoothHint'],
                      ['force-reduced', 'motionForceReduced', 'motionForceReducedHint'],
                    ] as const).map(([value, label, hint]) => (
                      <label key={value} className={css.choice} title={t(hint)}>
                        <input
                          type="radio"
                          className={css.choiceInput}
                          name="smooth-stream-motion"
                          checked={state.motionPreference === value}
                          disabled={!state.writable || state.saving || !state.enabled}
                          onChange={() => { edit({ motionPreference: value }) }}
                        />
                        {t(label)}
                      </label>
                    ))}
                  </span>
                </div>
                <label className={state.enabled ? css.field : `${css.field} ${css.fieldDisabled}`}>
                  <span className={css.fieldHead}>
                    <span className={css.label}>{t('thinkAutoExpand')}</span>
                    <input
                      type="checkbox"
                      className={css.toggle}
                      checked={state.thinkAutoExpand}
                      disabled={!state.writable || state.saving || !state.enabled}
                      onChange={(event) => { edit({ thinkAutoExpand: event.target.checked }) }}
                    />
                  </span>
                  <span className={css.hint}>{t('thinkAutoExpandHint')}</span>
                </label>
                <label className={state.enabled ? css.field : `${css.field} ${css.fieldDisabled}`}>
                  <span className={css.fieldHead}>
                    <span className={css.label}>{t('keepStreamOnToolCall')}</span>
                    <input
                      type="checkbox"
                      className={css.toggle}
                      checked={state.keepStreamOnToolCall}
                      disabled={!state.writable || state.saving || !state.enabled}
                      onChange={(event) => { edit({ keepStreamOnToolCall: event.target.checked }) }}
                    />
                  </span>
                  <span className={css.hint}>{t('keepStreamOnToolCallHint')}</span>
                </label>
                <label className={state.debugAvailable ? css.field : `${css.field} ${css.fieldDisabled}`}>
                  <span className={css.fieldHead}>
                    <span className={css.label}>{t('debugEnabled')}</span>
                    <input
                      type="checkbox"
                      className={css.toggle}
                      checked={state.debugEnabled}
                      disabled={!state.debugAvailable || !state.writable || state.saving}
                      onChange={(event) => { edit({ debugEnabled: event.target.checked }) }}
                    />
                  </span>
                  <span className={css.hint}>{state.debugAvailable ? t('debugEnabledHint') : t('debugUnavailable')}</span>
                </label>
                <div className={css.updateRow}>
                  <span className={css.updateCopy}>
                    <span className={css.label}>{t('updates')}</span>
                    <span className={css.hint}>
                      {state.restartRequired
                        ? t('restartRequired')
                        : state.installation === 'npm' ? t('updateHint')
                          : state.installation === 'development' ? t('developmentBuild') : t('updateUnavailable')}
                    </span>
                  </span>
                  <button
                    type="button"
                    className={css.update}
                    disabled={!state.canUpgrade || state.upgrading || state.restartRequired}
                    title={state.canUpgrade ? undefined : t('updateUnavailable')}
                    onClick={upgrade}
                  >
                    <span aria-hidden="true"><IconRefreshOutlineRegular /></span>
                    {t(state.upgrading ? 'updating' : 'update')}
                  </button>
                </div>
                {state.upgradeFailed ? <p className={css.failed} role="status">{t('updateFailed')}</p> : null}
                <div className={css.footer}>
                  {state.failed ? <p className={css.failed} role="status">{t('saveFailed')}</p> : null}
                  <button
                    type="button"
                    className={css.discard}
                    disabled={!state.dirty || state.saving}
                    onClick={discard}
                  >
                    {t('discard')}
                  </button>
                  <button
                    type="button"
                    className={css.save}
                    disabled={blocked}
                    onClick={save}
                  >
                    {t(state.saving ? 'saving' : 'save')}
                  </button>
                </div>
              </>
            ) : null}
          </div>
        )
        : null}
    </li>
  )
}
