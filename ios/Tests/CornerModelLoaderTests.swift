import XCTest
@testable import ReactNativeDocumentScanner

private final class StatusRuntime: CornerRuntime {
  let inputWidth = 224
  let inputHeight = 224
  func analyze(_ source: SourcePixelLease, seed: [Double]?) throws -> CornerPrediction {
    CornerPrediction(corners: [0, 0, 1, 0, 1, 1, 0, 1], score: 0, preprocessingMs: 0, inferenceMs: 0)
  }
}

final class CornerModelLoaderTests: XCTestCase {
  private var folder: URL!

  override func setUpWithError() throws {
    folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
  }

  override func tearDown() { try? FileManager.default.removeItem(at: folder) }

  private func model(_ name: String, _ byte: UInt8 = 1) throws -> String {
    let url = folder.appendingPathComponent(name)
    try Data([byte]).write(to: url)
    return url.path
  }

  private let entered = DispatchSemaphore(value: 0)
  private let release = DispatchSemaphore(value: 0)

  /// Rejects models whose first byte is 0xF and holds validation of 0x2 until `release`.
  private func loader(defaultSource: String = CornerModelLoader.defaultSource) -> CornerModelLoader {
    let entered = entered, release = release
    return CornerModelLoader(inference: CornerInference(makeRuntime: { data in
      guard data.first != 0xF else { throw CornerInferenceError.modelLoadFailed }
      if data.first == 2 { entered.signal(); _ = release.wait(timeout: .now() + 5) }
      return StatusRuntime()
    }), defaultSource: defaultSource)
  }

  private func waited(_ loader: CornerModelLoader) -> Result<Bool, ScannerCodeError> {
    let done = expectation(description: "waited")
    var result: Result<Bool, ScannerCodeError>?
    loader.wait { result = $0; done.fulfill() }
    wait(for: [done], timeout: 5)
    return result!
  }

  /// An explicit `source`, or with nil a camera without a source, which follows the loader's configured default.
  private func loaded(_ loader: CornerModelLoader, _ source: String?) -> ScannerErrorCode? {
    let done = expectation(description: "loaded")
    var code: ScannerErrorCode?
    if let source { loader.load(source) { code = $0; done.fulfill() } }
    else { loader.loadCameraSource("") { code = $0; done.fulfill() } }
    wait(for: [done], timeout: 5)
    return code
  }

  func testWithoutARequestTheModelIsAbsent() {
    XCTAssertEqual(try waited(loader()).get(), false)
  }

  func testTheDefaultLoadsOnceAndLaterRequestsJoinIt() throws {
    let source = try model("default.tflite"), loader = loader(defaultSource: source)
    var statuses: [String] = []
    let remove = loader.observe { status in
      switch status {
      case .absent: statuses.append("absent")
      case .loading: statuses.append("loading")
      case .ready: statuses.append("ready")
      case .failed: statuses.append("failed")
      }
    }
    loader.startDefault()
    loader.startDefault()
    XCTAssertNil(loaded(loader, nil))
    XCTAssertEqual(try waited(loader).get(), true)
    XCTAssertNil(loaded(loader, source))
    remove()
    remove()
    XCTAssertEqual(statuses, ["absent", "loading", "ready"], "joined requests publish nothing new")
  }

  func testAPendingDefaultFollowsTheExplicitReplacementWhichTheDefaultNeverReplaces() throws {
    let fallback = try model("default.tflite", 2), custom = try model("custom.tflite")
    let loader = loader(defaultSource: fallback)
    let followed = expectation(description: "followed")
    var defaultCode: ScannerErrorCode? = .inputInvalid, waitResult: Result<Bool, ScannerCodeError>?
    loader.loadCameraSource("") { defaultCode = $0; followed.fulfill() }
    XCTAssertEqual(entered.wait(timeout: .now() + 5), .success, "the default is validating")
    loader.wait { waitResult = $0 }
    let explicit = expectation(description: "explicit")
    var explicitCode: ScannerErrorCode? = .inputInvalid
    loader.load(custom) { explicitCode = $0; explicit.fulfill() }
    release.signal()
    wait(for: [followed, explicit], timeout: 5)
    XCTAssertNil(defaultCode, "the default reports the replacement's outcome")
    XCTAssertNil(explicitCode)
    guard case .failure(let error) = waitResult else { return XCTFail("The wait was superseded.") }
    XCTAssertEqual(error.code, .modelSuperseded)
    XCTAssertNil(loaded(loader, nil))
    guard case .ready(let source, _) = loader.status else { return XCTFail("Ready.") }
    XCTAssertEqual(source, custom)
  }

  func testFailuresAreTypedAndOnlyAnExplicitRequestRetries() throws {
    let broken = try model("broken.tflite", 0xF), loader = loader(defaultSource: try model("default.tflite"))
    XCTAssertEqual(loaded(loader, broken), .modelLoadFailed)
    guard case .failed(let source, .modelLoadFailed) = loader.status else { return XCTFail("Failed.") }
    XCTAssertEqual(source, broken)
    XCTAssertThrowsError(try waited(loader).get()) { XCTAssertEqual(($0 as? ScannerCodeError)?.code, .modelLoadFailed) }
    XCTAssertEqual(loaded(loader, nil), .modelLoadFailed,
                   "the default joins the failed explicit request")
    try Data([1]).write(to: URL(fileURLWithPath: broken))
    XCTAssertNil(loaded(loader, broken))
    XCTAssertEqual(try waited(loader).get(), true)
  }

  func testAListenerCanRequestAReplacementWithoutDeadlockAndSeesTheLatestStatus() throws {
    let fallback = try model("default.tflite"), custom = try model("custom.tflite")
    let loader = loader(defaultSource: fallback)
    let ready = expectation(description: "replacement ready")
    var inside = false, last: String?
    let remove = loader.observe { status in
      XCTAssertFalse(inside, "calls never overlap")
      inside = true
      defer { inside = false }
      switch status {
      case .loading(let source) where source == fallback: loader.load(custom) { _ in }
      case .ready(let source, _): last = source; if source == custom { ready.fulfill() }
      default: break
      }
    }
    loader.startDefault()
    wait(for: [ready], timeout: 5)
    remove()
    XCTAssertEqual(last, custom)
  }
}
