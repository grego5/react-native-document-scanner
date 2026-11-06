import QuartzCore
import UIKit

/// Shows a contain-fitted upright photo with its crop, and edits the crop by touch: corners
/// move alone, edges move both of their corners. While a corner is dragged, a magnifier
/// floats above the finger, or beside it on the side away from the nearer left or right
/// edge when there is no room above, gliding between the two. Two fingers that start away
/// from the handles pinch and pan the photo, up to `maxZoom`, and a double tap fits it
/// again; handles keep their size on screen. What a touch sequence does is chosen by where
/// its first finger lands. The photo and overlays are layers, so a drag only moves them.
/// Main only.
@MainActor
final class CropEditorView: UIView {
  typealias Touch = AnyHashable

  /// Largest scale over the contain fit.
  static let maxZoom: CGFloat = 5
  // Sizes in points. Touch reach gives 44 pt targets; the visible handle stays on the actual coordinate.
  static let touchRadius: CGFloat = 22
  static let cornerRadius: CGFloat = 12
  static let gripLength: CGFloat = 26
  static let gripThickness: CGFloat = 6
  static let magnifierSize: CGFloat = 120
  static let magnifierMargin: CGFloat = 12
  /// Gap between the finger and the magnifier's edge.
  static let fingerClearance: CGFloat = 40
  static let crosshair: CGFloat = 24
  /// The magnifier's scale over the contain fit.
  static let magnification: CGFloat = 3
  /// The least the magnifier enlarges the zoomed photo, so it still reads as a magnifier.
  static let minMagnification: CGFloat = 1.5
  /// Time constant of the magnifier's glide toward its target, in seconds.
  static let glide: TimeInterval = 0.07
  /// A second tap that lands this soon and this near after the first fits the photo again.
  static let doubleTapInterval: TimeInterval = 0.3
  static let doubleTapSlop: CGFloat = 100
  /// A touch that moves further than this, or stays down longer, is not a tap.
  static let tapSlop: CGFloat = 8
  static let tapDuration: TimeInterval = 0.5

  private static let color = CGColor(srgbRed: 0x38 / 255.0, green: 0xD6 / 255.0, blue: 0xA8 / 255.0, alpha: 1)
  private static let crosshairColor = CGColor(srgbRed: 1, green: 0x3B / 255.0, blue: 0x30 / 255.0, alpha: 1)

  /// Called with the edited corners when a drag that changed it ends.
  var onCornersEdited: ((CornerCoordinates) -> Void)?
  /// Called when the view joins or leaves a window.
  var onAttach: (() -> Void)?
  var onDetach: (() -> Void)?

  /// The upright photo. A replacement keeps the zoom, so a filtered copy of the same photo
  /// can take its place; call `resetZoom` for a different photo. Clearing it cancels the
  /// touch sequence in progress.
  var image: CGImage? {
    didSet {
      if image == nil { cancelSequence() }
      imageView.image = image.map { UIImage(cgImage: $0) }
      magnifiedImage.contents = image
      update()
    }
  }

  /// Keeps the crop an upright rectangle: only its corners move, the opposite corner fixed,
  /// with no edge handles and no pinch, pan or double-tap zoom. Turning it on fits the photo
  /// and squares the crop to its bounding rectangle.
  var rectangle = false {
    didSet {
      guard rectangle != oldValue else { return }
      cancelSequence()
      if rectangle {
        corners = CropCornerEditing.boundingRectangle(corners)
        zoom = 1
        pan = .zero
      }
      update()
    }
  }

  /// The crop in normalized upright coordinates.
  private(set) var corners: CornerCoordinates = CropCornerEditing.fullFrame
  /// The photo's scale over its contain fit, and its center's offset from the view's center.
  private(set) var zoom: CGFloat = 1
  private(set) var pan = CGPoint.zero
  /// Where the photo is drawn; empty until the view and the photo have a size.
  private(set) var content = CGRect.zero
  /// Whether a handle is being dragged.
  var isDragging: Bool { drag != nil }

