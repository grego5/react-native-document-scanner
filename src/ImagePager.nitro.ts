import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules'
import type { ScanSession } from './ScanSession.nitro'
import type { ScannerFailure } from './ScannerFailure'

export type ImagePagerDirection = 'auto' | 'ltr' | 'rtl'

/**
 * Native horizontal paging over images, one page per view width, each zoomable as `ImageViewer`. Paging is blocked
 * while the shown page is zoomed, so its drags pan it; at the fit they swipe between pages. A page leaving the view,
 * by a swipe or an `selectedPageId` change, fits again. Pages show their thumbnails as `ImageViewer` does, and only the zoomed page
 * decodes its full image. Gestures stay on the main thread and never cross into JavaScript.
 */
export interface ImagePagerViewProps extends HybridViewProps {
  /** Native session pages in logical input order. New renders retain the previous image until replacement decodes. */
  session: ScanSession
  /** Optional complete order to apply to the session. Omit to follow its stored order. */
  pageIds?: string[]
  /** Inherit Android direction, or override it for this pager. */
  direction?: ImagePagerDirection
  /** Selected logical page ID; empty selects no page. */
  selectedPageId: string
  /** Whether a swipe may move to another page; default true. */
  swipeEnabled?: boolean
  /** A swipe settled on another page ID. The pager shows it even if `selectedPageId` is not updated. */
  onPageChange?: (pageId: string) => void
  /** A page's thumbnail is shown. */
  onLoad?: (pageId: string) => void
  /** The shown page could not be loaded; its previous image, if any, remains visible. A neighbour that failed loads again, and reports, once shown. */
  onError?: (failure: ScannerFailure) => void
}

export interface ImagePagerViewMethods extends HybridViewMethods {}

export type ImagePagerView = HybridView<
  ImagePagerViewProps,
  ImagePagerViewMethods,
  { android: 'kotlin' }
>
