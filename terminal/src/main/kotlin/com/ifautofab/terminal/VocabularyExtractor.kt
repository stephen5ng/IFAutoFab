package com.ifautofab.terminal

import java.io.File
import java.io.RandomAccessFile

/**
 * Extracts vocabulary from Z-machine game files (.z3, .z4, .z5, .z8).
 * Pure Kotlin implementation without Android dependencies.
 */
object VocabularyExtractor {

    // Heuristic word sets for Infocom games where dictionary flags are zero.
    // Only single-word tokens — Z-machine dictionaries never contain multi-word entries.
    private val HEURISTIC_VERBS = setOf(
        "take", "drop", "get", "give", "go", "look", "examine",
        "open", "close", "lock", "unlock", "read", "wear", "remove", "put",
        "push", "pull", "turn", "rotate", "press", "twist", "squeeze",
        "shake", "move", "lift", "throw", "attack", "kill", "fight", "hit",
        "eat", "drink", "smell", "listen", "jump", "climb",
        "enter", "exit", "leave", "inventory", "i", "wait", "z", "quit", "save",
        "restore", "restart", "script", "unscript", "verbose", "brief", "superbrief",
        "answer", "ask", "tell", "say", "shout", "sing", "swear", "wave",
        "burn", "light", "extinguish", "blow", "rub", "dust", "wash", "clean",
        "dig", "fill", "empty", "pour", "type", "write", "draw",
        "tie", "attach", "detach", "fasten", "unfasten", "connect", "disconnect",
        "buy", "pay", "sell", "consult", "pray", "think", "feel", "sleep",
        "wake", "kick", "kiss", "touch", "hold", "keep",
        "release", "break", "cut", "chop", "slice",
        "shoot", "point", "set", "adjust", "change", "switch", "on", "off",
        "recharge", "plug", "unplug", "descend",
        "ascend", "walk", "run", "leap", "crawl", "swim", "fly", "board",
        "disembark", "sit", "stand", "lie", "lean", "hide", "search",
        "pick", "insert", "place"
    )

    private val HEURISTIC_PREPOSITIONS = setOf(
        "with", "without", "from", "to", "at", "on", "onto", "off", "into",
        "of", "over", "under", "behind", "through", "between", "around",
        "about", "against", "using", "by", "via", "for", "as", "than", "except",
        "inside", "outside", "underneath", "beneath"
    )