  let imageView = UIImageView()
  let magnifier = UIView()
  let magnifiedImage = CALayer()
  let outline = CAShapeLayer()
  let handles = CAShapeLayer()
  let grips = CAShapeLayer()
  private let magnifiedOutline = CAShapeLayer()
  private let crosshairs = CAShapeLayer()

  /// `start` is the corners before the touch, `base` the corners after snapping the handle under the finger.
  private struct Drag {
    let corner: Bool
    let index: Int
    let touch: Touch
    let down: CGPoint
    let start: CornerCoordinates
    let base: CornerCoordinates
  }

  private enum Gesture { case handle, view }

  private var gesture = Gesture.view
  /// False when the sequence's first finger landed before the photo was shown; the whole
  /// sequence is then ignored.
  private var active = false
  private var touches: [Touch: CGPoint] = [:]
  private var drag: Drag?
  /// The multi-touch centroid and spread the photo last moved for; nil with fewer than two fingers.
  private var focus: (center: CGPoint, span: CGFloat)?
  /// A single-finger view touch that may still be a tap.
  private var tap: (point: CGPoint, time: TimeInterval, moved: Bool)?
  private var lastTap: (point: CGPoint, time: TimeInterval)?
  private var finger = CGPoint.zero
  /// The magnifier's shown center, eased toward its target each frame; nil until a drag's first frame.
  private var magnifierCenter: CGPoint?
  private var lastFrame: TimeInterval = 0
  private var link: CADisplayLink?

  override init(frame: CGRect) {
    super.init(frame: frame)
    clipsToBounds = true
    isMultipleTouchEnabled = true
    imageView.contentMode = .scaleToFill
    imageView.isHidden = true
    addSubview(imageView)
    for shape in [outline, handles, magnifiedOutline] {
      shape.strokeColor = Self.color
      shape.fillColor = nil
      shape.lineWidth = 2
      shape.lineJoin = .round
    }
    grips.fillColor = Self.color
    for shape in [outline, grips, handles] {
      shape.isHidden = true
      layer.addSublayer(shape)
    }
    let size = Self.magnifierSize
    magnifier.bounds = CGRect(x: 0, y: 0, width: size, height: size)
    magnifier.isHidden = true
    magnifier.isUserInteractionEnabled = false
    magnifier.clipsToBounds = true
    magnifier.backgroundColor = .black
    magnifier.layer.cornerRadius = size / 2
    magnifier.layer.borderColor = UIColor.white.cgColor
    magnifier.layer.borderWidth = 2
    magnifiedImage.contentsGravity = .resize
    magnifier.layer.addSublayer(magnifiedImage)
    magnifier.layer.addSublayer(magnifiedOutline)
    let arm = Self.crosshair / 2
    let lines = CGMutablePath()
    lines.move(to: CGPoint(x: size / 2 - arm, y: size / 2))
    lines.addLine(to: CGPoint(x: size / 2 + arm, y: size / 2))
    lines.move(to: CGPoint(x: size / 2, y: size / 2 - arm))
    lines.addLine(to: CGPoint(x: size / 2, y: size / 2 + arm))
    crosshairs.path = lines
    crosshairs.strokeColor = Self.crosshairColor
    crosshairs.lineWidth = 1
    magnifier.layer.addSublayer(crosshairs)
    addSubview(magnifier)
  }

  required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

  /// Fits the whole photo in the view again.
  func resetZoom() {
    zoom = 1
    pan = .zero
    update()
  }

  /// Replaces the crop with a valid in-frame corners and cancels a drag in progress.
  func replaceCorners(_ value: CornerCoordinates) throws {
    guard CropCornerEditing.isValid(value) else { throw ScannerCodeError(code: .inputInvalid) }
    corners = rectangle ? CropCornerEditing.boundingRectangle(value) : value
    if drag != nil {
      drag = nil
      stopGlide()
    }
    update()
  }

  /// The view position of corner `index`.
  func corner(_ index: Int) -> CGPoint {
    CGPoint(x: content.minX + CGFloat(corners[index * 2]) * content.width,
            y: content.minY + CGFloat(corners[index * 2 + 1]) * content.height)
  }

