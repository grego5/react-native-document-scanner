import Foundation
import CoreVideo
import CoreGraphics

struct CornerPrediction: Sendable, Equatable {
  // Normalized upright TL/TR/BR/BL. Scores are returned unchanged; live policy owns gating.
  let quad: [Double]?
  let score: Double
  let preprocessingMs: Double
  let inferenceMs: Double
}

struct CornerAnalysis: Sendable {
  let width: Int
  let height: Int
  let generation: UInt64
  let prediction: CornerPrediction
}

enum CornerInferenceError: Error, Equatable {
  case modelUnavailable, modelLoadFailed, inferenceFailed, disposed, staleSelection
}

/// Factory seam exercises interpreter failure/lifetime without a second production runtime.
protocol CornerRuntime: AnyObject {
  var inputWidth: Int { get }
  var inputHeight: Int { get }
  func analyze(_ source: SourcePixelLease, seed: [Double]?) throws -> CornerPrediction
  func analyzeLive(_ buffer: CVPixelBuffer, orientation: UInt8, crop: CGRect) throws -> CornerPrediction
}

extension CornerRuntime {
  func analyzeLive(_ buffer: CVPixelBuffer, orientation: UInt8, crop: CGRect) throws -> CornerPrediction {
    throw CornerInferenceError.inferenceFailed
  }
}

private final class NativeCornerRuntime: CornerRuntime {
  private let runtime: ScannerCornerRuntime
  var inputWidth: Int { Int(runtime.inputWidth) }
  var inputHeight: Int { Int(runtime.inputHeight) }
  init(data: Data) throws { runtime = try ScannerCornerRuntime(model: data) }
  func analyzeLive(_ buffer: CVPixelBuffer, orientation: UInt8, crop: CGRect) throws -> CornerPrediction {
    let value = try runtime.analyze(buffer, orientation: orientation, crop: crop)
    return CornerPrediction(quad: value.quad?.map(\.doubleValue), score: value.score,
                            preprocessingMs: value.preprocessingMs, inferenceMs: value.inferenceMs)
  }
  func analyze(_ source: SourcePixelLease, seed: [Double]?) throws -> CornerPrediction {
    try source.withUnsafeBytes { bytes in
      let value = try runtime.analyzeOwner(source, bytes: bytes.baseAddress!, byteCount: UInt(bytes.count),
        width: UInt32(source.width), height: UInt32(source.height), rowStride: UInt(source.rowStride),
        seed: seed?.map { NSNumber(value: $0) })
      return CornerPrediction(quad: value.quad?.map(\.doubleValue), score: value.score,
                              preprocessingMs: value.preprocessingMs, inferenceMs: value.inferenceMs)
    }
  }
}

/// One serial CPU interpreter for live and photo owners. All model/runtime/users state
/// is queue-confined. Calls are synchronous for native background workers so a source
/// lease cannot be returned while inference still borrows its pixels.
final class CornerInference: @unchecked Sendable {
  static let shared = CornerInference()

  final class Selection: Sendable {
    fileprivate let identity = UUID()
    fileprivate let data: Data
    let generation: UInt64
    let inputWidth: Int
    let inputHeight: Int
    fileprivate init(data: Data, generation: UInt64, width: Int, height: Int) {
      self.data = data; self.generation = generation; inputWidth = width; inputHeight = height
    }
  }

  private let queue = DispatchQueue(label: "document-scanner.inference")
  private let makeRuntime: @Sendable (Data) throws -> any CornerRuntime
  private var users = Set<UUID>()
  private var selected: Selection?
  private var generation: UInt64 = 0
  private var runtime: (UUID, any CornerRuntime)?

  init(makeRuntime: @escaping @Sendable (Data) throws -> any CornerRuntime = { try NativeCornerRuntime(data: $0) }) {
    self.makeRuntime = makeRuntime
  }

  /// Callers freeze this snapshot at photo admission. It owns model bytes, never runtime handles.
  func selection() throws -> Selection {
    try queue.sync {
      guard let selected else { throw CornerInferenceError.modelUnavailable }
      return selected
    }
  }

  /// Read/copy model data on the caller's background worker. Validation, warmup and
  /// publication serialize here. An outdated replacement request never publishes: `admit` runs on
  /// the inference queue with the validated selection immediately before it becomes current, and
  /// returning false leaves the current selection untouched and throws `staleSelection`.
  @discardableResult
  func replace(data: Data, expectedGeneration: UInt64? = nil,
               admit: ((Selection) -> Bool)? = nil) throws -> Selection {
    try queue.sync {
      if let expectedGeneration, expectedGeneration != generation { throw CornerInferenceError.staleSelection }
      guard !data.isEmpty, data.count <= 128 * 1024 * 1024 else { throw CornerInferenceError.modelLoadFailed }
      let owned = data.withUnsafeBytes { Data($0) }
      let candidate: any CornerRuntime
      do { candidate = try makeRuntime(owned) }
      catch { throw CornerInferenceError.modelLoadFailed }
      let next = Selection(data: owned, generation: generation + 1,
                           width: candidate.inputWidth, height: candidate.inputHeight)
      if let admit, !admit(next) { throw CornerInferenceError.staleSelection }
      generation += 1
      selected = next
      runtime = users.isEmpty ? nil : (next.identity, candidate)
      return next
    }
  }

