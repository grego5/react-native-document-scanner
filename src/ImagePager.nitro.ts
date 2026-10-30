import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules'
import type { ScannerFailure } from './ScannerFailure'

/**
 * Native horizontal paging over images, one page per view width, each zoomable as `ImageViewer`. Paging is blocked
 * while the shown page is zoomed, so its drags pan it; at the fit they swipe between pages. A page leaving the view,
 * by a swipe or an `index` change, fits again. Pages show their thumbnails as `ImageViewer` does, and only the zoomed page
 * decodes its full image. Gestures stay on the main thread and never cross into JavaScript.
 */
export interface ImagePagerViewProps extends HybridViewProps {
  /** Local file:// JPEG or PNG pages, loaded as `ImageViewer` loads its `uri`. A changed uri keeps the page's previous image until the new one fades in. */
  uris: string[]
  /** The page to show, clamped to the pages; a changed index jumps there without animation. */
  index: number
  /** Whether a swipe may move to another page; default true. */
  swipeEnabled?: boolean
  /** A swipe settled on another page. The pager shows it even if `index` is not updated. */
  onIndexChange?: (index: number) => void
  /** A page's thumbnail is shown. */
  onLoad?: (uri: string) => void
  /** The shown page could not be loaded; its previous image, if any, remains visible. A neighbour that failed loads again, and reports, once shown. */
  onError?: (failure: ScannerFailure) => void
}

export interface ImagePagerViewMethods extends HybridViewMethods {}

export type ImagePagerView = HybridView<
  ImagePagerViewProps,
  ImagePagerViewMethods,
  { android: 'kotlin' }
>
