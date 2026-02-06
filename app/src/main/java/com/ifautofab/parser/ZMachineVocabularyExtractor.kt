package com.ifautofab.parser

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Android logger implementation.
 */
private class AndroidLoggerVocab : Logger {
    override fun d(tag: String, msg: String): Int = Log.d(tag, msg)
    override fun i(tag: String, msg: String): Int = Log.i(tag, msg)
    override fun w(tag: String, msg: String): Int = Log.w(tag, msg)
    override fun e(tag: String, msg: String, e: Throwable?): Int = Log.e(tag, msg, e)
}

/**
 * Extension function to read unsigned byte from RandomAccessFile.
 */
private fun RandomAccessFile.readUnsignedByteExt(): Int {
    val b = readByte()
    return b.toInt() and 0xFF
}

/**
 * Extension function to read big-endian unsigned short from RandomAccessFile.
 */
private fun RandomAccessFile.readUnsignedShortBE(): Int {
    val high = readUnsignedByteExt()
    val low = readUnsignedByteExt()
    return (high shl 8) or low
}

/**
 * Extracts vocabulary from Z-machine game files (.z3, .z4, .z5, .z8).
 *
 * Based on the Z-machine specification:
 * - Header contains dictionary address at bytes 0x08-0x09 (word address)
 * - Dictionary format: number of entries, entry length, then entries
 * - Each entry contains parsed words with parsed data flags
 */
object ZMachineVocabularyExtractor {

    private const val TAG = "ZMachineVocabExtractor"
    var logger: Logger = AndroidLoggerVocab()

    /**
     * Extracts vocabulary from a Z-machine game file.
     *
     * @param gameFile The Z-machine game file
     * @return ZMachineVocabulary containing all extracted words by type
     */
    fun extract(gameFile: File): ZMachineVocabulary? {
        if (!gameFile.exists()) {
            logger.e(TAG, "Game file does not exist: ${gameFile.path}")
            return null
        }

        val extension = gameFile.extension.lowercase()
        if (extension !in listOf("z3", "z4", "z5", "z6", "z7", "z8")) {
            logger.e(TAG, "Not a Z-machine file: $extension")
            return null
        }

        logger.d(TAG, "Extracting vocabulary from ${gameFile.name} (version detection...")

        try {
            RandomAccessFile(gameFile, "r").use { raf ->
                val version = raf.readVersion()
                logger.d(TAG, "Z-machine version: $version")

                if (version < 3 || version > 8) {
                    logger.e(TAG, "Unsupported Z-machine version: $version")
                    return null
                }

                val dictAddr = raf.readDictionaryAddress(version)
                logger.d(TAG, "Dictionary address at packed: 0x${dictAddr.toString(16).uppercase()}")

                if (dictAddr == 0) {
                    logger.e(TAG, "No dictionary found in game file")
                    return null
                }

                return parseDictionary(raf, dictAddr, version)
            }
        } catch (e: Exception) {
            logger.e(TAG, "Error extracting vocabulary: ${e.message}", e)
            return null
        }
    }

    /**
     * Reads the Z-machine version from the file header.
     * Byte at 0x00 contains the version number.
     */
    private fun RandomAccessFile.readVersion(): Int {
        seek(0x00)
        return readUnsignedByteExt()
    }

    /**
     * Reads the dictionary address from the header.
     * For Z3: Address is at 0x08-0x09 (word address, needs * 2 for byte offset)
     For Z5+: Address is at 0x08-0x09 (word address, * 4 for Z4+, * 2 for Z3)
     */
    private fun RandomAccessFile.readDictionaryAddress(version: Int): Int {
        // Dictionary address is stored as a "packed address" in big-endian at 0x08-0x09
        seek(0x08)
        val highByte = readUnsignedByteExt()
        val lowByte = readUnsignedByteExt()
        val packedAddr = (highByte shl 8) or lowByte

        // Convert packed address to byte offset based on version
        // Z3: multiply by 2, Z4+: multiply by 4
        return when (version) {
            3 -> packedAddr * 2
            else -> packedAddr * 4
        }
    }

    /**
     * Parses the dictionary starting at the given address.
     *
     * Dictionary format:
     - Byte 0-1: Number of entries (big-endian)
     - Byte 2: Entry length in bytes
     - Followed by entries: each has parsed data flags + word text
     */
    private fun parseDictionary(raf: RandomAccessFile, dictAddr: Int, version: Int): ZMachineVocabulary? {
        raf.seek(dictAddr.toLong())

        // Read dictionary header
        val numEntries = raf.readUnsignedShortBE()
        val entryLength = raf.readUnsignedByteExt()

        logger.d(TAG, "Dictionary: $numEntries entries, each $entryLength bytes at offset $dictAddr")

        if (numEntries <= 0 || numEntries > 10000) {
            logger.e(TAG, "Invalid number of entries: $numEntries")
            return null
        }

        if (entryLength < 4) {
            logger.e(TAG, "Invalid entry length: $entryLength")
            return null
        }

        val vocabulary = ZMachineVocabulary(version = version)

        // Parse each dictionary entry
        for (i in 0 until numEntries) {
            val entryOffset = dictAddr + 3 + (i * entryLength)
            raf.seek(entryOffset.toLong())

            // Read parsed data flags (4 bytes, but we mainly care about the first byte for part of speech)
            val flags = ByteArray(4)
            raf.read(flags)

            // Determine word type from flags
            val wordType = classifyWordType(flags)

            // Read the word (Z-code text encoding - 2 bytes per character in ZSCII)
            val wordBytes = ByteArray(entryLength - 4)
            raf.read(wordBytes)
            val word = decodeZString(wordBytes)

            vocabulary.addWord(word, wordType)
        }

        logger.d(TAG, "Extracted ${vocabulary.totalWords} words: ${vocabulary.verbs.size} verbs, ${vocabulary.nouns.size} nouns, ${vocabulary.adjectives.size} adjectives, etc.")
        return vocabulary
    }

