package app.translatr

import org.dom4j.Document
import org.dom4j.DocumentHelper
import org.dom4j.Element
import org.dom4j.io.OutputFormat
import org.dom4j.io.SAXReader
import org.dom4j.io.XMLWriter
import java.io.File
import java.io.FileWriter

object XmlParser {
    /**
     * Parse strings.xml and extract key-value pairs
     */
    fun parseStringsXml(file: File): Map<String, String> {
        if (!file.exists()) {
            throw IllegalArgumentException("Source file not found: ${file.absolutePath}")
        }
        
        val reader = SAXReader()
        val document = reader.read(file)
        val root = document.rootElement
        
        val strings = mutableMapOf<String, String>()
        
        root.elements("string").forEach { element ->
            val name = element.attributeValue("name")
            val value = element.text
            if (name != null && value != null) {
                strings[name] = value
            }
        }
        
        return strings
    }

    /**
     * Read string keys in document order from strings.xml
     */
    fun readStringKeyOrder(file: File): List<String> {
        if (!file.exists()) {
            return emptyList()
        }

        val reader = SAXReader()
        val document = reader.read(file)
        val root = document.rootElement

        val keys = mutableListOf<String>()

        root.elements("string").forEach { element ->
            val name = element.attributeValue("name")
            if (name != null) {
                keys.add(name)
            }
        }

        return keys
    }

    /**
     * Merge an existing order with a desired order, preserving existing order
     * for shared keys and appending new keys in the desired order.
     */
    fun mergeKeyOrder(existingOrder: List<String>, desiredOrder: List<String>): List<String> {
        if (existingOrder.isEmpty()) {
            return desiredOrder
        }

        val existingSet = existingOrder.toSet()
        val desiredSet = desiredOrder.toSet()

        val merged = existingOrder.filter { it in desiredSet }.toMutableList()
        merged.addAll(desiredOrder.filterNot { it in existingSet })
        return merged
    }
    
    /**
     * Write translations to values-{lang}/strings.xml
     */
    fun writeStringsXml(
        outputDir: File,
        language: String,
        translations: Map<String, String>,
        orderedKeys: List<String>? = null
    ) {
        val langDir = File(outputDir, "values-$language")
        langDir.mkdirs()
        
        val outputFile = File(langDir, "strings.xml")
        
        // Create XML document
        val document = DocumentHelper.createDocument()
        val root = document.addElement("resources")
        
        if (orderedKeys != null) {
            // Preserve source order when provided
            orderedKeys.forEach { key ->
                val value = translations[key] ?: return@forEach
                root.addElement("string")
                    .addAttribute("name", key)
                    .addText(value)
            }
            // Append any keys not present in orderedKeys (should be none)
            translations.keys
                .filterNot { key -> orderedKeys.contains(key) }
                .sorted()
                .forEach { key ->
                    root.addElement("string")
                        .addAttribute("name", key)
                        .addText(translations.getValue(key))
                }
        } else {
            // Deterministic output when no order is provided
            translations.entries.sortedBy { it.key }.forEach { (key, value) ->
                root.addElement("string")
                    .addAttribute("name", key)
                    .addText(value)
            }
        }
        
        // Write formatted XML
        val format = OutputFormat.createPrettyPrint().apply {
            indent = "    "
            encoding = "UTF-8"
        }
        
        FileWriter(outputFile).use { writer ->
            val xmlWriter = XMLWriter(writer, format)
            xmlWriter.write(document)
            xmlWriter.close()
        }
    }
    
    /**
     * Auto-detect target languages from existing values-{lang} directories
     */
    fun detectTargetLanguages(outputDir: File): List<String> {
        if (!outputDir.exists()) {
            return emptyList()
        }
        
        return outputDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("values-") }
            ?.map { it.name.removePrefix("values-") }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }
}
