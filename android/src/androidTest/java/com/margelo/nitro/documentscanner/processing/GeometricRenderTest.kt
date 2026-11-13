package com.margelo.nitro.documentscanner.processing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.soloader.SoLoader
import com.margelo.nitro.JNIOnLoad
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.*
import java.security.MessageDigest
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GeometricRenderTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  @Before fun initialize() {
    SoLoader.init(context, false)
    JNIOnLoad.initializeNativeNitro()
    OpenCvRuntime.ensureLoaded()
  }
  @Test fun prefilterPixelsMatchPreMigrationGoldens() {
    val fixtureAssets = InstrumentationRegistry.getInstrumentation().context.assets
    val goldens = fixtureAssets.open("parity/warp.tsv").bufferedReader().use { it.readLines() }
      .filter { !it.startsWith("#") && it.isNotBlank() }.drop(1).associate { row -> row.split('\t').let { it[0] to it } }
    for (name in listOf("flat", "bent", "ambiguous", "outside", "scaled")) {
      val source = fixture(name)
      val into = Mat()
      try {
        val corners = if (name == "outside") doubleArrayOf(-.02,.125,.875,.125,.875,.875,.125,.875)
          else doubleArrayOf(.125,.125,.875,.125,.875,.875,.125,.875)
        val scale = if (name == "scaled") 2.0 else 1.0
        val result = NativeRenderGeometry.render(source, into, corners, scale, if (scale > 1) 720.0 else null, true)
        val metadata = doubleArrayOf(if (result.curved) 1.0 else 0.0) +
          result.validContentPolygon.flatMap { listOf(it.x, it.y) }.toDoubleArray()
        val pixels = ByteArray((into.total()*into.channels()).toInt()).also { into.get(0,0,it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(pixels).joinToString("") { "%02x".format(it) }
        val expected = goldens.getValue(name)
        assertEquals(name, expected[1].toInt(), into.cols())
        assertEquals(name, expected[2].toInt(), into.rows())
        assertEquals("$name pre-filter RGBA", expected[3], hash)
        assertArrayEquals(name, expected[4].split(',').map(String::toDouble).toDoubleArray(), metadata, 1e-12)
        val straight = Mat()
        try {
          val straightResult = NativeRenderGeometry.render(source, straight, corners, scale, if (scale > 1) 720.0 else null, false)
          assertEquals(result.validContentPolygon, straightResult.validContentPolygon)
          if (!result.curved) assertEquals(0.0, Core.norm(into, straight, Core.NORM_INF), 0.0)
        } finally { straight.release() }
      } finally { source.release(); into.release() }
    }
  }
  @Test fun unsupportedBendsAndBoundaryFailuresDoNotModifySource() {
    val source = Mat(96,128,CvType.CV_8UC4,Scalar(120.0,80.0,40.0,255.0))
    val before = source.clone()
    val into = Mat()
    val corners = doubleArrayOf(.1,.1,.9,.1,.9,.9,.1,.9)
    try {
      val result = NativeRenderGeometry.render(source,into,corners,1.0,null,true)
      assertFalse(result.curved)
      assertTrue(result.validContentPolygon.size >= 4)
      assertThrows(IllegalArgumentException::class.java) { NativeRenderGeometry.render(source,source,corners,1.0,null,true) }
      assertThrows(IllegalArgumentException::class.java) { NativeRenderGeometry.render(source,into,corners.copyOf(7),1.0,null,true) }
      assertThrows(com.margelo.nitro.documentscanner.InvalidInputException::class.java) {
        NativeRenderGeometry.render(source,into,corners.copyOf().apply {this[0]=Double.NaN},1.0,null,true)
      }
      assertEquals(0.0,Core.norm(before,source,Core.NORM_INF),0.0)
      NativeRenderGeometry.render(source,into,corners,1.0,null,false)
      assertFalse(into.empty())
    } finally {source.release();before.release();into.release()}
  }
  internal fun fixture(name: String): Mat {
    fun bend(a: Double,b: Double,t: Double)=t*(1-t)*(a+b*t)
    fun shade(sx: Double,sy: Double): Int {
      var x=sx-80;var y=sy-60
      if (name=="bent" || name=="outside" || name=="scaled") repeat(30) {
        val u=x/480;val v=y/360
        val px=x-(1-u)*bend(-12.0,-20.0,v)+u*bend(16.0,0.0,v)
        val py=y-(1-v)*bend(-40.0,0.0,u)+v*bend(-28.0,-16.0,u)
        x+=sx-80-px;y+=sy-60-py
      }
      if (x !in 0.0..480.0 || y !in 0.0..360.0) return 64
      // A competing middle rule exercises the existing ambiguity handling alongside the outer page edge.
      if (name=="ambiguous" && x in 130.0..350.0 && y in 9.0..15.0) return 0
      val ink=x in 20.0..460.0 && y in 20.0..340.0 &&
        ((1..7).any {abs(x-it*60)<=1.5} || (1..5).any {abs(y-it*60)<=1.5})
      return if(ink) 0 else 255
    }
    val bytes=ByteArray(640*480*4)
    for(y in 0 until 480) for(x in 0 until 640) {
      val level=listOf(-.25,.25).sumOf { dx->listOf(-.25,.25).sumOf {dy->shade(x+dx,y+dy)}}/4
      val i=(y*640+x)*4
      bytes[i]=level.toByte();bytes[i+1]=level.toByte();bytes[i+2]=level.toByte();bytes[i+3]=255.toByte()
    }
    return Mat(480,640,CvType.CV_8UC4).also {it.put(0,0,bytes)}
  }
}