  /// The view position of the middle of the edge from corner `index` to the next.
  func edgeMiddle(_ index: Int) -> CGPoint {
    let a = corner(index), b = corner((index + 1) % 4)
    return CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
  }

  // MARK: Touches

  override func touchesBegan(_ set: Set<UITouch>, with event: UIEvent?) {
    for touch in set { touchDown(ObjectIdentifier(touch), at: touch.location(in: self), time: touch.timestamp) }
  }

  override func touchesMoved(_ set: Set<UITouch>, with event: UIEvent?) {
    var positions: [Touch: CGPoint] = [:]
    for touch in set { positions[ObjectIdentifier(touch)] = touch.location(in: self) }
    touchMoved(positions)
  }

  override func touchesEnded(_ set: Set<UITouch>, with event: UIEvent?) {
    for touch in set { touchUp(ObjectIdentifier(touch), at: touch.location(in: self), time: touch.timestamp) }
  }

  override func touchesCancelled(_ set: Set<UITouch>, with event: UIEvent?) {
    for touch in set {
      touchUp(ObjectIdentifier(touch), at: touch.location(in: self), time: touch.timestamp, cancelled: true)
    }
  }

  /// A finger landed; `time` is in seconds on any monotonic clock.
  func touchDown(_ id: Touch, at point: CGPoint, time: TimeInterval) {
    if touches.isEmpty {
      active = image != nil && layoutContent()
      if active { gesture = handle(at: point) == nil ? .view : .handle }
      // A rectangle crop never zooms; a touch away from its corners does nothing.
      if active, rectangle, gesture == .view { active = false }
    }
    touches[id] = point
    guard active else { return }
    switch gesture {
    case .view:
      if touches.count == 1 {
        if let last = lastTap, time - last.time <= Self.doubleTapInterval,
           distance(last.point, point) <= Self.doubleTapSlop {
          lastTap = nil
          tap = nil
          resetZoom()
        } else {
          tap = (point, time, false)
        }
      } else {
        tap = nil
        lastTap = nil
      }
      focus = measure()
    case .handle:
      // Further fingers on a handle drag are ignored.
      guard touches.count == 1, let grabbed = handle(at: point) else { return }
      let isCorner = grabbed.corner, index = grabbed.index
      // A handle press ends any tap sequence, so it never completes a double tap.
      lastTap = nil
      let start = corners
      // The grabbed corner, or the middle of the grabbed edge, jumps under the finger at once.
      let snapped: CornerCoordinates?
      let x = Double((point.x - content.minX) / content.width), y = Double((point.y - content.minY) / content.height)
      if isCorner && rectangle {
        snapped = CropCornerEditing.moveRectangleCorner(start, index, x: x, y: y)
      } else if isCorner {
        snapped = CropCornerEditing.moveCorner(start, index, x: x, y: y)
      } else {
        let middle = edgeMiddle(index)
        snapped = CropCornerEditing.moveEdge(start, index, dx: Double((point.x - middle.x) / content.width),
                                           dy: Double((point.y - middle.y) / content.height))
      }
      corners = snapped ?? start
      drag = Drag(corner: isCorner, index: index, touch: id, down: point, start: start, base: corners)
      finger = point
      magnifierCenter = nil
      if isCorner { startGlide() }
      update()
    }
  }

