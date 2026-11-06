import { getNativeScanSession, synchronizeSession, type ScanSession } from './ScanSession'
import { callback, getHostComponent } from 'react-native-nitro-modules'
import { forwardRef, useImperativeHandle, useLayoutEffect, useMemo, useRef } from 'react'
import type { ViewProps } from 'react-native'
import type {
  CropPreset,
  CropDraftPreset,
  DocumentCropEditorMethods,
  DocumentCropEditorProps,
} from './DocumentCropEditor.nitro'
import type { DocumentCorners } from './DocumentScannerProcessor.nitro'
import type { ScannerFailure } from './ScannerFailure'
import { ScannerError, scannerOperation } from './ScannerError'
import DocumentCropEditorViewConfig from '../nitrogen/generated/shared/json/DocumentCropEditorViewConfig.json'

const NativeCropEditor = getHostComponent<
  DocumentCropEditorProps,
  DocumentCropEditorMethods
>('DocumentCropEditorView', () => DocumentCropEditorViewConfig)

export interface CropEditorProps
  extends Omit<DocumentCropEditorProps, 'hybridRef' | 'onError' | 'session'>,
    ViewProps {
  session: ScanSession
  onError?: (error: ScannerError) => void
}

/** Native crop editing over a captured photo; see `DocumentCropEditorProps`. */
export interface CropEditorRef {
  submit(): Promise<void>
  getCorners(): Promise<DocumentCorners>
  setPreset(preset: CropPreset): Promise<void>
}

export const CropEditor = forwardRef<CropEditorRef, CropEditorProps>(function CropEditor({
  session,
  pageId,
  filter = 'none',
  rectangle = false,
  onLoad,
  onPresetChange,
  onError,
  ...props
}: CropEditorProps, ref) {
  // Stable wrappers calling the latest handlers, so new handlers each render do not reach native.
  const handlers = useRef({ onLoad, onError, onPresetChange })
  useLayoutEffect(() => {
    handlers.current = { onLoad, onError, onPresetChange }
  })
  const native = useMemo(
    () => ({
      onPresetChange: callback((preset: CropDraftPreset) => handlers.current.onPresetChange?.(preset)),
      onLoad: callback((width: number, height: number) => handlers.current.onLoad?.(width, height)),
      onError: callback((failure: ScannerFailure) => handlers.current.onError?.(new ScannerError(failure.code))),
    }),
    [],
  )

  const nativeRef = useRef<DocumentCropEditorMethods | null>(null)
  const owner = useRef(session)
  useLayoutEffect(() => { owner.current = session }, [session])
  const setRef = useMemo(() => callback((value: DocumentCropEditorMethods | null) => { nativeRef.current = value }), [])
  useImperativeHandle(ref, () => {
    const view = () => {
      if (!nativeRef.current) throw new ScannerError('input/invalid')
      return nativeRef.current
    }
    return {
      submit: async () => {
        const session = owner.current
        await scannerOperation(() => view().submitResult(), 'input/invalid')
        synchronizeSession(session)
      },
      getCorners: () => scannerOperation(() => view().getCornersResult(), 'input/invalid'),
      setPreset: (preset: CropPreset) => scannerOperation(() => view().setPresetResult(preset), 'input/invalid').then(() => {}),
    }
  }, [])
  return (
    <NativeCropEditor
      {...props}
      session={getNativeScanSession(session)}
      pageId={pageId}
      hybridRef={setRef}
      filter={filter}
      rectangle={rectangle}
      onPresetChange={native.onPresetChange}
      onLoad={native.onLoad}
      onError={native.onError}
    />
  )
})
