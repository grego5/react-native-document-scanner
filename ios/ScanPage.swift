import Foundation

/// Normalized upright TL/TR/BR/BL x,y pairs; comparable without C++ struct equality.
typealias QuadCoordinates = [Double]

enum SessionQuad {
  static let fullFrame: QuadCoordinates = [0, 0, 1, 0, 1, 1, 0, 1]

  static func coordinates(_ quad: DocumentQuad) -> QuadCoordinates {
    [quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft].flatMap { [$0.x, $0.y] }
  }

  static func quad(_ value: QuadCoordinates) -> DocumentQuad {
    DocumentQuad(topLeft: DocumentPoint(x: value[0], y: value[1]), topRight: DocumentPoint(x: value[2], y: value[3]),
                 bottomRight: DocumentPoint(x: value[4], y: value[5]), bottomLeft: DocumentPoint(x: value[6], y: value[7]))
  }

  static func renderable(_ value: QuadCoordinates) -> Bool {
    value.count == 8 && (try? DocumentRenderService.validate(quad(value))) != nil
  }
}

/// Effective render options frozen at admission; omitted defaults equal explicit ones.
struct RenderSettings: Equatable {
  let removeShadows: Bool
  let quality: Int
  let filter: DocumentFilter
  let format: DocumentImageFormat
  let paperWidth: Double?
  let paperHeight: Double?
  let dpi: Double?
  let followPageEdges: Bool

  init(_ options: DocumentRenderOptions?) throws {
    let effective = try DocumentRenderService.effectiveOptions(options)
    removeShadows = effective.removeShadows ?? true
    quality = Int(((effective.jpegQuality ?? 0.92) * 100).rounded())
    filter = effective.filter ?? DocumentFilter.none
    format = effective.format ?? .jpeg
    paperWidth = effective.paperSize?.width
    paperHeight = effective.paperSize?.height
    dpi = effective.dpi
    followPageEdges = effective.followPageEdges ?? true
  }

  var options: DocumentRenderOptions {
    DocumentRenderOptions(removeShadows: removeShadows, jpegQuality: Double(quality) / 100, filter: filter, format: format,
      paperSize: paperWidth.flatMap { width in paperHeight.map { PaperSize(width: width, height: $0) } },
      dpi: dpi, followPageEdges: followPageEdges)
  }

  func with(filter: DocumentFilter? = nil, removeShadows: Bool? = nil) throws -> RenderSettings {
    let current = options
    return try RenderSettings(DocumentRenderOptions(removeShadows: removeShadows ?? self.removeShadows,
      jpegQuality: current.jpegQuality, filter: filter ?? self.filter, format: format,
      paperSize: current.paperSize, dpi: dpi, followPageEdges: followPageEdges))
  }
}

struct AnalysisValue: Equatable {
  let width: Int
  let height: Int
  let duration: Double
  let candidate: QuadCoordinates?
  let score: Double

  var nitro: DocumentAnalysis {
    DocumentAnalysis(sourceWidth: Double(width), sourceHeight: Double(height), duration: duration,
      candidate: candidate.map { DocumentCandidate(quad: SessionQuad.quad($0), score: score) })
  }
}

/// Transport metadata only: no paths or artifact objects.
struct PageMetadata: Equatable {
  let id: String
  let revision: Int
  let readyRevision: Int?
  let analyzing: Bool
  let rendering: Bool
  let analysis: AnalysisValue?
  let quad: QuadCoordinates
  let settings: RenderSettings
  let analysisFailure: ScannerErrorCode?
  let renderFailure: ScannerErrorCode?

  var nitro: ScanPageMetadata {
    ScanPageMetadata(id: id, revision: Double(revision), readyRevision: readyRevision.map(Double.init),
      analyzing: analyzing, rendering: rendering, ready: readyRevision == revision, analysis: analysis?.nitro,
      quad: SessionQuad.quad(quad), options: settings.options,
      analysisFailure: analysisFailure.map { ScannerFailure(code: $0) },
      renderFailure: renderFailure.map { ScannerFailure(code: $0) })
  }
}

/// Mutable only under ScanSessionState's lock. Originals are immutable; every render
/// derives from the original.
final class ScanPage {
  struct RenderKey: Equatable {
    let quad: QuadCoordinates
    let settings: RenderSettings
  }

  final class Artifact {
    let image: SessionResources.Resource
    let thumbnail: SessionResources.Resource
    let width: Int
    let height: Int
    let validContentPolygon: [DocumentPoint]
    init(image: SessionResources.Resource, thumbnail: SessionResources.Resource, width: Int, height: Int,
         validContentPolygon: [DocumentPoint]) {
      self.image = image; self.thumbnail = thumbnail; self.width = width; self.height = height
      self.validContentPolygon = validContentPolygon
    }
  }

  let id: String
  let original: SessionResources.Resource
  let frame: SessionResources.Resource?
  let selection: CornerInference.Selection?
  let liveQuad: QuadCoordinates?
  var settings: RenderSettings
  var quad = SessionQuad.fullFrame
  var explicitGeometry = false
  var requestedRevision = 1
  var readyRevision: Int?
  var exportAnalysisPins = 0
  var analysisResult: Result<AnalysisValue, Error>?
  var analyzing = true
  var analysis: AnalysisValue?
  var analysisFailure: ScannerErrorCode?
  var renderFailure: ScannerErrorCode?
  var cache: [(key: RenderKey, artifact: Artifact)] = []
  var displayed: Artifact?

  init(id: String, original: SessionResources.Resource, frame: SessionResources.Resource?, settings: RenderSettings,
       selection: CornerInference.Selection?, liveQuad: QuadCoordinates?) {
    self.id = id; self.original = original; self.frame = frame; self.settings = settings
    self.selection = selection; self.liveQuad = liveQuad
  }

  var key: RenderKey { RenderKey(quad: quad, settings: settings) }

  func cached(_ key: RenderKey) -> Artifact? { cache.first { $0.key == key }?.artifact }

  var metadata: PageMetadata {
    PageMetadata(id: id, revision: requestedRevision, readyRevision: readyRevision, analyzing: analyzing,
      rendering: !analyzing && readyRevision != requestedRevision && renderFailure == nil,
      analysis: analysis, quad: quad, settings: settings, analysisFailure: analysisFailure, renderFailure: renderFailure)
  }
}
