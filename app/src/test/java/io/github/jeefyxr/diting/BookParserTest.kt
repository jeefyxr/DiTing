package io.github.jeefyxr.diting

import io.github.jeefyxr.diting.data.BookFormatException
import io.github.jeefyxr.diting.data.BookParser
import io.github.jeefyxr.diting.data.BookStore
import io.github.jeefyxr.diting.data.EpubParser
import io.github.jeefyxr.diting.data.HtmlText
import io.github.jeefyxr.diting.data.TxtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookParserTest {
    @get:Rule val tmp = TemporaryFolder()

    private val novel = "﻿书名\r\n\r\n　　这是简介。\r\n\r\n第一章 风起\r\n　　天色已晚。\r\n\r\n  他推开门。\r\n第二章　云涌\r\n雨下了一夜。\r\n番外 后来\r\n很多年以后。\r\n"

    @Test fun txtSplitsChaptersAndIndentsParagraphs() {
        val book = BookParser.parse("我的小说.txt", novel.toByteArray(Charsets.UTF_8))
        assertEquals("我的小说", book.title)
        assertEquals(listOf("我的小说", "第一章 风起", "第二章　云涌", "番外 后来"), book.chapters.map { it.title })
        assertEquals("　　书名\n　　这是简介。", book.chapters[0].text)
        assertEquals("　　天色已晚。\n　　他推开门。", book.chapters[1].text)
    }

    @Test fun txtDecodesGbkUtf16AndUtf8() {
        val expected = BookParser.parse("a.txt", novel.toByteArray(Charsets.UTF_8))
        val plain = novel.removePrefix("﻿")
        assertEquals(expected, BookParser.parse("a.txt", plain.toByteArray(Charset.forName("GBK"))))
        assertEquals(expected, BookParser.parse("a.txt", byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + plain.toByteArray(Charsets.UTF_16LE)))
        assertEquals(expected, BookParser.parse("a.txt", plain.toByteArray(Charsets.UTF_8)))
    }

    @Test fun headingDetectionIsNotFooledByProse() {
        assertTrue(TxtParser.isHeading("第一百二十三章 归来"))
        assertTrue(TxtParser.isHeading("第12回"))
        assertTrue(TxtParser.isHeading("Chapter 7 The End"))
        assertTrue(TxtParser.isHeading("楔子"))
        assertFalse(TxtParser.isHeading("第一次见到她的时候，天正下着雨，他站在屋檐下等了很久很久，直到街灯一盏一盏亮起来，才看见她撑着伞从巷口走来。"))
        assertFalse(TxtParser.isHeading("他说第三天再来。"))
    }

    @Test fun longTextWithoutHeadingsIsSplitWithoutLosingText() {
        val para = "这是一段没有任何章节标题的文字，用来测试超长章节的切分。"
        val text = (1..2000).joinToString("\n") { "$it$para" }
        val book = BookParser.parse("长文.txt", text.toByteArray())
        assertTrue(book.chapters.size > 1)
        assertTrue(book.chapters.all { it.text.length <= BookParser.MAX_CHAPTER_CHARS })
        assertEquals("长文 (1/${book.chapters.size})", book.chapters[0].title)
        val rejoined = book.chapters.joinToString("\n") { it.text }.split('\n').map { it.trim('　') }
        assertEquals(text.split('\n'), rejoined)
    }

    @Test fun singleHugeLineIsSplit() {
        val book = BookParser.parse("x.txt", "字".repeat(30_000).toByteArray())
        assertEquals(3, book.chapters.size)
        assertEquals(30_000, book.chapters.sumOf { it.text.count { c -> c == '字' } })
    }

    @Test fun emptyFileIsRejected() {
        assertThrows(BookFormatException::class.java) { BookParser.parse("empty.txt", "  \n\n".toByteArray()) }
        assertThrows(BookFormatException::class.java) { BookParser.parse("bad.epub", "not a zip".toByteArray()) }
    }

    @Test fun htmlToText() {
        val html = """<html><head><title>T</title><style>p{color:red}</style></head><body>
            <h1>标题 &amp; 副标题</h1><p>第一段，<b>加粗</b>
            仍是第一段。</p><p>第二段&#8212;&#x4e2d;&nbsp;</p><div>甲<br/>乙</div><!-- <p>注释</p> --><script>var a = "<p>x</p>";</script></body></html>"""
        assertEquals(listOf("标题 & 副标题", "第一段，加粗 仍是第一段。", "第二段—中", "甲", "乙"), HtmlText.lines(html))
        assertEquals("标题 & 副标题", HtmlText.firstHeading(html))
        assertNull(HtmlText.firstHeading("<p>没有标题</p>"))
    }

    @Test fun epubPathResolution() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubParser.resolve("OEBPS", "text/ch1.xhtml#p3"))
        assertEquals("text/第一章.xhtml", EpubParser.resolve("OEBPS/nav", "../../text/%E7%AC%AC%E4%B8%80%E7%AB%A0.xhtml"))
        assertEquals("a+b.xhtml", EpubParser.resolve("", "a+b.xhtml"))
    }

    private fun epub(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val container = "META-INF/container.xml" to
        """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    @Test fun epub2WithNcx() {
        val bytes = epub(
            "mimetype" to "application/epub+zip",
            container,
            "OEBPS/content.opf" to """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>围城 &amp; 其他</dc:title></metadata>
                <manifest>
                  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
                  <item id='c1' href='text/c%201.xhtml' media-type='application/xhtml+xml'/>
                  <item id="c2" href="text/c2.xhtml" media-type="application/xhtml+xml"/>
                  <item id="img" href="img/a.png" media-type="image/png"/>
                </manifest>
                <spine toc="ncx"><itemref idref="cover"/><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OEBPS/toc.ncx" to """<ncx><navMap>
                <navPoint id="a"><navLabel><text>第一章 归国</text></navLabel><content src="text/c%201.xhtml#top"/></navPoint>
                </navMap></ncx>""",
            "OEBPS/text/cover.xhtml" to "<html><body><img src='../img/a.png'/></body></html>",
            "OEBPS/text/c 1.xhtml" to "<html><head><title>x</title></head><body><h2>第一章 归国</h2><p>红海早过了。</p><p>船在印度洋面上开驶着。</p></body></html>",
            "OEBPS/text/c2.xhtml" to "<html><body><h1>第二章</h1><p>据说“女朋友”就是“情人”的学名。</p></body></html>",
            "OEBPS/img/a.png" to "binary",
        )
        val book = BookParser.parse("weicheng.epub", bytes)
        assertEquals("围城 & 其他", book.title)
        assertEquals(listOf("第一章 归国", "第二章"), book.chapters.map { it.title })
        assertEquals("　　红海早过了。\n　　船在印度洋面上开驶着。", book.chapters[0].text)
        assertEquals("　　据说“女朋友”就是“情人”的学名。", book.chapters[1].text)
    }

    @Test fun epub3WithNavDocument() {
        val bytes = epub(
            container,
            "OEBPS/content.opf" to """<package version="3.0"><metadata><dc:title id="t">三体</dc:title></metadata><manifest>
                <item properties="nav" id="nav" href="nav.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="c1"/></spine></package>""",
            "OEBPS/nav.xhtml" to """<html><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">1. <span>科学边界</span></a></li></ol></nav></body></html>""",
            "OEBPS/c1.xhtml" to "<html><body><p>汪淼觉得，来找他的这四个人是一个奇怪的组合。</p></body></html>",
        )
        // Detected as EPUB by content even without the extension.
        val book = BookParser.parse("download", bytes)
        assertEquals("三体", book.title)
        assertEquals(listOf("1. 科学边界"), book.chapters.map { it.title })
    }

    @Test fun storeRoundTrip() {
        val store = BookStore(tmp.newFolder("books"))
        assertTrue(store.list().isEmpty())
        val a = store.import("我的小说.txt", novel.toByteArray(), now = 1000)
        val b = store.import("第二本.txt", "只有一段。".toByteArray(), now = 2000)
        assertEquals(listOf(b.id, a.id), store.list().map { it.id })

        val loaded = store.load(a.id)!!
        assertEquals(a, loaded)
        assertEquals(4, loaded.chapters.size)
        assertEquals("　　天色已晚。\n　　他推开门。", store.readChapter(loaded, 1))
        assertEquals("　　很多年以后。", store.readChapter(loaded, 3))
        assertEquals(loaded.totalBytes, loaded.chapters.sumOf { it.length.toLong() })

        store.delete(a.id)
        assertNull(store.load(a.id))
        assertEquals(1, store.list().size)
    }
}
