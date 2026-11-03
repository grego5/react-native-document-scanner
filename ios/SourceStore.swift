import Foundation
import CoreGraphics
import UIKit

/// File and pixel pin. Native consumers borrow bytes only inside withUnsafeBytes;
/// an ObjC++ consumer passes this NSObject as ScannerBorrowSourcePixels' owner.
final class SourcePixelLease: NSObject, @unchecked Sendable {
  let url: URL
  let header: SourceImageHeader
  private let pixels: SourcePixels
  private let releasePin: () -> Void
  var width: Int { pixels.width }
  var height: Int { pixels.height }
  var rowStride: Int { pixels.rowStride }

  fileprivate init(url: URL, header: SourceImageHeader, pixels: SourcePixels, release: @escaping () -> Void) {
    self.url = url; self.header = header; self.pixels = pixels; self.releasePin = release
  }
  deinit { releasePin() }

  func withUnsafeBytes<T>(_ operation: (UnsafeRawBufferPointer) throws -> T) rethrows -> T {
    try withExtendedLifetime(self) { try pixels.withUnsafeBytes(operation) }
  }
  func makeImage() throws -> CGImage { try pixels.makeImage(owner: self) }
}

/// One process-wide owner, never owned by a camera/view/processor. Sessions retain
/// resource URIs internally and retire them here; no source path enters JS metadata.
/// Entries/file pins, publication, decoding and eviction are confined to queue.
/// The independently locked budget accounts bytes until their last native owner dies.
final class SourceStore: @unchecked Sendable {
  static let shared: SourceStore = {
    let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    return SourceStore(directory: base.appendingPathComponent("DocumentScannerSources"))
  }()

  private final class Entry: @unchecked Sendable {
    let url: URL
    let header: SourceImageHeader
    let owned: Bool
    var pins = 0
    var retired = false
    // Retirement observers, notified on queue after the final pin's removal attempt.
    var waiters: [(Error?) -> Void] = []
    init(url: URL, header: SourceImageHeader, owned: Bool) {
      self.url = url; self.header = header; self.owned = owned
    }
  }

  private let queue = DispatchQueue(label: "document-scanner.sources")
  private let stateLock = NSLock()
  private var closed = false
  let directory: URL
  let budget: SourceMemoryBudget
  private let notifications: NotificationCenter
  private var memoryObserver: NSObjectProtocol?
  private let writeJPEG: (SourcePixels, URL, [CFString: Any], Double) throws -> Void
  private var entries: [URL: Entry] = [:]
  // Only the most recent 256 completed retirements support idempotent remove.
  // Older URLs lose ownership recognition and are rejected, never deleted.
  private var retiredURLs: [URL] = []
  private static let retirementHistoryLimit = 256
  private var resident: (URL, SourcePixels)?
  // One reduced thumbnail, with the same lease/accounting rules. No unbounded URI cache.
  private var thumbnail: (URL, Int, SourcePixels)?

