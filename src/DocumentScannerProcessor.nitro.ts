import type { HybridObject } from 'react-native-nitro-modules'
import type { ScannerFailure } from './ScannerFailure'

export type DocumentPointProvenance = 'observed' | 'inferred' | 'predicted'
export type DocumentPageSide = 'top' | 'right' | 'bottom' | 'left'

export interface DocumentPoint {
  x: number
  y: number
}

export interface DocumentQuad {
  topLeft: DocumentPoint
  topRight: DocumentPoint
  bottomRight: DocumentPoint
  bottomLeft: DocumentPoint
}

export interface DocumentCornerProvenance {
  topLeft: DocumentPointProvenance
  topRight: DocumentPointProvenance
  bottomRight: DocumentPointProvenance
  bottomLeft: DocumentPointProvenance
}

export interface DocumentSideProvenance {
  top: DocumentPointProvenance
  right: DocumentPointProvenance
  bottom: DocumentPointProvenance
  left: DocumentPointProvenance
}

export interface DocumentA4PageEstimateRange {
  missingSide: DocumentPageSide
  /** The original model geometry is the near bound; neither bound is a confidence interval. */
  modelQuad: DocumentQuad
  /** An A4 aspect constrained review estimate for the missing edge. */
  a4Quad: DocumentQuad
}

export interface DocumentCandidate {
  quad: DocumentQuad
  /** Ranking evidence, not a probability. */
  score: number
  corners: DocumentCornerProvenance
  sides: DocumentSideProvenance
  a4PageRange?: DocumentA4PageEstimateRange
}

export interface DocumentAnalysis {
  /** Dimensions after applying source EXIF orientation. */
  sourceWidth: number
  sourceHeight: number
  /** Analysis duration in milliseconds, including decoding. */
  duration: number
  candidate?: DocumentCandidate
}

/**
 * The remaining filters except `none` first even out the paper's lighting: they white-balance the
 * page to its paper and divide it by an estimate of the bare paper around each pixel, so shadows, uneven light and
 * colour casts drop out and the paper turns white; that flat white is also what makes the file small. The estimate
 * comes only from neutral areas near the brightest on the page, so coloured areas and photos darker than about 60% of
 * the paper keep their tones, and one estimate serves all channels, so colours keep their ratios. Light gray areas
 * count as paper and whiten. `enhance` keeps colour, for stamps, highlighter and signatures;
 * `grayscale` drops it; both are then sharpened and their mid-tones darkened so faint strokes read. `blackWhite`
 * makes a high-contrast grayscale page while preserving gray pixels around faint and thin text strokes.
 *
 * All three then even out the ink across the page: thin ink that glare or distance left faded is deepened toward the
 * page's darkest ink (at most 2.5 times its contrast), and softer areas are sharpened more. Broad mid-tone areas, such
 * as photos, are measured apart from thin ink and keep their tones.
 */
export type DocumentFilter = 'none' | 'enhance' | 'grayscale' | 'blackWhite'

/** `png` is 4-bit grayscale (16 levels), allowed with `grayscale` or `blackWhite`; `jpeg` keeps full tones and colour. */
export type DocumentImageFormat = 'jpeg' | 'png'

/** A sheet's size in centimetres; which side is longer does not matter. A4 is 21 x 29.7. */
export interface PaperSize {
  width: number
  height: number
}

/** The quad's corners map exactly to the output corners; no margin or canvas is added. */
export interface DocumentRenderOptions {
  /**
   * Lifts shaded paper toward the page's bright paper before the filter, at most twice its brightness. Defaults to
   * true. The paper's brightness is estimated with ink removed, and one gain serves all channels, so colours keep their
   * ratios; a shadow lit by differently coloured light keeps a slight tint without a filter. Areas darker than paper
   * and wider than a text line, such as fingers or solid dark boxes, are lifted as well.
   */
  removeShadows?: boolean
  /** JPEG quality from 0 through 1; defaults to 0.92. Ignored for `png`. */
  jpegQuality?: number
  /** Defaults to `none`. */
  filter?: DocumentFilter
  /** Defaults to `jpeg`. PNG quantizes to 16 gray levels without dithering and compresses them losslessly. */
  format?: DocumentImageFormat
  /**
   * The paper the page is printed on, from 1 to 200 cm a side. With it, the page's longer side is drawn as the paper's
   * longer side at `dpi`; the outline keeps its own proportions, so the page is scaled, not stretched to the paper. The
   * output is never enlarged past the page's own pixels in the photo, and the paper at `dpi` may be at most 4096 px on
   * its longer side. Without it, the output keeps the page's own size in the photo, up to 4096 px.
   */
  paperSize?: PaperSize
  /** Dots per inch on `paperSize`, from 50 to 600; defaults to 200. Requires `paperSize`. */
  dpi?: number
  /**
   * Bends each side onto the page's own edge where the page curves between the quad's corners, as a page held in a
   * hand or fresh from a printer does, so no background shows along it. Defaults to true. The corners stay where the
   * quad puts them, and a side whose edge is not found clearly stays straight. Skipped when a corner lies outside the
   * image. Set false to keep exactly the straight quad.
   */
  followPageEdges?: boolean
}

/**
 * `none` centres the image at one point per pixel, possibly past the page; `fill` stretches it over the page;
 * `contain` fits the whole image inside; `cover` fills the page, clipping what overflows.
 */
export type ImageFit = 'none' | 'fill' | 'contain' | 'cover'

