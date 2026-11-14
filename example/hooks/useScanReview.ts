import { useReducer, useRef, useState, useSyncExternalStore } from 'react'
import { Alert } from 'react-native'
import type { CropEditorRef, DocumentFilter, ScanSession as NativeScanSession } from 'react-native-document-scanner'
import { errorMessage } from '../lib/errorMessage'
import { INITIAL_SESSION, sessionReducer } from '../lib/scanSession'

/** UI state for one fixed native session. The parent owns creation and close. */
export function useScanReview(native: NativeScanSession, reset: () => void) {
  const metadata = useSyncExternalStore(native.subscribe, native.getSession)
  const [storedState, dispatch] = useReducer((state: typeof INITIAL_SESSION, action: Parameters<typeof sessionReducer>[1]) => sessionReducer(state, action, metadata.pageIds), INITIAL_SESSION)
  const membership = { type: 'membership' as const, pageIds: metadata.pageIds }
  const state = sessionReducer(storedState, membership, metadata.pageIds)
  // Reconcile UI selection when native membership changes.
  if (state !== storedState) dispatch(membership)
  const [working, setWorking] = useState(false)
  const operationLock = useRef(false)
  const run = async (operation: () => Promise<void>) => {
    if (operationLock.current) return
    operationLock.current = true
    setWorking(true)
    try { await operation() }
    finally { operationLock.current = false; setWorking(false) }
  }
  const command = (operation: () => Promise<void>) => {
    void run(operation).catch((error: unknown) => {
      if (error instanceof Error && error.name === 'AbortError') return
      Alert.alert('Page', errorMessage(error, 'Could not update the page.'))
    })
  }
  const page = state.selectedId == null ? undefined : metadata.pages[state.selectedId]
  const deletePage = async (pageId: string) => {
    await native.removePage(pageId)
    const remaining = metadata.pageIds.filter(id => id !== pageId)
    dispatch({ type: 'delete', pageId, nextId: state.selectedId === pageId
      ? remaining[Math.min(metadata.pageIds.indexOf(pageId), remaining.length - 1)] ?? null : state.selectedId })
  }
  return {
    ...state, native, page, pageIds: metadata.pageIds, working, reset: () => { if (!operationLock.current) reset() },
    reviewCapture: (pageId: string) => dispatch({ type: 'captured', pageId }),
    run,
    /**
     * Runs an import as the current operation and routes its admitted page IDs while still owning it: one page opens
     * review, several open the summary, none stays put.
     */
    importPages: (operation: () => Promise<readonly string[] | undefined>) =>
      run(async () => {
        const pageIds = await operation()
        const [first] = pageIds ?? []
        if (pageIds?.length === 1 && first != null) dispatch({ type: 'captured', pageId: first })
        else if (pageIds != null && pageIds.length > 1) dispatch({ type: 'navigate', screen: 'summary' })
      }),
    navigate: (screen: 'camera' | 'summary') => { if (!operationLock.current) dispatch({ type: 'navigate', screen }) },
    startCrop: () => { if (page) dispatch({ type: 'start-crop' }) },
    cancelCrop: () => { if (!operationLock.current) dispatch({ type: 'end-crop' }) },
    submitCrop: (editor: CropEditorRef) => {
      const screen = state.screen
      if (screen.type !== 'review' || !screen.cropping) return
      command(async () => {
        await editor.submit()
        dispatch({ type: 'end-crop' })
      })
    },
    setShadowRemoval: (value: boolean) => { if (page) command(() => native.setShadowRemoval(page.id, value)) },
    setFilter: (filter: DocumentFilter) => { if (page) command(() => native.setFilter(page.id, filter)) },
    dismissReview: () => {
      if (operationLock.current) return
      const screen = state.screen
      if (screen.type === 'review' && screen.origin === 'capture') command(() => deletePage(screen.pageId))
      else dispatch({ type: 'navigate', screen: 'summary' })
    },
    select: (pageId: string) => dispatch({ type: 'select', pageId }),
    deletePage: (pageId: string) => command(() => deletePage(pageId)),
  }
}
export type ScanReview = ReturnType<typeof useScanReview>
