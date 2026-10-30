import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules'
import type { DocumentFilter, DocumentQuad } from './DocumentScannerProcessor.nitro'
import type { ScannerFailure } from './ScannerFailure'

/**
 * Native crop editing over an upright, contain-fitted photo. Corners move alone; an edge moves both of its corners
 * by the same displacement, shortened at the photo's bounds. Moves are clamped to the photo, and moves that would
 * make the quad crossed, concave or collapsed are ignored. Dragging a corner shows a magnifier beside the finger. Two
 * fingers placed away from the handles pinch and pan the photo up to 5x, and a double tap fits it again; handles keep
 * their size on screen, and the quad stays in photo coordinates.
 */
export interface DocumentCropEditorProps extends HybridViewProps {
  /** Local file:// JPEG to edit; its EXIF orientation is applied. */
  originalUri: string
  /**
   * Crop in normalized upright photo coordinates. Setting a different quad replaces the crop, and the editor keeps its
   * own edits until then, so reporting an edit back through this prop is not required.
   */
  quad: DocumentQuad
  /**
   * Previews the photo under a render filter; defaults to `none`. The whole photo is filtered, so the paper estimate
   * also sees what lies outside the crop and the preview can differ slightly from the rendered page. The preview is
   * filtered at most 2400 pixels on its long side, a default render's size, off the main thread, the editor showing its
   * current image until it is done; a newer filter or photo discards an older one's result.
   */
  filter?: DocumentFilter
  /** Called with the edited crop when a drag that changed it ends. */
  onQuadChange?: (quad: DocumentQuad) => void
  /** Called with the upright photo's decoded size, in pixels, each time a new `originalUri` is shown. */
  onLoad?: (width: number, height: number) => void
  /**
   * Called when the photo cannot be loaded, leaving the editor empty, or a filter preview cannot be prepared, leaving
   * the current image.
   */
  onError?: (failure: ScannerFailure) => void
}

export interface DocumentCropEditorMethods extends HybridViewMethods {}

export type DocumentCropEditorView = HybridView<
  DocumentCropEditorProps,
  DocumentCropEditorMethods,
  { android: 'kotlin' }
>
