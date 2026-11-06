import Foundation
import NitroModules

/// Thin typed boundary. The registry/state, not this JS wrapper's lifetime, owns pages.
class HybridScanSession: HybridScanSessionSpec {
  /// Nil only when a direct construction could not open a session directory.
  let state: ScanSessionState?
  private let exports: SessionExports?

  init(state: ScanSessionState) {
    self.state = state
    exports = SessionExports(state: state)
    super.init()
  }

  /// Autolinked construction opens its own session, as the processor factory does.
  override convenience init() {
    if let state = try? ScanSessionRegistry.shared.createState() { self.init(state: state) }
    else { self.init(unavailable: ()) }
  }

  /// A view's prop default before JS sets its session; opens nothing.
  static func placeholder() -> HybridScanSession { HybridScanSession(unavailable: ()) }

  private init(unavailable: Void) {
    state = nil
    exports = nil
    super.init()
  }

  private static func failure(_ error: Error, _ fallback: ScannerErrorCode) -> ScannerFailure {
    ScannerCodeError.failure(error, fallback: fallback)
  }

  private func open() throws -> ScanSessionState {
    guard let state else { throw ScannerCodeError(code: .processorUnavailable) }
    return state
  }

  func createPdfResult(pageIds: [String]?, options: SessionPdfOptions) throws -> Promise<Variant_String_ScannerFailure> {
    let promise = Promise<Variant_String_ScannerFailure>()
    guard let exports else { return .resolved(withResult: .second(ScannerFailure(code: .processorUnavailable))) }
    exports.createPdf(pageIds, options: options) { result in
      switch result {
      case .success(let uri): promise.resolve(withResult: .first(uri))
      case .failure(let error): promise.resolve(withResult: .second(HybridScanSession.failure(error, .pdfWriteFailed)))
      }
    }
    return promise
  }

  func exportImagesResult(pageIds: [String]?, outputDirectory: String) throws -> Promise<Variant__String__ScannerFailure> {
    let promise = Promise<Variant__String__ScannerFailure>()
    guard let exports else { return .resolved(withResult: .second(ScannerFailure(code: .processorUnavailable))) }
    exports.exportImages(pageIds, directory: outputDirectory) { result in
      switch result {
      case .success(let uris): promise.resolve(withResult: .first(uris))
      case .failure(let error): promise.resolve(withResult: .second(HybridScanSession.failure(error, .storageWriteFailed)))
      }
    }
    return promise
  }

  /// Sample collection is deferred on iOS.
  func saveCaptureSampleResult(pageId: String) throws -> Promise<Variant_String_ScannerFailure> {
    .resolved(withResult: .second(ScannerUnsupported.failure))
  }

  func setDocumentCropResult(pageId: String, corners: DocumentCorners) throws -> Promise<Variant_Bool_ScannerFailure> {
    admit { try $0.setDocumentCrop(pageId, corners: corners) }
  }

  func setFilterResult(pageId: String, filter: DocumentFilter) throws -> Promise<Variant_Bool_ScannerFailure> {
    admit { try $0.setFilter(pageId, filter: filter) }
  }

  func setShadowRemovalResult(pageId: String, enabled: Bool) throws -> Promise<Variant_Bool_ScannerFailure> {
    admit { try $0.setShadowRemoval(pageId, enabled: enabled) }
  }

  func setPageOrderResult(pageIds: [String]) throws -> Promise<Variant_Bool_ScannerFailure> {
    admit { try $0.setPageOrder(pageIds) }
  }

  func removePageResult(pageId: String) throws -> Promise<Variant_Bool_ScannerFailure> {
    admit { try $0.removePage(pageId) }
  }

  /// Accepted mutations publish native state before resolving.
  private func admit(_ action: (ScanSessionState) throws -> Void) -> Promise<Variant_Bool_ScannerFailure> {
    do {
      try action(try open())
      return .resolved(withResult: .first(true))
    } catch {
      return .resolved(withResult: .second(HybridScanSession.failure(error, .processorUnavailable)))
    }
  }

  func closeResult() throws -> Promise<Variant_Bool_ScannerFailure> {
    guard let state else { return .resolved(withResult: .first(true)) }
    let promise = Promise<Variant_Bool_ScannerFailure>()
    state.close().whenComplete { error in
      if let error { promise.resolve(withResult: .second(HybridScanSession.failure(error, .cacheDeleteFailed))) }
      else { promise.resolve(withResult: .first(true)) }
    }
    return promise
  }

  func getSession() throws -> ScanSessionSnapshot {
    guard let state else {
      return ScanSessionSnapshot(sourceVersion: 0, closed: true, pageIds: [], pages: [:])
    }
    return state.snapshot()
  }

  func observeResult(onChange: @escaping (_ change: ScanSessionChange) -> Void) throws -> Variant_ScanSessionObservation_ScannerFailure {
    do {
      let registration = try open().observe(onChange)
      return .first(ScanSessionObservation(snapshot: registration.snapshot, unsubscribe: registration.unsubscribe))
    } catch {
      return .second(HybridScanSession.failure(error, .processorUnavailable))
    }
  }
}
