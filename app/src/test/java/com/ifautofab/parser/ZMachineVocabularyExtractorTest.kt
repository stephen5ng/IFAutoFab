package com.ifautofab.parser

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Ignore
import java.io.File

/**
 * Tests for Z-machine vocabulary extraction.
 *
 * Note: These tests require actual Z-machine game files to run.
 * Place test games in test resources or update paths below.
 */
class ZMachineVocabularyExtractorTest {

    @Before
    fun setup() {
        // Use no-op logger for tests
        ZMachineVocabularyExtractor.logger = NoOpLogger
    }

    /**
     * Tests extracting vocabulary from Zork I.
     * Requires zork1.z3 to be present in test resources.
     */
    @Test
    fun testExtractZork1Vocabulary() {
        val zorkFile = File("src/test/resources/games/zork1.z3")
        if (!zorkFile.exists()) {
            println("Skipping test - zork1.z3 not found at ${zorkFile.path}")
            return
        }

        println("Extracting vocabulary from ${zorkFile.path}")
        val vocab = ZMachineVocabularyExtractor.extract(zorkFile)

        assertNotNull("Vocabulary should be extracted", vocab)
        assertTrue("Should have verbs", vocab!!.verbs.isNotEmpty())
        assertTrue("Should have nouns", vocab.nouns.isNotEmpty())

        // Zork I is known to have these core verbs
        val expectedVerbs = listOf("take", "drop", "open", "close", "go", "look", "inventory", "save", "restore")
        val foundVerbs = vocab.verbs.intersect(expectedVerbs.toSet())
        assertTrue("Should find common Zork verbs. Found: $foundVerbs", foundVerbs.size >= 3)

        println(vocab.toDebugString())
    }

    /**
     * Tests JSON export of vocabulary.
     */
    @Test
    fun testVocabularyJsonExport() {
        val zorkFile = File("src/test/resources/games/zork1.z3")
        if (!zorkFile.exists()) {
            println("Skipping test - zork1.z3 not found")
            return
        }

        val vocab = ZMachineVocabularyExtractor.extract(zorkFile)
        assertNotNull(vocab)

        val json = vocab!!.toJson()
        assertTrue("JSON should contain version info", json.contains("\"version\""))
        assertTrue("JSON should contain verbs", json.contains("\"verbs\""))
        assertTrue("JSON should contain nouns", json.contains("\"nouns\""))

        println("Vocabulary JSON:")
        println(json)
    }

    /**
     * Tests with Planetfall if available.
     */
    @Ignore("Requires Planetfall game file")
    @Test
    fun testExtractPlanetfallVocabulary() {
        val planetfallFile = File("src/test/resources/games/planetfall.z3")
        if (!planetfallFile.exists()) {
            println("Skipping test - planetfall.z3 not found")
            return
        }

        val vocab = ZMachineVocabularyExtractor.extract(planetfallFile)

        assertNotNull("Vocabulary should be extracted", vocab)
        assertTrue("Should have words", vocab!!.totalWords > 100)

        println("Planetfall vocabulary: ${vocab.totalWords} words")
        println("Verbs: ${vocab.verbs.size}, Nouns: ${vocab.nouns.size}")
    }

    /**
     * Tests handling of non-existent files.
     */
    @Test
    fun testNonExistentFile() {
        val vocab = ZMachineVocabularyExtractor.extract(File("/nonexistent/zork.z3"))
        assertNull("Non-existent file should return null", vocab)
    }

    /**
     * Tests handling of invalid file types.
     */
    @Test
    fun testInvalidFileType() {
        // Create a text file with wrong extension
        val tempFile = File.createTempFile("test", ".txt")
        tempFile.writeText("Not a Z-machine file")

        val vocab = ZMachineVocabularyExtractor.extract(tempFile)
        assertNull("Non-Z file should return null", vocab)

        tempFile.delete()
    }
}
