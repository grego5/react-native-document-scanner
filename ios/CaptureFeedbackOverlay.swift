import UIKit

/// A main-thread presentation of capture events. Its display link only repaints.
final class CaptureFeedbackOverlay: UIView {
  var color: UInt32 = 0xFFFFFFFF
  private let guide: LiveGuideOverlay
  private let shape = CAShapeLayer()
  private let progress = CAShapeLayer()
  private var link: CADisplayLink?
  private var stage: CaptureStage?
  private var start: CFTimeInterval = 0
  private var releasedSweep = 0.0
  private var heldPath: CGPath?

  init(guide: LiveGuideOverlay) {
    self.guide = guide
    super.init(frame: .zero)
    isUserInteractionEnabled = false
    for (layer, width) in [(shape, CGFloat(2.5)), (progress, CGFloat(4))] {
      layer.lineWidth = width
      layer.lineJoin = .round
      layer.lineCap = .round
      self.layer.addSublayer(layer)
    }
    progress.fillColor = nil
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }

  override func didMoveToWindow() {
    super.didMoveToWindow()
    if window == nil { clear() }
  }

  func show(_ next: CaptureStage) {
    guard window != nil else { return }
    let now = CACurrentMediaTime()
    if next == .waiting { heldPath = nil }
    else if heldPath == nil { heldPath = guide.feedbackPath }
    if next == .captured {
      releasedSweep = stage == .shooting ? CaptureFeedbackTimeline.shooting(now - start).sweep : 0
      UINotificationFeedbackGenerator().notificationOccurred(.success)
    } else if next == .failed {
      UINotificationFeedbackGenerator().notificationOccurred(.error)
    }
    stage = next
    start = now
    if link == nil {
      let link = CADisplayLink(target: CaptureFeedbackTick(self), selector: #selector(CaptureFeedbackTick.tick(_:)))
      link.add(to: .main, forMode: .common)
      self.link = link
    }
    tick()
  }

  func clear() {
    link?.invalidate()
    link = nil
    stage = nil
    heldPath = nil
    shape.path = nil
    progress.path = nil
  }

  fileprivate func tick() {
    guard let stage else { return }
    let elapsed = CACurrentMediaTime() - start
    let frame: CaptureFeedbackTimeline.Frame
    switch stage {
    case .waiting: frame = CaptureFeedbackTimeline.waiting(elapsed)
    case .shooting: frame = CaptureFeedbackTimeline.shooting(elapsed)
    case .captured: frame = CaptureFeedbackTimeline.captured(elapsed, from: releasedSweep)
    case .failed: frame = CaptureFeedbackTimeline.failed(elapsed)
    }
    if frame.finished { return clear() }
    let base = UIColor(argb: stage == .failed ? 0xFFFF453A : color).cgColor
    let path = heldPath ?? guide.feedbackPath
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    shape.path = path
    shape.strokeColor = base.copy(alpha: base.alpha * CGFloat(frame.outline))
    shape.fillColor = base.copy(alpha: base.alpha * CGFloat(frame.fill))
    progress.path = path
    progress.strokeColor = base.copy(alpha: base.alpha * CGFloat(frame.line))
    progress.strokeEnd = CGFloat(frame.sweep)
    CATransaction.commit()
  }
}

@MainActor
private final class CaptureFeedbackTick: NSObject {
  weak var overlay: CaptureFeedbackOverlay?
  init(_ overlay: CaptureFeedbackOverlay) { self.overlay = overlay }
  @objc func tick(_ link: CADisplayLink) {
    guard let overlay else { link.invalidate(); return }
    overlay.tick()
  }
}
