import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules'
import type { ScannerFailure } from './ScannerFailure'

/**
 * Native image viewing: an upright image contain-fitted to the view. Two fingers pinch and pan it up to 5x, one finger
 * pans a zoomed image, and a double tap zooms to 2.5x around the tapped point or back to fit. The pan stops at the
 * image's edges. Gestures stay on the main thread and never cross into JavaScript.
 *
 * In a pager, host one per page: a zoomed image keeps one-finger drags from a natively scrolling parent, and
 * `onZoomChange` lets the pager also disable swiping while zoomed. The view holds an image only while attached to a
 * window: a page the pager moves out of its window (react-native-pager-view's `offscreenPageLimit`, a Compose pager's
 * window, a recycling list) lets go of its images and fits again, and shows its thumbnail again once attached.
 * Thumbnails are shared by uri across views, so that is a memory hit unless the cache dropped it. A failed load is
 * retried when the view becomes visible again.
 */
export interface ImageViewerViewProps extends HybridViewProps {
  /**
   * Local file:// JPEG or PNG to show; its EXIF orientation is applied. It shows as a thumbnail at the live frame's
   * size; zooming past 1.1x loads the full image, up to 4096 pixels on its long side, which replaces it in place while
   * still zoomed. A different uri fades the new thumbnail in at the fit over the previous image; the previous image remains visible while
   * loading.
   */
  uri: string
  /** Called when the image cannot be loaded; any previously displayed image remains visible. */
  onError?: (failure: ScannerFailure) => void
  /** The current URI's thumbnail has been installed in the native view. */
  onLoad?: (uri: string) => void
  /** The image was zoomed past the fit, or returned to it. */
  onZoomChange?: (zoomed: boolean) => void
}

export interface ImageViewerViewMethods extends HybridViewMethods {
  /** Whether the image is zoomed past the fit, as last reported by `onZoomChange`. */
  isZoomed(): boolean
  /** Returns the image to the fit, as for a page the pager has left. */
  resetZoom(): void
}

export type ImageViewerView = HybridView<
  ImageViewerViewProps,
  ImageViewerViewMethods,
  { android: 'kotlin' }
>