  /// Live callers must compare the frozen generation before publishing/resetting guidance.
  /// A historical photo selection remains usable without reactivating it for live callers.
  func isCurrent(_ snapshot: Selection) -> Bool {
    queue.sync { selected === snapshot }
  }

  func acquire() -> Lease {
    let id = UUID()
    queue.sync { _ = users.insert(id) }
    return Lease(owner: self, id: id)
  }

  private func analyze(id: UUID, source: SourcePixelLease, seed: [Double]?, selection: Selection?) throws -> CornerAnalysis {
    try queue.sync {
      guard users.contains(id) else { throw CornerInferenceError.disposed }
      guard let selection = selection ?? selected else { throw CornerInferenceError.modelUnavailable }
      let model = try open(selection)
      let result: CornerPrediction
      do { result = try model.analyze(source, seed: seed) }
      catch { throw CornerInferenceError.inferenceFailed }
      return CornerAnalysis(width: source.width, height: source.height,
                                 generation: selection.generation, prediction: result)
    }
  }

  // Called only on queue. Historical photos can swap the workspace without
  // publishing their frozen selection as the current live model.
  private func open(_ selection: Selection) throws -> any CornerRuntime {
    if runtime?.0 != selection.identity {
      runtime = nil
      do { runtime = (selection.identity, try makeRuntime(selection.data)) }
      catch { throw CornerInferenceError.modelLoadFailed }
    }
    return runtime!.1
  }

  private func analyzeLive(id: UUID, buffer: CVPixelBuffer, orientation: UInt8, crop: CGRect) throws -> CornerAnalysis {
    try queue.sync {
      guard users.contains(id) else { throw CornerInferenceError.disposed }
      guard let selected else { throw CornerInferenceError.modelUnavailable }
      let model = try open(selected)
      let result: CornerPrediction
      do { result = try model.analyzeLive(buffer, orientation: orientation, crop: crop) }
      catch { throw CornerInferenceError.inferenceFailed }
      let swapped = orientation >= 5
      return CornerAnalysis(width: Int(swapped ? crop.height : crop.width),
                            height: Int(swapped ? crop.width : crop.height),
                            generation: selected.generation, prediction: result)
    }
  }

  private func prepare(id: UUID) throws {
    try queue.sync {
      guard users.contains(id) else { throw CornerInferenceError.disposed }
      guard let selected else { throw CornerInferenceError.modelUnavailable }
      _ = try open(selected)
    }
  }

  /// Joins already-enqueued owner releases, useful at a containing session's cleanup boundary.
  func drain() { queue.sync {} }

  final class Lease: @unchecked Sendable {
    private let owner: CornerInference
    private let id: UUID
    private let lock = NSLock()
    private var closed = false
    fileprivate init(owner: CornerInference, id: UUID) { self.owner = owner; self.id = id }
    deinit { close() }
    func analyze(_ source: SourcePixelLease, seed: [Double]? = nil, selection: Selection? = nil) throws -> CornerAnalysis {
      lock.lock(); let disposed = closed; lock.unlock()
      guard !disposed else { throw CornerInferenceError.disposed }
      return try owner.analyze(id: id, source: source, seed: seed, selection: selection)
    }
    /// The caller retains its capture buffer until this synchronous method returns.
    /// Crop is encoded-pixel coordinates; output is normalized to that upright region.
    func analyzeLive(_ buffer: CVPixelBuffer, orientation: UInt8 = 1, crop: CGRect? = nil) throws -> CornerAnalysis {
      lock.lock(); let disposed = closed; lock.unlock()
      guard !disposed else { throw CornerInferenceError.disposed }
      let region = crop ?? CGRect(x: 0, y: 0, width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer))
      return try owner.analyzeLive(id: id, buffer: buffer, orientation: orientation, crop: region)
    }
    /// Opens the selected model's interpreter ahead of the first live frame.
    func prepare() throws {
      lock.lock(); let disposed = closed; lock.unlock()
      guard !disposed else { throw CornerInferenceError.disposed }
      try owner.prepare(id: id)
    }
    /// Acquire before inference admission. No inference-queue -> source-queue cycle exists:
    /// the store's queue is released before the inference worker borrows the lease.
    func analyzePhoto(store: SourceStore = .shared, url: URL, seed: [Double]? = nil,
                      selection: Selection? = nil) throws -> CornerAnalysis {
      lock.lock(); let disposed = closed; lock.unlock()
      guard !disposed else { throw CornerInferenceError.disposed }
      let source = try store.acquire(url)
      return try analyze(source, seed: seed, selection: selection)
    }
    /// Nonblocking close. Running inference finishes before the last owner drops runtime.
    func close() {
      lock.lock()
      if closed { lock.unlock(); return }
      closed = true; lock.unlock()
      let owner = self.owner, id = self.id
      owner.queue.async {
        owner.users.remove(id)
        if owner.users.isEmpty { owner.runtime = nil }
      }
    }
  }
}
