package com.margelo.nitro.documentscanner

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePagerViewTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test
  fun hybridSelectionUsesPageIdsAcrossOrderChangesAndRejectsUnknownSelection() {
    instrumentation.runOnMainSync {
      val context = instrumentation.targetContext
      SessionViewFixture(context).use { fixture ->
        listOf("a", "b", "c").forEach(fixture::admit)
        val react = com.facebook.react.bridge.BridgeReactContext(context)
        val themed = com.facebook.react.uimanager.ThemedReactContext(react, context, "PagerValidation", -1)
        val hybrid = HybridImagePagerView(themed)
        hybrid.session = HybridScanSession(fixture.state)
        hybrid.pageIds = arrayOf("a", "b", "c")
        hybrid.selectedPageId = "b"
        hybrid.afterUpdate()
        val pager = hybrid.view as ImagePagerView
        assertEquals(1, pager.current)
        hybrid.pageIds = arrayOf("b", "c", "a")
        hybrid.afterUpdate()
        assertEquals(0, pager.current)
        assertEquals(listOf("b", "c", "a"), fixture.state.snapshot().pageIds.toList())
        hybrid.pageIds = null // Following the session does not reset its retained order.
        hybrid.afterUpdate()
        fixture.state.setPageOrder(listOf("c", "b", "a"))
        assertEquals(1, pager.current)
        hybrid.selectedPageId = "a"
        hybrid.afterUpdate()
        assertEquals(2, pager.current)
        var errors = 0
        hybrid.onError = { errors++ }
        hybrid.selectedPageId = "missing"
        hybrid.afterUpdate()
        assertEquals(1, errors)
        assertEquals(0, pager.adapter?.itemCount)
        hybrid.onDropView()
      }
    }
  }

  @Test
  fun acceptedPagesCanDisappearBeforeReactUpdatesThePagerProps() {
    instrumentation.runOnMainSync {
      val context = instrumentation.targetContext
      SessionViewFixture(context).use { fixture ->
        listOf("a", "b").forEach(fixture::admit)
        val react = com.facebook.react.bridge.BridgeReactContext(context)
        val themed = com.facebook.react.uimanager.ThemedReactContext(react, context, "PagerDeletion", -1)
        val hybrid = HybridImagePagerView(themed)
        hybrid.session = HybridScanSession(fixture.state)
        hybrid.pageIds = arrayOf("a", "b")
        hybrid.selectedPageId = "a"
        var errors = 0
        hybrid.onError = { errors++ }
        hybrid.afterUpdate()
        val pager = hybrid.view as ImagePagerView
        fixture.state.removePage("a")
        hybrid.afterUpdate() // An unrelated React update still carries the deleted ID.
        assertEquals(1, pager.adapter?.itemCount)
        fixture.state.removePage("b")
        hybrid.afterUpdate()
        assertEquals(0, pager.adapter?.itemCount)
        hybrid.pageIds = emptyArray()
        hybrid.selectedPageId = ""
        hybrid.afterUpdate()
        assertEquals(0, errors)
        hybrid.pageIds = arrayOf("unknown")
        hybrid.selectedPageId = "unknown"
        hybrid.afterUpdate()
        assertEquals(1, errors)
        hybrid.onDropView()
      }
    }
  }

  @Test
  fun directionAndOrderChangesKeepTheLogicalSelectedId() {
    val activity = instrumentation.startActivitySync(
      Intent(instrumentation.targetContext, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as ComponentActivity
    val fixture = SessionViewFixture(activity)
    lateinit var pager: ImagePagerView
    try {
      instrumentation.runOnMainSync {
        listOf("a", "b", "c").forEach(fixture::admit)
        val parent = FrameLayout(activity).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        pager = ImagePagerView(activity, onPageSettled = {}, onImageLoaded = {}, onImageFailed = {})
        parent.addView(pager)
        activity.setContentView(parent)
        pager.setPages(listOf("a", "b", "c"), fixture.state)
        pager.show(1)
        pager.setDirection(ImagePagerDirection.LTR)
        assertEquals(View.LAYOUT_DIRECTION_LTR, pager.layoutDirection)
        assertEquals(1, pager.current)
        pager.setPages(listOf("b", "c", "a"), fixture.state)
        pager.show(1)
        assertEquals(0, pager.current)
        pager.setDirection(ImagePagerDirection.RTL)
        assertEquals(0, pager.current)
        pager.setDirection(ImagePagerDirection.AUTO)
        pager.show(2)
      }
      instrumentation.waitForIdleSync()
      instrumentation.runOnMainSync {
        assertEquals(View.LAYOUT_DIRECTION_RTL, pager.layoutDirection)
        assertEquals(2, pager.current)
      }
    } finally {
      instrumentation.runOnMainSync { pager.release(); activity.finish() }
      fixture.close()
    }
  }

  @Test
  fun droppingThePagerWhileItsScreenAnimatesAwayKeepsItsPages() {
    val activity = instrumentation.startActivitySync(
      Intent(instrumentation.targetContext, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as ComponentActivity
    val fixture = SessionViewFixture(activity)
    try {
      lateinit var pager: ImagePagerView
      instrumentation.runOnMainSync {
        pager = ImagePagerView(activity, onPageSettled = {}, onImageLoaded = {}, onImageFailed = {})
        activity.setContentView(FrameLayout(activity).apply { addView(pager, 400, 600) })
        listOf("a", "b", "c").forEach(fixture::admit)
        pager.setPages(listOf("a", "b", "c"), fixture.state)
        pager.show(1)
        pager.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        pager.layout(0, 0, 400, 600)
      }
      instrumentation.waitForIdleSync()
      instrumentation.runOnMainSync {
        assertTrue("pages laid out", pager.childCount > 0)
        // As react-native-screens holds a closing screen's views in a transition.
        for (index in 0 until pager.childCount) pager.startViewTransition(pager.getChildAt(index))
        // React drops the pager while its screen is still on screen.
        pager.release()
        pager.requestLayout()
      }
      // A relayout that recycled the transitioning pages would throw here.
      instrumentation.waitForIdleSync()
      instrumentation.runOnMainSync {
        assertTrue("the pages stay for the animation", pager.childCount > 0)
        (pager.parent as ViewGroup).removeView(pager)
      }
      instrumentation.waitForIdleSync()
    } finally {
      fixture.close()
      activity.finish()
    }
  }
}
