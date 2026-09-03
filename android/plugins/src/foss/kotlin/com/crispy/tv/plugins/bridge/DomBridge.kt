package com.crispy.tv.plugins.bridge

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class DomBridge {

    private val documents = ConcurrentHashMap<String, org.jsoup.nodes.Document>()
    private val registeredElements = ConcurrentHashMap<Pair<String, String>, Element>()
    private val counter = AtomicLong()

    fun load(html: String): String {
        val documentId = newId("doc")
        documents[documentId] = Jsoup.parse(html)
        return documentId
    }

    fun select(documentId: String, selector: String): String {
        val document = documents[documentId] ?: return "[]"
        return writeIds(document.select(selector).toList())
    }

    fun find(documentId: String, elementId: String, selector: String): String {
        val element = elementById(documentId, elementId) ?: return "[]"
        return writeIds(element.select(selector).toList())
    }

    fun text(documentId: String, elementIds: List<String>): String {
        return elementIds
            .mapNotNull { id -> elementById(documentId, id)?.text() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    fun innerHtml(documentId: String, elementId: String): String {
        return elementById(documentId, elementId)?.html().orEmpty()
    }

    fun html(documentId: String, elementId: String): String {
        if (elementId.isBlank()) {
            return documents[documentId]?.outerHtml().orEmpty()
        }
        return elementById(documentId, elementId)?.outerHtml().orEmpty()
    }

    fun attr(documentId: String, elementId: String, name: String): String {
        val element = elementById(documentId, elementId) ?: return UNDEFINED
        if (!element.hasAttr(name)) {
            return UNDEFINED
        }
        return element.attr(name)
    }

    fun next(documentId: String, elementId: String): String {
        val next = elementById(documentId, elementId)?.nextElementSibling() ?: return NONE
        return register(documentId, next)
    }

    fun prev(documentId: String, elementId: String): String {
        val previous = elementById(documentId, elementId)?.previousElementSibling() ?: return NONE
        return register(documentId, previous)
    }

    fun dispose(documentId: String) {
        documents.remove(documentId)
        registeredElements.keys.removeAll { it.first == documentId }
    }

    private fun elementById(documentId: String, elementId: String): Element? {
        registeredElements[Pair(documentId, elementId)]?.let { return it }
        return documents[documentId]?.select("[data-crispy-eid='$elementId']")?.firstOrNull()
    }

    private fun register(documentId: String, element: Element): String {
        elementId(element)?.let { return it }
        val id = newId("el")
        registeredElements[Pair(documentId, id)] = element
        return id
    }

    private fun elementId(element: Element): String? = element.attr("data-crispy-eid").takeIf { it.isNotEmpty() }

    private fun writeIds(elements: List<Element>): String {
        val ids = elements.map { element ->
            elementId(element) ?: newId("el").also { element.attr("data-crispy-eid", it) }
        }
        return org.json.JSONArray(ids).toString()
    }

    private fun newId(prefix: String): String = "$prefix-${counter.incrementAndGet()}"

    private companion object {
        const val UNDEFINED = "__UNDEFINED__"
        const val NONE = "__NONE__"
    }
}
