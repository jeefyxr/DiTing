package io.github.jeefyxr.diting.data

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

data class ParsedChapter(val title: String, val text: String)
/** [cover] is the encoded cover image of an EPUB, if it has one. */
class ParsedBook(val title: String, val chapters: List<ParsedChapter>, val cover: ByteArray? = null)

class BookFormatException(message: String) : Exception(message)

/** Turns an imported file into plain-text chapters. Pure JVM code so it can be unit tested. */
object BookParser {
    /** Chapters longer than this are split so that laying one out stays fast on a phone. */
    const val MAX_CHAPTER_CHARS = 12_000
    private const val INDENT = "\u3000\u3000"

    fun parse(fileName: String, bytes: ByteArray): ParsedBook {
        val base = fileName.substringBeforeLast('.').ifBlank { fileName }
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val book = if (fileName.endsWith(".epub", ignoreCase = true) || isZip) {
            EpubParser.parse(base, bytes)
        } else {
            TxtParser.parse(base, bytes)
        }
        val chapters = book.chapters.filter { it.text.isNotBlank() }.flatMap(::splitOversized)
        if (chapters.isEmpty()) throw BookFormatException("文件中没有可阅读的文字")
        return ParsedBook(book.title.ifBlank { base }, chapters, book.cover)
    }

    /** Joins non-empty lines into paragraphs with the conventional two-character indent. */
    internal fun paragraphs(lines: List<String>): String = lines.joinToString("\n") { INDENT + it }

    internal fun cleanLine(line: String): String = line.trim { it.isWhitespace() || it == '\uFEFF' || it == '\u00A0' }

    private fun splitOversized(ch: ParsedChapter): List<ParsedChapter> {
        if (ch.text.length <= MAX_CHAPTER_CHARS) return listOf(ch)
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        for (para in ch.text.split('\n')) {
            var p = para
            // A single enormous paragraph (text with no line breaks) is cut hard.
            while (p.length > MAX_CHAPTER_CHARS) {
                if (sb.isNotEmpty()) { parts.add(sb.toString()); sb.setLength(0) }
                parts.add(p.substring(0, MAX_CHAPTER_CHARS))
                p = p.substring(MAX_CHAPTER_CHARS)
            }
            if (sb.isNotEmpty() && sb.length + 1 + p.length > MAX_CHAPTER_CHARS) { parts.add(sb.toString()); sb.setLength(0) }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(p)
        }
        if (sb.isNotEmpty()) parts.add(sb.toString())
        return parts.mapIndexed { i, t -> ParsedChapter("${ch.title} (${i + 1}/${parts.size})", t) }
    }
}

object TxtParser {
    private val HEADING = Regex(
        "^(第\\s*[0-9０-９零〇一二两三四五六七八九十百千万]+\\s*[章回节卷集部篇幕话].{0,40}" +
            "|(序章|序言|序幕|楔子|引子|前言|后记|尾声|番外|终章).{0,30}" +
            "|(?i:chapter|part)\\s+[0-9ivxlc]+.{0,40})$"
    )

    fun decode(b: ByteArray): String {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.size >= 3 && u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF) return String(b, 3, b.size - 3, Charsets.UTF_8)
        if (b.size >= 2 && u(0) == 0xFF && u(1) == 0xFE) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && u(0) == 0xFE && u(1) == 0xFF) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            // Most non-UTF-8 Chinese text files are GBK/GB2312, both subsets of GB18030.
            String(b, Charset.forName("GB18030"))
        }
    }

    fun isHeading(line: String) = line.length <= 50 && HEADING.matches(line)

    fun parse(title: String, bytes: ByteArray): ParsedBook {
        val chapters = ArrayList<ParsedChapter>()
        var current = title
        var body = ArrayList<String>()
        fun flush() {
            if (body.isNotEmpty()) chapters.add(ParsedChapter(current, BookParser.paragraphs(body)))
            body = ArrayList()
        }
        for (raw in decode(bytes).lineSequence()) {
            val line = BookParser.cleanLine(raw)
            if (line.isEmpty()) continue
            if (isHeading(line)) { flush(); current = line } else body.add(line)
        }
        flush()
        return ParsedBook(title, chapters)
    }
}

/** Minimal EPUB 2/3 reader: follows the spine and keeps only the text. */
object EpubParser {
    private const val MAX_UNPACKED = 300L * 1024 * 1024
    private val SKIP_EXT = setOf("svg", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "css", "js")
    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp")
    private const val MAX_IMAGE = 8 * 1024 * 1024
    private val ATTR = Regex("([\\w:.-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    fun parse(fallbackTitle: String, bytes: ByteArray): ParsedBook {
        val files = unzip(bytes)
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: throw BookFormatException("不是有效的 EPUB 文件")
        val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1)
            ?: throw BookFormatException("EPUB 缺少内容清单")
        val opf = files[opfPath]?.toString(Charsets.UTF_8) ?: throw BookFormatException("EPUB 缺少内容清单")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val title = Regex("(?is)<dc:title[^>]*>(.*?)</dc:title>").find(opf)
            ?.let { HtmlText.inline(it.groupValues[1]) }.orEmpty().ifBlank { fallbackTitle }

        class Item(val path: String, val type: String, val props: String)
        val manifest = HashMap<String, Item>()
        for (m in Regex("(?is)<(?:\\w+:)?item\\b([^>]*)>").findAll(opf)) {
            val a = attrs(m.groupValues[1])
            val id = a["id"] ?: continue
            val href = a["href"] ?: continue
            manifest[id] = Item(resolve(opfDir, href), a["media-type"].orEmpty(), a["properties"].orEmpty())
        }
        val spineTag = Regex("(?is)<(?:\\w+:)?spine\\b([^>]*)>").find(opf)
        val spine = Regex("(?is)<(?:\\w+:)?itemref\\b([^>]*)>").findAll(opf)
            .mapNotNull { attrs(it.groupValues[1])["idref"] }.mapNotNull { manifest[it] }.toList()
        if (spine.isEmpty()) throw BookFormatException("EPUB 没有正文")

