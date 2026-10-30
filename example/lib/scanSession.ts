import type { DocumentAnalysis, DocumentCandidate, DocumentFilter, DocumentQuad } from 'react-native-document-scanner'
import { clampQuadToFrame, isValidEditableQuad, sameQuad } from './documentReview'

export const MAX_PAGES = 50

/** The whole photo; a page still holding this object exports its original unchanged. */
export const FULL_FRAME: DocumentQuad = {
  topLeft: { x: 0, y: 0 },
  topRight: { x: 1, y: 0 },
  bottomRight: { x: 1, y: 1 },
  bottomLeft: { x: 0, y: 1 },
}

/** An accepted page; `revision` counts saved edits so a stale save cannot overwrite a newer one. */
export interface ScanPage {
  id: string
  originalUri: string
  analysis: DocumentAnalysis | null
  quad: DocumentQuad
  /** Whether `quad` started from the detected page or from the full frame. */
  crop: boolean
  /** Last cropped outline, retained while Crop is off. */
  croppedQuad?: DocumentQuad
  filter: DocumentFilter
  removeShadows?: boolean
  outputUri: string
  /** A small copy of `outputUri` for page lists. */
  thumbnailUri: string
  revision: number
}

/** A page rendered from the original photo with the given crop and filter. */
export interface PagePreview {
  uri: string
  /** A small copy of `uri`, when the render wrote one. */
  thumbnailUri?: string
  quad: DocumentQuad
  filter: DocumentFilter
  removeShadows?: boolean
}

/** The photo under review: a new shot when `pageId` is null, otherwise an accepted page being edited. */
export interface ReviewDraft {
  id: string
  pageId: string | null
  /** The page revision the edit started from. */
  revision: number
  originalUri: string
  /** The camera's capture of its latest live frame, shown until the first render; null without one. */
  frameUri: string | null
  analyzing: boolean
  analysis: DocumentAnalysis | null
  quad: DocumentQuad | null
  crop: boolean
  /** Last cropped outline, retained while Crop is off. */
  croppedQuad?: DocumentQuad
  filter: DocumentFilter
  removeShadows?: boolean
  /** The latest render; it is current only while it matches `quad` and `filter`, and is what an accept saves. */
  preview: PagePreview | null
  /**
   * Finished renders of the current `quad`, one per filter and shadow setting: returning to one shows it again
   * without rendering. A crop change empties it, and its files are deleted once the draft no longer holds them.
   */
  renders: PagePreview[]
  /** The outline being moved in crop mode, over the original photo; null while the rendered page is shown. */
  cropQuad: DocumentQuad | null
}

export interface SessionState {
  /** Default for new captures; saved pages keep their own shadow setting. */
  removeShadows: boolean
  pages: ScanPage[]
  draft: ReviewDraft | null
  /** Whether the retained pager overlay is visible when no draft is open. */
  summary: boolean
  selectedId: string | null
}

export type SessionAction =
  | { type: 'captured'; draftId: string; uri: string; frameUri?: string; liveCandidate?: DocumentCandidate }
  | { type: 'analyzed'; draftId: string; analysis: DocumentAnalysis }
  | { type: 'analysis-failed'; draftId: string }
  | { type: 'start-crop' }
  | { type: 'edited'; quad: DocumentQuad }
  /** Submit makes the crop-mode outline the draft's quad; cancel keeps the quad it started from. */
  | { type: 'end-crop'; submit: boolean }
  /** A finished render; `show` is false for one the user has moved past, which is only kept for reuse. */
  | { type: 'previewed'; draftId: string; preview: PagePreview; show: boolean }
  | { type: 'toggle-crop' }
  | { type: 'set-filter'; filter: DocumentFilter }
  | { type: 'set-remove-shadows'; value: boolean }
  | { type: 'set-default-remove-shadows'; value: boolean }
  | { type: 'discard-draft' }
  /** After saving, return to the camera or keep the selected page in the pager. */
  | { type: 'committed'; draftId: string; pageId: string; then: 'camera' | 'summary' }
  | { type: 'edit-page'; pageId: string; draftId: string }
  /** Swiping to another accepted page while editing one that has no unsaved changes. */
  | { type: 'switch-page'; pageId: string; draftId: string }
  | { type: 'show-summary'; open: boolean }
  | { type: 'select'; pageId: string }
  /** The pages' ids in their new order; ignored unless it names exactly the current pages. */
  | { type: 'delete'; pageId: string }
  | { type: 'reset' }

