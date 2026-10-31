import { getNativeScanSession, type ScanSession } from './ScanSession'
import { callback, getHostComponent } from 'react-native-nitro-modules'
import { useLayoutEffect, useMemo, useRef } from 'react'
import type { ViewProps } from 'react-native'
import type { ImagePagerViewMethods, ImagePagerViewProps } from './ImagePager.nitro'
import type { ScannerFailure } from './ScannerFailure'
import { ScannerError } from './ScannerError'
import ImagePagerViewConfig from '../nitrogen/generated/shared/json/ImagePagerViewConfig.json'

const NativeImagePager = getHostComponent<
  ImagePagerViewProps,
  ImagePagerViewMethods
>('ImagePagerView', () => ImagePagerViewConfig)

export interface ImagePagerProps
  extends Omit<ImagePagerViewProps, 'hybridRef' | 'onError' | 'session' | 'pageIds'>,
    ViewProps {
  session: ScanSession
  pageIds?: readonly string[]
  onError?: (error: ScannerError) => void
}

/** Native swiping between zoomable images; see `ImagePagerViewProps`. */
export function ImagePager({ session, pageIds, direction = 'auto', swipeEnabled = true, onPageChange, onError, onLoad, ...props }: ImagePagerProps) {
  const nativePageIds = useMemo(() => pageIds === undefined ? undefined : [...pageIds], [pageIds])
  // A stable wrapper calling the latest handler, so a new handler each render does not reach native.
  const handlers = useRef({ onPageChange, onError, onLoad })
  useLayoutEffect(() => {
    handlers.current = { onPageChange, onError, onLoad }
  })
  const native = useMemo(
    () => ({
      onPageChange: callback((pageId: string) => handlers.current.onPageChange?.(pageId)),
      onLoad: callback((pageId: string) => handlers.current.onLoad?.(pageId)),
      onError: callback((failure: ScannerFailure) => handlers.current.onError?.(new ScannerError(failure.code))),
    }),
    [],
  )

  // Always set, since React sends a removed prop as null, which the native optional cannot take.
  return (
    <NativeImagePager
      {...props}
      pageIds={nativePageIds}
      session={getNativeScanSession(session)}
      direction={direction}
      swipeEnabled={swipeEnabled}
      onPageChange={native.onPageChange}
      onError={native.onError}
      onLoad={native.onLoad}
    />
  )
}