        // Chapter titles from the table of contents (EPUB 3 nav document, else EPUB 2 NCX).
        val labels = HashMap<String, String>()
        manifest.values.firstOrNull { "nav" in it.props.split(' ') }?.let { nav ->
            val html = files[nav.path]?.toString(Charsets.UTF_8) ?: return@let
            val dir = nav.path.substringBeforeLast('/', "")
            for (m in Regex("(?is)<a\\b([^>]*)>(.*?)</a>").findAll(html)) {
                val href = attrs(m.groupValues[1])["href"] ?: continue
                labels.putIfAbsent(resolve(dir, href), HtmlText.inline(m.groupValues[2]))
            }
        }
        val ncx = spineTag?.let { attrs(it.groupValues[1])["toc"] }?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
        if (ncx != null) {
            val xml = files[ncx.path]?.toString(Charsets.UTF_8).orEmpty()
            val dir = ncx.path.substringBeforeLast('/', "")
            val point = Regex("(?is)<(?:\\w+:)?text>(.*?)</(?:\\w+:)?text>\\s*</(?:\\w+:)?navLabel>\\s*<(?:\\w+:)?content\\b([^>]*)>")
            for (m in point.findAll(xml)) {
                val src = attrs(m.groupValues[2])["src"] ?: continue
                labels.putIfAbsent(resolve(dir, src), HtmlText.inline(m.groupValues[1]))
            }
        }

        val chapters = ArrayList<ParsedChapter>()
        for (item in spine) {
            val html = files[item.path]?.let(TxtParser::decode) ?: continue
            val lines = HtmlText.lines(html).toMutableList()
            if (lines.isEmpty()) continue
            val name = labels[item.path]?.takeIf { it.isNotBlank() }
                ?: HtmlText.firstHeading(html)
                ?: "第 ${chapters.size + 1} 节"
            // The heading is shown as the chapter title, so do not repeat it as the first paragraph.
            if (lines.first() == name) lines.removeAt(0)
            if (lines.isNotEmpty()) chapters.add(ParsedChapter(name, BookParser.paragraphs(lines)))
        }
        // EPUB 3 marks the cover in the manifest; EPUB 2 names it in a <meta name="cover">.
        val coverItem = manifest.values.firstOrNull { "cover-image" in it.props.split(' ') }
            ?: Regex("(?is)<(?:\\w+:)?meta\\b([^>]*)>").findAll(opf).map { attrs(it.groupValues[1]) }
                .firstOrNull { it["name"] == "cover" }?.get("content")?.let { manifest[it] }
        val cover = coverItem?.takeIf { it.type.startsWith("image/") && it.type != "image/svg+xml" }?.let { files[it.path] }
        return ParsedBook(title, chapters, cover)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    if (e.isDirectory || ext in SKIP_EXT) continue
                    val data = zin.readBytes()
                    // Images are only kept as cover candidates.
                    if (ext in IMAGE_EXT && data.size > MAX_IMAGE) continue
                    total += data.size
                    if (total > MAX_UNPACKED) throw BookFormatException("EPUB 文件过大")
                    out[e.name] = data
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw BookFormatException("不是有效的 EPUB 文件")
        }
        return out
    }

    private fun attrs(s: String): Map<String, String> =
        ATTR.findAll(s).associate { it.groupValues[1].substringAfter(':') to HtmlText.entities(it.groupValues[2] + it.groupValues[3]) }

    /** Resolves a manifest href against a directory inside the archive, dropping any #fragment. */
    internal fun resolve(dir: String, href: String): String {
        val clean = href.substringBefore('#')
        val decoded = try { URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { clean }
        val parts = ArrayList<String>()
        for (p in (if (dir.isEmpty()) decoded else "$dir/$decoded").split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }
}

object HtmlText {
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "mdash" to "—", "ndash" to "–",
        "hellip" to "…", "middot" to "·", "copy" to "©", "emsp" to " ", "ensp" to " ", "thinsp" to " "
    )
    private val ENTITY = Regex("&(#[xX]?[0-9a-fA-F]+|\\w+);")
    private val DROP = Regex("(?is)<(head|script|style|svg)\\b.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val SPACE = Regex("\\s+")
    private val BREAK = Regex("(?i)<br\\b[^>]*>|</?(p|div|h[1-6]|li|tr|blockquote|section|article|hr|dt|dd|pre|title|body)\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val HEADING = Regex("(?is)<h[1-3]\\b[^>]*>(.*?)</h[1-3]\\s*>")

    fun entities(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[e]
        } ?: m.value
    }

    private fun codePoint(cp: Int): String? = if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else null

    /** Text of an inline fragment, on one line. */
    fun inline(html: String): String = BookParser.cleanLine(entities(TAG.replace(SPACE.replace(html, " "), "")))

    fun firstHeading(html: String): String? = HEADING.find(html)?.let { inline(it.groupValues[1]) }?.takeIf { it.isNotBlank() }

    /** Non-empty text lines of a document, one per block element. */
    fun lines(html: String): List<String> {
        var s = COMMENT.replace(DROP.replace(html, ""), "")
        s = SPACE.replace(s, " ")
        s = BREAK.replace(s, "\n")
        s = entities(TAG.replace(s, ""))
        return s.split('\n').map(BookParser::cleanLine).filter { it.isNotEmpty() }
    }
}
