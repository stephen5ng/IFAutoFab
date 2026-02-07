package com.ifautofab.terminal

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class VocabularyExtractorTest {

    private val gamesDir = File("app/src/main/assets/games")

    @Test
    fun testZork1VocabularyExtraction() {
        val gameFile = File(gamesDir, "zork1.z3")
        if (!gameFile.exists()) {
            println("Skipping test: $gameFile not found")
            return
        }

        val vocab = VocabularyExtractor.extract(gameFile)
        assertNotNull("Vocabulary should be extracted", vocab)
        assertEquals("Z-machine version should be 3", 3, vocab!!.version)

        // Common verbs that should exist in Zork I
        assertTrue("Should contain verb 'take'", vocab.containsVerb("take"))
        assertTrue("Should contain verb 'look'", vocab.containsVerb("look"))
        assertTrue("Should contain verb 'go'", vocab.containsVerb("go"))
        assertTrue("Should contain verb 'open'", vocab.containsVerb("open"))
        assertTrue("Should contain verb 'close'", vocab.containsVerb("close"))
        assertTrue("Should contain verb 'read'", vocab.containsVerb("read"))
        assertTrue("Should contain verb 'drop'", vocab.containsVerb("drop"))

        // Common nouns
        assertTrue("Should contain noun 'mailbox'", vocab.containsWord("mailbox"))
        assertTrue("Should contain noun 'door'", vocab.containsWord("door"))
        assertTrue("Should contain noun 'leaf'", vocab.containsWord("leaf"))
        assertTrue("Should contain noun 'leaflet'", vocab.containsWord("leaflet"))

        // Prepositions
        assertTrue("Should contain preposition 'with'", vocab.containsWord("with"))
        assertTrue("Should contain preposition 'from'", vocab.containsWord("from"))
        assertTrue("Should contain preposition 'on'", vocab.containsWord("on"))
        assertTrue("Should contain preposition 'in'", vocab.containsWord("in"))

        // Test getAllWords
        val allWords = vocab.getAllWords()
        assertTrue("getAllWords should not be empty", allWords.isNotEmpty())
        assertTrue("getAllWords should contain 'north'", allWords.contains("north"))

        println("Zork I: ${vocab.getSummary()}")
        println("  Total words: ${allWords.size}")
    }

    @Test
    fun testHitchhikersGuideVocabularyExtraction() {
        val gameFile = File(gamesDir, "hhgg.z3")
        if (!gameFile.exists()) {
            println("Skipping test: $gameFile not found")
            return
        }

        val vocab = VocabularyExtractor.extract(gameFile)
        assertNotNull("Vocabulary should be extracted", vocab)
        assertEquals("Z-machine version should be 3", 3, vocab!!.version)

        // Common verbs
        assertTrue("Should contain verb 'take'", vocab.containsVerb("take"))
        assertTrue("Should contain verb 'look'", vocab.containsVerb("look"))
        assertTrue("Should contain verb 'examine'", vocab.containsVerb("examine"))

        println("HHGTTG: ${vocab.getSummary()}")
    }

    @Test
    fun testPlanetfallVocabularyExtraction() {
        val gameFile = File(gamesDir, "planetfall.z3")
        if (!gameFile.exists()) {
            println("Skipping test: $gameFile not found")
            return
        }

        val vocab = VocabularyExtractor.extract(gameFile)
        assertNotNull("Vocabulary should be extracted", vocab)
        assertEquals("Z-machine version should be 3", 3, vocab!!.version)

        assertTrue("Should contain verb 'take'", vocab.containsVerb("take"))
        assertTrue("Should contain verb 'look'", vocab.containsVerb("look"))

        println("Planetfall: ${vocab.getSummary()}")
    }

    @Test
    fun testLostPigVocabularyExtraction() {
        val gameFile = File(gamesDir, "LostPig.z8")
        if (!gameFile.exists()) {
            println("Skipping test: $gameFile not found")
            return
        }

        val vocab = VocabularyExtractor.extract(gameFile)
        assertNotNull("Vocabulary should be extracted", vocab)
        assertEquals("Z-machine version should be 8", 8, vocab!!.version)

        // Lost Pig uses Inform 7, should have similar verb set
        assertTrue("Should contain verb 'take'", vocab.containsVerb("take"))
        assertTrue("Should contain verb 'look'", vocab.containsVerb("look"))
        assertTrue("Should contain verb 'go'", vocab.containsVerb("go"))

        println("Lost Pig: ${vocab.getSummary()}")
    }

    @Test
    fun testTangleVocabularyExtraction() {
        val gameFile = File(gamesDir, "Tangle.z5")
        if (!gameFile.exists()) {
            println("Skipping test: $gameFile not found")
            return
        }

        val vocab = VocabularyExtractor.extract(gameFile)
        assertNotNull("Vocabulary should be extracted", vocab)
        assertEquals("Z-machine version should be 5", 5, vocab!!.version)

        assertTrue("Should contain verb 'take'", vocab.containsVerb("take"))
        assertTrue("Should contain verb 'look'", vocab.containsVerb("look"))

        println("Tangle: ${vocab.getSummary()}")
    }

    @Test
    fun testCaseInsensitivity() {
        val gameFile = File(gamesDir, "zork1.z3")
        if (!gameFile.exists()) {
            println("Skipping test: $gameFile not found")
            return
        }

        val vocab = VocabularyExtractor.extract(gameFile)
        assertNotNull("Vocabulary should be extracted", vocab)

        // Test case-insensitive matching
        assertTrue("Should find 'TAKE' (uppercase)", vocab!!.containsVerb("TAKE"))
        assertTrue("Should find 'Take' (mixed case)", vocab!!.containsVerb("Take"))
        assertTrue("Should find 'tAkE' (mixed case)", vocab!!.containsVerb("tAkE"))
    }

    @Test
    fun testNonExistentFile() {
        val gameFile = File(gamesDir, "nonexistent.z3")
        val vocab = VocabularyExtractor.extract(gameFile)
        assertNull("Vocabulary should be null for non-existent file", vocab)
    }

    @Test
    fun testInvalidFileFormat() {
        // Create a dummy file with wrong extension in temp directory
        val dummyFile = File.createTempFile("dummy", ".txt")
        dummyFile.writeText("not a z-machine file")

        val vocab = VocabularyExtractor.extract(dummyFile)
        assertNull("Vocabulary should be null for non-Z-machine file", vocab)

        dummyFile.delete()
    }

    @Test
    fun testWordTrimmingAndNormalization() {
        val gameFile = File(gamesDir, "zork1.z3")
        if (!gameFile.exists()) {
            println("Skipping test: $gameFile not found")
            return
        }

        val vocab = VocabularyExtractor.extract(gameFile)
        assertNotNull("Vocabulary should be extracted", vocab)

        // Test whitespace trimming
        assertTrue("Should find 'take' with spaces", vocab!!.containsVerb("  take  "))
    }
}
