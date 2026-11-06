export const MAX_PAGES = 50
export const CAPTURE_PAPER = { width: 21, height: 29.7, dpi: 200 }

export interface PageReview {
  type: 'review'
  pageId: string
  origin: 'capture' | 'saved'
  cropping: boolean
}
export type ScanScreen = { type: 'camera' } | { type: 'summary' } | PageReview
export interface SessionState {
  selectedId: string | null
  screen: ScanScreen
}
export const INITIAL_SESSION: SessionState = {
  selectedId: null, screen: { type: 'camera' },
}
export type SessionAction =
  | { type: 'membership'; pageIds: readonly string[] }
  | { type: 'captured'; pageId: string }
  | { type: 'start-crop' }
  | { type: 'end-crop' }
  | { type: 'navigate'; screen: 'camera' | 'summary' }
  | { type: 'select'; pageId: string }
  | { type: 'delete'; pageId: string; nextId: string | null }

/** App selection and navigation; native owns pages and their order. */
export function sessionReducer(state: SessionState, action: SessionAction, pageIds: readonly string[] = []): SessionState {
  switch (action.type) {
    case 'membership': {
      const selectedId = state.selectedId != null && pageIds.includes(state.selectedId) ? state.selectedId : pageIds[0] ?? null
      const screen = state.screen.type === 'review' && !pageIds.includes(state.screen.pageId)
        ? { type: pageIds.length ? 'summary' as const : 'camera' as const } : state.screen
      return selectedId === state.selectedId && screen === state.screen ? state : { ...state, selectedId, screen }
    }
    case 'captured':
      return { ...state, selectedId: action.pageId,
        screen: { type: 'review', pageId: action.pageId, origin: 'capture', cropping: false } }
    case 'start-crop':
      return state.selectedId == null ? state : { ...state, screen: {
        type: 'review', pageId: state.selectedId,
        origin: state.screen.type === 'review' ? state.screen.origin : 'saved', cropping: true,
      } }
    case 'end-crop':
      return state.screen.type !== 'review' ? state :
        { ...state, screen: { ...state.screen, cropping: false } }
    case 'navigate':
      return { ...state, screen: { type: action.screen === 'summary' && pageIds.length > 0 ? 'summary' : 'camera' } }
    case 'select':
      return action.pageId === state.selectedId || !pageIds.includes(action.pageId) ||
        (state.screen.type === 'review' && state.screen.cropping) ? state :
        { ...state, selectedId: action.pageId, screen: { type: 'summary' } }
    case 'delete':
      return { ...state, selectedId: action.nextId,
        screen: action.nextId == null ? { type: 'camera' } :
          state.screen.type === 'review' && state.screen.pageId === action.pageId ? { type: 'summary' } : state.screen }
  }
}
