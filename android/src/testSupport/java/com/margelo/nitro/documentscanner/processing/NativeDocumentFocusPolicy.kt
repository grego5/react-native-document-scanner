package com.margelo.nitro.documentscanner.processing

import com.margelo.nitro.documentscanner.DocumentQuad

/** Compatibility value adapter for focused policy tests; production FocusController owns the same core policy. */
internal class DocumentFocusPolicy : AutoCloseable {
  private var closed = false
  private val handle = lazy { NativeFocus() }
  private val native get(): NativeFocus { check(!closed); return handle.value }
  fun reset() = native.documentResetNative()
  fun settled(success: Boolean, now: Long) = native.documentSettledNative(if (success) 1 else 0, now * 1_000_000)
  fun observe(quad: DocumentQuad?, trusted: Boolean, focusFailed: Boolean, now: Long, blocked: Boolean,
    acquireTrusted: Boolean = false): DocumentFocusAction = DocumentFocusAction.entries[
      native.observeNative(quad?.motionValues(), trusted, focusFailed, now * 1_000_000, blocked, acquireTrusted)]
  override fun close() { if (!closed) { closed = true; if (handle.isInitialized()) handle.value.close() } }
}
