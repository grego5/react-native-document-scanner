import { useEffect, useReducer, useRef, useState } from 'react'
import { Alert } from 'react-native'
import {
  DocumentScanner,
  type CapturedPhoto,
  type DocumentFilter,
  type DocumentQuad,
} from 'react-native-document-scanner'
import { CachedImageJanitor } from '../lib/cachedImageJanitor'
import { isValidEditableQuad } from '../lib/documentReview'
import { errorMessage } from '../lib/errorMessage'
import { removeOldPdfExports } from '../lib/pdfExports'
import { INITIAL_SESSION, isPreviewCurrent, ReviewError, referencedUris, sessionReducer, type SessionAction } from '../lib/scanSession'

/** Pages render at A4 and the PDF export's 200 dpi, so review shows exactly what is saved and exported. */
const PAPER = { paperSize: { width: 21, height: 29.7 }, dpi: 200 }

/**
 * One in-memory scan: ordered accepted pages and the photo under review. Cached files leave with the last reference
 * to them, and files from earlier launches are deleted before `ready`.
 */
export function useScanSession() {
  const [state, dispatch] = useReducer(sessionReducer, INITIAL_SESSION)
  const [janitor] = useState(() => new CachedImageJanitor())
  const [ready, setReady] = useState(false)
  const [rendering, setRendering] = useState(false)
  const [exporting, setExporting] = useState(false)
  const nextId = useRef(0)
  // Counts resets, so a capture requested for a discarded scan is not added to the next one.
  const generation = useRef(0)
  const newId = (prefix: string) => `${prefix}-${++nextId.current}`

  useEffect(() => {
    try {
      removeOldPdfExports()
    } catch (error) {
      console.warn('Could not delete expired PDF exports.', error)
    }
    janitor.removeLeftovers()
      .catch((error: unknown) => console.warn('Could not delete earlier scan images.', error))
      .finally(() => setReady(true))
  }, [janitor])

  useEffect(() => {
    janitor.collect(referencedUris(state))
  }, [janitor, state])

  const { draft } = state
  const analyzingId = draft?.analyzing ? draft.id : null
  const analyzingUri = draft?.originalUri
  const analyzingQuad = draft?.quad ?? undefined

  useEffect(() => {
    if (analyzingId == null || analyzingUri == null) return
    let active = true
    // A result for a draft that has since been discarded is ignored by the reducer.
    janitor.track(DocumentScanner.analyzePhoto(analyzingUri, analyzingQuad)).then(
      (analysis) => dispatch({ type: 'analyzed', draftId: analyzingId, analysis }),
      (error: unknown) => {
        dispatch({ type: 'analysis-failed', draftId: analyzingId })
        if (active) Alert.alert('Could not analyze this photo.', errorMessage(error, ''))
      },
    )
    return () => {
      active = false
    }
  }, [janitor, analyzingId, analyzingUri, analyzingQuad])

  const draftId = draft?.id
  const originalUri = draft?.originalUri
  const quad = draft?.quad
  const removeShadows = draft?.removeShadows ?? true
  const filter = draft?.filter
  const stale = draft != null && !isPreviewCurrent(draft)
  const cropping = draft?.cropQuad != null
  const analyzing = draft?.analyzing ?? false

  // Renders the preview from the original whenever the crop or filter changes outside crop mode to settings the draft
  // has no render of yet; accepting saves it. A failed render is tried again on the next crop or filter change.
  useEffect(() => {
    if (analyzing || !stale || cropping || draftId == null || originalUri == null || quad == null || filter == null) return
    if (!isValidEditableQuad(quad)) return
    let active = true
    setRendering(true)
    janitor.track(
      DocumentScanner.renderDocument(originalUri, quad, {
        ...PAPER,
        filter,
        removeShadows,
        format: filter === 'blackWhite' ? 'png' : 'jpeg',
      }),
    ).then(
      ({ outputUri, thumbnailUri }) => {
        // A render the user has moved past is kept for reuse rather than shown; the janitor deletes it if the draft
        // does not keep it.
        janitor.own(outputUri)
        janitor.own(thumbnailUri)
        const preview = { uri: outputUri, thumbnailUri, quad, filter, removeShadows }
        dispatch({ type: 'previewed', draftId, preview, show: active })
      },
      (error: unknown) => {
        if (active) Alert.alert('Could not render this page.', errorMessage(error, ''))
      },
    ).finally(() => {
      if (active) setRendering(false)
    })
    return () => {
      active = false
      setRendering(false)
    }
  }, [janitor, analyzing, stale, cropping, draftId, originalUri, quad, filter, removeShadows])

  /** `requestedIn` is the `generation` read when the shutter was pressed. */
  const capture = ({ originalUri: uri, frameUri, liveCandidate: captured }: CapturedPhoto, requestedIn: number) => {
    if (requestedIn !== generation.current) {
      janitor.release(uri)
      if (frameUri) janitor.release(frameUri)
      return
    }
    janitor.own(uri)
    if (frameUri) janitor.own(frameUri)
    dispatch({ type: 'captured', draftId: newId('draft'), uri, frameUri, liveCandidate: captured })
  }

  /**
   * Runs an export of the pages' files at session scope: no file is deleted until it settles, and `exporting` lets
   * every screen hold back navigation and edits meanwhile.
   */
  const exportPages = async (operation: () => Promise<void>) => {
    if (exporting) return
    setExporting(true)
    try {
      await janitor.track(operation())
    } finally {
      setExporting(false)
    }
  }

  /** Applies a review edit; the panel disables edits until the page's analysis settles, so an earlier call throws. */
  const edit = (action: SessionAction) => {
    if (draft?.analyzing) throw new ReviewError('review/analyzing')
    if (!draft) {
      if (!state.summary || state.selectedId == null) throw new ReviewError('review/no-draft')
      dispatch({ type: 'edit-page', pageId: state.selectedId, draftId: newId('draft') })
    }
    dispatch(action)
  }

  /**
   * Adds the shown preview as a page, or saves it over the edited page, then goes to `then`. Throws unless
   * the preview shows the current crop, filter and shadow setting.
   */
  const commitDraft = (then: 'camera' | 'summary' = 'summary') => {
    if (!draft || draft.analyzing || draft.cropQuad || !isPreviewCurrent(draft)) {
      throw new ReviewError('review/preview-not-ready')
    }
    dispatch({ type: 'committed', draftId: draft.id, pageId: draft.pageId ?? newId('page'), then })
  }

  return {
    ...state,
    ready,
    rendering,
    exporting,
    generation: generation.current,
    capture,
    exportPages,
    commitDraft,
    startCrop: () => edit({ type: 'start-crop' }),
    editQuad: (edited: DocumentQuad) => edit({ type: 'edited', quad: edited }),
    endCrop: (submit: boolean) => edit({ type: 'end-crop', submit }),
    toggleCrop: () => edit({ type: 'toggle-crop' }),
    setRemoveShadows: (value: boolean) => edit({ type: 'set-remove-shadows', value }),
    setDefaultRemoveShadows: (value: boolean) => dispatch({ type: 'set-default-remove-shadows', value }),
    setFilter: (filter: DocumentFilter) => edit({ type: 'set-filter', filter }),
    discardDraft: () => dispatch({ type: 'discard-draft' }),
    editPage: (pageId: string) => dispatch({ type: 'edit-page', pageId, draftId: newId('draft') }),
    /** Ignored unless the open page has no unsaved changes; the review pager only swipes then. */
    switchPage: (pageId: string) => dispatch({ type: 'switch-page', pageId, draftId: newId('draft') }),
    showSummary: (open: boolean) => dispatch({ type: 'show-summary', open }),
    select: (pageId: string) => dispatch({ type: 'select', pageId }),
    deletePage: (pageId: string) => dispatch({ type: 'delete', pageId }),
    reset: () => {
      generation.current++
      dispatch({ type: 'reset' })
    },
  }
}

export type ScanSession = ReturnType<typeof useScanSession>
