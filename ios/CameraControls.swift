import CoreGraphics
import Foundation
import os

/// Main-thread executor of focus, torch and exposure decisions for one camera view. `FocusPolicy`
/// owns scan ownership and generations and `TorchPolicy` the auto-torch latch; this class owns
/// only hardware requests (serialized on the camera's session owner), their completion evidence,
/// deadlines and the published snapshot. Every request and observation belongs to one binding;
/// `unbind` retires it, so a late completion or focus event of an earlier binding never reaches
/// the policies.
///
/// The camera delivers command completions and focus samples on main in the order its session
/// queue produced them, so this class needs no clocks to tell them apart. A scan ends at the first
/// sample after its own command's completion that shows the device in focus mode `.locked`:
/// applying the command leaves the device in `.autoFocus` until that scan ends, and every sample
/// before the completion, including an earlier scan finishing while the command waited, was taken
/// before it applied.
///
/// AVFoundation's evidence is weaker than Camera2's. That end says the scan finished, not that it
/// focused, so every scan settles with an unknown result. `isAdjustingFocus` is reported as
/// `scan`; nothing else asserts focus, so the autofocus state is never `focused`, `unfocused` or
/// `locked` on iOS.
final class CameraControls: @unchecked Sendable {
  struct Settings: Equatable {
    var tapToFocus = true
    /// Requested EV; clamped to the device's range when applied.
    var exposureBias = 0.0
    var minFrameRate = 0.0
    var torch = TorchMode.off
    var lowLightBoost = true
  }

  /// Continuous autofocus aims at the centre of the frame, where the guide holds a page. Device
  /// points are unit coordinates, so the centre is the same in every orientation.
  static let centre = CGPoint(x: 0.5, y: 0.5)

  private let camera: any CameraSessionControl
  private let clock: () -> Int64
  private let schedule: (_ delayNs: Int64, _ block: @escaping @Sendable () -> Void) -> Void
  private let logger = Logger(subsystem: "ReactNativeDocumentScanner", category: "controls")

  /// A photo is exposing: taps and new scans are refused, since its focus must not change.
  var shotInFlight: () -> Bool = { false }
  /// A `takePhoto` call is waiting or shooting: torch, exposure-bias and frame-rate changes wait for
  /// it, so the light a capture is judged on does not change.
  var capturePending: () -> Bool = { false }
  /// The focus snapshot or the autofocus state changed.
  var onChange: (() -> Void)?

  // Main only.
  private(set) var snapshot = FocusSnapshot()
  private(set) var autofocus = AutofocusState.inactive
  private(set) var settings = Settings()
  private var policy = FocusPolicy()
  private var capabilities: CameraCapabilities?
  /// Retires every request and observation of an earlier binding.
  private var binding = 0
  private var scan: Scan?
  /// Platform timer revision only; it never represents focus ownership.
  private var wakeRevision = 0
  private var torchRequest: (lit: Bool, id: Int)?
  private var torchSerial = 0
  /// The torch state the device last accepted; nil before any or after a refusal.
  private(set) var torchApplied: Bool?
  /// Exposure-bias and frame-rate commands the device has not answered yet.
  private var exposurePending = 0
  /// An exposure-bias or frame-rate change waits for the capture in progress.
  private var exposureDeferred = false
  /// When the device last applied a light change (torch, exposure bias or frame rate), as main
  /// heard it: frames exposed before then may show the earlier light.
  private(set) var lightChangedNs: Int64 = 0

  // Frame queue and main, under `torchLock`.
  private let torchLock = NSLock()
  private var torchPolicy = TorchPolicy()
  private var torchAuto = false
  /// Frames are admitted only while bound: a frame of a stopped run never reaches the next one.
  private var torchBinding: Int?

  private final class Scan {
    let token: Int64
    let binding: Int
    /// The device applied the scan's command; focus samples delivered before then describe an
    /// earlier state.
    var applied = false
    init(token: Int64, binding: Int) { self.token = token; self.binding = binding }
  }

  init(camera: any CameraSessionControl, clock: @escaping () -> Int64 = hostClockNs,
       schedule: ((Int64, @escaping @Sendable () -> Void) -> Void)? = nil) {
    self.camera = camera
    self.clock = clock
    self.schedule = schedule ?? { delay, block in
      DispatchQueue.main.asyncAfter(deadline: .now() + .nanoseconds(Int(max(0, delay))), execute: block)
    }
  }

  var isBound: Bool { capabilities != nil }

  // MARK: - Binding

  private var requestedBoost: Bool?

  /// A configured run: aims continuous autofocus and applies every control the device supports.
  func bind(_ capabilities: CameraCapabilities) {
    binding += 1
    self.capabilities = capabilities
    scan = nil
    torchRequest = nil
    torchApplied = nil
    exposurePending = 0
    torchLock.lock(); torchBinding = binding; torchLock.unlock()
    aimContinuousFocus()
    applyExposure()
    requestedBoost = nil
    applyBoost()
    applyTorch()
  }

