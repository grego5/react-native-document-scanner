package com.margelo.nitro.documentscanner

import android.view.View
import android.view.ViewGroup

/** Ensures PreviewView's dynamically added implementation view is measured inside React Native. */
internal fun ViewGroup.installHierarchyFitter() {
  setOnHierarchyChangeListener(
    object : ViewGroup.OnHierarchyChangeListener {
      override fun onChildViewRemoved(parent: View?, child: View?) = Unit

      override fun onChildViewAdded(parent: View?, child: View?) {
        val host = parent ?: return
        val widthSpec = View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY)
        host.measure(widthSpec, heightSpec)
        host.layout(host.left, host.top, host.left + host.measuredWidth, host.top + host.measuredHeight)
      }
    },
  )
}
