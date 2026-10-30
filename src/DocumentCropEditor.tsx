import { callback, getHostComponent } from 'react-native-nitro-modules'
import { useLayoutEffect, useMemo, useRef } from 'react'
import type { ViewProps } from 'react-native'
import type {
  DocumentCropEditorMethods,
  DocumentCropEditorProps,
} from './DocumentCropEditor.nitro'
import type { DocumentQuad } from './DocumentScannerProcessor.nitro'
import type { ScannerFailure } from './ScannerFailure'
import { ScannerError } from './ScannerError'
import DocumentCropEditorViewConfig from '../nitrogen/generated/shared/json/DocumentCropEditorViewConfig.json'

const NativeCropEditor = getHostComponent<
  DocumentCropEditorProps,
  DocumentCropEditorMethods
>('DocumentCropEditorView', () => DocumentCropEditorViewConfig)

export interface CropEditorProps
  extends Omit<DocumentCropEditorProps, 'hybridRef' | 'onError'>,
    ViewProps {
  onError?: (error: ScannerError) => void
}

/** Native crop editing over a captured photo; see `DocumentCropEditorProps`. */
export function CropEditor({
  originalUri,
  quad,
  filter = 'none',
  onQuadChange,
  onLoad,
  onError,
  ...props
}: CropEditorProps) {
  // Stable wrappers calling the latest handlers, so new handlers each render do not reach native.
  const handlers = useRef({ onQuadChange, onLoad, onError })
  useLayoutEffect(() => {
    handlers.current = { onQuadChange, onLoad, onError }
  })
  const native = useMemo(
    () => ({
      onQuadChange: callback((edited: DocumentQuad) => handlers.current.onQuadChange?.(edited)),
      onLoad: callback((width: number, height: number) => handlers.current.onLoad?.(width, height)),
      onError: callback((failure: ScannerFailure) => handlers.current.onError?.(new ScannerError(failure.code))),
    }),
    [],
  )

  return (
    <NativeCropEditor
      {...props}
      originalUri={originalUri}
      quad={quad}
      filter={filter}
      onQuadChange={native.onQuadChange}
      onLoad={native.onLoad}
      onError={native.onError}
    />
  )
}
