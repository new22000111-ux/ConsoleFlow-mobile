package space.karrarnazim.ConsoleFlow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ChromeExtensionInstallerTest {

    @Test
    fun extractExtensionId_fromChromeStoreUrl() {
        val url = "https://chromewebstore.google.com/detail/sample/abcdefghijklmnopqrstuvwxyzzzzzz"
        val id = ChromeExtensionInstaller.extractExtensionId(url)
        assertEquals("abcdefghijklmnopqrstuvwxyzzzzzz", id)
    }

    @Test
    fun extractZipFromCrx_extractsZipPayload() {
        val fakeHeader = byteArrayOf(67, 114, 50, 52, 0, 0, 0) // mock CRX header bytes
        val fakeZip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4)
        val crx = fakeHeader + fakeZip

        val extracted = ChromeExtensionInstaller.extractZipFromCrx(crx)
        assertNotNull(extracted)
        assertEquals(0x50.toByte(), extracted[0])
        assertEquals(0x4B.toByte(), extracted[1])
    }

    @Test
    fun extractZipFromCrx_acceptsRawZip() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 9, 9)
        val extracted = ChromeExtensionInstaller.extractZipFromCrx(zip)
        assertTrue(extracted.contentEquals(zip))
    }

    @Test
    fun collectBackgroundScriptPaths_supportsMv2AndMv3() {
        val manifest = JSONObject(
            """
            {
              "background": {
                "scripts": ["bg/a.js", "bg/b.js"],
                "service_worker": "worker.js"
              }
            }
            """.trimIndent()
        )
        val scripts = ChromeExtensionInstaller.collectBackgroundScriptPaths(manifest)
        assertEquals(listOf("bg/a.js", "bg/b.js", "worker.js"), scripts)
    }
    @Test
    fun parseCrxPayload_resolvesLocalizedManifestName() {
        val zip = zipOf(
            "manifest.json" to """
                {
                  "name": "__MSG_appName__",
                  "default_locale": "en",
                  "content_scripts": [{"matches": ["<all_urls>"], "js": ["content.js"]}]
                }
            """.trimIndent(),
            "_locales/en/messages.json" to """
                {
                  "appName": {"message": "Readable Extension Name"}
                }
            """.trimIndent(),
            "content.js" to "console.log('ok');"
        )

        val payload = ChromeExtensionInstaller.parseCrxPayload("abcdefghijklmnopqrstuvwxyzzzzzz", zip)

        assertEquals("Readable Extension Name", payload.name)
    }

    @Test
    fun resolveExtensionName_fallsBackAcrossAvailableLocales() {
        val zip = zipOf(
            "manifest.json" to """
                {
                  "name": "__MSG_app_name__",
                  "default_locale": "ar"
                }
            """.trimIndent(),
            "_locales/en_US/messages.json" to """
                {
                  "app_name": {"message": "Fallback English Name"}
                }
            """.trimIndent()
        )

        val name = ChromeExtensionInstaller.resolveExtensionName(zip, "__MSG_app_name__")

        assertEquals("Fallback English Name", name)
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

}
