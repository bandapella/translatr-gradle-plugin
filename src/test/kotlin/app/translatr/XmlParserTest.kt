package app.translatr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XmlParserTest {
    @TempDir
    lateinit var tempDir: File
    
    @Test
    fun `parseStringsXml extracts key-value pairs`() {
        val xmlFile = File(tempDir, "strings.xml").apply {
            writeText("""
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">MyApp</string>
                    <string name="welcome_message">Welcome to MyApp!</string>
                    <string name="button_submit">Submit</string>
                </resources>
            """.trimIndent())
        }
        
        val result = XmlParser.parseStringsXml(xmlFile)
        
        assertEquals(3, result.size)
        assertEquals("MyApp", result["app_name"])
        assertEquals("Welcome to MyApp!", result["welcome_message"])
        assertEquals("Submit", result["button_submit"])
    }
    
    @Test
    fun `parseStringsXml handles special characters`() {
        val xmlFile = File(tempDir, "strings.xml").apply {
            writeText("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="with_quotes">He said "hello"</string>
    <string name="with_ampersand">A &amp; B</string>
    <string name="with_newline">Line 1
Line 2</string>
</resources>"""
            )
        }
        
        val result = XmlParser.parseStringsXml(xmlFile)
        
        assertEquals(3, result.size)
        assertTrue(result.containsKey("with_quotes"))
        assertTrue(result.containsKey("with_ampersand"))
        assertTrue(result.containsKey("with_newline"))
    }
    
    @Test
    fun `parseStringsXml throws on missing file`() {
        val missingFile = File(tempDir, "nonexistent.xml")
        
        assertFailsWith<IllegalArgumentException> {
            XmlParser.parseStringsXml(missingFile)
        }
    }
    
    @Test
    fun `readStringKeyOrder preserves document order`() {
        val xmlFile = File(tempDir, "strings.xml").apply {
            writeText("""
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="zebra">Z</string>
                    <string name="alpha">A</string>
                    <string name="beta">B</string>
                </resources>
            """.trimIndent())
        }
        
        val keys = XmlParser.readStringKeyOrder(xmlFile)
        
        assertEquals(listOf("zebra", "alpha", "beta"), keys)
    }
    
    @Test
    fun `mergeKeyOrder preserves existing order and appends new keys`() {
        val existing = listOf("key1", "key2", "key3")
        val desired = listOf("key3", "key4", "key1")
        
        val merged = XmlParser.mergeKeyOrder(existing, desired)
        
        assertEquals(listOf("key1", "key3", "key4"), merged)
    }
    
    @Test
    fun `mergeKeyOrder returns desired order when existing is empty`() {
        val merged = XmlParser.mergeKeyOrder(emptyList(), listOf("a", "b", "c"))
        
        assertEquals(listOf("a", "b", "c"), merged)
    }
    
    @Test
    fun `writeStringsXml creates properly formatted XML`() {
        val translations = mapOf(
            "app_name" to "MiApp",
            "welcome_message" to "¡Bienvenido a MiApp!"
        )
        
        XmlParser.writeStringsXml(tempDir, "es", translations)
        
        val outputFile = File(tempDir, "values-es/strings.xml")
        assertTrue(outputFile.exists())
        
        val content = outputFile.readText()
        assertTrue(content.contains("<string name=\"app_name\">MiApp</string>"))
        assertTrue(content.contains("<string name=\"welcome_message\">¡Bienvenido a MiApp!</string>"))
    }
    
    @Test
    fun `writeStringsXml preserves key order when provided`() {
        val translations = mapOf(
            "zebra" to "Z",
            "alpha" to "A",
            "beta" to "B"
        )
        val orderedKeys = listOf("zebra", "alpha", "beta")
        
        XmlParser.writeStringsXml(tempDir, "test", translations, orderedKeys)
        
        val outputFile = File(tempDir, "values-test/strings.xml")
        val keys = XmlParser.readStringKeyOrder(outputFile)
        
        assertEquals(orderedKeys, keys)
    }
    
    @Test
    fun `writeStringsXml sorts alphabetically when no order provided`() {
        val translations = mapOf(
            "zebra" to "Z",
            "alpha" to "A",
            "beta" to "B"
        )
        
        XmlParser.writeStringsXml(tempDir, "test", translations, null)
        
        val outputFile = File(tempDir, "values-test/strings.xml")
        val keys = XmlParser.readStringKeyOrder(outputFile)
        
        assertEquals(listOf("alpha", "beta", "zebra"), keys)
    }
    
    @Test
    fun `detectTargetLanguages finds existing language directories`() {
        File(tempDir, "values-es").mkdirs()
        File(tempDir, "values-fr").mkdirs()
        File(tempDir, "values-de").mkdirs()
        File(tempDir, "other-folder").mkdirs()
        
        val languages = XmlParser.detectTargetLanguages(tempDir)
        
        assertEquals(3, languages.size)
        assertTrue(languages.containsAll(listOf("es", "fr", "de")))
    }
    
    @Test
    fun `detectTargetLanguages returns empty list for nonexistent directory`() {
        val nonexistent = File(tempDir, "missing")
        
        val languages = XmlParser.detectTargetLanguages(nonexistent)
        
        assertTrue(languages.isEmpty())
    }
}
