import CoreGraphics
import Foundation

/// Engine work for one session; every call runs on the session's serial image worker.
protocol ScanSessionActions: AnyObject {
  /// Called at admission to freeze the model selection, without decoding or acquiring an interpreter.
  func prepareAnalysis(_ source: SessionResources.Lease, liveCorners: CornerCoordinates?,
                       selection: CornerInference.Selection?) throws -> () throws -> AnalysisValue
  func render(_ source: SessionResources.Lease, key: ScanPage.RenderKey) throws -> ScanPage.Artifact
  /// After the session's queue drains; releases the model lease.
  func close()
}

/// Work tied to a session's close before it reaches the image worker, such as a gallery import. Close takes every
/// registration in one step under the session lock, then runs each cancellation once, outside that lock.
protocol CloseRegistration: AnyObject, Sendable {
  /// True until close takes the registration or `tryUnregister()` removes it; for authorizing a presentation.
  var isRegistered: Bool { get }
  /// Claims a normal outcome: true when this removed the registration first. False once close has taken it, so the
  /// cancellation owns the outcome even before close has invoked it.
  func tryUnregister() -> Bool
}

/// Native admission and publication owner. Commands acknowledge settings immediately;
/// image work is keyed FIFO metadata. Page transitions use the lock, image actions run
/// outside it, and versioned changes are copied under it and delivered afterwards.
final class ScanSessionState: @unchecked Sendable {
  typealias PreviewReleased = @MainActor @Sendable () -> Void

  let resources: SessionResources
  private let actions: ScanSessionActions
  private let lock = NSRecursiveLock()
  private let events: ScanSessionEvents
  private var pages: [String: ScanPage] = [:]
  private var capturePreview: (id: String, image: CGImage, released: PreviewReleased)?
  private var queue: ScannerWorkQueue { resources.workOwner.queue }
  private var closed = false
  private var publications = 0
  private let publicationsDrained = NativeCompletion()
  private var completion: NativeCompletion?
  // Work that has not reached the image worker yet, such as an open gallery picker, keyed by registration.
  private var closeCancellations: [Int: @Sendable () -> NativeCompletion] = [:]
  private var nextCancellation = 0
  /// The shared model status this session last observed. Import admission and the observer both use it under the lock.
  private var model: CornerModelStatus = .absent
  private var stopObservingModel: (() -> Void)?

  /// Registers for the app-wide model status, current status first; returns the removal.
  typealias ModelObservation = (@escaping (CornerModelStatus) -> Void) -> () -> Void

  init(resources: SessionResources, actions: ScanSessionActions,
       dispatcher: @escaping (@escaping () -> Void) -> Void,
       models: ModelObservation = { CornerModelLoader.shared.observe($0) }) {
    self.resources = resources
    self.actions = actions
    events = ScanSessionEvents(lock: lock, dispatcher: dispatcher)
    stopObservingModel = models { [weak self] in self?.modelChanged($0) }
  }

  private func locked<T>(_ action: () throws -> T) rethrows -> T {
    lock.lock(); defer { lock.unlock() }
    return try action()
  }

  private func transition<T>(_ action: () throws -> T) rethrows -> T {
    lock.lock()
    defer { lock.unlock(); events.flush() }
    return try action()
  }

  /// Runs `cancel` once, on the closing thread, if close takes the registration first. Close completes only after
  /// the completion `cancel` returns, such as a picker's release of its slot; work without async cleanup returns a
  /// completed one. Throws `processor/disposed` when already closed.
  func registerCloseCancellation(_ cancel: @escaping @Sendable () -> NativeCompletion) throws -> CloseRegistration {
    let key: Int = try locked {
      try requireOpen()
      nextCancellation += 1
      closeCancellations[nextCancellation] = cancel
      return nextCancellation
    }
    return Registration(state: self, key: key)
  }

  private final class Registration: CloseRegistration, @unchecked Sendable {
    private let state: ScanSessionState
    private let key: Int
    init(state: ScanSessionState, key: Int) { self.state = state; self.key = key }
    var isRegistered: Bool { state.locked { state.closeCancellations[key] != nil } }
    func tryUnregister() -> Bool { state.locked { state.closeCancellations.removeValue(forKey: key) != nil } }
  }

  private func changed(_ page: ScanPage) { events.updatePage(page.metadata) }

  func snapshot() -> ScanSessionSnapshot { locked { events.latest } }

