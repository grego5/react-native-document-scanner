import { getNativeScanSession, type ScanSession } from './ScanSession'
import { callback, getHostComponent } from 'react-native-nitro-modules'
import { forwardRef, useImperativeHandle, useLayoutEffect, useMemo, useRef } from 'react'
import type { ViewProps } from 'react-native'
import type { ImageViewerViewMethods, ImageViewerViewProps } from './ImageViewer.nitro'
import type { ScannerFailure } from './ScannerFailure'
import { ScannerError } from './ScannerError'
import ImageViewerViewConfig from '../nitrogen/generated/shared/json/ImageViewerViewConfig.json'

const NativeImageViewer = getHostComponent<
  ImageViewerViewProps,
  ImageViewerViewMethods
>('ImageViewerView', () => ImageViewerViewConfig)

export interface ImageViewerProps
  extends Omit<ImageViewerViewProps, 'hybridRef' | 'onError' | 'session'>,
    ViewProps {
  session: ScanSession
  onError?: (error: ScannerError) => void
}

/** A mounted `ImageViewer`; both calls are safe before the native view exists or after it is gone. */
export interface ImageViewerRef {
  /** Whether the image is zoomed past the fit. */
  isZoomed(): boolean
  /** Returns the image to the fit. */
  resetZoom(): void
}

/** Native pinch, pan and double-tap zoom over an image; see `ImageViewerViewProps`. */
export const ImageViewer = forwardRef<ImageViewerRef, ImageViewerProps>(function ImageViewer(
  { session, pageId, onError, onLoad, onZoomChange, ...props },
  forwardedRef,
) {
  // A stable wrapper calling the latest handler, so a new handler each render does not reach native.
  const handlers = useRef({ onError, onLoad, onZoomChange })
  useLayoutEffect(() => {
    handlers.current = { onError, onLoad, onZoomChange }
  })
  const nativeRef = useRef<ImageViewerViewMethods | null>(null)
  const native = useMemo(
    () => ({
      onLoad: callback((loadedUri: string) => handlers.current.onLoad?.(loadedUri)),
      onError: callback((failure: ScannerFailure) => handlers.current.onError?.(new ScannerError(failure.code))),
      onZoomChange: callback((zoomed: boolean) => handlers.current.onZoomChange?.(zoomed)),
      hybridRef: callback((view: ImageViewerViewMethods | null) => {
        nativeRef.current = view
      }),
    }),
    [],
  )
  useImperativeHandle(
    forwardedRef,
    () => ({
      isZoomed: () => nativeRef.current?.isZoomed() ?? false,
      resetZoom: () => nativeRef.current?.resetZoom(),
    }),
    [],
  )

  return (
    <NativeImageViewer
      {...props}
      session={getNativeScanSession(session)}
      pageId={pageId}
      onError={native.onError}
      onLoad={native.onLoad}
      onZoomChange={native.onZoomChange}
      hybridRef={native.hybridRef}
    />
  )
})
