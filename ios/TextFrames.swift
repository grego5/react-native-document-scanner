import CoreGraphics
import CoreVideo
import Foundation
import ImageIO
import UniformTypeIdentifiers
import Vision

/// An upright greyscale crop of the region: `width` by `height` luma bytes, row by row. Reading text needs no colour,
/// so the text camera keeps the frame's luma plane alone.
struct LumaCrop {
  let width: Int
  let height: Int
  let bytes: [UInt8]
}

/// A kept crop of the region: `region` of the view, exposed at `time` seconds on the frame clock, and how sharp it is
/// against other frames of the same view.
struct KeptTextFrame {
  let id: Int
  let time: Double
  let sharpness: Double
  let region: TextRegion
  let crop: LumaCrop
}

/// The sharpest frames of the last `keep` seconds, at most `size`, which a capture reads from. Age is measured
/// against the newest frame seen, so frames kept before the camera stopped stay for its fallback.
struct TextFrameRing {
  static let keep = 1.0
  static let size = 4
  private(set) var frames: [KeptTextFrame] = []
  private var newest = -Double.infinity

  /// Whether a frame at `time` scoring `sharpness` would be kept.
  mutating func wants(time: Double, sharpness: Double) -> Bool {
    prune(time)
    return frames.count < Self.size || sharpness > (frames.map(\.sharpness).min() ?? 0)
  }

  mutating func add(_ frame: KeptTextFrame) {
    prune(frame.time)
    frames.append(frame)
    if frames.count > Self.size, let weakest = frames.indices.min(by: { frames[$0].sharpness < frames[$1].sharpness }) {
      frames.remove(at: weakest)
    }
  }

  func sharpest() -> KeptTextFrame? { frames.max { $0.sharpness < $1.sharpness } }

  mutating func clear() {
    frames = []
    newest = -Double.infinity
  }

  private mutating func prune(_ time: Double) {
    newest = max(newest, time)
    frames.removeAll { $0.time < newest - Self.keep }
  }
}

/// The pixel rectangle of `unit`, normalized, in a `width` by `height` luma plane, or nil when too small to read.
func textRegionPixels(_ unit: CGRect, width: Int, height: Int) -> CGRect? {
  // Both ends rounded and clamped, so the size never reaches past the plane.
  func edge(_ value: CGFloat, _ size: Int) -> CGFloat { min(max((value * CGFloat(size)).rounded(), 0), CGFloat(size)) }
  let left = edge(unit.minX, width)
  let top = edge(unit.minY, height)
  let rect = CGRect(x: left, y: top, width: edge(unit.maxX, width) - left, height: edge(unit.maxY, height) - top)
  return rect.width >= 16 && rect.height >= 16 ? rect : nil
}

/// A frame's luma plane, borrowed while `body` runs.
func withLumaPlane<T>(_ buffer: CVPixelBuffer, _ body: (UnsafePointer<UInt8>, _ rowBytes: Int, _ width: Int, _ height: Int) -> T?) -> T? {
  guard CVPixelBufferGetPlaneCount(buffer) >= 1 else { return nil }
  CVPixelBufferLockBaseAddress(buffer, .readOnly)
  defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
  guard let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0) else { return nil }
  return body(base.assumingMemoryBound(to: UInt8.self), CVPixelBufferGetBytesPerRowOfPlane(buffer, 0),
              CVPixelBufferGetWidthOfPlane(buffer, 0), CVPixelBufferGetHeightOfPlane(buffer, 0))
}

/// How sharp `rect` of a luma plane is: the mean squared luma gradient over a grid of about 1,500 points. Blur and
/// shake lose the edges print has, so the same view scores lower; the score means nothing across views, so frames are
/// ranked only against others of one region.
func lumaSharpness(_ plane: UnsafePointer<UInt8>, rowBytes: Int, rect: CGRect) -> Double {
  let left = Int(rect.minX) + 1
  let top = Int(rect.minY) + 1
  let width = Int(rect.width) - 2
  let height = Int(rect.height) - 2
  guard width >= 4, height >= 4 else { return 0 }
  let samples = 1_500.0
  let columns = min(max(Int((samples * Double(width) / Double(height)).squareRoot().rounded()), 2), width)
  let rows = min(max(Int(samples) / columns, 2), height)
  var energy = 0.0
  for j in 0..<rows {
    let y = top + Int((Double(j) + 0.5) * Double(height) / Double(rows))
    for i in 0..<columns {
      let x = left + Int((Double(i) + 0.5) * Double(width) / Double(columns))
      let gx = Double(plane[y * rowBytes + x + 1]) - Double(plane[y * rowBytes + x - 1])
      let gy = Double(plane[(y + 1) * rowBytes + x]) - Double(plane[(y - 1) * rowBytes + x])
      energy += gx * gx + gy * gy
    }
  }
  return energy / Double(rows * columns)
}

/// `rect` of a luma plane, copied row by row.
func lumaCrop(_ plane: UnsafePointer<UInt8>, rowBytes: Int, rect: CGRect) -> LumaCrop {
  let left = Int(rect.minX)
  let top = Int(rect.minY)
  let width = Int(rect.width)
  let height = Int(rect.height)
  var bytes = [UInt8](repeating: 0, count: width * height)
  bytes.withUnsafeMutableBufferPointer { out in
    for row in 0..<height {
      (out.baseAddress! + row * width).update(from: plane + (top + row) * rowBytes + left, count: width)
    }
  }
  return LumaCrop(width: width, height: height, bytes: bytes)
}