  func observe(_ callback: @escaping (ScanSessionChange) -> Void) throws -> ScanSessionEvents.Registration {
    try transition {
      try requireOpen()
      return events.observe(callback)
    }
  }

  func hasPage(_ id: String) -> Bool { locked { !closed && pages[id] != nil } }

  func retainedPreview(_ id: String) -> CGImage? {
    locked { capturePreview?.id == id ? capturePreview?.image : nil }
  }

  func releaseCapturePreview(_ id: String) {
    let released: PreviewReleased? = locked {
      guard capturePreview?.id == id else { return nil }
      let released = capturePreview?.released
      capturePreview = nil
      return released
    }
    if let released { DispatchQueue.main.async { released() } }
  }

  // MARK: Source admission

  /// The internal handoff used by captures and native fixtures. Takes a SourceStore-
  /// published original (and an optional committed session frame); a failed or
  /// obsolete admission retires them, so no unadmitted source outlives the attempt.
  func admitSource(id: String, source: URL, frame: SessionResources.Resource? = nil, liveCorners: DocumentCorners? = nil,
                   options: DocumentRenderOptions? = nil,
                   selection: CornerInference.Selection? = try? CornerInference.shared.selection(),
                   autoCrop: Bool = true, preview: CGImage? = nil,
                   previewReleased: @escaping PreviewReleased = {}) throws {
    try adopt(source, frame: frame) { original in
      try admit(id: id, original: original, frame: frame, liveCorners: liveCorners.map(SessionCorners.coordinates),
                settings: RenderSettings(options), selection: selection, autoCrop: autoCrop, imported: false,
                preview: preview, previewReleased: previewReleased)
    }
  }

  /// Admits an imported SourceStore-published original, which has no frame or live corners. The page follows the
  /// shared model; a failed admission retires the source.
  func admitImport(id: String, source: URL, settings: RenderSettings, autoCrop: Bool) throws {
    try adopt(source, frame: nil) { original in
      try admit(id: id, original: original, frame: nil, liveCorners: nil, settings: settings, selection: nil,
                autoCrop: autoCrop, imported: true)
    }
  }

  /// Adopts `source` and runs `admit` with it, retiring the source and `frame` when either fails.
  private func adopt(_ source: URL, frame: SessionResources.Resource?,
                     admit: (SessionResources.Resource) throws -> Void) throws {
    let original: SessionResources.Resource
    do { original = try resources.adoptSource(source) }
    catch {
      if let frame { resources.remove(frame) }
      // A closed session still owns the handoff; a foreign or claimed source is not ours to retire.
      if ScannerCodeError.code(error, fallback: .inputInvalid) == .processorDisposed {
        resources.registry.store.retire(source) { _ in }
      }
      throw error
    }
    do {
      try admit(original)
    } catch {
      resources.remove(original)
      if let frame { resources.remove(frame) }
      throw error
    }
  }

  /// Takes ownership of already published session resources only on success. Without `autoCrop` the page keeps the
  /// full frame: it is still analyzed, so the editor's automatic preset and samples have the candidate, but the
  /// candidate is never applied by itself. A capture analyzes with its frozen `selection` before its first render. An
  /// `imported` page ignores `selection` and follows the shared model: analyzed at once when it is ready, analyzed
  /// without a model when it is absent or failed, otherwise displayed at full frame until it settles.
  private func admit(id: String, original: SessionResources.Resource, frame: SessionResources.Resource?,
                     liveCorners: CornerCoordinates?, settings: RenderSettings, selection: CornerInference.Selection?,
                     autoCrop: Bool, imported: Bool, preview: CGImage? = nil,
                     previewReleased: @escaping PreviewReleased = {}) throws {
    try transition {
      try requireOpen()
      guard !id.isEmpty, pages[id] == nil, original.owner === resources, original.kind == .original,
            frame.map({ $0.owner === resources && $0.kind == .frame }) ?? true,
            liveCorners.map(SessionCorners.renderable) ?? true,
            !pages.values.contains(where: { $0.original === original || (frame != nil && $0.frame === frame) })
      else { throw ScannerCodeError.inputInvalid }
      try resources.lease(original).close()
      if let frame { try resources.lease(frame).close() }
      let page = ScanPage(id: id, original: original, frame: frame, settings: settings, liveCorners: liveCorners)
      page.explicitGeometry = !autoCrop
      switch (imported, model) {
      case (true, .loading):
        page.awaitingModel = true
        page.analysisGatesRender = false
      case (true, .ready(_, let shared)): try analyze(page, selection: shared)
      case (true, _): try analyze(page, selection: nil)
      case (false, _): try analyze(page, selection: selection)
      }
      pages[id] = page
      if let preview {
        if let capturePreview { releaseCapturePreview(capturePreview.id) }
        capturePreview = (id, preview, previewReleased)
      }
      changed(page)
      if page.awaitingModel { requestRender(page) }
    }
  }

