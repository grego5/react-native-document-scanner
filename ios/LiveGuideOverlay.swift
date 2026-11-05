import QuartzCore
import UIKit

/// Eases the drawn guide toward each new detection, in view points. Main only.
struct QuadTransition {
  static let defaultDurationNs: Int64 = 50_000_000

  private(set) var points = [CGFloat](repeating: 0, count: 8)
  private var from = [CGFloat](repeating: 0, count: 8)
  private var target = [CGFloat](repeating: 0, count: 8)
  private var startedNs: Int64 = 0
  private(set) var hasPoints = false
  private(set) var isAnimating = false
  /// Easing length for later retargets; zero or less snaps.
  var durationNs = defaultDurationNs

  /// Starts from the last drawn position; a corner jumping farther than `snapDistance` snaps.
  mutating func retarget(_ next: [CGFloat], nowNs: Int64, snapDistance: CGFloat) {
    let jump = stride(from: 0, to: 8, by: 2).contains { index in
      hypot(next[index] - points[index], next[index + 1] - points[index + 1]) > snapDistance
    }
    target = next
    if !hasPoints || jump || durationNs <= 0 {
      points = next
      hasPoints = true
      isAnimating = false
      return
    }
    from = points
    startedNs = nowNs
    isAnimating = points != target
  }

  mutating func advance(nowNs: Int64) {
    guard isAnimating else { return }
    let progress = durationNs <= 0 ? 1 : min(1, max(0, CGFloat(nowNs - startedNs) / CGFloat(durationNs)))
    if progress >= 1 {
      points = target
      isAnimating = false
    } else {
      points = (0..<8).map { from[$0] + (target[$0] - from[$0]) * progress }
    }
  }

  mutating func clear() {
    hasPoints = false
    isAnimating = false
  }
}

/// Main-thread visual guidance over the preview. It never changes geometry or captures. The guide
/// is drawn at full opacity while tracking and dimmed while held; while searching, fixed corner
/// brackets mark a centered page-shaped area instead.
final class LiveGuideOverlay: UIView {
  static let defaultColor: UInt32 = 0xFF61E1CF
  private static let heldAlpha: CGFloat = 0.45
  private static let viewfinderAlpha: CGFloat = 0.6
  private static let viewfinderFill: CGFloat = 0.8
  /// Of the page width.
  private static let viewfinderArm: CGFloat = 0.12
  /// A-series height over width.
  private static let pageAspect: CGFloat = 1.4142

  private let outline = CAShapeLayer()
  private let brackets = CAShapeLayer()
  private var transition = QuadTransition()
  private var link: CADisplayLink?
  private var stateAlpha: CGFloat = 1
  private(set) var drawnAcceptanceId: Int64 = 0
  private var laidOutSize = CGSize.zero
  private let clock: () -> Int64

  /// Draws the searching brackets while no guide is shown.
  var viewfinder = false { didSet { if viewfinder != oldValue { refresh() } } }
  /// The preview shows the frame's centered square, letterboxed to the view's centered square.
  var square = false { didSet { if square != oldValue { setNeedsLayout() } } }
  var transitionNs: Int64 {
    get { transition.durationNs }
    set { transition.durationNs = newValue }
  }
  /// The framing the preview layer shows. A drawn guide was mapped through the old one, so a
  /// change clears it until the next result.
  var previewFraming: CameraFraming? { didSet { if previewFraming != oldValue { clear() } } }
  /// ARGB guide color for every state; its alpha scales each state's opacity.
  var color: UInt32 = LiveGuideOverlay.defaultColor { didSet { if color != oldValue { refresh() } } }

  var hasOutline: Bool { transition.hasPoints }
  var drawnPoints: [CGFloat] { transition.points }
  var strokeAlpha: CGFloat { stateAlpha }
  var showsViewfinder: Bool { viewfinder && !transition.hasPoints && brackets.path != nil }

