package kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle

import com.google.ar.core.Session
import org.junit.Assert.*
import org.junit.Test

/** SDK doubles observe the original lazy geometry and resume order; no real camera is opened. */
class AndroidRuntimeArPlatformTest {
    @Test fun textureGeometryAndResumeAreReadAndInvokedAtOriginalPoints() {
        val trace=mutableListOf<String>()
        val display=object:RuntimeArDisplayGeometry {
            override val textureId:Int get(){trace+="texture";return 7}
            override val width:Int get(){trace+="width";return 320}
            override val height:Int get(){trace+="height";return 240}
            override fun rotation():Int{trace+="rotation";return 2}
        }
        val session=object:Session(){
            override fun setCameraTextureNames(ids:IntArray){assertArrayEquals(intArrayOf(7),ids);trace+="bind"}
            override fun setDisplayGeometry(rotation:Int,width:Int,height:Int){assertEquals(2,rotation);assertEquals(320,width);assertEquals(240,height);trace+="geometry"}
            override fun resume(){trace+="resume"}
        }
        val platform=AndroidRuntimeArPlatform({error("Native resume must not ask for Activity")},display)
        assertTrue(platform.resumeBeforePublish(session))
        assertEquals(listOf("texture","texture","bind","width","height","rotation","width","height","geometry","resume"),trace)
    }
    @Test fun absentTextureAndViewportDoNotReadExtraGeometryOrActivity() {
        var resumed=0;var textureReads=0
        val platform=AndroidRuntimeArPlatform({error("Activity")},object:RuntimeArDisplayGeometry {
            override val textureId:Int get(){textureReads++;return 0}
            override val width=0
            override val height:Int get()=error("height must short circuit")
            override fun rotation():Int=error("rotation must be unused")
        })
        val session=object:Session(){override fun resume(){resumed++}}
        assertFalse(platform.resumeBeforePublish(session));assertEquals(1,textureReads);assertEquals(1,resumed)
    }
    @Test fun geometryValuesAreNotCapturedBeforeTheRotationQuery() {
        var width=320;var height=240
        val platform=AndroidRuntimeArPlatform({error("Activity")},object:RuntimeArDisplayGeometry {
            override val textureId=0
            override val width get()=width
            override val height get()=height
            override fun rotation():Int{width=640;height=480;return 1}
        })
        val session=object:Session(){
            override fun setDisplayGeometry(rotation:Int,width:Int,height:Int){assertEquals(640,width);assertEquals(480,height)}
            override fun resume(){}
        }
        platform.resumeBeforePublish(session)
    }
    @Test fun nativeTextureFailureDoesNotProceedToGeometryOrResume() {
        val failure=IllegalStateException("texture")
        val platform=AndroidRuntimeArPlatform({error("Activity")},object:RuntimeArDisplayGeometry {
            override val textureId=7
            override val width:Int get()=error("geometry must not be queried after texture failure")
            override val height:Int get()=error("height")
            override fun rotation():Int=error("rotation")
        })
        val session=object:Session(){override fun setCameraTextureNames(ids:IntArray){throw failure};override fun resume(){fail("resume after failed texture")}}
        try{platform.resumeBeforePublish(session);fail("must preserve native exception")}catch(actual:IllegalStateException){assertSame(failure,actual)}
    }
}
