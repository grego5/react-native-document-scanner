import type { HybridObject } from 'react-native-nitro-modules'
import type { ScannerFailure } from './ScannerFailure'
import type { ScanSession } from './ScanSession.nitro'

export interface DocumentPoint {
  x: number
  y: number
}

export interface DocumentCorners {
  topLeft: DocumentPoint
  topRight: DocumentPoint
  bottomRight: DocumentPoint
  bottomLeft: DocumentPoint
}

export interface DocumentCandidate {
  corners: DocumentCorners
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

/** `enhance` retains color, `grayscale` removes color, and `blackWhite` produces high-contrast grayscale. */
export type DocumentFilter = 'none' | 'enhance' | 'grayscale' | 'blackWhite'

/** `png` is 4-bit grayscale (16 levels), allowed with `grayscale` or `blackWhite`; `jpeg` keeps full tones and colour. */
export type DocumentImageFormat = 'jpeg' | 'png' | 'auto'

/** A sheet's size in centimetres; which side is longer does not matter. A4 is 21 x 29.7. */
export interface PaperSize {
  width: number
  height: number
}

/** The document corners map exactly to the output corners; no margin or canvas is added. */
export interface DocumentRenderOptions {
  /** Brighten shaded paper before filtering; defaults to true. */
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
   * Follow curved page edges while retaining the submitted corners; defaults to true. An undetected edge stays straight,
   * and correction is skipped when a corner lies outside the image.
   */
  simpleDewarp?: boolean
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

/** Native result transport; the public DocumentScanner facade unwraps values and rejects typed failures.
 * Originals stay native/session-private. Android sources use its existing JPEG loader;
 * the iOS source layer accepts TIFF/JPEG/PNG with EXIF applied before pixel consumers. */
export interface DocumentScannerProcessor
  extends HybridObject<{ ios: 'swift'; android: 'kotlin' }> {
  /**
   * Creates an explicitly owned session; close it when its pages are no longer needed. The first session starts
   * loading the Expo plugin's model unless a source was already requested.
   */
  createSessionResult(): ScanSession | ScannerFailure
  /**
   * Waits for the app-wide corner model's current request, starting the Expo plugin's model first if nothing was
   * requested. `true` once it is active, `false` without any model; a failed or superseded request rejects.
   */
  waitForCornerDetectionModelResult(): Promise<boolean | ScannerFailure>
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