/// `crop` turned `clockwise` by a multiple of 90 degrees, as the preview turns the sensor's image upright.
func turnedLuma(_ crop: LumaCrop, clockwise degrees: Int) -> LumaCrop {
  let turn = ((degrees % 360) + 360) % 360 / 90
  guard turn != 0 else { return crop }
  let (w, h) = (crop.width, crop.height)
  let sideways = turn % 2 == 1
  let outWidth = sideways ? h : w
  let outHeight = sideways ? w : h
  var out = [UInt8](repeating: 0, count: crop.bytes.count)
  for y in 0..<h {
    for x in 0..<w {
      let (tx, ty): (Int, Int) = switch turn {
      case 1: (h - 1 - y, x)
      case 2: (w - 1 - x, h - 1 - y)
      default: (y, w - 1 - x)
      }
      out[ty * outWidth + tx] = crop.bytes[y * w + x]
    }
  }
  return LumaCrop(width: outWidth, height: outHeight, bytes: out)
}

/// `crop` as a greyscale image, which Vision reads and the JPEG encoder takes.
func greyImage(_ crop: LumaCrop) -> CGImage? {
  guard let provider = CGDataProvider(data: Data(crop.bytes) as CFData) else { return nil }
  return CGImage(width: crop.width, height: crop.height, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: crop.width,
                 space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                 provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
}

/// `image` as a JPEG data URI, for the selection's preview: nothing is written, so nothing needs cleaning up; nil when it
/// cannot be encoded.
func jpegDataURI(_ image: CGImage, quality: Double = 0.85) -> String? {
  let data = NSMutableData()
  guard let destination = CGImageDestinationCreateWithData(data as CFMutableData, UTType.jpeg.identifier as CFString, 1, nil) else {
    return nil
  }
  CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
  guard CGImageDestinationFinalize(destination) else { return nil }
  return "data:image/jpeg;base64," + (data as Data).base64EncodedString()
}

/// A line as read, in pixels of the crop with a top-left origin: its corners from top left clockwise, how sure the
/// recognizer is of it, and its words with their own corners.
struct ReadTextLine {
  let corners: [CGPoint]
  let confidence: Double
  let words: [(text: String, corners: [CGPoint])]
}

/// The lines Vision reads in `image`, in pixels of it with a top-left origin. Throws when the recognizer fails.
func readTextLines(_ image: CGImage) throws -> [ReadTextLine] {
  let request = VNRecognizeTextRequest()
  request.recognitionLevel = .accurate
  // Codes, serials and names are read as printed, not corrected into dictionary words.
  request.usesLanguageCorrection = false
  try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
  let width = CGFloat(image.width)
  let height = CGFloat(image.height)
  // Vision's corners are normalized with a bottom-left origin.
  func pixels(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * width, y: (1 - p.y) * height) }
  func corners(_ box: VNRectangleObservation) -> [CGPoint] {
    [pixels(box.topLeft), pixels(box.topRight), pixels(box.bottomRight), pixels(box.bottomLeft)]
  }
  return (request.results ?? []).compactMap { observation in
    guard let candidate = observation.topCandidates(1).first else { return nil }
    let text = candidate.string
    var words: [(text: String, corners: [CGPoint])] = []
    var index = text.startIndex
    while index < text.endIndex {
      guard let start = text[index...].firstIndex(where: { !$0.isWhitespace }) else { break }
      let end = text[start...].firstIndex(where: \.isWhitespace) ?? text.endIndex
      let range = start..<end
      if let box = try? candidate.boundingBox(for: range) { words.append((String(text[range]), corners(box))) }
      index = end
    }
    return ReadTextLine(corners: corners(observation), confidence: Double(candidate.confidence), words: words)
  }
}

/// The words of `lines` in a `width` by `height` crop, in reading order, numbered by line and normalized to the crop.
/// Lines run across the text's direction, the median of their top edges so one odd line does not turn the rest, and
/// words along it.
func textWords(_ lines: [ReadTextLine], width: Double, height: Double) -> [TextWord] {
  guard width > 0, height > 0 else { return [] }
  func centre(_ q: [CGPoint]) -> CGPoint {
    CGPoint(x: q.map(\.x).reduce(0, +) / CGFloat(q.count), y: q.map(\.y).reduce(0, +) / CGFloat(q.count))
  }
  let angles = lines.filter { $0.corners.count == 4 }
    .map { atan2(Double($0.corners[1].y - $0.corners[0].y), Double($0.corners[1].x - $0.corners[0].x)) }.sorted()
  let angle = angles.isEmpty ? 0 : angles[angles.count / 2]
  let alongX = cos(angle)
  let alongY = sin(angle)
  func along(_ q: [CGPoint]) -> Double { let c = centre(q); return Double(c.x) * alongX + Double(c.y) * alongY }
  func across(_ q: [CGPoint]) -> Double { let c = centre(q); return -Double(c.x) * alongY + Double(c.y) * alongX }
  func point(_ p: CGPoint) -> DocumentPoint { DocumentPoint(x: Double(p.x) / width, y: Double(p.y) / height) }
  return lines.filter { $0.corners.count == 4 }.sorted { across($0.corners) < across($1.corners) }.enumerated()
    .flatMap { index, line in
      line.words.filter { !$0.text.allSatisfy(\.isWhitespace) && $0.corners.count == 4 }
        .sorted { along($0.corners) < along($1.corners) }
        .map { word in
          let xs = word.corners.map { Double($0.x) }
          let ys = word.corners.map { Double($0.y) }
          let q = word.corners
          return TextWord(
            text: word.text,
            bounds: TextRegion(x: xs.min()! / width, y: ys.min()! / height,
                               width: (xs.max()! - xs.min()!) / width, height: (ys.max()! - ys.min()!) / height),
            corners: DocumentCorners(topLeft: point(q[0]), topRight: point(q[1]), bottomRight: point(q[2]),
                                     bottomLeft: point(q[3])),
            line: Double(index), confidence: line.confidence)
        }
    }
}
