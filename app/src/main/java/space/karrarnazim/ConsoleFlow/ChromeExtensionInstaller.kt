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
    val script: String
)

object ChromeExtensionInstaller {
    private val extensionIdRegex = Regex("([a-z]{32})")

    fun extractExtensionId(url: String): String? = extensionIdRegex.find(url)?.value

    fun buildCrxDownloadUrl(extensionId: String): String =
        "https://clients2.google.com/service/update2/crx?response=redirect&prodversion=120.0.0.0&acceptformat=crx2,crx3&x=id%3D$extensionId%26installsource%3Dondemand%26uc"

    fun extractZipFromCrx(crxBytes: ByteArray): ByteArray {
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

        if (scriptBuilder.isBlank()) throw IllegalStateException("No compatible content scripts found")

        return ParsedExtensionPayload(
            extensionId = extensionId,
            name = name,
            matchPattern = normalizeChromeMatch(matchPattern),
            script = scriptBuilder.toString()
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
}
