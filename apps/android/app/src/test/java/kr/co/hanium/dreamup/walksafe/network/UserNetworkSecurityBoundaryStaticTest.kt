package kr.co.hanium.dreamup.walksafe.network

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserNetworkSecurityBoundaryStaticTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun productionDisablesCleartextAndDebugAllowsOnlyExactLoopbackHosts() {
        val main = parseXml(File("src/main/AndroidManifest.xml"))
        val mainApplication = main.getElementsByTagName("application").item(0)
        assertEquals(
            "false",
            mainApplication.attributes
                .getNamedItemNS(androidNamespace, "usesCleartextTraffic")
                .nodeValue,
        )

        val debugFile = File("src/debug/AndroidManifest.xml")
        val debugText = debugFile.readText()
        assertFalse(debugText.contains("usesCleartextTraffic=\"true\""))
        val debug = parseXml(debugFile)
        val debugApplication = debug.getElementsByTagName("application").item(0)
        assertEquals(
            "@xml/debug_network_security_config",
            debugApplication.attributes
                .getNamedItemNS(androidNamespace, "networkSecurityConfig")
                .nodeValue,
        )

        val config = parseXml(File("src/debug/res/xml/debug_network_security_config.xml"))
        val bases = config.getElementsByTagName("base-config")
        assertEquals(1, bases.length)
        assertEquals("false", bases.item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue)
        val domainConfigs = config.getElementsByTagName("domain-config")
        assertEquals(1, domainConfigs.length)
        assertEquals(
            "true",
            domainConfigs.item(0).attributes.getNamedItem("cleartextTrafficPermitted").nodeValue,
        )
        val domains = config.getElementsByTagName("domain")
        assertEquals(2, domains.length)
        val allowed = buildSet {
            for (index in 0 until domains.length) {
                val domain = domains.item(index)
                assertEquals(
                    "false",
                    domain.attributes.getNamedItem("includeSubdomains").nodeValue,
                )
                add(domain.textContent.trim())
            }
        }
        assertEquals(setOf("127.0.0.1", "localhost"), allowed)
        assertEquals(0, config.getElementsByTagName("trust-anchors").length)
        assertEquals(0, config.getElementsByTagName("debug-overrides").length)
    }

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }.newDocumentBuilder().parse(file)
}