  /// Pins the original only now and submits analysis with `selection`, frozen for its result. Under the lock.
  private func analyze(_ page: ScanPage, selection: CornerInference.Selection?) throws {
    let source = try resources.lease(page.original)
    let analyze: () throws -> AnalysisValue
    do { analyze = try actions.prepareAnalysis(source, liveCorners: page.liveCorners, selection: selection) }
    catch { source.close(); throw error }
    page.awaitingModel = false
    page.selection = selection
    page.analyzing = true
    queue.submit(resources.workOwner, kind: .analysis, pageId: page.id,
      operation: { [self] in
        try locked { if page.exportAnalysisPins == 0 { try requireCurrent(page) } }
        return try analyze()
      },
      onSuccess: { [self] analysis in
        source.close()
        finishAnalysis(page, .success(analysis))
      },
      onFailure: { [self] error in
        source.close()
        finishAnalysis(page, .failure(error))
      })
  }

  /// Ready submits every waiting import, in page order, with its selection; absent or failed analyzes them without
  /// a model, so they keep their full frame. Loading keeps them waiting. Configuration errors never fail a page.
  private func modelChanged(_ status: CornerModelStatus) {
    transition {
      guard !closed else { return }
      model = status
      let selection: CornerInference.Selection?
      switch status {
      case .loading: return
      case .ready(_, let ready): selection = ready
      case .absent, .failed: selection = nil
      }
      for page in events.pageIds.compactMap({ pages[$0] }) where page.awaitingModel {
        do { try analyze(page, selection: selection) }
        catch {
          page.awaitingModel = false
          page.analysisFailure = ScannerCodeError.code(error, fallback: .analysisFailed)
          changed(page)
        }
      }
    }
  }

  // MARK: Commands

  func setDocumentCrop(_ id: String, corners: DocumentCorners) throws {
    try transition {
      let page = try page(id)
      try DocumentRenderService.validate(corners)
      // Even an explicit full-frame edit during analysis protects that choice from its candidate.
      page.explicitGeometry = true
      edit(page, corners: SessionCorners.coordinates(corners), settings: page.settings)
    }
  }

  func setFilter(_ id: String, filter: DocumentFilter) throws {
    try transition {
      let page = try page(id)
      edit(page, corners: page.corners, settings: try page.settings.with(filter: filter))
    }
  }

  func setShadowRemoval(_ id: String, enabled: Bool) throws {
    try transition {
      let page = try page(id)
      edit(page, corners: page.corners, settings: try page.settings.with(removeShadows: enabled))
    }
  }

  func setPageOrder(_ ids: [String]) throws {
    try transition {
      try requireOpen()
      try events.setPageOrder(ids)
    }
  }

  func removePage(_ id: String) throws {
    try transition {
      let page = try page(id)
      pages.removeValue(forKey: id)
      releaseCapturePreview(id)
      events.removePage(id)
      var artifacts = page.cache.map(\.artifact)
      if let displayed = page.displayed, !artifacts.contains(where: { $0 === displayed }) { artifacts.append(displayed) }
      artifacts.forEach(retire)
      resources.remove(page.original)
      if let frame = page.frame { resources.remove(frame) }
    }
  }

  // MARK: Revisions and publication

  private func edit(_ page: ScanPage, corners: CornerCoordinates, settings: RenderSettings) {
    if page.corners == corners && page.settings == settings && page.renderFailure == nil { return }
    page.corners = corners
    page.settings = settings
    revise(page)
    changed(page)
    if !page.renderWaitsForAnalysis { requestRender(page) }
  }