  init(directory: URL, byteLimit: Int = 256 * 1024 * 1024,
       notifications: NotificationCenter = .default,
       writeJPEG: @escaping (SourcePixels, URL, [CFString: Any], Double) throws -> Void = SourceImageLoader.writeJPEG) {
    self.directory = directory.standardizedFileURL.resolvingSymlinksInPath()
    budget = SourceMemoryBudget(limit: byteLimit)
    self.notifications = notifications
    self.writeJPEG = writeJPEG
    memoryObserver = notifications.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification,
                                               object: nil, queue: nil) { [weak self] _ in
      self?.enqueueEviction()
    }
  }

  deinit { if let memoryObserver { notifications.removeObserver(memoryObserver) } }

  private func whileOpen<T>(_ operation: () throws -> T) throws -> T {
    stateLock.lock(); defer { stateLock.unlock() }
    if closed { throw SourceStoreError.disposed }
    return try operation()
  }

  private func requireOpen() throws { try whileOpen {} }

  /// Copies borrowed capture pixels once, normalizes EXIF/crop, then publishes a
  /// provisional JPEG atomically. The first acquire reuses those original pixels.
  /// This synchronous API belongs on the native image/capture worker, never main.
  func publish(image: CGImage, orientation: Int = 1, crop: CGRect? = nil,
               metadata: [CFString: Any] = [:], quality: Double = 0.95) throws -> URL {
    try queue.sync {
      try requireOpen()
      try SourceImageLoader.validateSize(image.width, image.height)
      guard (1...8).contains(orientation), image.bitsPerPixel <= 64 else { throw SourceStoreError.invalidInput }
      evictOnQueue()
      let rgbaBytes = image.width * image.height * 4
      // Reserve codec/image staging conservatively (up to 64-bit decoded input,
      // plus an encoding image) before admission; no other operation runs here.
      let staging = try budget.reserve(rgbaBytes * 3)
      return try withExtendedLifetime(staging) {
        let pixels = try SourcePixels(image: image, orientation: orientation, crop: crop,
                                      ticket: budget.reserve(rgbaBytes))
        do { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true) }
        catch { throw SourceStoreError.writeFailed }
        // Refuse a directory replaced by a symlink after this store was created.
        guard directory.path == directory.resolvingSymlinksInPath().path else { throw SourceStoreError.invalidInput }
        let id = UUID().uuidString
        let temporary = directory.appendingPathComponent(id + ".partial")
        let destination = directory.appendingPathComponent(id + ".jpg")
        var published = false
        defer {
          try? FileManager.default.removeItem(at: temporary)
          if !published { try? FileManager.default.removeItem(at: destination) }
        }
        do {
          try writeJPEG(pixels, temporary, metadata, quality)
          try requireOpen()
          try FileManager.default.moveItem(at: temporary, to: destination)
          try requireOpen()
          let header = try SourceImageLoader.inspect(destination)
          return try whileOpen {
            entries[destination] = Entry(url: destination, header: header, owned: true)
            resident = (destination, pixels)
            published = true
            return destination
          }
        } catch let error as SourceStoreError { throw error }
        catch { throw SourceStoreError.writeFailed }
      }
    }
  }

  /// Fail-fast admission: no semaphore wait/deadlock while another operation owns
  /// a lease. Retry after releasing it. Shutdown rejects queued sync acquires.
  /// maxDimension requests the reduced thumbnail slot (at most 512 pixels).
  func acquire(_ url: URL, maxDimension: Int? = nil) throws -> SourcePixelLease {
    try queue.sync {
      try requireOpen()
      let key = try canonical(url)
      if let maxDimension, !(1...512).contains(maxDimension) { throw SourceStoreError.invalidInput }
      let entry: Entry
      if let existing = entries[key] {
        guard !existing.retired else { throw SourceStoreError.invalidInput }
        entry = existing
      } else {
        guard !retiredURLs.contains(key) else { throw SourceStoreError.invalidInput }
        entry = Entry(url: key, header: try SourceImageLoader.inspect(key), owned: false)
        entries[key] = entry
      }
      let pixels: SourcePixels
      if maxDimension == nil, let cached = resident, cached.0 == key {
        pixels = cached.1
      } else if let size = maxDimension, let cached = thumbnail, cached.0 == key, cached.1 == size {
        pixels = cached.2
      } else if let size = maxDimension, let cached = resident, cached.0 == key {
        thumbnail = nil
        let scale = min(1, Double(size) / Double(max(cached.1.width, cached.1.height)))
        let count = max(1, Int(floor(Double(cached.1.width) * scale)))
          * max(1, Int(floor(Double(cached.1.height) * scale))) * 4
        let staging = try budget.reserve(count * 3)
        pixels = try withExtendedLifetime(staging) {
          try SourcePixels(image: cached.1.makeImage(), orientation: 1, crop: nil, maxDimension: size,
                           ticket: budget.reserve(count))
        }
        thumbnail = (key, size, pixels)
      } else {
        if maxDimension == nil { resident = nil } else { thumbnail = nil }
        // ImageIO private decoder allocations are transient; reserve their pixel
        // staging too, including full input dimensions for TIFF reduced reads.
        let staging = try budget.reserve(entry.header.rgbaBytes * 3)
        pixels = try withExtendedLifetime(staging) {
          let count: Int
          if let size = maxDimension {
            let scale = min(1, Double(size) / Double(max(entry.header.width, entry.header.height)))
            count = max(1, Int(ceil(Double(entry.header.width) * scale)))
              * max(1, Int(ceil(Double(entry.header.height) * scale))) * 4
          } else { count = entry.header.rgbaBytes }
          return try SourceImageLoader.decode(key, header: entry.header, maxDimension: maxDimension,
                                               ticket: budget.reserve(count))
        }
        try requireOpen()
        if let size = maxDimension { thumbnail = (key, size, pixels) }
        else { resident = (key, pixels) }
      }
      return try whileOpen {
        entry.pins += 1
        return SourcePixelLease(url: key, header: entry.header, pixels: pixels) { [self] in
          queue.async { [self] in
            entry.pins -= 1
            if entry.retired { removeReporting(entry) }
          }
        }
      }
    }
  }

  private func canonical(_ url: URL) throws -> URL {
    guard url.isFileURL, url.host == nil || url.host == "" || url.host == "localhost",
          url.query == nil, url.fragment == nil else { throw SourceStoreError.invalidInput }
    return url.standardizedFileURL.resolvingSymlinksInPath()
  }

  /// Validate the ENTIRE request before retirement. Only files this store actually
  /// published are removable, never host inputs or unregistered lookalike names.
  /// Retired entries reject new acquisitions; a file remains until its last pin.
  func remove(_ urls: [URL]) throws {
    try queue.sync {
      try requireOpen()
      let request: [Entry] = try urls.compactMap { url in
        let key = try canonical(url)
        guard key == url.standardizedFileURL else { throw SourceStoreError.invalidInput }
        if let entry = entries[key], entry.owned {
          _ = try regularFileExists(key)
          return entry
        }
        // A tombstone recognizes only an absent file. A replacement at the same
        // path belongs to somebody else and must never be removed by this store.
        guard retiredURLs.contains(key), try !regularFileExists(key) else {
          throw SourceStoreError.invalidInput
        }
        return nil
      }
      for entry in request {
        entry.retired = true
        if resident?.0 == entry.url { resident = nil }
        if thumbnail?.0 == entry.url { thumbnail = nil }
      }
      for entry in request { try removeIfReady(entry) }
    }
  }

  /// Whether this store published `url` and has not finished retiring it.
  func owns(_ url: URL) -> Bool {
    queue.sync {
      guard (try? requireOpen()) != nil, let key = try? canonical(url), key == url.standardizedFileURL,
            let entry = entries[key] else { return false }
      return entry.owned && !entry.retired
    }
  }

  /// Retires one owned source like remove, then reports its physical removal once
  /// the final pin drains; a failed removal stays registered so a later call retries.
  /// `completion` runs on this store's queue and must not call back into the store.
  func retire(_ url: URL, completion: @escaping (Error?) -> Void) {
    let entry: Entry?
    do {
      entry = try queue.sync { () throws -> Entry? in
        try requireOpen()
        let key = try canonical(url)
        guard key == url.standardizedFileURL else { throw SourceStoreError.invalidInput }
        guard let entry = entries[key], entry.owned else {
          guard retiredURLs.contains(key), try !regularFileExists(key) else { throw SourceStoreError.invalidInput }
          return nil
        }
        entry.retired = true
        if resident?.0 == key { resident = nil }
        if thumbnail?.0 == key { thumbnail = nil }
        entry.waiters.append(completion)
        removeReporting(entry)
        return entry
      }
    } catch { return completion(error) }
    if entry == nil { completion(nil) }
  }

  private func removeReporting(_ entry: Entry) {
    guard entry.pins == 0 else { return }
    let result: Error?
    do { try removeIfReady(entry); result = nil }
    catch {
      NSLog("DocumentScanner source retirement failed: %@", String(describing: error))
      result = error
    }
    let waiters = entry.waiters
    entry.waiters = []
    waiters.forEach { $0(result) }
  }

  /// Startup recovery. Sessions do not outlive their process, so a generated original
  /// (`<UUID>.jpg` or its `.partial` staging) without an entry here was stranded by an
  /// earlier process. Runs on queue, serialized with publish; registered, foreign-named,
  /// symlinked and non-regular items stay. `completion` runs on queue.
  func recoverAbandoned(completion: @escaping (Error?) -> Void) {
    queue.async { [self] in
      guard (try? requireOpen()) != nil else { return completion(nil) }
      let keys: [URLResourceKey] = [.isSymbolicLinkKey, .isRegularFileKey]
      let children: [URL]
      do { children = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys) }
      catch let error as CocoaError where error.code == .fileReadNoSuchFile { return completion(nil) }
      catch { return completion(error) }
      let active = Set(entries.keys.map(\.path))
      var failure: Error?
      for child in children {
        let stem = child.deletingPathExtension().lastPathComponent
        guard ["jpg", "partial"].contains(child.pathExtension), UUID(uuidString: stem)?.uuidString == stem,
              let values = try? child.resourceValues(forKeys: Set(keys)),
              values.isSymbolicLink != true, values.isRegularFile == true else { continue }
        let file = directory.appendingPathComponent(child.lastPathComponent)
        guard !active.contains(file.path) else { continue }
        do { try FileManager.default.removeItem(at: file) }
        catch { failure = failure ?? error }
      }
      completion(failure)
    }
  }

  private func regularFileExists(_ url: URL) throws -> Bool {
    do {
      let values = try FileManager.default.attributesOfItem(atPath: url.path)
      guard values[.type] as? FileAttributeType == .typeRegular else { throw SourceStoreError.invalidInput }
      return true
    } catch let error as CocoaError where error.code == .fileReadNoSuchFile { return false }
    catch let error as SourceStoreError { throw error }
    catch { throw SourceStoreError.invalidInput }
  }

  private func removeIfReady(_ entry: Entry) throws {
    guard entry.pins == 0 else { return }
    do {
      if try regularFileExists(entry.url) {
        guard entry.url == entry.url.resolvingSymlinksInPath() else { throw SourceStoreError.invalidInput }
        try FileManager.default.removeItem(at: entry.url)
      }
    } catch let error as CocoaError where error.code == .fileReadNoSuchFile { /* removed concurrently */ }
    catch let error as SourceStoreError { throw error }
    catch { throw SourceStoreError.writeFailed }

    // The last pin has drained and physical removal succeeded (or was already
    // done). Drop the full header/metadata; keep only bounded ownership history.
    // Shutdown may already have released this registry entry.
    guard entries[entry.url] === entry else { return }
    entries.removeValue(forKey: entry.url)
    retiredURLs.append(entry.url)
    if retiredURLs.count > Self.retirementHistoryLimit { retiredURLs.removeFirst() }
  }

  private func enqueueEviction() {
    queue.async { [weak self] in self?.evictOnQueue() }
  }

  func evict() { queue.sync { evictOnQueue() } }
  private func evictOnQueue() { resident = nil; thumbnail = nil }

  /// Marks closed BEFORE joining the queue, so queued requests fail with disposed.
  /// Existing leases/CGImages remain valid and accounted. Durable backing survives;
  /// session retirement is explicit via remove, and previously retired pins drain.
  func shutdown() {
    stateLock.lock()
    if closed { stateLock.unlock(); return }
    closed = true
    stateLock.unlock()
    queue.async { [self] in
      evictOnQueue()
      entries.removeAll()
      retiredURLs.removeAll()
      // In-flight leases retain their own Entry/header until the unpin callback
      // drains, including any deferred file removal. No new work is admitted.
      if let memoryObserver { notifications.removeObserver(memoryObserver) }
      memoryObserver = nil
    }
  }

  /// Synchronize deferred unpins for cleanup coordinators/tests, without exposing queue.
  func drain() { queue.sync {} }
}
