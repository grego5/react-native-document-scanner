import Foundation
import NitroModules
import UIKit

/// Native crop editing for a session page's original; the unsubmitted draft stays in the
/// editor. Nitro sets props and mounts the view on the main thread, where all state lives;
/// methods may come from JavaScript's thread and run on main.
final class HybridDocumentCropEditorView: HybridDocumentCropEditorViewSpec, @unchecked Sendable {
  /// Full resolution for typical captures, so the 3x magnifier stays sharp.
  static let decodeLimit = 4096
  /// The longer side the editor filters its photo at; detail beyond this is not visible while editing.
  static let filterPreviewLongSide = 2400

  private lazy var editor: CropEditorView = MainActor.assumeIsolated {
    let editor = CropEditorView()
    editor.onQuadEdited = { [weak self] _ in
      guard let self else { return }
      chosen = true
      publishPreset()
    }
    editor.onAttach = { [weak self] in
      guard let self, !dropped else { return }
      binding?.start()
    }
    editor.onDetach = { [weak self] in
      guard let self else { return }
      binding?.close()
      source?.close(); source = nil
      discard()
      self.editor.resetZoom()
    }
    return editor
  }

  var view: UIView { editor }
  var session: (any HybridScanSessionSpec) = HybridScanSession.placeholder()
  var pageId: String = ""
  var filter: DocumentFilter? = nil
  /// A new listener hears the current classification.
  var onPresetChange: ((_ preset: CropDraftPreset) -> Void)? = nil { didSet { reportedPreset = nil } }
  var onLoad: ((_ width: Double, _ height: Double) -> Void)? = nil
  var onError: ((_ failure: ScannerFailure) -> Void)? = nil

  // Main only.
  private var bound = false
  private var boundState: ScanSessionState?
  private var boundId: String?
  private var binding: SessionPageBinding?
  private var source: SessionPageSource?
  private var dropped = false
  private var reportedPreset: CropDraftPreset?
  /// False while the draft only suggests a replacement for a saved crop the editor cannot
  /// edit; an edit or preset makes it the user's choice.
  private var chosen = true
  /// The decoded photo, unfiltered; nil while loading or after a failure.
  private(set) var photo: CGImage?
  /// The filter of the last preview requested.
  private var previewFilter = DocumentFilter.none
  // A load or filter result is applied only while its generation is current, and a queued
  // read that is already superseded is skipped.
  private let loadGeneration = LoadGeneration()
  private let filterGeneration = LoadGeneration()

  /// Binds a fresh draft only when the configured session or page changes.
  func afterUpdate() {
    MainActor.assumeIsolated {
      guard !dropped else { return }
      let state = (session as? HybridScanSession)?.state
      if !bound || state !== boundState || pageId != boundId {
        bound = true
        binding?.close()
        source?.close(); source = nil
        boundState = state; boundId = pageId
        reportedPreset = nil
        chosen = true
        if let saved = state?.snapshot().pages[pageId]?.quad { draft(SessionQuad.coordinates(saved)) }
        discard()
        binding = state.map { owner in
          SessionPageBinding(state: owner, id: pageId, original: true, changed: { [weak self] next in
            guard let self else { next?.close(); return }
            changed(next)
          }, failed: { [weak self] in self?.onError?(ScannerFailure(code: $0.code)) })
        }
        if editor.window != nil { binding?.start() }
        if state == nil || !chosen { onError?(ScannerFailure(code: .inputInvalid)) }
      }
      publishPreset()
      let wanted = filter ?? DocumentFilter.none
      if wanted != previewFilter {
        previewFilter = wanted
        if let photo { preview(photo) }
      }
    }
  }

  // Explicit unmount detaches observation and invalidates even already queued reads.
  func onDropView() {
    MainActor.assumeIsolated {
      dropped = true
      binding?.close(); binding = nil
      source?.close(); source = nil
      discard()
    }
  }

  func submitResult() throws -> Promise<Variant_Bool_ScannerFailure> {
    onMain { editor in
      let (state, id) = try self.available(chosen: true)
      try state.setDocumentCrop(id, quad: SessionQuad.quad(editor.quad))
      return .first(true)
    } failed: { .second($0) }
  }

  func getQuadResult() throws -> Promise<Variant_ScannerFailure_DocumentQuad> {
    onMain { editor in
      _ = try self.available(chosen: true)
      return .second(SessionQuad.quad(editor.quad))
    } failed: { .first($0) }
  }

  func setPresetResult(preset: CropPreset) throws -> Promise<Variant_Bool_ScannerFailure> {
    onMain { editor in
      let (state, id) = try self.available()
      switch preset {
      case .none: try editor.replaceQuad(CropQuadEditing.fullFrame)
      case .auto:
        guard let automatic = state.snapshot().pages[id]?.analysis?.candidate?.quad else {
          throw ScannerCodeError(code: .inputInvalid)
        }
        try editor.replaceQuad(SessionQuad.coordinates(automatic))
      @unknown default: throw ScannerCodeError(code: .inputInvalid)
      }
      self.chosen = true
      self.publishPreset()
      return .first(true)
    } failed: { .second($0) }
  }

