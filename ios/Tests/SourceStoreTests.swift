import XCTest
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import UIKit
@testable import ReactNativeDocumentScanner

private final class SourceTestResult: @unchecked Sendable {
  private let lock = NSLock()
  private var values: [SourceStoreError] = []
  func append(_ value: SourceStoreError) { lock.lock(); values.append(value); lock.unlock() }
  var errors: [SourceStoreError] { lock.lock(); defer { lock.unlock() }; return values }
}

final class SourceStoreTests: XCTestCase {
  private let expected: [[UInt8]] = [[20,60,100,140,180,220], [100,60,20,220,180,140],
    [220,180,140,100,60,20], [140,180,220,20,60,100], [20,140,60,180,100,220],
    [140,20,180,60,220,100], [220,100,180,60,140,20], [100,220,60,180,20,140]]

  private func folder() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    addTeardownBlock { try? FileManager.default.removeItem(at: url) }
    return url.resolvingSymlinksInPath()
  }

  private func image() throws -> CGImage {
    // Six unique opaque pixels: A B C / D E F. No compression in the reference.
    let bytes: [UInt8] = [20,30,40,255, 60,70,80,255, 100,110,120,255,
                          140,150,160,255, 180,190,200,255, 220,230,240,255]
    let provider = try XCTUnwrap(CGDataProvider(data: Data(bytes) as CFData))
    return try XCTUnwrap(CGImage(width: 3, height: 2, bitsPerComponent: 8, bitsPerPixel: 32,
      bytesPerRow: 12, space: CGColorSpace(name: CGColorSpace.sRGB)!,
      bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue), provider: provider,
      decode: nil, shouldInterpolate: false, intent: .defaultIntent))
  }

  private func fixture(_ image: CGImage, type: UTType, orientation: Int, folder: URL) throws -> URL {
    let url = folder.appendingPathComponent(UUID().uuidString + "." + type.preferredFilenameExtension!)
    let destination = try XCTUnwrap(CGImageDestinationCreateWithURL(url as CFURL, type.identifier as CFString, 1, nil))
    CGImageDestinationAddImage(destination, image, [kCGImagePropertyOrientation: orientation,
      kCGImageDestinationLossyCompressionQuality: 1] as CFDictionary)
    XCTAssertTrue(CGImageDestinationFinalize(destination))
    return url
  }

  private func red(_ lease: SourcePixelLease) -> [UInt8] {
    lease.withUnsafeBytes { bytes in stride(from: 0, to: bytes.count, by: 4).map { bytes[$0] } }
  }

  func testAllOrientationsPublishUprightWithoutFirstDecode() throws {
    let store = SourceStore(directory: try folder())
    defer { store.shutdown() }
    for orientation in 1...8 {
      let url = try store.publish(image: image(), orientation: orientation)
      let lease = try store.acquire(url)
      XCTAssertEqual(red(lease), expected[orientation - 1], "EXIF \(orientation)")
      XCTAssertEqual(lease.width, orientation >= 5 ? 2 : 3)
      XCTAssertEqual(lease.height, orientation >= 5 ? 3 : 2)
      let header = try SourceImageLoader.inspect(url)
      XCTAssertEqual(header.orientation, 1)
      XCTAssertEqual(header.width, lease.width)
      XCTAssertEqual(header.height, lease.height)
    }
  }

  func testTIFFPNGJPEGHeadersAndAllEXIFOrientations() throws {
    let root = try folder()
    let store = SourceStore(directory: root.appendingPathComponent("owned"))
    defer { store.shutdown() }
    for type in [UTType.tiff, .png, .jpeg] {
      for orientation in 1...8 {
        let url = try fixture(image(), type: type, orientation: orientation, folder: root)
        let header = try SourceImageLoader.inspect(url)
        XCTAssertEqual(header.width, 3)
        XCTAssertEqual(header.height, 2)
        XCTAssertEqual(header.orientation, orientation, "\(type) EXIF \(orientation)")
        let full = try store.acquire(url)
        XCTAssertEqual(full.width, header.uprightWidth)
        XCTAssertEqual(full.height, header.uprightHeight)
        for (actual, expected) in zip(red(full), expected[orientation - 1]) {
          XCTAssertLessThanOrEqual(abs(Int(actual) - Int(expected)), type == .jpeg ? 12 : 0,
                                   "\(type) EXIF \(orientation)")
        }
        let thumbnail = try store.acquire(url, maxDimension: 2)
        XCTAssertLessThanOrEqual(max(thumbnail.width, thumbnail.height), 2)
        store.evict()
      }
    }
  }

  func testResidentIdentityAndReloadedJPEGPixelAccess() throws {
    let store = SourceStore(directory: try folder())
    defer { store.shutdown() }
    let url = try store.publish(image: image(), quality: 1)
    var first: SourcePixelLease? = try store.acquire(url)
    let address = first!.withUnsafeBytes { UInt(bitPattern: $0.baseAddress!) }
    let second = try store.acquire(URL(string: url.absoluteString)!)
    XCTAssertEqual(second.withUnsafeBytes { UInt(bitPattern: $0.baseAddress!) }, address)
    let before = try Data(contentsOf: url)
    store.evict()
    let reloaded = try store.acquire(url)
    XCTAssertNotEqual(reloaded.withUnsafeBytes { UInt(bitPattern: $0.baseAddress!) }, address)
    XCTAssertEqual(reloaded.width, second.width)
    XCTAssertEqual(reloaded.height, second.height)
    for (resident, decoded) in zip(red(second), red(reloaded)) {
      XCTAssertLessThanOrEqual(abs(Int(resident) - Int(decoded)), 12) // provisional JPEG is lossy
    }
    XCTAssertEqual(try Data(contentsOf: url), before)
    first = nil
  }

  func testLiveMemoryLimitCountsSeveralEvictedLeases() throws {
    let store = SourceStore(directory: try folder(), byteLimit: 144) // 24-byte source; 72-byte staging
    defer { store.shutdown() }
    var leases: [SourcePixelLease] = []
    for _ in 0..<3 {
      let url = try store.publish(image: image())
      leases.append(try store.acquire(url))
      XCTAssertLessThanOrEqual(store.budget.liveBytes, 144)
    }
    XCTAssertEqual(store.budget.liveBytes, 72)
    XCTAssertThrowsError(try store.publish(image: image())) { error in
      guard let sourceError = error as? SourceStoreError else { return XCTFail("Expected source error") }
      XCTAssertEqual(sourceError, .memoryLimit)
    }
    XCTAssertEqual(red(leases[0]).first, 20)
    leases.removeFirst()
    store.drain()
    XCTAssertNoThrow(try store.publish(image: image()))
    store.evict()
    leases.removeAll()
    store.drain()
    XCTAssertEqual(store.budget.liveBytes, 0)
  }

  func testMemoryPressureAndShutdownKeepBorrowedPixelsAlive() throws {
    let notifications = NotificationCenter()
    let store = SourceStore(directory: try folder(), notifications: notifications)
    let url = try store.publish(image: image())
    var lease: SourcePixelLease? = try store.acquire(url)
    notifications.post(name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
    XCTAssertEqual(store.budget.liveBytes, 24)
    XCTAssertEqual(red(lease!).first, 20)
    store.shutdown()
    XCTAssertThrowsError(try store.acquire(url)) { error in
      guard let sourceError = error as? SourceStoreError else { return XCTFail("Expected source error") }
      XCTAssertEqual(sourceError, .disposed)
    }
    XCTAssertEqual(red(lease!).first, 20)
    lease = nil
    store.drain()
    XCTAssertEqual(store.budget.liveBytes, 0)
    XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
  }

  func testWholeRequestDeletionRejectsForeignDirectoriesAndLinks() throws {
    let root = try folder()
    let store = SourceStore(directory: root.appendingPathComponent("owned"))
    defer { store.shutdown() }
    let owned = try store.publish(image: image())
    let foreign = try fixture(image(), type: .png, orientation: 1, folder: root)
    _ = try store.acquire(foreign)
    for invalid in [foreign, root, URL(string: "https://example.com/photo.jpg")!] {
      XCTAssertThrowsError(try store.remove([owned, invalid]))
      XCTAssertTrue(FileManager.default.fileExists(atPath: owned.path))
      XCTAssertNoThrow(try store.acquire(owned))
    }
    let link = root.appendingPathComponent("link")
    try FileManager.default.createSymbolicLink(at: link, withDestinationURL: owned)
    XCTAssertThrowsError(try store.remove([link]))
    let aliasDirectory = root.appendingPathComponent("alias")
    try FileManager.default.createSymbolicLink(at: aliasDirectory, withDestinationURL: store.directory)
    XCTAssertThrowsError(try store.remove([aliasDirectory.appendingPathComponent(owned.lastPathComponent)]))
    XCTAssertTrue(FileManager.default.fileExists(atPath: foreign.path))
  }

  func testRetirementDefersDeletionThroughPixelAndCGImageLeases() throws {
    let store = SourceStore(directory: try folder())
    defer { store.shutdown() }
    let url = try store.publish(image: image())
    var lease: SourcePixelLease? = try store.acquire(url)
    var cgImage: CGImage? = try lease!.makeImage()
    try store.remove([url])
    XCTAssertThrowsError(try store.acquire(url))
    lease = nil
    store.drain()
    XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    XCTAssertEqual(cgImage!.width, 3)
    cgImage = nil
    store.drain()
    XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    XCTAssertNoThrow(try store.remove([url, url]))
    XCTAssertEqual(store.budget.liveBytes, 0)
  }

  func testAlreadyRemovedOwnedFileAndReplacedSymlinkAreSafe() throws {
    let root = try folder()
    let store = SourceStore(directory: root.appendingPathComponent("owned"))
    defer { store.shutdown() }
    let url = try store.publish(image: image())
    try FileManager.default.removeItem(at: url)
    XCTAssertNoThrow(try store.remove([url]))
    let replaced = try store.publish(image: image())
    try FileManager.default.removeItem(at: replaced)
    let foreign = try fixture(image(), type: .png, orientation: 1, folder: root)
    let bytes = try Data(contentsOf: foreign)
    try FileManager.default.createSymbolicLink(at: replaced, withDestinationURL: foreign)
    XCTAssertThrowsError(try store.remove([replaced]))
    XCTAssertEqual(try Data(contentsOf: foreign), bytes)
  }

  func testFailedPublicationCleansTemporaryAndReleasesBudget() throws {
    let root = try folder()
    let store = SourceStore(directory: root, writeJPEG: { _, url, _, _ in
      try Data([1,2,3]).write(to: url)
      throw SourceStoreError.writeFailed
    })
    defer { store.shutdown() }
    XCTAssertThrowsError(try store.publish(image: image()))
    XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty)
    XCTAssertEqual(store.budget.liveBytes, 0)
  }

  func testCropPublicationNormalizesDimensionsAndRetainsCaptureMetadata() throws {
    let store = SourceStore(directory: try folder())
    defer { store.shutdown() }
    let metadata: [CFString: Any] = [kCGImagePropertyExifDictionary: [kCGImagePropertyExifExposureTime: 0.01,
      kCGImagePropertyExifFocalLength: 4.5, kCGImagePropertyExifPixelXDimension: 999],
      kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "Test camera", kCGImagePropertyTIFFOrientation: 8]]
    let url = try store.publish(image: image(), orientation: 6,
      crop: CGRect(x: 0, y: 1, width: 2, height: 2), metadata: metadata)
    let lease = try store.acquire(url)
    XCTAssertEqual(lease.width, 2)
    XCTAssertEqual(lease.height, 2)
    XCTAssertEqual(red(lease), [180,60,220,100])
    let header = try SourceImageLoader.inspect(url)
    XCTAssertEqual(header.orientation, 1)
    XCTAssertEqual(header.width, 2)
    let exif = try XCTUnwrap(header.properties[kCGImagePropertyExifDictionary] as? [CFString: Any])
    XCTAssertEqual(exif[kCGImagePropertyExifExposureTime] as? Double, 0.01)
    XCTAssertEqual(exif[kCGImagePropertyExifPixelXDimension] as? Int, 2)
    XCTAssertThrowsError(try store.publish(image: image(), crop: CGRect(x: -1, y: 0, width: 2, height: 2)))
  }

  func testShutdownRejectsQueuedAcquireAndRollsBackRunningPublication() throws {
    let root = try folder()
    let entered = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    let store = SourceStore(directory: root, writeJPEG: { pixels, url, metadata, quality in
      entered.signal()
      release.wait()
      try SourceImageLoader.writeJPEG(pixels, to: url, metadata: metadata, quality: quality)
    })
    let input = try image()
    let errors = SourceTestResult()
    let group = DispatchGroup()
    group.enter()
    DispatchQueue.global().async {
      defer { group.leave() }
      do { _ = try store.publish(image: input) }
      catch { errors.append(error as? SourceStoreError ?? .writeFailed) }
    }
    XCTAssertEqual(entered.wait(timeout: .now() + 5), .success)
    group.enter()
    DispatchQueue.global().async {
      defer { group.leave() }
      do { _ = try store.acquire(root.appendingPathComponent("pending.jpg")) }
      catch { errors.append(error as? SourceStoreError ?? .loadFailed) }
    }
    store.shutdown()
    release.signal()
    XCTAssertEqual(group.wait(timeout: .now() + 5), .success)
    store.drain()
    XCTAssertEqual(errors.errors.count, 2)
    XCTAssertTrue(errors.errors.allSatisfy { $0 == .disposed })
    XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: root.path).isEmpty)
    XCTAssertEqual(store.budget.liveBytes, 0)
  }

  func testRetirementHistoryIsBoundedAndRejectsReplacementFiles() throws {
    let store = SourceStore(directory: try folder())
    defer { store.shutdown() }
    let input = try image()
    var urls: [URL] = []
    for _ in 0..<257 {
      let url = try store.publish(image: input)
      try store.remove([url])
      urls.append(url)
    }
    // A reclaimed URI is no longer recognized as owned. Recent absent URLs
    // remain idempotent without retaining their full source entries/metadata.
    XCTAssertThrowsError(try store.remove([urls[0]])) { error in
      XCTAssertEqual(error as? SourceStoreError, .invalidInput)
    }
    XCTAssertNoThrow(try store.remove([urls[1], urls.last!]))
    let replacement = urls.last!
    let foreignBytes = Data([1, 2, 3])
    try foreignBytes.write(to: replacement)
    let live = try store.publish(image: input)
    XCTAssertThrowsError(try store.remove([live, replacement]))
    XCTAssertNoThrow(try store.acquire(live)) // whole-request validation preserved
    XCTAssertEqual(try Data(contentsOf: replacement), foreignBytes)
    XCTAssertThrowsError(try store.acquire(replacement))
  }

  func testMemoryWarningReturnsBeforeBusySourceWorkerAndEventuallyEvicts() throws {
    let notifications = NotificationCenter()
    let entered = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    let delivered = DispatchSemaphore(value: 0)
    let finished = DispatchGroup()
    let errors = SourceTestResult()
    let store = SourceStore(directory: try folder(), notifications: notifications,
      writeJPEG: { pixels, url, metadata, quality in
        entered.signal()
        release.wait()
        try SourceImageLoader.writeJPEG(pixels, to: url, metadata: metadata, quality: quality)
      })
    defer { store.shutdown() }
    let input = try image()
    finished.enter()
    DispatchQueue.global().async {
      defer { finished.leave() }
      do { _ = try store.publish(image: input) }
      catch { errors.append(error as? SourceStoreError ?? .writeFailed) }
    }
    XCTAssertEqual(entered.wait(timeout: .now() + 5), .success)
    DispatchQueue.global().async {
      notifications.post(name: UIApplication.didReceiveMemoryWarningNotification, object: nil)
      delivered.signal()
    }
    let delivery = delivered.wait(timeout: .now() + 2)
    // Always release the worker, including when a blocking observer regresses.
    release.signal()
    XCTAssertEqual(delivery, .success)
    XCTAssertEqual(finished.wait(timeout: .now() + 5), .success)
    store.drain()
    XCTAssertTrue(errors.errors.isEmpty)
    XCTAssertEqual(store.budget.liveBytes, 0)
  }

  func testConcurrentPublishAcquireEvictAndRetire() throws {
    let store = SourceStore(directory: try folder(), byteLimit: 4096)
    defer { store.shutdown() }
    let input = try image()
    let errors = SourceTestResult()
    DispatchQueue.concurrentPerform(iterations: 20) { _ in
      do {
        let url = try store.publish(image: input)
        let lease = try store.acquire(url)
        XCTAssertEqual(lease.width, 3)
        store.evict()
        try store.remove([url])
        XCTAssertEqual(lease.withUnsafeBytes { $0.count }, 24)
      } catch { errors.append(error as? SourceStoreError ?? .loadFailed) }
    }
    store.drain()
    XCTAssertTrue(errors.errors.isEmpty)
    XCTAssertEqual(store.budget.liveBytes, 0)
  }
}
