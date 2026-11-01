import type { HybridObject } from 'react-native-nitro-modules'
import type { ScannerFailure } from './ScannerFailure'
import type { ScanSession } from './ScanSession.nitro'

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

export interface DocumentCandidate {
  quad: DocumentQuad
  /** Ranking evidence, not a probability. */
  score: number
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
export type DocumentImageFormat = 'jpeg' | 'png' | 'auto'

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
  /** Defaults to `jpeg`. `auto` uses PNG for blackWhite and JPEG otherwise. PNG quantizes to 16 gray levels without dithering and compresses them losslessly. */
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
  /** Creates an explicitly owned session; close it when its pages are no longer needed. */
  createSessionResult(): ScanSession | ScannerFailure
  /**
   * Opens the system folder picker for sample collection, such as a folder in Downloads or Documents, and resolves with
   * the folder now in use. Nothing is written until `saveCaptureSample` is called. The library keeps the grant across restarts and releases the previous folder's;
   * cancelling keeps the current folder. Picking a folder turns collection on. Needs a foreground Activity.
   */
  pickSampleFolderResult(): Promise<SampleFolder | ScannerFailure>
  /** The picked folder while its grant holds; a grant revoked in settings counts as none. */
  getSampleFolder(): SampleFolder
  /** Releases the picked folder and turns sample collection off. */
  clearSampleFolder(): void
  /**
   * Turns sample collection on or off while keeping the picked folder, across restarts. On without a folder does
   * nothing.
   */
  setSampleCollection(on: boolean): void
  /** Set a private file directory or persisted SAF tree; preserves collection enabled state. */
  setSampleFolder(uri: string): void
}
