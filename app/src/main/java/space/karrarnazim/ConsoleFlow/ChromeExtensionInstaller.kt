package space.karrarnazim.ConsoleFlow

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

data class ParsedExtensionPayload(
    val extensionId: String,
    val name: String,
    val matchPattern: String,
    val script: String,
    val popupPath: String?,
    val sidePanelPath: String?,
    val optionsPage: String?
)

object ChromeExtensionInstaller {
    private val extensionIdRegex = Regex("([a-z]{32})")

    fun extractExtensionId(url: String): String? = extensionIdRegex.find(url)?.value

    fun buildCrxDownloadUrl(extensionId: String): String =
        "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=120.0.0.0&acceptformat=crx2,crx3&x=id%3D$extensionId%26installsource%3Dondemand%26uc"

    fun extractZipFromCrx(crxBytes: ByteArray): ByteArray {
        if (crxBytes.size >= 4 &&
            crxBytes[0] == 0x50.toByte() &&
            crxBytes[1] == 0x4B.toByte() &&
            crxBytes[2] == 0x03.toByte() &&
            crxBytes[3] == 0x04.toByte()
        ) {
            return crxBytes
        }

        val magic = if (crxBytes.size >= 4) String(crxBytes.copyOfRange(0, 4), Charsets.US_ASCII) else ""
        if (magic == "Cr24" && crxBytes.size >= 12) {
            val version = littleEndianInt(crxBytes, 4)
            val zipStart = when (version) {
                2 -> {
                    if (crxBytes.size < 16) -1 else {
                        val publicKeyLen = littleEndianInt(crxBytes, 8)
                        val signatureLen = littleEndianInt(crxBytes, 12)
                        16 + publicKeyLen + signatureLen
                    }
                }
                3 -> {
                    val headerSize = littleEndianInt(crxBytes, 8)
                    12 + headerSize
                }
                else -> -1
            }
            if (zipStart in 0 until crxBytes.size - 4 &&
                crxBytes[zipStart] == 0x50.toByte() &&
                crxBytes[zipStart + 1] == 0x4B.toByte() &&
                crxBytes[zipStart + 2] == 0x03.toByte() &&
                crxBytes[zipStart + 3] == 0x04.toByte()
            ) {
                return crxBytes.copyOfRange(zipStart, crxBytes.size)
            }
        }

        val signature = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK..
        var start = -1
        for (i in 0..(crxBytes.size - signature.size)) {
            if (crxBytes[i] == signature[0] &&
                crxBytes[i + 1] == signature[1] &&
                crxBytes[i + 2] == signature[2] &&
                crxBytes[i + 3] == signature[3]
            ) {
                start = i
                break
            }
        }
        if (start < 0) throw IllegalStateException("Invalid CRX package")
        return crxBytes.copyOfRange(start, crxBytes.size)
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun parseCrxPayload(extensionId: String, zipBytes: ByteArray): ParsedExtensionPayload {
        val files = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val data = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var len = zip.read(buffer)
                    while (len > 0) {
                        data.write(buffer, 0, len)
                        len = zip.read(buffer)
                    }
                    files[entry.name] = data.toString(Charsets.UTF_8.name())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val manifestRaw = files["manifest.json"] ?: throw IllegalStateException("manifest.json not found")
        val manifest = JSONObject(manifestRaw)
        val name = manifest.optString("name", "Chrome Extension $extensionId")
        val contentScripts = manifest.optJSONArray("content_scripts") ?: JSONArray()
        val scriptBuilder = StringBuilder()
        var matchPattern = "*"
        val popupPath = manifest.optJSONObject("action")?.optString("default_popup")?.ifBlank { null }
            ?: manifest.optJSONObject("browser_action")?.optString("default_popup")?.ifBlank { null }
        val sidePanelPath = manifest.optJSONObject("side_panel")?.optString("default_path")?.ifBlank { null }
        val optionsPage = manifest.optJSONObject("options_ui")?.optString("page")?.ifBlank { null }
            ?: manifest.optString("options_page").ifBlank { null }

        for (i in 0 until contentScripts.length()) {
            val obj = contentScripts.optJSONObject(i) ?: continue
            val matches = obj.optJSONArray("matches")
            if (matches != null && matches.length() > 0 && matchPattern == "*") {
                matchPattern = matches.optString(0, "*")
            }

            val cssArray = obj.optJSONArray("css") ?: JSONArray()
            for (j in 0 until cssArray.length()) {
                val fileName = cssArray.optString(j)
                if (fileName.isNotBlank()) {
                    val css = files[fileName] ?: continue
                    val cssEscaped = JSONObject.quote(css)
                    scriptBuilder.append(
                        """
                        (function(){
                            var style=document.createElement('style');
                            style.setAttribute('data-cf-ext', ${JSONObject.quote(extensionId)});
                            style.appendChild(document.createTextNode($cssEscaped));
                            (document.head||document.documentElement).appendChild(style);
                        })();
                        
                        """.trimIndent()
                    )
                }
            }

            val jsArray = obj.optJSONArray("js") ?: JSONArray()
            for (j in 0 until jsArray.length()) {
                val fileName = jsArray.optString(j)
                if (fileName.isNotBlank()) {
                    val source = files[fileName] ?: continue
                    scriptBuilder.append("\n/* $fileName */\n")
                    scriptBuilder.append(source)
                    scriptBuilder.append("\n")
                }
            }
        }

        val hasBackground = collectBackgroundScriptPaths(manifest).isNotEmpty()
        if (scriptBuilder.isBlank() && popupPath.isNullOrBlank() && sidePanelPath.isNullOrBlank() && optionsPage.isNullOrBlank() && !hasBackground) {
            throw IllegalStateException("No compatible content scripts or extension UI pages found")
        }

        return ParsedExtensionPayload(
            extensionId = extensionId,
            name = name,
            matchPattern = normalizeChromeMatch(matchPattern),
            script = scriptBuilder.toString(),
            popupPath = popupPath,
            sidePanelPath = sidePanelPath,
            optionsPage = optionsPage
        )
    }

    private fun normalizeChromeMatch(matchPattern: String): String {
        if (matchPattern == "<all_urls>") return "*"
        return matchPattern
            .replace("*://", "")
            .replace("http://", "")
            .replace("https://", "")
            .replace("/*", "")
            .replace("*.", "")
    }

    fun extractFileFromZip(zipBytes: ByteArray, targetPath: String): ByteArray? {
        val normalized = targetPath.trimStart('/')
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == normalized) {
                    val data = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    var len = zip.read(buffer)
                    while (len > 0) {
                        data.write(buffer, 0, len)
                        len = zip.read(buffer)
                    }
                    return data.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    fun extractTextFileFromZip(zipBytes: ByteArray, targetPath: String): String? {
        return extractFileFromZip(zipBytes, targetPath)?.toString(Charsets.UTF_8)
    }

    fun readManifest(zipBytes: ByteArray): JSONObject? {
        val raw = extractTextFileFromZip(zipBytes, "manifest.json") ?: return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun collectBackgroundScriptPaths(manifest: JSONObject): List<String> {
        val scripts = mutableListOf<String>()
        val background = manifest.optJSONObject("background")
        val mv2Scripts = background?.optJSONArray("scripts")
        if (mv2Scripts != null) {
            for (i in 0 until mv2Scripts.length()) {
                val path = mv2Scripts.optString(i)
                if (path.isNotBlank()) scripts.add(path)
            }
        }
        val mv3ServiceWorker = background?.optString("service_worker")
        if (!mv3ServiceWorker.isNullOrBlank()) {
            scripts.add(mv3ServiceWorker)
        }
        return scripts
    }
}