/** Whether the draft's preview shows its current crop and filter, so accepting it saves what the user sees. */
export function isPreviewCurrent(draft: ReviewDraft): draft is ReviewDraft & { quad: DocumentQuad; preview: PagePreview } {
  const { preview } = draft
  return preview != null && preview.quad === draft.quad && preview.filter === draft.filter && (preview.removeShadows ?? true) === (draft.removeShadows ?? true)
}

/** A review action called before the page allows it; the review panel disables these, so it marks a wiring bug. */
export class ReviewError extends Error {
  readonly code: 'review/no-draft' | 'review/analyzing' | 'review/preview-not-ready'

  constructor(code: ReviewError['code']) {
    super(code)
    this.name = 'ReviewError'
    this.code = code
  }
}

/**
 * Whether the review may move to another accepted page: the draft edits one, is idle, and still shows the page as
 * saved, so leaving it loses nothing.
 */
export function canSwitchPage({ draft, pages }: SessionState): boolean {
  const page = draft?.pageId == null ? undefined : pages.find(({ id }) => id === draft.pageId)
  return page != null && draft != null && !draft.analyzing && draft.cropQuad == null &&
    isPreviewCurrent(draft) && draft.preview.uri === page.outputUri && draft.revision === page.revision
}

/** [draft] with the outline [quad]; renders of another outline leave the cache. */
function withQuad(draft: ReviewDraft, quad: DocumentQuad | null): ReviewDraft {
  return quad === draft.quad ? draft : { ...draft, quad, renders: draft.renders.filter((render) => render.quad === quad) }
}

/** [draft] showing its cached render of the current outline, filter and shadow setting, when there is one. */
function withCachedPreview(draft: ReviewDraft): ReviewDraft {
  const removeShadows = draft.removeShadows ?? true
  const cached = draft.renders.find((render) =>
    render.quad === draft.quad && render.filter === draft.filter && (render.removeShadows ?? true) === removeShadows)
  return cached ? { ...draft, preview: cached } : draft
}

/** A draft that opens [page] as saved. */
function pageDraft(page: ScanPage, id: string): ReviewDraft {
  const preview = {
    uri: page.outputUri, thumbnailUri: page.thumbnailUri, quad: page.quad, filter: page.filter, removeShadows: page.removeShadows,
  }
  return {
    id,
    pageId: page.id,
    revision: page.revision,
    originalUri: page.originalUri,
    frameUri: null,
    analyzing: false,
    analysis: page.analysis,
    quad: page.quad,
    crop: page.crop,
    croppedQuad: page.croppedQuad ?? (page.crop ? page.quad : undefined),
    filter: page.filter,
    removeShadows: page.removeShadows,
    // The saved output already shows the page's crop and filter.
    preview,
    renders: [preview],
    cropQuad: null,
  }
}

export const INITIAL_SESSION: SessionState = { removeShadows: true, pages: [], draft: null, summary: false, selectedId: null }

/** Review edits and acceptance, which wait for the page's analysis; the review panel disables them until then. */
const REVIEW_ACTIONS = new Set<SessionAction['type']>([
  'start-crop', 'edited', 'end-crop', 'toggle-crop', 'set-filter', 'set-remove-shadows', 'committed',
])

