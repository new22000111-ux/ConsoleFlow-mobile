package space.karrarnazim.ConsoleFlow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
