package com.margelo.nitro.documentscanner

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagePagerViewTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  @Test
  fun droppingThePagerWhileItsScreenAnimatesAwayKeepsItsPages() {
    val activity = instrumentation.startActivitySync(
      Intent(instrumentation.targetContext, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as ComponentActivity
    try {
      lateinit var pager: ImagePagerView
      instrumentation.runOnMainSync {
        pager = ImagePagerView(activity, onPageSettled = {}, onImageLoaded = {}, onImageFailed = {})
        activity.setContentView(FrameLayout(activity).apply { addView(pager, 400, 600) })
        pager.setPages(listOf("", "", ""))
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
      activity.finish()
    }
  }
}