export function sessionReducer(state: SessionState, action: SessionAction): SessionState {
  if (state.draft?.analyzing && REVIEW_ACTIONS.has(action.type)) return state
  switch (action.type) {
    case 'captured': {
      if (state.draft || state.pages.length >= MAX_PAGES) return state
      // The live guide's outline previews at once; analysis then refines it.
      const live = action.liveCandidate ? clampQuadToFrame(action.liveCandidate.quad) : null
      const seed = live && isValidEditableQuad(live) ? live : null
      return {
        ...state,
        draft: {
          id: action.draftId,
          pageId: null,
          revision: 0,
          originalUri: action.uri,
          frameUri: action.frameUri ?? null,
          analyzing: true,
          analysis: null,
          quad: seed,
          crop: seed != null,
          croppedQuad: seed ?? undefined,
          filter: 'none',
          removeShadows: state.removeShadows,
          preview: null,
          renders: [],
          cropQuad: null,
        },
      }
    }
    case 'analyzed': {
      const { draft } = state
      if (draft?.id !== action.draftId) return state
      // Start from the detected page when there is one, otherwise from the full frame. An unchanged outline keeps its
      // quad object, so the live outline's preview stays current.
      const detected = action.analysis.candidate?.quad
      const quad = detected ? clampQuadToFrame(detected) : FULL_FRAME
      return {
        ...state,
        draft: {
          ...withQuad(draft, draft.quad && sameQuad(draft.quad, quad) ? draft.quad : quad),
          analyzing: false,
          analysis: action.analysis,
          crop: detected != null,
          croppedQuad: detected ? quad : undefined,
        },
      }
    }
    case 'analysis-failed': {
      // Without an analysis the page can still be cropped by hand, from the live outline or the full frame.
      const { draft } = state
      if (draft?.id !== action.draftId) return state
      return {
        ...state,
        draft: { ...withQuad(draft, draft.quad ?? FULL_FRAME), analyzing: false, crop: draft.quad != null },
      }
    }
    case 'start-crop': {
      const { draft } = state
      if (!draft?.quad || draft.cropQuad) return state
      return { ...state, draft: { ...draft, cropQuad: draft.quad } }
    }
    case 'edited':
      if (!state.draft?.cropQuad) return state
      return { ...state, draft: { ...state.draft, cropQuad: action.quad } }
    case 'end-crop': {
      const { draft } = state
      if (!draft?.cropQuad) return state
      // An outline that ends where it started keeps its quad object, so the current preview stays current.
      const quad = action.submit && !(draft.quad && sameQuad(draft.cropQuad, draft.quad)) ? draft.cropQuad : draft.quad
      const cropped = quad != null && !sameQuad(quad, FULL_FRAME)
      return { ...state, draft: { ...withQuad(draft, quad), cropQuad: null,
        crop: action.submit ? cropped : draft.crop,
        croppedQuad: action.submit && cropped ? quad : draft.croppedQuad } }
    }
    case 'previewed': {
      const { draft } = state
      if (draft?.id !== action.draftId) return state
      const { preview } = action
      const shown = action.show ? { ...draft, preview } : draft
      // Only renders of the current outline are kept; one replaces any earlier render with the same settings.
      if (preview.quad !== draft.quad) return shown === draft ? state : { ...state, draft: shown }
      const renders = shown.renders.filter((render) =>
        render.filter !== preview.filter || (render.removeShadows ?? true) !== (preview.removeShadows ?? true))
      return { ...state, draft: { ...shown, renders: [...renders, preview] } }
    }
    case 'toggle-crop': {
      const { draft } = state
      const croppedQuad = draft?.croppedQuad ?? draft?.analysis?.candidate?.quad
      if (!draft || !croppedQuad) return state
      return { ...state, draft: {
        ...withQuad(draft, draft.crop ? FULL_FRAME : clampQuadToFrame(croppedQuad)),
        crop: !draft.crop, croppedQuad: draft.crop ? draft.quad ?? croppedQuad : croppedQuad,
      } }
    }
    case 'set-default-remove-shadows':
      return { ...state, removeShadows: action.value }
    case 'set-remove-shadows':
      return state.draft ? { ...state, draft: withCachedPreview({ ...state.draft, removeShadows: action.value }) } : state
    case 'set-filter':
      if (!state.draft) return state
      return { ...state, draft: withCachedPreview({ ...state.draft, filter: action.filter }) }
    case 'discard-draft':
      return state.draft ? { ...state, draft: null, summary: state.pages.length > 0 } : state
    case 'committed': {
      const { draft } = state
      if (draft?.id !== action.draftId || draft.cropQuad || !isPreviewCurrent(draft)) return state
      const fields = { quad: draft.quad, crop: draft.crop, croppedQuad: draft.croppedQuad, filter: draft.filter, removeShadows: draft.removeShadows, outputUri: draft.preview.uri,
        thumbnailUri: draft.preview.thumbnailUri ?? draft.preview.uri }
      if (draft.pageId == null) {
        if (state.pages.length >= MAX_PAGES) return state
        const page: ScanPage = {
          id: action.pageId,
          originalUri: draft.originalUri,
          analysis: draft.analysis,
          revision: 0,
          ...fields,
        }
        return { ...state, pages: [...state.pages, page], draft: null, selectedId: page.id, summary: action.then === 'summary' }
      }
      const index = state.pages.findIndex((page) => page.id === draft.pageId)
      if (index < 0 || state.pages[index].revision !== draft.revision) return { ...state, draft: null }
      const pages = state.pages.slice()
      pages[index] = { ...pages[index], ...fields, revision: draft.revision + 1 }
      return { ...state, pages, draft: null, summary: action.then === 'summary', selectedId: draft.pageId }
    }
    case 'edit-page': {
      const page = state.pages.find(({ id }) => id === action.pageId)
      if (!page || state.draft) return state
      return { ...state, selectedId: page.id, draft: pageDraft(page, action.draftId) }
    }
    case 'switch-page': {
      const page = state.pages.find(({ id }) => id === action.pageId)
      if (!page || !state.draft || !canSwitchPage(state)) return state
      return { ...state, selectedId: page.id, draft: pageDraft(page, action.draftId) }
    }
    case 'show-summary':
      return { ...state, summary: action.open && state.pages.length > 0 }
    case 'select':
      return state.pages.some(({ id }) => id === action.pageId) ? { ...state, selectedId: action.pageId } : state
    case 'delete': {
      const index = state.pages.findIndex(({ id }) => id === action.pageId)
      if (index < 0) return state
      const pages = state.pages.filter(({ id }) => id !== action.pageId)
      // The page that takes the deleted one's place stays selected, or the new last page.
      const selectedId = pages[Math.min(index, pages.length - 1)]?.id ?? null
      // An edit of the deleted page has nothing left to save into.
      const draft = state.draft?.pageId === action.pageId ? null : state.draft
      return { ...state, pages, draft, selectedId, summary: pages.length > 0 && (state.summary || state.draft?.pageId === action.pageId) }
    }
    case 'reset':
      return { ...INITIAL_SESSION, removeShadows: state.removeShadows }
  }
}

/** Every cached file the session still needs: originals, accepted outputs and thumbnails, and the draft's photo, latest frame and renders. */
export function referencedUris(state: SessionState): Set<string> {
  const uris = new Set<string>()
  for (const page of state.pages) {
    uris.add(page.originalUri)
    uris.add(page.outputUri)
    uris.add(page.thumbnailUri)
  }
  const { draft } = state
  if (draft) {
    uris.add(draft.originalUri)
    if (draft.frameUri) uris.add(draft.frameUri)
    for (const render of draft.preview ? [draft.preview, ...draft.renders] : draft.renders) {
      uris.add(render.uri)
      if (render.thumbnailUri) uris.add(render.thumbnailUri)
    }
  }
  return uris
}