  /// The run stopped: scans end, observations and completions of this binding are ignored, and the
  /// auto-torch latch resets.
  func unbind(preservingTorchDecision: Bool = false) {
    binding += 1
    capabilities = nil
    scan = nil
    wakeRevision += 1
    policy.unbind()
    torchRequest = nil
    torchApplied = nil
    exposurePending = 0
    exposureDeferred = false
    lightChangedNs = 0
    torchLock.lock()
    if preservingTorchDecision { torchPolicy.interrupt() } else { torchPolicy.reset() }
    torchBinding = nil
    torchLock.unlock()
    autofocus = .inactive
    publish()
  }

  func update(_ next: Settings) {
    let previous = settings
    settings = next
    if next.torch != previous.torch {
      // A mode change resets the latch: a lit torch hid the room's own light.
      torchLock.lock(); torchPolicy.reset(); torchAuto = next.torch == .auto; torchLock.unlock()
    }
    guard isBound else { return }
    if next.exposureBias != previous.exposureBias || next.minFrameRate != previous.minFrameRate { applyExposure() }
    if next.lowLightBoost != previous.lowLightBoost { applyBoost() }
    if next.torch != previous.torch { applyTorch() }
  }

  // MARK: - Focus

  /// A tap's scan at a device point, also metering exposure there; it holds the lens for
  /// `FocusPolicy.tapHoldNs`. Refused while taps are off, a shot is in flight or the lens cannot
  /// scan to a point.
  func tap(at point: CGPoint) {
    guard settings.tapToFocus, let capabilities, capabilities.focusScans, !shotInFlight() else { return }
    let next = start(.tap)
    send(next, .scanFocus(point, meter: capabilities.exposurePoint ? point : nil))
  }

  /// A quality capture's own scan at a device point, held until `afterCapture`. A capture may
  /// restart its scan while it waits; only a shot in flight refuses one. A lens that cannot scan
  /// settles at once with an unknown result, so nothing waits for a scan that cannot happen.
  func capture(at point: CGPoint) {
    guard let capabilities, !shotInFlight() else { return }
    let next = start(.capture)
    if capabilities.focusScans {
      send(next, .scanFocus(point, meter: nil))
    } else {
      settle(next, success: nil)
    }
  }

  /// A capture ended: its scan ends, a tap past its hold ends, and deferred torch, exposure-bias
  /// and frame-rate changes apply.
  func afterCapture() {
    if policy.afterCapture(nowNs: clock()) { released() }
    scheduleDeadlines()
    if exposureDeferred { applyExposure() }
    applyBoost()
    applyTorch()
  }

  private func applyBoost() {
    let enabled = settings.lowLightBoost
    guard capabilities?.lowLightBoost == true, !capturePending(),
          requestedBoost != enabled else { return }
    requestedBoost = enabled
    sendExposure(.lowLightBoost(enabled), name: "low-light boost")
  }

  /// A focus sample of the bound device. `adjusting` drives the autofocus state; a pending scan
  /// whose command has applied settles when the device is `.locked` and still.
  func focusObserved(_ observation: FocusObservation) {
    guard isBound else { return }
    let state: AutofocusState = observation.adjusting ? .scan : .inactive
    let changed = state != autofocus
    autofocus = state
    if let current = scan, pending(current), current.applied, observation.locked, !observation.adjusting {
      settle(current, success: nil)
      return
    }
    if changed { onChange?() }
  }

  private func start(_ owner: FocusOwner) -> Scan {
    let now = clock()
    let next = Scan(token: policy.start(owner, nowNs: now), binding: binding)
    logger.debug("\(owner == .tap ? "tap" : "capture", privacy: .public) focus scan started")
    scan = next
    publish()
    scheduleDeadlines()
    return next
  }

  private func send(_ target: Scan, _ command: CameraCommand) {
    camera.perform(command) { [weak self] outcome in
      guard let self, pending(target) else { return }
      guard outcome.applied else {
        logger.info("focus scan refused by the device")
        return settle(target, success: false)
      }
      target.applied = true
    }
  }

  private func pending(_ target: Scan) -> Bool {
    scan === target && target.binding == binding && policy.scanToken == target.token && policy.pending
  }

  private func settle(_ target: Scan, success: Bool?) {
    guard pending(target), policy.complete(target.token, success: success) else { return }
    publish()
    scheduleDeadlines()
  }

  private func released() {
    scan = nil
    publish()
    aimContinuousFocus()
  }

  /// While no scan holds the lens, continuous autofocus aims at the centre and exposure meters
  /// there again, undoing a tap's point.
  private func aimContinuousFocus() {
    guard let capabilities, capabilities.continuousFocus || capabilities.focusPoint, policy.owner == nil else { return }
    camera.perform(.continuousFocus(Self.centre)) { [weak self] outcome in
      if !outcome.applied { self?.logger.info("continuous focus refused by the device") }
    }
  }