  /// Runs `operation` on main and resolves its result, or `failed` with its error
  /// (input/invalid unless it carries a code).
  private func onMain<T>(_ operation: @escaping @MainActor @Sendable (CropEditorView) throws -> T,
                         failed: @escaping @Sendable (ScannerFailure) -> T) -> Promise<T> {
    let promise = Promise<T>()
    DispatchQueue.main.async { [weak self] in
      MainActor.assumeIsolated {
        guard let self else { return promise.resolve(withResult: failed(ScannerFailure(code: .inputInvalid))) }
        do { promise.resolve(withResult: try operation(self.editor)) }
        catch { promise.resolve(withResult: failed(ScannerCodeError.failure(error, fallback: .inputInvalid))) }
      }
    }
    return promise
  }

  /// The bound page while it can still be edited; with `chosen`, also only once the draft is
  /// the user's crop rather than a suggested replacement.
  @MainActor
  private func available(chosen required: Bool = false) throws -> (ScanSessionState, String) {
    guard !dropped, binding?.valid == true, !required || chosen, let boundState, let boundId else {
      throw ScannerCodeError(code: .inputInvalid)
    }
    return (boundState, boundId)
  }

  /// Starts the draft from a page's saved crop. A saved crop the editor cannot edit, such as
  /// one outside the photo, which renders allow, is invalid input: the editor shows its clamp,
  /// or the full frame when even that is not a valid crop, as a suggestion only, and the draft
  /// can be read or submitted once an edit or preset replaces it.
  @MainActor
  private func draft(_ saved: QuadCoordinates) {
    chosen = CropQuadEditing.isValid(saved)
    let candidates = [saved, CropQuadEditing.clampToFrame(saved), CropQuadEditing.fullFrame]
    try? editor.replaceQuad(candidates.first(where: CropQuadEditing.isValid)!)
  }

  /// Classifies the draft once it is the user's crop.
  @MainActor
  private func publishPreset() {
    guard !dropped, binding?.valid == true, chosen, let onPresetChange else { return }
    let quad = editor.quad
    let automatic = boundId.flatMap { boundState?.snapshot().pages[$0]?.analysis?.candidate?.quad }
    let preset: CropDraftPreset
    if quad == CropQuadEditing.fullFrame { preset = .none }
    else if let automatic, quad == SessionQuad.coordinates(automatic) { preset = .auto }
    else { preset = .custom }
    guard preset != reportedPreset else { return }
    reportedPreset = preset
    onPresetChange(preset)
  }

  /// The binding delivers only changed sources, so every source is loaded.
  @MainActor
  private func changed(_ next: SessionPageSource?) {
    source?.close()
    source = next
    if next == nil { discard() } else { load() }
  }

  /// Drops the photo and any pending read.
  @MainActor
  private func discard() {
    loadGeneration.advance()
    filterGeneration.advance()
    photo = nil
    editor.image = nil
  }

  /// Shows the current source's original once decoded, then its preview under `previewFilter`.
  @MainActor
  private func load() {
    discard()
    let generation = loadGeneration.advance()
    editor.resetZoom()
    guard let source else { return }
    ScannerImageReads.read(source.retain(), generation: loadGeneration, generation, {
      try DisplayImageDecoder.decode($0.url, longSide: Self.decodeLimit)
    }) { [weak self] result in
      guard let self, loadGeneration.isCurrent(generation), binding?.valid != false else { return }
      switch result {
      case .success(let decoded):
        photo = decoded.image
        editor.image = decoded.image
        onLoad?(Double(decoded.image.width), Double(decoded.image.height))
        preview(decoded.image)
      case .failure:
        onError?(ScannerFailure(code: .imageLoadFailed))
      }
    }
  }

  /// Shows `photo` under `previewFilter`: at once for `none`, otherwise once a copy no larger
  /// than a default render is filtered off main, the editor keeping its current image until
  /// then. A newer request discards this one's result, or skips it if not yet started.
  @MainActor
  private func preview(_ photo: CGImage) {
    guard !dropped, binding?.valid == true else { return }
    let generation = filterGeneration.advance()
    let filter = previewFilter
    if filter == .none { return editor.image = photo }
    guard let pin = source?.retain() else { return }
    let input = DisplayImage(image: photo)
    let index = DocumentRenderService.filterIndex(filter)
    ScannerImageReads.read(pin, generation: filterGeneration, generation, { _ in
      try ScannerDocumentRenderer.filterPreview(input.image, longSide: Self.filterPreviewLongSide, filter: index)
    }) { [weak self] result in
      guard let self, filterGeneration.isCurrent(generation), binding?.valid != false else { return }
      switch result {
      case .success(let filtered): editor.image = filtered.image
      case .failure: onError?(ScannerFailure(code: .editorFilterFailed))
      }
    }
  }
}