    /**
     * Extracts vocabulary from a Z-machine game file.
     */
    fun extract(gameFile: File): Vocabulary? {
        if (!gameFile.exists()) {
            println("Error: Game file does not exist: ${gameFile.path}")
            return null
        }

        val extension = gameFile.extension.lowercase()
        if (extension !in listOf("z3", "z4", "z5", "z6", "z7", "z8")) {
            println("Error: Not a Z-machine file: $extension")
            return null
        }

        try {
            RandomAccessFile(gameFile, "r").use { raf ->
                val version = raf.readVersion()
                
                if (version < 3 || version > 8) {
                    println("Error: Unsupported Z-machine version: $version")
                    return null
                }

                val dictAddr = raf.readDictionaryAddress(version)
                if (dictAddr == 0) {
                    println("Error: No dictionary found in game file")
                    return null
                }

                return parseDictionary(raf, dictAddr, version)
            }
        } catch (e: Exception) {
            println("Error extracting vocabulary: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun RandomAccessFile.readUnsignedByteExt(): Int {
        val b = readByte()
        return b.toInt() and 0xFF
    }

    private fun RandomAccessFile.readUnsignedShortBE(): Int {
        val high = readUnsignedByteExt()
        val low = readUnsignedByteExt()
        return (high shl 8) or low
    }

    private fun RandomAccessFile.readVersion(): Int {
        seek(0x00)
        return readUnsignedByteExt()
    }

    private fun RandomAccessFile.readDictionaryAddress(version: Int): Int {
        seek(0x08)
        val highByte = readUnsignedByteExt()
        val lowByte = readUnsignedByteExt()
        // Dictionary address is absolute, not packed, for all versions
        return (highByte shl 8) or lowByte
    }

    private fun parseDictionary(raf: RandomAccessFile, dictAddr: Int, version: Int): Vocabulary? {
        raf.seek(dictAddr.toLong())

        // 1. Read number of separators
        val numSeparators = raf.readUnsignedByteExt()

        // 2. Skip separators
        raf.skipBytes(numSeparators)

        // 3. Read entry length
        val entryLength = raf.readUnsignedByteExt()

        // 4. Read number of entries
        val numEntries = raf.readUnsignedShortBE()

        if (numEntries <= 0 || numEntries > 10000 || entryLength < 4) {
             println("Error: Invalid dictionary format (entries=$numEntries, length=$entryLength)")
             return null
        }

        val vocabulary = Vocabulary(version = version)

        // Z-machine dictionary entries: Z-encoded text FIRST, then data bytes
        // V1-V3: 4 bytes Z-string (2 words), then data bytes
        // V4+: 6 bytes Z-string (3 words), then data bytes
        val zStringLength = if (version <= 3) 4 else 6
        val flagsLength = 1

        for (i in 0 until numEntries) {
            // Calculate offset: dictAddr + 1 (n) + n (separators) + 1 (len) + 2 (count) + i * len
            val startOfEntries = dictAddr + 1 + numSeparators + 1 + 2
            val entryOffset = startOfEntries + (i * entryLength)
            raf.seek(entryOffset.toLong())

            // Read Z-encoded text FIRST
            val wordBytes = ByteArray(zStringLength)
            raf.read(wordBytes)
            val word = decodeZString(wordBytes)

            // Read flags AFTER Z-string
            val flags = ByteArray(flagsLength)
            raf.read(flags)
            val wordType = classifyWordType(flags, word)

            vocabulary.addWord(word, wordType)
        }

        return vocabulary
    }

    private fun classifyWordType(flags: ByteArray, word: String): WordType {
        val flagByte = flags[0].toInt() and 0xFF

        // First try standard flag bits (used by Inform 6/7)
        if (flagByte != 0) {
            return when {
                (flagByte and 0x40) != 0 -> WordType.VERB
                (flagByte and 0x20) != 0 -> WordType.PREPOSITION
                (flagByte and 0x10) != 0 -> WordType.ADJECTIVE
                (flagByte and 0x08) != 0 -> WordType.NOUN
                else -> WordType.UNKNOWN
            }
        }

        // For Infocom games (flags are often 0x00), use heuristics based on word patterns
        return when {
            // Common directions (treated as verbs in IF)
            word in setOf("north", "south", "east", "west", "northeast", "northwest",
                          "southeast", "southwest", "up", "down", "in", "out") -> WordType.VERB

            // Common single-word verbs that appear in most IF games
            word in HEURISTIC_VERBS -> WordType.VERB

            // Prepositions (excluding words already caught as directions above)
            word in HEURISTIC_PREPOSITIONS -> WordType.PREPOSITION

            else -> WordType.UNKNOWN
        }
    }

    private fun decodeZString(bytes: ByteArray): String {
        val result = StringBuilder()
        var i = 0

        while (i < bytes.size - 1) {
            val b1 = bytes[i].toInt() and 0xFF
            val b2 = bytes[i + 1].toInt() and 0xFF

            val z1 = (b1 shr 2) and 0x1F
            val z2 = ((b1 and 0x03) shl 3) or (b2 shr 5)
            val z3 = b2 and 0x1F

            // Stop at shift/abbreviation codes (1-5) - they signal end of dictionary word
            if (z1 < 6) break
            result.append(zsciiToChar(z1))

            if (z2 < 6) break
            result.append(zsciiToChar(z2))

            if (z3 < 6) break
            result.append(zsciiToChar(z3))

            i += 2
        }

        return result.toString()
    }

    private fun zsciiToChar(code: Int): Char {
        // Z-machine dictionary entries use 5-bit Z-characters
        // Codes 6-31: a-z (a=6, b=7, ..., z=31)
        return when (code) {
            in 6..31 -> ('a' + (code - 6))
            else -> '?'
        }
    }
}

enum class WordType {
    VERB, NOUN, ADJECTIVE, PREPOSITION, UNKNOWN
}

data class Vocabulary(
    val version: Int,
    val verbs: MutableSet<String> = mutableSetOf(),
    val nouns: MutableSet<String> = mutableSetOf(),
    val adjectives: MutableSet<String> = mutableSetOf(),
    val prepositions: MutableSet<String> = mutableSetOf(),
    val misc: MutableSet<String> = mutableSetOf()
) {
    fun addWord(word: String, type: WordType) {
        val lowerWord = word.trim().lowercase()
        if (lowerWord.isNotEmpty()) {
            when (type) {
                WordType.VERB -> verbs.add(lowerWord)
                WordType.NOUN -> nouns.add(lowerWord)
                WordType.ADJECTIVE -> adjectives.add(lowerWord)
                WordType.PREPOSITION -> prepositions.add(lowerWord)
                WordType.UNKNOWN -> misc.add(lowerWord)
            }
        }
    }

    fun getSummary(): String {
        return "Vocabulary extract: ${verbs.size} verbs, ${nouns.size} nouns, ${adjectives.size} adjectives"
    }

    /**
     * Returns all words in the vocabulary across all categories.
     */
    fun getAllWords(): Set<String> {
        return verbs + nouns + adjectives + prepositions + misc
    }

    /**
     * Checks if a word exists in the vocabulary (case-insensitive).
     */
    fun containsWord(word: String): Boolean {
        return getAllWords().contains(word.trim().lowercase())
    }

    /**
     * Checks if a verb exists in the vocabulary (case-insensitive).
     */
    fun containsVerb(verb: String): Boolean {
        return verbs.contains(verb.trim().lowercase())
    }
}