  /// Fingers moved to `positions`.
  func touchMoved(_ positions: [Touch: CGPoint]) {
    for (id, point) in positions where touches[id] != nil { touches[id] = point }
    guard active else { return }
    switch gesture {
    case .view:
      if let current = tap, let point = touches.values.first, distance(current.point, point) > Self.tapSlop {
        tap = (current.point, current.time, true)
      }
      // Two-finger movement pans even at a constant spread, and the pinch scales around the
      // current centroid, so the translation counts once.
      guard let previous = focus, let next = measure() else { return }
      pan.x += next.center.x - previous.center.x
      pan.y += next.center.y - previous.center.y
      if previous.span > 0 {
        let scaled = min(max(zoom * next.span / previous.span, 1), Self.maxZoom)
        let ratio = scaled / zoom
        let centerX = bounds.midX + pan.x
        let centerY = bounds.midY + pan.y
        pan.x = next.center.x - (next.center.x - centerX) * ratio - bounds.midX
        pan.y = next.center.y - (next.center.y - centerY) * ratio - bounds.midY
        zoom = scaled
      }
      focus = next
      update()
    case .handle:
      guard let current = drag, let point = positions[current.touch], !content.isEmpty else { return }
      finger = point
      let dx = Double((point.x - current.down.x) / content.width)
      let dy = Double((point.y - current.down.y) / content.height)
      let base = current.base
      let x = base[current.index * 2] + dx, y = base[current.index * 2 + 1] + dy
      let next: CornerCoordinates?
      if current.corner && rectangle { next = CropCornerEditing.moveRectangleCorner(base, current.index, x: x, y: y) }
      else if current.corner { next = CropCornerEditing.moveCorner(base, current.index, x: x, y: y) }
      else { next = CropCornerEditing.moveEdge(base, current.index, dx: dx, dy: dy) }
      // An invalid move keeps the last valid corners.
      if let next { corners = next }
      update()
    }
  }

  /// A finger lifted, or the system cancelled it.
  func touchUp(_ id: Touch, at point: CGPoint, time: TimeInterval, cancelled: Bool = false) {
    guard touches.removeValue(forKey: id) != nil, active else { return }
    switch gesture {
    case .view:
      // Fewer than two fingers stop panning; a remaining pair rebases the focus.
      focus = measure()
      if touches.isEmpty {
        if let current = tap, !current.moved, !cancelled, time - current.time <= Self.tapDuration {
          lastTap = (point, time)
        }
        tap = nil
      }
    case .handle:
      if let current = drag, current.touch == id { endDrag(current) }
    }
  }

  /// Ignores the rest of the touch sequence in progress. A drag is abandoned: its corners
  /// returns to where it started and nothing is published.
  private func cancelSequence() {
    if !touches.isEmpty { active = false }
    tap = nil
    lastTap = nil
    focus = nil
    if let current = drag {
      drag = nil
      corners = current.start
      stopGlide()
    }
  }

  private func endDrag(_ current: Drag) {
    drag = nil
    stopGlide()
    update()
    if current.start != corners { onCornersEdited?(corners) }
  }

  /// The corner within reach of `point`, nearest first, otherwise the nearest edge middle within reach.
  private func handle(at point: CGPoint) -> (corner: Bool, index: Int)? {
    let corners = (0..<4).min { distance(corner($0), point) < distance(corner($1), point) }!
    if distance(corner(corners), point) <= Self.touchRadius { return (true, corners) }
    if rectangle { return nil }
    let edges = (0..<4).min { distance(edgeMiddle($0), point) < distance(edgeMiddle($1), point) }!
    if distance(edgeMiddle(edges), point) <= Self.touchRadius { return (false, edges) }
    return nil
  }

  private func measure() -> (center: CGPoint, span: CGFloat)? {
    guard touches.count >= 2 else { return nil }
    let count = CGFloat(touches.count)
    let center = CGPoint(x: touches.values.reduce(0) { $0 + $1.x } / count,
                         y: touches.values.reduce(0) { $0 + $1.y } / count)
    return (center, touches.values.reduce(0) { $0 + distance($1, center) } / count)
  }

  // MARK: Layout

