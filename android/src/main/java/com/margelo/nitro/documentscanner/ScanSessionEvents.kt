package com.margelo.nitro.documentscanner

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.Executor

/** Owns observer lifetimes and ordered transport delivery, sharing the page owner's short state lock. */
internal class ScanSessionEvents(
  private val lock: Any,
  private val dispatcher: Executor,
  private val report: (Throwable) -> Unit,
) {
  class Registration(val snapshot: ScanSessionSnapshot, val unsubscribe: () -> Unit)
  private class Observer(var callback: ((ScanSessionChange) -> Unit)?)
  private class Delivery(val snapshot: ScanSessionChange, val observers: List<Observer>)
  private val observers = LinkedHashSet<Observer>()
  private val pending = ArrayDeque<Delivery>()
  private var scheduled = false
  private val pages = LinkedHashMap<String, ScanPageMetadata>()
  private var version = 0.0
  private var closed = false
  private var cached: ScanSessionSnapshot? = null
  val latest: ScanSessionSnapshot
    get() {
      check(Thread.holdsLock(lock))
      return cached ?: ScanSessionSnapshot(version, closed, pages.keys.toTypedArray(),
        Collections.unmodifiableMap(HashMap(pages))).also { cached = it }
    }

  fun updatePage(page: ScanPageMetadata) {
    check(Thread.holdsLock(lock))
    if (pages[page.id] == page) return
    pages[page.id] = page
    enqueue(mapOf(page.id to page), emptyArray())
  }

  fun setPageOrder(ids: List<String>) {
    check(Thread.holdsLock(lock))
    requireInput(ids.size == pages.size && ids.toSet() == pages.keys) { "Expected every session page exactly once." }
    if (ids == pages.keys.toList()) return
    val ordered = ids.associateWith { pages.getValue(it) }
    pages.clear()
    pages.putAll(ordered)
    enqueue(emptyMap(), emptyArray(), ids.toTypedArray())
  }

  fun removePage(id: String) {
    check(Thread.holdsLock(lock))
    if (pages.remove(id) != null) enqueue(emptyMap(), arrayOf(id))
  }

  fun close() {
    check(Thread.holdsLock(lock))
    if (closed) return
    closed = true
    pages.clear()
    pending.clear()
    enqueue(emptyMap(), emptyArray())
    observers.clear()
  }

  private fun enqueue(updated: Map<String, ScanPageMetadata>, removed: Array<String>, order: Array<String>? = null) {
    cached = null
    version++
    val change = ScanSessionChange(version, closed, updated, removed, order)
    if (observers.isNotEmpty()) pending.addLast(Delivery(change, observers.toList()))
  }

  fun observe(callback: (ScanSessionChange) -> Unit): Registration {
    check(Thread.holdsLock(lock))
    val observer = Observer(callback)
    observers.add(observer)
    return Registration(latest) {
      synchronized(lock) {
        observer.callback = null
        observers.remove(observer)
      }
    }
  }

  /** Called after the outermost state transition: dispatcher/JS is never entered under the lock. */
  fun flush() {
    if (Thread.holdsLock(lock)) return
    val start = synchronized(lock) {
      if (pending.isEmpty() || scheduled) false else { scheduled = true; true }
    }
    if (!start) return
    try {
      dispatcher.execute(::drain)
    } catch (error: Throwable) {
      synchronized(lock) {
        pending.forEach { it.observers.forEach { observer -> observer.callback = null } }
        observers.forEach { it.callback = null }
        observers.clear()
        pending.clear()
        scheduled = false
      }
      diagnostic(error)
    }
  }

  private fun drain() {
    while (true) {
      val delivery = synchronized(lock) {
        if (pending.isEmpty()) { scheduled = false; null } else pending.removeFirst()
      } ?: return
      delivery.observers.forEach { observer ->
        val callback = synchronized(lock) {
          if (closed && !delivery.snapshot.closed) null else {
            observer.callback.also { if (delivery.snapshot.closed) observer.callback = null }
          }
        }
        // Callback invocation is claimed before any concurrent unsubscribe/close, then runs unlocked.
        if (callback != null) {
          try { callback(delivery.snapshot) } catch (error: Throwable) { diagnostic(error) }
        }
      }
    }
  }

  private fun diagnostic(error: Throwable) {
    try { report(error) } catch (_: Throwable) { /* Reporting cannot stall remaining observers. */ }
  }
}

internal object ScanSessionMainOwner : Executor {
  private val handler by lazy { Handler(Looper.getMainLooper()) }
  override fun execute(command: Runnable) {
    check(handler.post(command)) { "The Android main owner is unavailable." }
  }

  fun report(error: Throwable) { Log.w("RNDocumentScanner", "Session metadata delivery failed.", error) }
}