  private func finishAnalysis(_ page: ScanPage, _ result: Result<AnalysisValue, Error>) {
    transition {
      page.analysisResult = result
      guard current(page) else { return }
      page.analyzing = false
      switch result {
      case .success(let analysis):
        page.analysis = analysis
        page.analysisFailure = nil
      case .failure(let error):
        page.analysis = nil
        page.analysisFailure = ScannerCodeError.code(error, fallback: .analysisFailed)
      }
      var cropped = false
      if !page.explicitGeometry, let candidate = page.analysis?.candidate, SessionCorners.renderable(candidate),
         page.corners != candidate {
        page.corners = candidate
        revise(page)
        cropped = true
      }
      changed(page)
      // A page displayed while it waited already rendered its current geometry.
      if page.analysisGatesRender || cropped { requestRender(page) }
    }
  }

  private func requestRender(_ page: ScanPage) {
    let revision = page.requestedRevision
    let key = page.key
    if let cached = page.cached(key) { return publish(page, revision: revision, artifact: cached) }
    queue.submit(resources.workOwner, kind: .render, pageId: page.id,
      operation: { [self] () throws -> ScanPage.Artifact in
        let cached: ScanPage.Artifact? = try locked {
          try requireCurrent(page)
          guard page.requestedRevision == revision else { throw ScannerCodeError(code: .processorSuperseded) }
          return page.cached(key)
        }
        if let cached { return cached }
        let source = try resources.lease(page.original)
        defer { source.close() }
        return try actions.render(source, key: key)
      },
      onSuccess: { [self] artifact in
        transition {
          guard current(page), compatible(page.key, key) else { return retire(artifact) }
          let previous = page.cached(key)
          page.cache.removeAll { $0.key == key }
          page.cache.append((key, artifact))
          if let previous, previous !== artifact, previous !== page.displayed { retire(previous) }
          if page.requestedRevision == revision { publish(page, revision: revision, artifact: artifact) }
        }
      },
      onFailure: { [self] error in
        transition {
          guard current(page), page.requestedRevision == revision else { return }
          page.renderFailure = ScannerCodeError.code(error, fallback: .renderFailed)
          changed(page)
        }
      },
      onDiscard: { [self] in retire($0) })
  }

  /// Geometry/output configuration determines compatibility; filters and shadows each have a slot.
  private func compatible(_ a: ScanPage.RenderKey, _ b: ScanPage.RenderKey) -> Bool {
    a.corners == b.corners && (try? a.settings.with(filter: b.settings.filter, removeShadows: b.settings.removeShadows)) == b.settings
  }

  /// Every geometry or output change: a new revision without the old error or output it no longer matches.
  private func revise(_ page: ScanPage) {
    page.requestedRevision += 1
    page.renderFailure = nil
    let key = page.key
    page.cache.removeAll { entry in
      guard !compatible(key, entry.key) else { return false }
      if entry.artifact !== page.displayed { retire(entry.artifact) }
      return true
    }
  }

  /// Keeps the previous displayed artifact until its replacement is installed.
  private func publish(_ page: ScanPage, revision: Int, artifact: ScanPage.Artifact) {
    let previous = page.displayed
    page.displayed = artifact
    page.readyRevision = revision
    page.renderFailure = nil
    changed(page)
    if let previous, previous !== artifact, !page.cache.contains(where: { $0.artifact === previous }) { retire(previous) }
  }

  private func retire(_ artifact: ScanPage.Artifact) {
    resources.remove(artifact.image)
    resources.remove(artifact.thumbnail)
  }

  // MARK: Page reads

  /// Atomically pins the displayed render (with its thumbnail sidecar), else the
  /// retained frame, else the original. `original` pins the original only.
  func imageSource(_ id: String, original: Bool = false) throws -> SessionPageSource {
    try resolveSource(id, original: original, thumbnailOnly: false)!
  }

  /// The displayed thumbnail or retained frame; nil while neither exists. Never an original.
  func thumbnailSource(_ id: String) throws -> SessionPageSource? {
    try resolveSource(id, original: false, thumbnailOnly: true)
  }

  private func resolveSource(_ id: String, original: Bool, thumbnailOnly: Bool) throws -> SessionPageSource? {
    try locked {
      let page = try page(id)
      let artifact = original ? nil : page.displayed
      let source: SessionResources.Resource
      if thumbnailOnly {
        guard let resource = artifact?.thumbnail ?? page.frame else { return nil }
        source = resource
      } else {
        source = original ? page.original : artifact?.image ?? page.frame ?? page.original
      }
      var leases = [try resources.lease(source)]
      if !thumbnailOnly, let thumbnail = artifact?.thumbnail { leases.append(try resources.lease(thumbnail)) }
      return SessionPageSource(key: source.id, leases: leases,
                               cacheKey: thumbnailOnly ? artifact.map { $0.image.url.absoluteString } : nil)
    }
  }

