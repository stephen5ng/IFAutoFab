package com.ifautofab.terminal

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Extracts vocabulary from Z-machine game files (.z3, .z4, .z5, .z8).
 * Pure Kotlin implementation without Android dependencies.
 */
object VocabularyExtractor {

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

        for (i in 0 until numEntries) {
            // Calculate offset: dictAddr + 1 (n) + n (separators) + 1 (len) + 2 (count) + i * len
            val startOfEntries = dictAddr + 1 + numSeparators + 1 + 2
            val entryOffset = startOfEntries + (i * entryLength)
            raf.seek(entryOffset.toLong())

            val flags = ByteArray(4)
            raf.read(flags)

            val wordType = classifyWordType(flags)

            val wordBytes = ByteArray(entryLength - 4)
            raf.read(wordBytes)
            val word = decodeZString(wordBytes)

            vocabulary.addWord(word, wordType)
        }

        return vocabulary
    }

    private fun classifyWordType(flags: ByteArray): WordType {
        val flagByte = flags[0].toInt() and 0xFF
        return when {
            (flagByte and 0x40) != 0 -> WordType.VERB
            (flagByte and 0x20) != 0 -> WordType.PREPOSITION
            (flagByte and 0x10) != 0 -> WordType.ADJECTIVE
            (flagByte and 0x08) != 0 -> WordType.NOUN
            else -> WordType.UNKNOWN
        }
    }

    private fun decodeZString(bytes: ByteArray): String {
        val result = StringBuilder()
        var i = 0
        var done = false

        while (i < bytes.size - 1 && !done) {
            val b1 = bytes[i].toInt() and 0xFF
            val b2 = bytes[i + 1].toInt() and 0xFF

            val z1 = (b1 shr 2) and 0x1F
            val z2 = ((b1 and 0x03) shl 3) or (b2 shr 5)
            val z3 = b2 and 0x1F

            if (z1 != 0) result.append(zsciiToChar(z1)) else done = true
            if (!done && z2 != 0) result.append(zsciiToChar(z2)) else done = true
            if (!done && z3 != 0) result.append(zsciiToChar(z3)) else done = true

            i += 2
        }

        return result.toString().trim()
    }

    private fun zsciiToChar(code: Int): Char {
        return when (code) {
            0 -> '\u0000'
            in 1..6 -> ' '
            in 65..90 -> (code + 32).toChar() // ZSCII A1 table (lowercase)
            in 97..122 -> code.toChar()
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
}
