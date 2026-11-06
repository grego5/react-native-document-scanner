import Foundation

/// Invocation-owned ordered pins, resolved only on the session's serial image worker.
final class SessionExportSnapshot {
  final class Page {
    let id: String
    private let requested: ScanPage.RenderKey
    private let automatic: Bool
    private let analysis: () -> Result<AnalysisValue, Error>?
    let source: SessionResources.Lease
    fileprivate(set) var image: SessionResources.Lease?
    private let releaseAnalysis: () -> Void
    private var closed = false

    init(id: String, requested: ScanPage.RenderKey, automatic: Bool, analysis: @escaping () -> Result<AnalysisValue, Error>?,
         source: SessionResources.Lease, image: SessionResources.Lease?, releaseAnalysis: @escaping () -> Void) {
      self.id = id; self.requested = requested; self.automatic = automatic; self.analysis = analysis
      self.source = source; self.image = image; self.releaseAnalysis = releaseAnalysis
    }

    /// Settings are frozen at admission; an automatic page takes the analysis admitted
    /// before it in the FIFO, never a later edit or an older displayed render.
    func key() throws -> ScanPage.RenderKey {
      guard automatic else { return requested }
      switch analysis() {
      case .some(.success(let value)):
        let corners = value.candidate.flatMap { SessionCorners.renderable($0) ? $0 : nil } ?? SessionCorners.fullFrame
        return ScanPage.RenderKey(corners: corners, settings: requested.settings)
      case .some(.failure): throw ScannerCodeError(code: .analysisFailed)
      case .none: throw ScannerCodeError(code: .renderFailed) // FIFO order makes this unreachable.
      }
    }

    func close() {
      guard !closed else { return }
      closed = true
      image?.close()
      image = nil
      source.close()
      releaseAnalysis()
    }
  }

  let pages: [Page]
  private let resources: SessionResources
  private let actions: ScanSessionActions

  init(pages: [Page], resources: SessionResources, actions: ScanSessionActions) {
    self.pages = pages; self.resources = resources; self.actions = actions
  }

  /// Pinned render files in order, rendering missing revisions from the pinned original.
  func files() throws -> [URL] {
    try pages.map { page -> URL in
      if let image = page.image { return image.url }
      let artifact: ScanPage.Artifact
      do { artifact = try actions.render(page.source, key: page.key()) }
      catch { throw ScannerCodeError.bridged(error, failure: .renderFailed) }
      defer {
        resources.remove(artifact.image)
        resources.remove(artifact.thumbnail)
      }
      let image = try resources.lease(artifact.image)
      page.image = image
      return image.url
    }
  }

  func close() { pages.forEach { $0.close() } }
}

/// One FIFO job per invocation. Outputs stay staged beside their destination until
/// publication claims the open session; failed or stale work removes them.
final class SessionExports {
  private let state: ScanSessionState
  private var registry: ScanSessionRegistry { state.resources.registry }

  init(state: ScanSessionState) { self.state = state }

  func createPdf(_ ids: [String]?, options: SessionPdfOptions, completion: @escaping (Result<String, Error>) -> Void) {
    let snapshot: SessionExportSnapshot
    do { snapshot = try state.exportSnapshot(ids) } catch { return completion(.failure(error)) }
    do {
      let output = try PdfService.localURL(options.outputPath)
      try validateFile(output)
      submit(snapshot, completion: completion) { [self] in
        let staged = output.deletingLastPathComponent().appendingPathComponent(".scan-export-\(UUID().uuidString).pdf")
        let prepared = Prepared(files: [(staged, output)], validate: validateFile) { output.absoluteString }
        do {
          let files = try snapshot.files()
          try state.checkExportOpen()
          _ = try PdfService.write(pages: files, options: options, to: staged)
          return prepared
        } catch { prepared.close(); throw error }
      }
    } catch {
      snapshot.close()
      completion(.failure(error))
    }
  }

  func exportImages(_ ids: [String]?, directory: String, completion: @escaping (Result<[String], Error>) -> Void) {
    let snapshot: SessionExportSnapshot
    do { snapshot = try state.exportSnapshot(ids) } catch { return completion(.failure(error)) }
    do {
      let folder = try PdfService.localURL(directory)
      try registry.requireExternalOutput(folder)
      var isDirectory: ObjCBool = false
      guard FileManager.default.fileExists(atPath: folder.path, isDirectory: &isDirectory), isDirectory.boolValue,
            FileManager.default.isWritableFile(atPath: folder.path) else { throw ScannerCodeError.inputInvalid }
      submit(snapshot, completion: completion) { [self] in
        var copies: [(URL, URL)] = []
        do {
          for image in try snapshot.files() {
            try state.checkExportOpen()
            let output = folder.appendingPathComponent("scan-\(UUID().uuidString.lowercased()).\(image.pathExtension)")
            try validateFile(output)
            let staged = folder.appendingPathComponent(".\(output.lastPathComponent).tmp")
            copies.append((staged, output))
            try FileManager.default.copyItem(at: image, to: staged)
          }
          return Prepared(files: copies, validate: validateFile) { copies.map { $0.1.absoluteString } }
        } catch {
          copies.forEach { try? FileManager.default.removeItem(at: $0.0) }
          throw error
        }
      }
    } catch {
      snapshot.close()
      completion(.failure(error))
    }
  }

  private func submit<T>(_ snapshot: SessionExportSnapshot, completion: @escaping (Result<T, Error>) -> Void,
                         work: @escaping () throws -> Prepared<T>) {
    let owner = state.resources.workOwner
    owner.queue.submit(owner, kind: .pdf, operation: work,
      onSuccess: { [state] prepared in
        let result = Result { try state.publishExport { try prepared.publish() } }
        prepared.close()
        snapshot.close()
        completion(result)
      },
      onFailure: { error in
        snapshot.close()
        completion(.failure(error))
      },
      onDiscard: { $0.close() })
  }

  private final class Prepared<T> {
    private let files: [(staged: URL, output: URL)]
    private let validate: (URL) throws -> Void
    private let value: () -> T
    private var published = false

    init(files: [(URL, URL)], validate: @escaping (URL) throws -> Void, value: @escaping () -> T) {
      self.files = files.map { (staged: $0.0, output: $0.1) }; self.validate = validate; self.value = value
    }

    func publish() throws -> T {
      var moved: [URL] = []
      do {
        for file in files {
          try validate(file.output)
          // Same-directory POSIX rename atomically replaces the destination.
          guard rename(file.staged.path, file.output.path) == 0 else { throw CocoaError(.fileWriteUnknown) }
          moved.append(file.output)
        }
        published = true
        return value()
      } catch {
        moved.forEach { try? FileManager.default.removeItem(at: $0) }
        throw error
      }
    }

    func close() {
      if !published { files.forEach { try? FileManager.default.removeItem(at: $0.staged) } }
    }
  }

  private func validateFile(_ output: URL) throws {
    try registry.requireExternalOutput(output)
    let parent = output.deletingLastPathComponent()
    var isDirectory: ObjCBool = false
    guard FileManager.default.fileExists(atPath: parent.path, isDirectory: &isDirectory), isDirectory.boolValue,
          FileManager.default.isWritableFile(atPath: parent.path) else { throw ScannerCodeError.inputInvalid }
    if FileManager.default.fileExists(atPath: output.path, isDirectory: &isDirectory), isDirectory.boolValue {
      throw ScannerCodeError.inputInvalid
    }
  }
}