  // MARK: Exports

  /// One locked admission: validates every ID, then pins sources, matching renders and
  /// automatic analysis in invocation order (duplicates included). Automatic geometry waits only for analysis already
  /// queued ahead of the export; an import still waiting for the model exports the geometry and settings it has now.
  func exportSnapshot(_ requested: [String]?) throws -> SessionExportSnapshot {
    try locked {
      try requireOpen()
      let ids = requested ?? events.pageIds
      guard !ids.isEmpty else { throw ScannerCodeError.inputInvalid }
      let selected = try ids.map { try page($0) }
      var entries: [SessionExportSnapshot.Page] = []
      do {
        for page in selected {
          if let failure = page.renderFailure { throw ScannerCodeError(code: failure) }
          if !page.explicitGeometry, page.analysisFailure != nil { throw ScannerCodeError(code: .analysisFailed) }
          let source = try resources.lease(page.original)
          let image: SessionResources.Lease?
          do { image = try page.analyzing ? nil : page.cached(page.key).map { try resources.lease($0.image) } }
          catch { source.close(); throw error }
          let automatic = !page.explicitGeometry && page.analyzing
          if automatic { page.exportAnalysisPins += 1 }
          entries.append(SessionExportSnapshot.Page(id: page.id, requested: page.key, automatic: automatic,
            analysis: { [self] in locked { page.analysisResult } }, source: source, image: image,
            releaseAnalysis: { [self] in if automatic { locked { page.exportAnalysisPins -= 1 } } }))
        }
      } catch {
        entries.forEach { $0.close() }
        throw error
      }
      return SessionExportSnapshot(pages: entries, resources: resources, actions: actions)
    }
  }

  func checkExportOpen() throws { try locked { try requireOpen() } }

  /// Claims publication before close; file work runs unlocked. Close awaits every claim.
  func publishExport<T>(_ action: () throws -> T) throws -> T {
    try locked {
      try requireOpen()
      publications += 1
    }
    defer {
      let drained: Bool = locked {
        publications -= 1
        return closed && publications == 0
      }
      if drained { publicationsDrained.complete() }
    }
    return try action()
  }

  // MARK: Close

  /// Rejects new work immediately; completes after running work, pins, the model lease,
  /// claimed publications and physical cleanup. Idempotent.
  @discardableResult
  func close() -> NativeCompletion {
    stopObservingModel?() // Idempotent; a late status finds the session closed.
    let (result, first, cancellations): (NativeCompletion, Bool, [@Sendable () -> NativeCompletion]) = transition {
      if let completion { return (completion, false, []) }
      closed = true
      // Registration order, so cancellations run in the order their operations started.
      let pending = closeCancellations.sorted { $0.key < $1.key }.map(\.value)
      closeCancellations.removeAll()
      pages.removeAll()
      if let capturePreview { releaseCapturePreview(capturePreview.id) }
      events.close()
      let created = NativeCompletion()
      completion = created
      return (created, true, pending)
    }
    guard first else { return result }
    // Unlocked: a cancellation may settle its caller's promise or reenter this state.
    let cancelled = NativeCompletion.all(cancellations.map { $0() })
    if locked({ publications == 0 }) { publicationsDrained.complete() }
    let modelClosed = NativeCompletion()
    queue.close(resources.workOwner).whenComplete { [self] _ in
      resources.registry.cleanup.async { [self] in
        actions.close()
        modelClosed.complete()
      }
    }
    NativeCompletion.all([resources.close(), modelClosed, publicationsDrained, cancelled]).whenComplete { error in
      result.complete(error.map { ScannerCodeError(code: ScannerCodeError.code($0, fallback: .cacheDeleteFailed)) as Error })
    }
    return result
  }

  private func page(_ id: String) throws -> ScanPage {
    try requireOpen()
    guard let page = pages[id] else { throw ScannerCodeError.inputInvalid }
    return page
  }

  private func current(_ page: ScanPage) -> Bool { !closed && pages[page.id] === page }

  private func requireCurrent(_ page: ScanPage) throws {
    if !current(page) { throw ScannerCodeError(code: .processorSuperseded) }
  }

  private func requireOpen() throws {
    if closed { throw ScannerCodeError(code: .processorDisposed) }
  }
}