    /**
     * Classifies a word based on dictionary flags.
     * This is a heuristic based on common Z-machine conventions.
     * The actual parsing rules vary by game, but this provides a reasonable approximation.
     */
    private fun classifyWordType(flags: ByteArray): WordType {
        val flagByte = flags[0].toInt() and 0xFF

        // Bit 0x40 in the first flag byte often indicates a verb
        // Bit 0x20 often indicates a preposition
        // Other bits indicate other parts of speech

        return when {
            (flagByte and 0x40) != 0 -> WordType.VERB
            (flagByte and 0x20) != 0 -> WordType.PREPOSITION
            (flagByte and 0x10) != 0 -> WordType.ADJECTIVE
            (flagByte and 0x08) != 0 -> WordType.NOUN
            else -> WordType.UNKNOWN
        }
    }

    /**
     * Decodes a ZSCII string from dictionary entry.
     * Z-machine dictionary uses a special 5-bit encoding (2 bytes encode 3 ZSCII chars).
     */
    private fun decodeZString(bytes: ByteArray): String {
        val result = StringBuilder()
        var i = 0
        var done = false

        while (i < bytes.size - 1 && !done) {
            val b1 = bytes[i].toInt() and 0xFF
            val b2 = bytes[i + 1].toInt() and 0xFF

            // Each 2 bytes encode 3 ZSCII characters (5 bits each)
            val z1 = (b1 shr 2) and 0x1F      // bits 2-6 of first byte
            val z2 = ((b1 and 0x03) shl 3) or (b2 shr 5)  // bit 0-1 of byte1 + bits 5-7 of byte2
            val z3 = b2 and 0x1F                   // bits 0-4 of second byte

            if (z1 != 0) result.append(zsciiToChar(z1))
            else done = true

            if (!done && z2 != 0) result.append(zsciiToChar(z2))
            else done = true

            if (!done && z3 != 0) result.append(zsciiToChar(z3))
            else done = true

            i += 2
        }

        return result.toString().trim()
    }

    /**
     * Converts a ZSCII code to a character.
     Uses standard ZSCII to ASCII mapping.
     */
    private fun zsciiToChar(code: Int): Char {
        return when (code) {
            0 -> '\u0000'  // Null terminator
            in 1..6 -> ' '   // Newline, cursor movement - map to space
            in 65..90 -> (code + 32).toChar()  // Uppercase to lowercase
            in 97..122 -> code.toChar()  // Already lowercase
            else -> '?'  // Unknown character
        }
    }

}

/**
 * Container for extracted vocabulary from a Z-machine file.
 */
data class ZMachineVocabulary(
    val version: Int,
    val verbs: MutableSet<String> = mutableSetOf(),
    val nouns: MutableSet<String> = mutableSetOf(),
    val adjectives: MutableSet<String> = mutableSetOf(),
    val prepositions: MutableSet<String> = mutableSetOf(),
    val misc: MutableSet<String> = mutableSetOf()
) {
    private val allWords: MutableSet<String> = mutableSetOf()

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
            allWords.add(lowerWord)
        }
    }

    val totalWords: Int get() = allWords.size

    /**
     * Exports vocabulary to JSON format for saving/sharing.
     */
    fun toJson(): String {
        val json = JSONObject()
        json.put("version", version)
        json.put("total_words", totalWords)
        json.put("verbs", JSONArray(verbs.sorted()))
        json.put("nouns", JSONArray(nouns.sorted()))
        json.put("adjectives", JSONArray(adjectives.sorted()))
        json.put("prepositions", JSONArray(prepositions.sorted()))
        json.put("misc", JSONArray(misc.sorted()))
        return json.toString(2)
    }

    fun toDebugString(): String {
        return """
            |Z-Machine Vocabulary (v$version)
            |=========================
            |Verbs (${verbs.size}): ${verbs.sorted().take(20).joinToString(", ")}${if (verbs.size > 20) "..." else ""}
            |Nouns (${nouns.size}): ${nouns.sorted().take(20).joinToString(", ")}${if (nouns.size > 20) "..." else ""}
            |Adjectives (${adjectives.size}): ${adjectives.sorted().take(20).joinToString(", ")}${if (adjectives.size > 20) "..." else ""}
            |Prepositions (${prepositions.size}): ${prepositions.sorted().joinToString(", ")}
            |Total unique words: $totalWords
        """.trimMargin()
    }
}

/**
 * Parts of speech in the Z-machine dictionary.
 */
enum class WordType {
    VERB, NOUN, ADJECTIVE, PREPOSITION, UNKNOWN
}
