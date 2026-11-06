import XCTest
import CoreGraphics
import ImageIO
import CoreVideo
@testable import ReactNativeDocumentScanner

private final class RuntimeProbe: @unchecked Sendable {
  let lock = NSLock()
  private var made = 0
  private var destroyed = 0
  var entered: DispatchSemaphore?
  var proceed: DispatchSemaphore?
  var counts: (Int, Int) { lock.lock(); defer { lock.unlock() }; return (made, destroyed) }
  func create() { lock.lock(); made += 1; lock.unlock() }
  func destroy() { lock.lock(); destroyed += 1; lock.unlock() }
}

private final class FakeCornerRuntime: CornerRuntime {
  let probe: RuntimeProbe
  let inputWidth = 320
  let inputHeight = 192
  init(_ probe: RuntimeProbe) { self.probe = probe; probe.create() }
  deinit { probe.destroy() }
  func analyze(_ source: SourcePixelLease, seed: [Double]?) throws -> CornerPrediction {
    probe.entered?.signal(); probe.proceed?.wait()
    return CornerPrediction(quad: [0,0,1,0,1,1,0,1], score: 0.1, preprocessingMs: 0, inferenceMs: 0)
  }
}

final class CornerInferenceTests: XCTestCase {
  private func source() throws -> (SourceStore, URL) {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let context = try XCTUnwrap(CGContext(data: nil, width: 48, height: 32, bitsPerComponent: 8,
      bytesPerRow: 48 * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    context.setFillColor(CGColor(gray: 0.8, alpha: 1)); context.fill(CGRect(x: 0,y: 0,width: 48,height: 32))
    let store = SourceStore(directory: directory)
    addTeardownBlock { store.shutdown(); store.drain(); try? FileManager.default.removeItem(at: directory) }
    return (store, try store.publish(image: XCTUnwrap(context.makeImage())))
  }

  func testTwoOwnersFailedReplacementAndHistoricalSelection() throws {
    let probe = RuntimeProbe()
    let owner = CornerInference { data in
      if data.first == 0 { throw CornerInferenceError.modelLoadFailed }
      return FakeCornerRuntime(probe)
    }
    let a = owner.acquire(), b = owner.acquire()
    let first = try owner.replace(data: Data([1]))
    XCTAssertEqual(first.inputWidth, 320); XCTAssertEqual(first.inputHeight, 192)
    XCTAssertThrowsError(try owner.replace(data: Data([0])))
    XCTAssertTrue(owner.isCurrent(first))
    let (store, url) = try source()
    XCTAssertEqual(try a.analyzePhoto(store: store, url: url).generation, first.generation)
    let next = try owner.replace(data: Data([2]), expectedGeneration: first.generation)
    XCTAssertFalse(owner.isCurrent(first))
    XCTAssertThrowsError(try owner.replace(data: Data([3]), expectedGeneration: first.generation)) {
      XCTAssertEqual($0 as? CornerInferenceError, .staleSelection)
    }
    XCTAssertTrue(owner.isCurrent(next))
    XCTAssertEqual(try b.analyzePhoto(store: store, url: url, selection: first).generation, first.generation)
    XCTAssertTrue(owner.isCurrent(next)) // A frozen photo never reactivates its model for live work.
    a.close(); a.close(); owner.drain()
    XCTAssertThrowsError(try a.analyzePhoto(store: store, url: url))
    XCTAssertEqual(try b.analyzePhoto(store: store, url: url).generation, next.generation)
    b.close(); owner.drain()
    XCTAssertEqual(probe.counts.0, probe.counts.1)
    let c = owner.acquire()
    XCTAssertEqual(try c.analyzePhoto(store: store, url: url).generation, next.generation)
    c.close(); owner.drain()
    XCTAssertEqual(probe.counts.0, probe.counts.1)
  }

  func testClosingLastOwnerDoesNotReleaseRunningSourceOrInterpreter() throws {
    let probe = RuntimeProbe()
    probe.entered = DispatchSemaphore(value: 0); probe.proceed = DispatchSemaphore(value: 0)
    let owner = CornerInference { _ in FakeCornerRuntime(probe) }
    let lease = owner.acquire()
    try owner.replace(data: Data([1]))
    let (store, url) = try source()
    let done = expectation(description: "running inference drains")
    DispatchQueue.global().async {
      defer { done.fulfill() }
      do { _ = try lease.analyzePhoto(store: store, url: url) }
      catch { XCTFail("Running inference failed: \(error)") }
    }
    XCTAssertEqual(probe.entered!.wait(timeout: .now() + 3), .success)
    lease.close() // Must return while inference is blocked.
    try store.remove([url]); store.evict()
    XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    XCTAssertEqual(probe.counts.1, 0)
    probe.proceed!.signal()
    wait(for: [done], timeout: 5)
    owner.drain(); store.drain()
    XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    XCTAssertEqual(probe.counts.0, probe.counts.1)
  }

  private func model() throws -> Data {
    let owners = [Bundle(for: HybridCameraPermission.self), Bundle.main] + Bundle.allBundles + Bundle.allFrameworks
    let url = try XCTUnwrap(owners.compactMap { $0.url(forResource: "DocumentScannerModels", withExtension: "bundle") }.first)
    return try Data(contentsOf: XCTUnwrap(Bundle(url: url)?.url(forResource: "model", withExtension: "tflite")))
  }

  func testNativeModelFailurePreservesSelectionAndResidentReloadAgreement() throws {
    let owner = CornerInference(), lease = owner.acquire()
    defer { lease.close(); owner.drain() }
    let selection = try owner.replace(data: model())
    XCTAssertEqual(selection.inputWidth, 224); XCTAssertEqual(selection.inputHeight, 224)
    XCTAssertThrowsError(try owner.replace(data: Data("invalid".utf8)))
    XCTAssertTrue(owner.isCurrent(selection))
    let (store, url) = try source()
    let first = try lease.analyzePhoto(store: store, url: url)
    store.evict()
    let second = try lease.analyzePhoto(store: store, url: url)
    XCTAssertEqual(first.width, 48); XCTAssertEqual(second.height, 32)
    XCTAssertEqual(first.prediction.score, second.prediction.score, accuracy: 0.005)
    if let a = first.prediction.quad, let b = second.prediction.quad {
      for (x,y) in zip(a,b) { XCTAssertEqual(x,y,accuracy: 0.015) }
    } else { XCTAssertEqual(first.prediction.quad == nil, second.prediction.quad == nil) }
  }

  private func fixtureModel(_ name: String) throws -> Data {
    let bundle = Bundle(for: CornerInferenceTests.self)
    return try Data(contentsOf: XCTUnwrap(bundle.url(forResource: name, withExtension: "tflite")))
  }

  func testExactNativeTensorValidationWarmupAndSeedMapping() throws {
    let owner = CornerInference(), lease = owner.acquire()
    defer { lease.close(); owner.drain() }
    let selection = try owner.replace(data: fixtureModel("rectangular"))
    XCTAssertEqual(selection.inputWidth, 320); XCTAssertEqual(selection.inputHeight, 192)
    for name in ["bad-output", "two-inputs", "byte-input", "nonfinite-warmup"] {
      XCTAssertThrowsError(try owner.replace(data: fixtureModel(name)), name)
      XCTAssertTrue(owner.isCurrent(selection), name)
    }
    let (store, url) = try source()
    let unseeded = try lease.analyzePhoto(store: store, url: url)
    let seed = [0.3,0.3,0.7,0.3,0.7,0.7,0.3,0.7]
    let seeded = try lease.analyzePhoto(store: store, url: url, seed: seed)
    let actual = try XCTUnwrap(seeded.prediction.quad)
    let raw = try XCTUnwrap(unseeded.prediction.quad)
    // Independent expected crop: 48x32 source -> bounds (12,4)-(36,28).
    for i in 0..<4 {
      XCTAssertEqual(actual[2*i], (12 + raw[2*i]*24)/48, accuracy: 1e-7)
      XCTAssertEqual(actual[2*i+1], (4 + raw[2*i+1]*24)/32, accuracy: 1e-7)
    }
    XCTAssertEqual(seeded.prediction.score, 0.1, accuracy: 1e-7)
    let invalid = try lease.analyzePhoto(store: store, url: url, seed: [0,0,0,0,0,0,0,0])
    XCTAssertEqual(invalid.prediction.quad, unseeded.prediction.quad)
    XCTAssertThrowsError(try lease.analyzePhoto(store: store, url: url, seed: [0]))
    XCTAssertNoThrow(try lease.analyzePhoto(store: store, url: url))
  }

  func testLiveBufferLeaseUnlocksAndReleasesOnSuccessAndFailure() throws {
    let owner = CornerInference(), lease = owner.acquire(), probe = RuntimeProbe()
    try owner.replace(data: fixtureModel("rectangular"))
    defer { lease.close(); owner.drain() }
    let bytes = UnsafeMutableRawPointer.allocate(byteCount: 48*32*4, alignment: 16)
    bytes.initializeMemory(as: UInt8.self, repeating: 255, count: 48*32*4)
    var buffer: CVPixelBuffer?
    let token = Unmanaged.passRetained(probe).toOpaque()
    let status = CVPixelBufferCreateWithBytes(kCFAllocatorDefault, 48, 32, kCVPixelFormatType_32BGRA,
      bytes, 48*4, { context, address in
        UnsafeMutableRawPointer(mutating: address)!.deallocate()
        Unmanaged<RuntimeProbe>.fromOpaque(context!).takeRetainedValue().destroy()
      }, token, nil, &buffer)
    guard status == kCVReturnSuccess else {
      bytes.deallocate(); Unmanaged<RuntimeProbe>.fromOpaque(token).release()
      XCTFail("Cannot create synthetic live buffer"); return
    }
    let first = try lease.analyzeLive(XCTUnwrap(buffer), orientation: 6,
      crop: CGRect(x: 8,y: 4,width: 24,height: 16))
    XCTAssertEqual(first.width, 16); XCTAssertEqual(first.height, 24)
    XCTAssertNotNil(first.prediction.quad)
    XCTAssertThrowsError(try lease.analyzeLive(XCTUnwrap(buffer), orientation: 0))
    XCTAssertThrowsError(try lease.analyzeLive(XCTUnwrap(buffer), crop: CGRect(x: -1,y: 0,width: 4,height: 4)))
    // Force preprocessing to throw after locking, then recover with the same buffer.
    XCTAssertEqual(CVPixelBufferLockBaseAddress(buffer!, []), kCVReturnSuccess)
    bytes.storeBytes(of: UInt8(0), toByteOffset: 3, as: UInt8.self)
    CVPixelBufferUnlockBaseAddress(buffer!, [])
    XCTAssertThrowsError(try lease.analyzeLive(XCTUnwrap(buffer)))
    XCTAssertEqual(CVPixelBufferLockBaseAddress(buffer!, []), kCVReturnSuccess)
    bytes.storeBytes(of: UInt8(255), toByteOffset: 3, as: UInt8.self)
    CVPixelBufferUnlockBaseAddress(buffer!, [])
    XCTAssertNoThrow(try lease.analyzeLive(XCTUnwrap(buffer)))
    buffer = nil
    XCTAssertEqual(probe.counts.1, 1) // No runtime/result retained the caller's capture buffer.
  }

  func testPrivateTensorAndImageParity() throws {
    guard let directory = ProcessInfo.processInfo.environment["DOCUMENT_SCANNER_INFERENCE_FIXTURES"] else {
      throw XCTSkip("Private photos/tensors not supplied; set DOCUMENT_SCANNER_INFERENCE_FIXTURES")
    }
    let root = URL(fileURLWithPath: directory)
    let references = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("references.json"))) as? [String: Any])
    let rows = try XCTUnwrap(references["photos"] as? [[String: Any]])
    let data = try model(), runtime = try ScannerCornerRuntime(model: data)
    let owner = CornerInference(), lease = owner.acquire()
    try owner.replace(data: data)
    defer { lease.close(); owner.drain() }
    let store = SourceStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
    defer { store.shutdown(); store.drain(); try? FileManager.default.removeItem(at: store.directory) }
    var maximumTensor = 0.0, maximumImage = 0.0, sumImage = 0.0, corners = 0
    var reloadWorst = 0.0, reloadSum = 0.0, reloadCorners = 0
    for row in rows {
      try autoreleasepool {
        let name = try XCTUnwrap(row["name"] as? String)
        let expected = try XCTUnwrap(row["tensorflow"] as? [Double])
        let tensor = try Data(contentsOf: root.appendingPathComponent(name + ".input.f32"))
        let output = try runtime.predictTensor(tensor)
        let quad = try XCTUnwrap(output.quad).map(\.doubleValue)
        for (actual, wanted) in zip(quad + [output.score], expected) {
          maximumTensor = max(maximumTensor, abs(actual-wanted))
          XCTAssertEqual(actual, wanted, accuracy: 2e-5, name)
        }
        for suffix in [".png", ".jpg"] {
          let url = root.appendingPathComponent(name + suffix)
          let result = try lease.analyzePhoto(store: store, url: url)
          let predicted = try XCTUnwrap(result.prediction.quad, name + suffix)
          for index in 0..<4 {
            let error = hypot(predicted[index*2]-expected[index*2], predicted[index*2+1]-expected[index*2+1])
            maximumImage = max(maximumImage,error); sumImage += error; corners += 1
            XCTAssertLessThanOrEqual(error, 0.015, name + suffix)
          }
          store.evict()
        }
        let imageSource = try XCTUnwrap(CGImageSourceCreateWithURL(root.appendingPathComponent(name + ".png") as CFURL, nil))
        let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(imageSource, 0, nil))
        let owned = try store.publish(image: image)
        let resident = try XCTUnwrap(lease.analyzePhoto(store: store, url: owned).prediction.quad)
        store.evict()
        let reloaded = try XCTUnwrap(lease.analyzePhoto(store: store, url: owned).prediction.quad)
        for index in 0..<4 {
          let error = hypot(resident[2*index]-reloaded[2*index], resident[2*index+1]-reloaded[2*index+1])
          reloadWorst = max(reloadWorst, error); reloadSum += error; reloadCorners += 1
          XCTAssertLessThanOrEqual(error, 0.015, name + " resident/reloaded")
        }
        try store.remove([owned]); store.evict()
      }
    }
    XCTAssertGreaterThan(corners, 0)
    XCTAssertLessThanOrEqual(reloadSum / Double(reloadCorners), 0.005)
    XCTAssertLessThanOrEqual(sumImage / Double(corners), 0.005)
    print("Task20 parity: photos=\(rows.count), tensorMax=\(maximumTensor), imageMean=\(sumImage / Double(corners)), imageWorst=\(maximumImage), reloadMean=\(reloadSum / Double(reloadCorners)), reloadWorst=\(reloadWorst)")
  }
}
