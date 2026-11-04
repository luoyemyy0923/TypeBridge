package cn.aclin.typebridge.windows.inject

import com.sun.jna.Pointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证 JNA 的 INPUT 结构体能被反射正确实例化与写入（此前 private 类导致 SendInput 抛异常）。 */
class InputStructTests {

    @Test
    fun inputStructCanBeCreatedAndWritten() {
        val input = INPUT()
        input.setKeyboard(0, 'A'.code.toShort(), 0x0004) // KEYEVENTF_UNICODE
        input.write()
        assertTrue(input.size() >= 24)
    }

    @Test
    fun structSizesMatchNativeX64() {
        // x64：KEYBDINPUT = 24 字节（2+2+4+4+8，dwExtraInfo 按 8 字节对齐）
        assertEquals(24, KEYBDINPUT().size())
        // x64：INPUT = 40 字节（type 4 + padding 4 + MOUSEINPUT 联合体 32）
        assertEquals(40, INPUT().size())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun inputArrayUsesContiguousMemory() {
        // 此前 SendInput 用 arrayOf(down, up) 传参，报
        // "Structure array elements must use contiguous memory"。这里验证 toArray 方案：
        // 两个元素能写入不同字段值，且内存连续（backing pointer 不同、size 一致）。
        val inputs = INPUT().toArray(2) as Array<INPUT>
        inputs[0].setKeyboard(0, 'A'.code.toShort(), 0x0004)
        inputs[1].setKeyboard(0, 'B'.code.toShort(), 0x0006)
        inputs[0].write()
        inputs[1].write()
        assertEquals(40, inputs[0].size())
        assertEquals(40, inputs[1].size())
        // 连续内存：两个元素的 native 地址相差恰好一个结构体大小
        val delta = Pointer.nativeValue(inputs[1].pointer) - Pointer.nativeValue(inputs[0].pointer)
        assertEquals(40L, delta)
    }
}
