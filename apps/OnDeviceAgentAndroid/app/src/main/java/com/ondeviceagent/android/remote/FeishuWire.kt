package com.ondeviceagent.android.remote

import java.io.ByteArrayOutputStream

/** 按飞书官方 pbbp2.proto 编解码必要字段，保留响应中的其他字段；避免引入整套服务端 SDK。 */
object FeishuWire {
    const val MAX_BYTES = 1_048_576
    data class Field(val number: Int, val wire: Int, val data: ByteArray)
    data class Frame(val fields: List<Field>) {
        fun number(id: Int): Long = fields.firstOrNull { it.number == id && it.wire == 0 }?.let { Reader(it.data).varint() } ?: 0
        fun bytes(id: Int) = fields.firstOrNull { it.number == id && it.wire == 2 }?.data ?: byteArrayOf()
        fun headers(): Map<String, String> = fields.filter { it.number == 5 }.associate {
            val header = Frame(decode(it.data))
            header.bytes(1).toString(Charsets.UTF_8) to header.bytes(2).toString(Charsets.UTF_8)
        }
        fun ack(code: Int) = encode(fields.filterNot { it.number == 8 } + blob(8, "{\"code\":$code}".toByteArray()) + header("biz_rt", "0"))
    }
    fun frame(bytes: ByteArray): Frame {
        val f = decode(bytes)
        require((1..4).all { n -> f.any { it.number == n && it.wire == 0 } }) { "缺少帧必填字段" }
        return Frame(f)
    }
    fun ping(service: Long) = encode(listOf(number(1, 0), number(2, 0), number(3, service), number(4, 0), header("type", "ping")))
    fun number(id: Int, value: Long) = Field(id, 0, varint(value))
    fun blob(id: Int, value: ByteArray) = Field(id, 2, value)
    fun header(key: String, value: String) = blob(5, encode(listOf(blob(1, key.toByteArray()), blob(2, value.toByteArray()))))
    fun encode(fields: List<Field>): ByteArray = ByteArrayOutputStream().apply {
        fields.forEach { field ->
            write(varint((field.number.toLong() shl 3) or field.wire.toLong()))
            if (field.wire == 2) write(varint(field.data.size.toLong()))
            write(field.data)
        }
    }.toByteArray()
    private fun varint(value: Long): ByteArray = ByteArrayOutputStream().apply {
        var n = value
        while (n and -128L != 0L) { write(((n and 127) or 128).toInt()); n = n ushr 7 }
        write(n.toInt())
    }.toByteArray()
    fun decode(bytes: ByteArray): List<Field> {
        require(bytes.size <= MAX_BYTES)
        val reader = Reader(bytes)
        val fields = mutableListOf<Field>()
        while (reader.at < bytes.size) {
            require(fields.size < 512)
            val tag = reader.varint(); val id = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
            require(id > 0)
            val data = when (wire) {
                0 -> { val begin = reader.at; reader.varint(); bytes.copyOfRange(begin, reader.at) }
                1 -> reader.take(8)
                2 -> { val size = reader.varint(); require(size in 0..MAX_BYTES.toLong()); reader.take(size.toInt()) }
                5 -> reader.take(4)
                else -> error("不支持的帧字段类型")
            }
            fields += Field(id, wire, data)
        }
        return fields
    }
    private class Reader(val bytes: ByteArray, var at: Int = 0) {
        fun varint(): Long {
            var n = 0L
            for (shift in 0..63 step 7) {
                require(at < bytes.size)
                val b = bytes[at++].toInt() and 255
                if (shift == 63) require(b <= 1)
                n = n or ((b and 127).toLong() shl shift)
                if (b < 128) return n
            }
            error("非法变长整数")
        }
        fun take(size: Int): ByteArray {
            require(size >= 0 && size <= bytes.size - at)
            return bytes.copyOfRange(at, at + size).also { at += size }
        }
    }
}

class FeishuFragments {
    private data class Parts(val at: Long, val chunks: Array<ByteArray?>)
    private val pending = mutableMapOf<String, Parts>()
    @Synchronized fun collect(frame: FeishuWire.Frame, now: Long): ByteArray? {
        val headers = frame.headers()
        val sum = headers["sum"]?.toIntOrNull() ?: 1
        val seq = headers["seq"]?.toIntOrNull() ?: 0
        require(sum in 1..32 && seq in 0 until sum)
        if (sum == 1) return frame.bytes(8)
        val id = headers["message_id"].orEmpty(); require(id.isNotBlank())
        pending.entries.removeAll { now - it.value.at > 30_000 }
        require(pending.size < 32 || id in pending)
        val parts = pending.getOrPut(id) { Parts(now, arrayOfNulls(sum)) }
        require(parts.chunks.size == sum)
        parts.chunks[seq] = frame.bytes(8)
        require(parts.chunks.sumOf { it?.size ?: 0 } <= FeishuWire.MAX_BYTES)
        if (parts.chunks.any { it == null }) return null
        pending.remove(id)
        return ByteArrayOutputStream().apply { parts.chunks.forEach { write(it!!) } }.toByteArray()
    }
}
