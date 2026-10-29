package com.ondeviceagent.android

import com.ondeviceagent.android.remote.*
import org.junit.Assert.*
import org.junit.Test

class FeishuWireTest {
    @Test fun `心跳符合官方协议字段及二进制样例`() {
        val expected = "08001000180120002a0c0a0474797065120470696e67".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, FeishuWire.ping(1))
        val frame = FeishuWire.frame(expected)
        assertEquals(1L, frame.number(3)); assertEquals("ping", frame.headers()["type"])
    }
    private fun fragment(seq: Int, data: String, sum: Int = 2) = FeishuWire.frame(FeishuWire.encode(listOf(
        FeishuWire.number(1, 123), FeishuWire.number(2, 456), FeishuWire.number(3, 1), FeishuWire.number(4, 1),
        FeishuWire.header("type", "event"), FeishuWire.header("message_id", "event1"), FeishuWire.header("sum", "$sum"), FeishuWire.header("seq", "$seq"),
        FeishuWire.blob(8, data.toByteArray()), FeishuWire.blob(9, "log".toByteArray()))))
    @Test fun `确认帧保留路由序列和扩展日志号`() {
        val ack = FeishuWire.frame(fragment(0, "body").ack(200))
        assertEquals(123L, ack.number(1)); assertEquals(456L, ack.number(2)); assertEquals(1L, ack.number(4))
        assertEquals("{\"code\":200}", ack.bytes(8).toString(Charsets.UTF_8))
        assertEquals("log", ack.bytes(9).toString(Charsets.UTF_8))
    }
    @Test fun `乱序和重复分片只在完整时组装`() {
        val parts = FeishuFragments()
        assertNull(parts.collect(fragment(1, "后半"), 1000))
        assertNull(parts.collect(fragment(1, "后半"), 1001))
        assertEquals("前半后半", parts.collect(fragment(0, "前半"), 1002)!!.toString(Charsets.UTF_8))
    }
    @Test fun `畸形长度与分片边界拒绝解析`() {
        assertThrows(IllegalArgumentException::class.java) { FeishuWire.decode(byteArrayOf(66, 127)) }
        assertThrows(IllegalArgumentException::class.java) { FeishuWire.frame(byteArrayOf(8, 0)) }
        assertThrows(IllegalArgumentException::class.java) { FeishuFragments().collect(fragment(33, "x", 34), 0) }
    }
}