  private func publish() {
    snapshot = FocusSnapshot(generation: policy.generation, owner: policy.owner, locked: policy.locked,
                             pending: policy.pending, heldUntilNs: policy.heldUntilNs, changedNs: clock())
    onChange?()
  }

  private func scheduleDeadlines() {
    wakeRevision += 1
    let revision = wakeRevision
    guard isBound else { return }
    let now = clock()
    // An overdue tap waits for the shot in flight; `afterCapture` ends it.
    let tap = policy.tapDeadline.flatMap { $0 > now || !shotInFlight() ? $0 : nil }
    guard let deadline = [tap, policy.scanDeadline].compactMap({ $0 }).min() else { return }
    schedule(deadline - now) { [weak self] in
      guard let self, revision == wakeRevision else { return }
      let time = clock()
      if let current = scan {
        if let until = policy.tapDeadline, time >= until,
           policy.expireTap(current.token, nowNs: time, shot: shotInFlight()) {
          released()
        } else if let until = policy.scanDeadline, time >= until, pending(current) {
          logger.info("capture focus scan timed out")
          settle(current, success: false)
        }
      }
      scheduleDeadlines()
    }
  }

  // MARK: - Torch, exposure and frame rate

  /// Frame queue: one frame's exposure evidence for the auto torch. A frame without an exposure is
  /// no evidence of darkness, so it restarts the sustained-darkness wait like a bright one.
  func frameObserved(_ metadata: CameraFrameMetadata) {
    torchLock.lock()
    let owner = torchBinding
    let light = owner != nil && torchAuto
      && torchPolicy.observe(observationNs: metadata.hostNs, exposureNs: metadata.exposureNs ?? 0, iso: metadata.iso)
    torchLock.unlock()
    guard light else { return }
    DispatchQueue.main.async { [weak self] in
      guard let self, binding == owner else { return }
      applyTorch()
    }
  }

  private var autoLit: Bool { torchLock.lock(); defer { torchLock.unlock() }; return torchPolicy.lit }

  /// A torch, exposure-bias or frame-rate change the device has not answered yet; a capture's
  /// light is not ready meanwhile.
  var lightPending: Bool { torchRequest != nil || exposurePending > 0 }

  /// Sets the torch from the mode and the auto decision. A change waits while a photo is in
  /// flight; `afterCapture` applies it. A refused change is retried by the next call.
  private func applyTorch() {
    guard let capabilities, capabilities.torch, !capturePending() else { return }
    let lit: Bool
    switch settings.torch {
    case .on: lit = true
    case .auto: lit = autoLit
    case .off: lit = false
    }
    // The pending request, else the accepted state; nothing known yet always sends.
    let current: Bool? = torchRequest?.lit ?? torchApplied
    guard lit != current else { return }
    torchSerial += 1
    let id = torchSerial, owner = binding
    torchRequest = (lit, id)
    camera.perform(.torch(lit)) { [weak self] outcome in
      guard let self, binding == owner, torchRequest?.id == id else { return }
      torchRequest = nil
      torchApplied = outcome.applied ? lit : nil
      if outcome.applied { lightChanged(outcome) }
      if !outcome.applied { logger.info("torch lit=\(lit, privacy: .public) refused by the device") }
      onChange?()
    }
  }

  /// Sends the exposure bias and frame rate, which change the light frames are exposed with. Like
  /// the torch, they wait for a capture in progress; `afterCapture` sends them.
  private func applyExposure() {
    guard let capabilities else { return }
    guard !capturePending() else { exposureDeferred = true; return }
    exposureDeferred = false
    if let bias = CameraExposurePolicy.bias(settings.exposureBias, range: capabilities.exposureBias) {
      sendExposure(.exposureBias(bias), name: "exposure bias")
    }
    if !capabilities.frameRates.isEmpty {
      let range = CameraExposurePolicy.frameRate(minFps: settings.minFrameRate, ranges: capabilities.frameRates)
      sendExposure(.frameRate(range), name: "frame rate")
    }
  }

  private func sendExposure(_ command: CameraCommand, name: String) {
    exposurePending += 1
    let owner = binding
    camera.perform(command) { [weak self] outcome in
      guard let self, binding == owner else { return }
      exposurePending -= 1
      if outcome.applied { lightChanged(outcome) } else { logger.info("\(name, privacy: .public) refused by the device") }
      onChange?()
    }
  }

  /// Frames before the change's cutoff were lit the old way: the first frame the device reports
  /// exposed with it (an exposure bias), else the acknowledgement's arrival. The cutoff never moves
  /// back, so an earlier frame time cannot undo a later change.
  private func lightChanged(_ outcome: CommandOutcome) {
    lightChangedNs = max(lightChangedNs, outcome.fromNs ?? clock())
  }
}