  init(clock: @escaping () -> Int64 = { Int64(DispatchTime.now().uptimeNanoseconds) }) {
    self.clock = clock
    super.init(frame: .zero)
    isUserInteractionEnabled = false
    isAccessibilityElement = false
    for shape in [brackets, outline] {
      shape.fillColor = nil
      shape.lineWidth = 2.5
      shape.lineJoin = .round
      shape.lineCap = .round
      shape.actions = ["path": NSNull(), "strokeColor": NSNull(), "hidden": NSNull()]
      layer.addSublayer(shape)
    }
    refresh()
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

  override func layoutSubviews() {
    super.layoutSubviews()
    outline.frame = bounds
    brackets.frame = bounds
    brackets.path = viewfinderPath()
    // A drawn guide belongs to the old size; the next result redraws it.
    if bounds.size != laidOutSize {
      laidOutSize = bounds.size
      clear()
    }
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil { clear() }
  }

  func clear() {
    stopAnimating()
    transition.clear()
    drawnAcceptanceId = 0
    stateAlpha = 1
    refresh()
  }

  func show(_ result: LiveGuideResult) {
    switch result.status {
    case .searching, .unavailable: clear()
    case .tracking: draw(result, alpha: 1)
    case .held:
      if transition.hasPoints && result.acceptanceId == drawnAcceptanceId {
        // Dim in place; an in-flight transition may finish.
        stateAlpha = Self.heldAlpha
        refresh()
      } else {
        draw(result, alpha: Self.heldAlpha)
      }
    }
  }

  /// The result's quad in view points. Detection and preview may turn the buffer differently
  /// (AVFoundation's capture and preview angles differ, for example, in a portrait-locked app held
  /// sideways), so each corner goes back to normalized buffer coordinates through the detection
  /// framing, then into `preview`, which is presented aspect-fitted as the preview layer is. Without
  /// a preview framing the detection framing is assumed to be the one shown.
  static func mappedPoints(_ result: LiveGuideResult, preview: CameraFraming?, in bounds: CGRect) -> [CGFloat]? {
    guard let quad = result.quad, quad.count == 8, let framing = result.framing else { return nil }
    let shown = preview ?? framing
    let rect = shown.presentation(in: bounds)
    guard !rect.isEmpty else { return nil }
    var points: [CGFloat] = []
    points.reserveCapacity(8)
    for index in stride(from: 0, to: 8, by: 2) {
      let point = shown.toFramed(framing.toBuffer(CGPoint(x: quad[index], y: quad[index + 1])))
      points.append(rect.minX + point.x * rect.width)
      points.append(rect.minY + point.y * rect.height)
    }
    return points.allSatisfy(\.isFinite) ? points : nil
  }

  private func draw(_ result: LiveGuideResult, alpha: CGFloat) {
    guard window != nil, let points = Self.mappedPoints(result, preview: previewFraming, in: bounds) else {
      return clear()
    }
    // Large jumps may be a new document; avoid sweeping the guide across the preview.
    transition.retarget(points, nowNs: clock(), snapDistance: hypot(bounds.width, bounds.height) * 0.2)
    drawnAcceptanceId = result.acceptanceId
    stateAlpha = alpha
    refresh()
    if transition.isAnimating { startAnimating() }
  }

  private func startAnimating() {
    guard link == nil else { return }
    let link = CADisplayLink(target: DisplayLinkTarget(self), selector: #selector(DisplayLinkTarget.tick))
    link.add(to: .main, forMode: .common)
    self.link = link
  }

  private func stopAnimating() {
    link?.invalidate()
    link = nil
  }

  fileprivate func tick() {
    transition.advance(nowNs: clock())
    refresh()
    if !transition.isAnimating { stopAnimating() }
  }

  private func refresh() {
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    let base = UIColor(argb: color)
    if transition.hasPoints {
      let points = transition.points
      let path = CGMutablePath()
      path.move(to: CGPoint(x: points[0], y: points[1]))
      for index in 1...3 { path.addLine(to: CGPoint(x: points[index * 2], y: points[index * 2 + 1])) }
      path.closeSubpath()
      outline.path = path
      outline.strokeColor = base.withAlphaComponent(base.alpha * stateAlpha).cgColor
      outline.isHidden = false
    } else {
      outline.path = nil
      outline.isHidden = true
    }
    brackets.strokeColor = base.withAlphaComponent(base.alpha * Self.viewfinderAlpha).cgColor
    brackets.isHidden = !(viewfinder && !transition.hasPoints)
    CATransaction.commit()
  }

  /// Corner brackets around the largest A-series portrait page that fits 80% of the view, or of
  /// its centered square, centered.
  private func viewfinderPath() -> CGPath? {
    guard bounds.width > 0, bounds.height > 0 else { return nil }
    let w = square ? min(bounds.width, bounds.height) : bounds.width
    let h = square ? w : bounds.height
    let pageWidth = min(w * Self.viewfinderFill, h * Self.viewfinderFill / Self.pageAspect)
    let pageHeight = pageWidth * Self.pageAspect
    let rect = CGRect(x: bounds.midX - pageWidth / 2, y: bounds.midY - pageHeight / 2,
                      width: pageWidth, height: pageHeight)
    let arm = pageWidth * Self.viewfinderArm
    let path = CGMutablePath()
    for (x, y) in [(rect.minX, rect.minY), (rect.maxX, rect.minY), (rect.maxX, rect.maxY), (rect.minX, rect.maxY)] {
      let dx = x == rect.minX ? arm : -arm, dy = y == rect.minY ? arm : -arm
      path.move(to: CGPoint(x: x, y: y + dy))
      path.addLine(to: CGPoint(x: x, y: y))
      path.addLine(to: CGPoint(x: x + dx, y: y))
    }
    return path
  }
}

/// Breaks the display link's strong reference to the overlay.
private final class DisplayLinkTarget: NSObject {
  weak var overlay: LiveGuideOverlay?
  init(_ overlay: LiveGuideOverlay) { self.overlay = overlay }
  @objc func tick() { overlay?.tick() }
}

extension UIColor {
  convenience init(argb: UInt32) {
    self.init(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255,
              blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat(argb >> 24) / 255)
  }

  fileprivate var alpha: CGFloat {
    var value: CGFloat = 0
    getRed(nil, green: nil, blue: nil, alpha: &value)
    return value
  }
}
