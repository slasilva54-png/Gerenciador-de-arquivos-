package com.example.engine.bencode

import java.nio.charset.StandardCharsets

class BencodeParser(private val data: ByteArray) {
    private var index = 0

    fun parse(): Any {
        if (index >= data.size) throw IllegalArgumentException("EOF reached")
        val c = data[index].toInt().toChar()
        return when (c) {
            'i' -> {
                index++ // Skip 'i'
                parseInteger()
            }
            'l' -> {
                index++ // Skip 'l'
                parseList()
            }
            'd' -> {
                index++ // Skip 'd'
                parseDictionary()
            }
            else -> {
                if (c.isDigit()) {
                    parseString()
                } else {
                    throw IllegalArgumentException("Unexpected character at index $index: $c")
                }
            }
        }
    }

    private fun parseInteger(): Long {
        val start = index
        while (index < data.size && data[index].toInt().toChar() != 'e') {
            index++
        }
        if (index >= data.size) throw IllegalArgumentException("Integer not closed")
        val str = String(data, start, index - start, StandardCharsets.US_ASCII)
        index++ // Skip 'e'
        return str.toLong()
    }

    private fun parseString(): ByteArray {
        val start = index
        while (index < data.size && data[index].toInt().toChar() != ':') {
            index++
        }
        if (index >= data.size) throw IllegalArgumentException("String length delimiter ':' not found")
        val lenStr = String(data, start, index - start, StandardCharsets.US_ASCII)
        val length = lenStr.toInt()
        index++ // Skip ':'
        if (index + length > data.size) throw IllegalArgumentException("String length $length exceeds data size")
        val result = ByteArray(length)
        System.arraycopy(data, index, result, 0, length)
        index += length
        return result
    }

    private fun parseList(): List<Any> {
        val list = mutableListOf<Any>()
        while (index < data.size && data[index].toInt().toChar() != 'e') {
            list.add(parse())
        }
        if (index >= data.size) throw IllegalArgumentException("List not closed")
        index++ // Skip 'e'
        return list
    }

    private fun parseDictionary(): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        while (index < data.size && data[index].toInt().toChar() != 'e') {
            val key = parse()
            val keyStr = when (key) {
                is ByteArray -> String(key, StandardCharsets.UTF_8)
                is String -> key
                else -> throw IllegalArgumentException("Dictionary keys must be strings/byte arrays")
            }
            val value = parse()
            map[keyStr] = value
        }
        if (index >= data.size) throw IllegalArgumentException("Dictionary not closed")
        index++ // Skip 'e'
        return map
    }
}
