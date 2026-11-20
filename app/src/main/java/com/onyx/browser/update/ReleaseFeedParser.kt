package com.onyx.browser.update

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.regex.Pattern

/** Parses the public GitHub releases Atom feed into [AtomEntry] items. */
object ReleaseFeedParser {

    // ── Atom Feed Parsing ────────────────────────────────────────────────────

    data class AtomEntry(val tag: String, val title: String, val contentHtml: String)

    fun parseAtomEntries(xml: String): List<AtomEntry> {
        val entries = mutableListOf<AtomEntry>()
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            var insideEntry = false
            var title: String? = null
            var tag: String? = null
            var contentHtml: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val name = parser.name ?: ""
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (name.equals("entry", ignoreCase = true)) {
                            insideEntry = true
                        } else if (insideEntry) {
                            when {
                                name.equals("title", ignoreCase = true) -> {
                                    title = parser.nextText()
                                }
                                name.equals("link", ignoreCase = true) -> {
                                    val href = parser.getAttributeValue(null, "href") ?: ""
                                    if (href.contains("/releases/tag/")) {
                                        tag = href.substringAfterLast("/releases/tag/").trim()
                                    }
                                }
                                name.equals("id", ignoreCase = true) -> {
                                    if (tag == null) {
                                        val idText = parser.nextText()
                                        if (idText.contains("/releases/tag/")) {
                                            tag = idText.substringAfterLast("/releases/tag/").trim()
                                        } else if (idText.contains("/")) {
                                            tag = idText.substringAfterLast("/").trim()
                                        }
                                    }
                                }
                                name.equals("content", ignoreCase = true) -> {
                                    contentHtml = parser.nextText()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (name.equals("entry", ignoreCase = true) && insideEntry) {
                            if (!tag.isNullOrBlank()) {
                                entries.add(
                                    AtomEntry(
                                        tag = tag,
                                        title = title ?: "Onyx Browser $tag",
                                        contentHtml = contentHtml ?: ""
                                    )
                                )
                            }
                            insideEntry = false
                            title = null
                            tag = null
                            contentHtml = null
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // Fall back to regex parsing
        }

        if (entries.isNotEmpty()) return entries
        return parseAtomEntriesRegex(xml)
    }

    private fun parseAtomEntriesRegex(xml: String): List<AtomEntry> {
        val entries = mutableListOf<AtomEntry>()
        val entryPattern = Pattern.compile("<entry>([\\s\\S]*?)</entry>")
        val entryMatcher = entryPattern.matcher(xml)
        while (entryMatcher.find()) {
            val entryContent = entryMatcher.group(1) ?: continue
            val tagMatcher = Pattern.compile("""/releases/tag/([^"'\s>]+)""").matcher(entryContent)
            val tag = if (tagMatcher.find()) tagMatcher.group(1)?.trim() ?: continue else continue

            val titleMatcher = Pattern.compile("<title[^>]*>([^<]+)</title>").matcher(entryContent)
            val title = if (titleMatcher.find()) titleMatcher.group(1)?.trim() ?: "Onyx Browser $tag" else "Onyx Browser $tag"

            val contentMatcher = Pattern.compile("<content[^>]*>([\\s\\S]*?)</content>").matcher(entryContent)
            val rawHtml = if (contentMatcher.find()) contentMatcher.group(1) ?: "" else ""
            val contentHtml = unescapeXmlEntities(rawHtml)

            entries.add(AtomEntry(tag = tag, title = title, contentHtml = contentHtml))
        }
        return entries
    }

    private fun unescapeXmlEntities(text: String): String {
        return text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
    }

}
