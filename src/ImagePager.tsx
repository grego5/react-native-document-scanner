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
  extends Omit<ImagePagerViewProps, 'hybridRef' | 'onError'>,
    ViewProps {
  onError?: (error: ScannerError) => void
}

/** Native swiping between zoomable images; see `ImagePagerViewProps`. */
export function ImagePager({ swipeEnabled = true, onIndexChange, onError, onLoad, ...props }: ImagePagerProps) {
  // A stable wrapper calling the latest handler, so a new handler each render does not reach native.
  const handlers = useRef({ onIndexChange, onError, onLoad })
  useLayoutEffect(() => {
    handlers.current = { onIndexChange, onError, onLoad }
  })
  const native = useMemo(
    () => ({
      onIndexChange: callback((index: number) => handlers.current.onIndexChange?.(index)),
      onLoad: callback((loadedUri: string) => handlers.current.onLoad?.(loadedUri)),
      onError: callback((failure: ScannerFailure) => handlers.current.onError?.(new ScannerError(failure.code))),
    }),
    [],
  )

  // Always set, since React sends a removed prop as null, which the native optional cannot take.
  return (
    <NativeImagePager
      {...props}
      swipeEnabled={swipeEnabled}
      onIndexChange={native.onIndexChange}
      onError={native.onError}
      onLoad={native.onLoad}
    />
  )
}
