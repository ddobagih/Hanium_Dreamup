package kr.co.hanium.dreamup.walksafe

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidManifestCameraFeatureStaticTest {
    @Test
    fun baseCameraIsOptionalSoDevicesWithoutItCanUseLimitedMode() {
        val manifest = parseXml(File("src/main/AndroidManifest.xml"))
        val features = manifest.getElementsByTagName("uses-feature")
        val baseCameraFeatures = (0 until features.length)
            .map(features::item)
            .filter { it.androidAttribute("name") == "android.hardware.camera" }

        assertEquals(1, baseCameraFeatures.size)
        assertEquals("false", baseCameraFeatures.single().androidAttribute("required"))
    }

    private fun org.w3c.dom.Node.androidAttribute(name: String): String? =
        attributes.getNamedItemNS(ANDROID_NAMESPACE, name)?.nodeValue

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newDocumentBuilder().parse(file)

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
