import type {
  HybridView,
  HybridViewMethods,
  HybridViewProps,
} from 'react-native-nitro-modules'
import type { DocumentFilter, DocumentQuad } from './DocumentScannerProcessor.nitro'
import type { ScanSession } from './ScanSession.nitro'
import type { ScannerFailure } from './ScannerFailure'

/**
 * Native crop editing over an upright, contain-fitted photo. Corners move alone; an edge moves both of its corners
 * by the same displacement, shortened at the photo's bounds. Moves are clamped to the photo, and moves that would
 * make the quad crossed, concave or collapsed are ignored. Dragging a corner shows a magnifier beside the finger. Two
 * fingers placed away from the handles pinch and pan the photo up to 5x, and a double tap fits it again; handles keep
 * their size on screen, and the quad stays in photo coordinates.
 */
export interface DocumentCropEditorProps extends HybridViewProps {
  /** Native session page whose immutable original is edited; EXIF orientation is applied.
   * Native source formats: Android JPEG; iOS TIFF/JPEG/PNG. Source paths remain session-private. */
  session: ScanSession
  pageId: string
  /**
   * Previews the photo under a render filter; defaults to `none`. The whole photo is filtered, so the paper estimate
   * also sees what lies outside the crop and the preview can differ slightly from the rendered page. The preview is
   * filtered at most 2400 pixels on its long side, a default render's size, off the main thread, the editor showing its
   * current image until it is done; a newer filter or photo discards an older one's result.
   */
  filter?: DocumentFilter
  /**
   * Keeps the crop an upright rectangle, as for an ordinary photo: only corners move, the opposite corner fixed and
   * the other two following; there are no edge handles and no zoom; `auto` takes the candidate's bounding rectangle,
   * and a non-rectangular crop is squared to its bounding rectangle when shown. Defaults to false.
   */
  rectangle?: boolean
  /** Initial draft classification and changes after presets or corner drags. Full frame takes precedence over auto. */
  onPresetChange?: (preset: CropDraftPreset) => void
  /** Called with the upright photo's decoded size, in pixels, each time a new page is shown. */
  onLoad?: (width: number, height: number) => void
  /**
   * Called when the photo cannot be loaded, leaving the editor empty, or a filter preview cannot be prepared, leaving
   * the current image.
   */
  onError?: (failure: ScannerFailure) => void
}

export type CropPreset = 'none' | 'auto'
export type CropDraftPreset = CropPreset | 'custom'

export interface DocumentCropEditorMethods extends HybridViewMethods {
  /** Validate and apply the native draft to the bound session page. */
  submitResult(): Promise<boolean | ScannerFailure>
  /** Read the native draft without committing it. Rejects after page removal or view disposal. */
  getQuadResult(): Promise<DocumentQuad | ScannerFailure>
  /** Replace only the editor draft; auto requires an available analysis candidate. */
  setPresetResult(preset: CropPreset): Promise<boolean | ScannerFailure>
}

export type DocumentCropEditorView = HybridView<
  DocumentCropEditorProps,
  DocumentCropEditorMethods,
  { ios: 'swift'; android: 'kotlin' }
>