/** The options of `react-native-images-to-pdf`, with the same meanings. */
export interface CreatePdfOptions {
  /** Absolute path or file:// URI; created or replaced. Its folder must exist. */
  outputPath: string
  /** Absolute paths or file:// URIs of the images, one page each, in order. */
  pages: string[]
  /** Defaults to `none`; without a page size every page is its image's size, and fitting changes nothing. */
  imageFit?: ImageFit
  /** Whether each image stands upright by its EXIF orientation. Defaults to true. */
  autoRotateExif?: boolean
  /**
   * JPEG quality from 0 to 1 for images that are re-encoded; defaults to 0.72. Setting it re-encodes every JPEG.
   * It does not affect 4-bit grayscale PNGs, which stay 4-bit inside the PDF.
   */
  jpegQuality?: number
  /**
   * Maximum resolution of a placed image in pixels per inch, never enlarging it; defaults to 200 with a page size and
   * is not allowed without one. Larger images are downscaled; 4-bit grayscale PNGs remain 4-bit, other images use JPEG.
   */
  targetDpi?: number
  /** Page width in points, 1/72 inch each; with `pageHeight` or `pageAspectRatio`. */
  pageWidth?: number
  /** Page height in points; with `pageWidth` or `pageAspectRatio`. */
  pageHeight?: number
  /** Page width over height, with exactly one of `pageWidth` and `pageHeight`. */
  pageAspectRatio?: number
}

export interface RenderedDocument {
  originalUri: string
  outputUri: string
  /**
   * A JPEG of the finished page reduced to the camera's live analysis frame size (640 px on the longer side unless
   * `analysisWidth` sets another), never enlarged; a cached file like `outputUri`. A `ImageViewer` already holds it for
   * `outputUri` when the render resolves, so the page and its thumbnail change together.
   */
  thumbnailUri: string
  width: number
  height: number
  /** Upright output coordinates enclosing captured pixels; uncaptured canvas stays outside this polygon. */
  validContentPolygon: DocumentPoint[]
}

/**
 * The folder collected samples are saved into; `uri` and `name` are absent while none is picked, which turns sample
 * collection off.
 */
export interface SampleFolder {
  /** Private file directory URI or picked Storage Access Framework tree URI. */
  uri?: string
  /** Its display name. */
  name?: string
  /** Samples are being collected: a folder is picked and collection is not stopped. */
  collecting: boolean
}

/** Native result transport; the public DocumentScanner facade unwraps values and rejects typed failures. */
export interface DocumentScannerProcessor
  extends HybridObject<{ android: 'kotlin' }> {
  /**
   * Opens the system folder picker for sample collection, such as a folder in Downloads or Documents, and resolves with
   * the folder now in use. While collection is on, the Camera keeps its latest live inference frame and each photo's last
   * frame before the shutter in memory, so `saveLiveSample` and `saveCaptureSample` can save them; nothing is written
   * until one of them is called. The library keeps the grant across restarts and releases the previous folder's;
   * cancelling keeps the current folder. Picking a folder turns collection on. Needs a foreground Activity.
   */
  pickSampleFolderResult(): Promise<SampleFolder | ScannerFailure>
  /** The picked folder while its grant holds; a grant revoked in settings counts as none. */
  getSampleFolder(): SampleFolder
  /** Releases the picked folder and turns sample collection off. */
  clearSampleFolder(): void
  /**
   * Turns sample collection on or off while keeping the picked folder, across restarts. Off drops the frames kept for
   * samples, including photos not saved yet. On without a folder does nothing.
   */
  setSampleCollection(on: boolean): void
  /** Set a private file directory or persisted SAF tree; preserves collection enabled state. */
  setSampleFolder(uri: string): void
  /**
   * Saves a photo the Camera took as a sample: `<ms>-<long edge>.jpg`, an unchanged copy of the photo, beside
   * `<ms>-<long edge>.json` with `quad`, the outline the user accepted, as its label and `analyzePhoto`'s result on it as
   * the prediction, and the photo's last live inference frame before the shutter as `<ms>-<long edge>.png` and `.json`,
   * with the live prediction. Both share the shutter time `<ms>`, and the long edge is that image's upright longer side.
   * Each JSON is one entry of the `tools/label-samples` format, so the folder opens there directly. Without `quad`, the
   * photo is saved unlabeled. Fails with `input/invalid` while sample collection is off, and saves only the photo for one the
   * Camera did not take while a folder was picked, or one taken too long ago (the last 8 are kept). Resolves with the
   * photo's file name.
   */
  saveCaptureSampleResult(originalUri: string, quad?: DocumentQuad): Promise<string | ScannerFailure>
  /** Optional normalized upright live quad selects a region with 10% margin per side for photo inference. */
  analyzePhotoResult(originalUri: string, liveQuad?: DocumentQuad): Promise<DocumentAnalysis | ScannerFailure>
  renderDocumentResult(
    originalUri: string,
    quad: DocumentQuad,
    options?: DocumentRenderOptions,
  ): Promise<RenderedDocument | ScannerFailure>
  /**
   * Writes the pages into a PDF at `outputPath` and resolves with its file:// URI. A JPEG that needs no scaling is
   * copied in unchanged when `jpegQuality` is unset. Non-interlaced 4-bit grayscale PNGs embed their compressed data
   * unchanged when no resizing is needed, and remain 4-bit when downscaled. Other images are decoded, scaled to
   * `targetDpi`, flattened on white and encoded as JPEG.
   */
  createPdfResult(options: CreatePdfOptions): Promise<string | ScannerFailure>
  /**
   * Deletes photos and renders this library wrote to its own cache folder. Every URI is checked before anything is
   * deleted, so one foreign path, directory or symbolic link fails the whole call; files already gone are skipped.
   * Native success is true; the public facade resolves void. Failure is a ScannerFailure payload.
   */
  removeCachedImagesResult(uris: string[]): Promise<boolean | ScannerFailure>
}