  override func layoutSubviews() {
    super.layoutSubviews()
    update()
  }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil {
      cancelSequence()
      update()
      onDetach?()
    } else {
      if drag?.corner == true { startGlide() }
      onAttach?()
    }
  }

  /// Places the photo: contain-fitted, scaled by `zoom`, and panned no further than keeps the
  /// view covered along each axis the zoomed photo fills, centered along the others. False
  /// until the view and photo have a size.
  private func layoutContent() -> Bool {
    guard let image, bounds.width > 0, bounds.height > 0, image.width > 0, image.height > 0 else {
      content = .zero
      return false
    }
    let width = CGFloat(image.width), height = CGFloat(image.height)
    let scale = min(bounds.width / width, bounds.height / height) * zoom
    let size = CGSize(width: width * scale, height: height * scale)
    let reachX = max(0, (size.width - bounds.width) / 2)
    let reachY = max(0, (size.height - bounds.height) / 2)
    pan = CGPoint(x: min(max(pan.x, -reachX), reachX), y: min(max(pan.y, -reachY), reachY))
    content = CGRect(x: bounds.midX + pan.x - size.width / 2, y: bounds.midY + pan.y - size.height / 2,
                     width: size.width, height: size.height)
    return true
  }

  private func update() {
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    defer { CATransaction.commit() }
    guard layoutContent() else {
      imageView.isHidden = true
      for shape in [outline, grips, handles] { shape.isHidden = true }
      magnifier.isHidden = true
      return
    }
    imageView.isHidden = false
    imageView.frame = content
    let path = cornersPath()
    outline.path = path
    let circles = CGMutablePath()
    let gripShapes = CGMutablePath()
    let radius = Self.cornerRadius
    let grip = CGRect(x: -Self.gripLength / 2, y: -Self.gripThickness / 2, width: Self.gripLength, height: Self.gripThickness)
    for index in 0..<4 {
      let point = corner(index)
      circles.addEllipse(in: CGRect(x: point.x - radius, y: point.y - radius, width: radius * 2, height: radius * 2))
      let next = corner((index + 1) % 4)
      let middle = edgeMiddle(index)
      let turn = CGAffineTransform(translationX: middle.x, y: middle.y)
        .rotated(by: atan2(next.y - point.y, next.x - point.x))
      if !rectangle { gripShapes.addPath(CGPath(roundedRect: grip, cornerWidth: Self.gripThickness / 2,
                                cornerHeight: Self.gripThickness / 2, transform: nil), transform: turn) }
    }
    handles.path = circles
    grips.path = gripShapes
    for shape in [outline, grips, handles] { shape.isHidden = false }
    if let drag, drag.corner {
      placeMagnifier(drag.index, now: CACurrentMediaTime())
      magnifier.isHidden = false
    } else {
      magnifier.isHidden = true
      magnifierCenter = nil
    }
  }

  private func cornersPath() -> CGPath {
    let path = CGMutablePath()
    path.addLines(between: (0..<4).map(corner))
    path.closeSubpath()
    return path
  }

  private func placeMagnifier(_ index: Int, now: TimeInterval) {
    let radius = Self.magnifierSize / 2
    let low = radius + Self.magnifierMargin
    let offset = Self.fingerClearance + radius
    // Above the finger; without room there, level with it on the side away from the nearer left or right edge.
    let above = finger.y - offset >= low
    let side: CGFloat = finger.x < bounds.midX ? 1 : -1
    let target = CGPoint(x: min(max(above ? finger.x : finger.x + side * offset, low), bounds.width - low),
                         y: min(max(above ? finger.y - offset : finger.y, low), bounds.height - low))
    var center = target
    if let shown = magnifierCenter {
      let step = 1 - exp(-min(max(now - lastFrame, 0), 0.1) / Self.glide)
      center = CGPoint(x: shown.x + (target.x - shown.x) * step, y: shown.y + (target.y - shown.y) * step)
    }
    magnifierCenter = center
    lastFrame = now
    magnifier.center = center
    // The zoom already enlarges the photo, so the magnifier adds only what keeps its total near `magnification`.
    let scale = max(Self.magnification / zoom, Self.minMagnification)
    let point = corner(index)
    magnifiedImage.frame = CGRect(x: (content.minX - point.x) * scale + radius, y: (content.minY - point.y) * scale + radius,
                                  width: content.width * scale, height: content.height * scale)
    var transform = CGAffineTransform(a: scale, b: 0, c: 0, d: scale, tx: radius - point.x * scale, ty: radius - point.y * scale)
    magnifiedOutline.path = cornersPath().copy(using: &transform)
  }

  // The display link only eases the magnifier; it retains this view until stopped.
  private func startGlide() {
    guard link == nil, window != nil else { return }
    let next = CADisplayLink(target: self, selector: #selector(frameTick))
    next.add(to: .main, forMode: .common)
    link = next
  }

  private func stopGlide() {
    link?.invalidate()
    link = nil
  }

  @objc private func frameTick() { update() }
}

private func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }
