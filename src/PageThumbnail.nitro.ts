import type { HybridView, HybridViewMethods, HybridViewProps } from 'react-native-nitro-modules'
import type { ScanSession } from './ScanSession.nitro'

export type PageThumbnailResizeMode = 'cover' | 'contain'

/** Image-only tile. The host owns arrangement, selection, gestures, badges and labels. */
export interface PageThumbnailViewProps extends HybridViewProps {
  session: ScanSession
  pageId: string
  /** Fill and crop, or fit entirely within the tile; defaults to cover. */
  resizeMode?: PageThumbnailResizeMode
}

export interface PageThumbnailViewMethods extends HybridViewMethods {}

export type PageThumbnailView = HybridView<
  PageThumbnailViewProps,
  PageThumbnailViewMethods,
  { ios: 'swift'; android: 'kotlin' }
>
