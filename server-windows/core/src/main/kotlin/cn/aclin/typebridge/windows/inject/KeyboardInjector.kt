package cn.aclin.typebridge.windows.inject

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.Union
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.win32.W32APIOptions
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

// Win32 常量
private const val KEYEVENTF_KEYUP = 0x0002
private const val KEYEVENTF_UNICODE = 0x0004
private const val INPUT_KEYBOARD = 1
private const val VK_RETURN = 0x0D
private const val VK_SHIFT = 0x10
private const val VK_CONTROL = 0x11
private const val VK_V = 0x56

interface User32 : Library {
    fun SendInput(nInputs: Int, pInputs: Array<INPUT>, cbSize: Int): Int
    fun keybd_event(bVk: Int, bScan: Int, dwFlags: Int, dwExtraInfo: BaseTSD.ULONG_PTR)

    companion object {
        val INSTANCE: User32 = Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

class KEYBDINPUT : Structure() {
    @JvmField var wVk: Short = 0
    @JvmField var wScan: Short = 0
    @JvmField var dwFlags: Int = 0
    @JvmField var time: Int = 0
    @JvmField var dwExtraInfo: BaseTSD.ULONG_PTR = BaseTSD.ULONG_PTR(0)
    override fun getFieldOrder() = listOf("wVk", "wScan", "dwFlags", "time", "dwExtraInfo")
}

class MOUSEINPUT : Structure() {
    @JvmField var dx: Int = 0
    @JvmField var dy: Int = 0
    @JvmField var mouseData: Int = 0
    @JvmField var dwFlags: Int = 0
    @JvmField var time: Int = 0
    @JvmField var dwExtraInfo: BaseTSD.ULONG_PTR = BaseTSD.ULONG_PTR(0)
    override fun getFieldOrder() = listOf("dx", "dy", "mouseData", "dwFlags", "time", "dwExtraInfo")
}

class HARDWAREINPUT : Structure() {
    @JvmField var uMsg: Int = 0
    @JvmField var wParamL: Short = 0
    @JvmField var wParamH: Short = 0
    override fun getFieldOrder() = listOf("uMsg", "wParamL", "wParamH")
}

class INPUTUNION : Union() {
    @JvmField var mi: MOUSEINPUT = MOUSEINPUT()
    @JvmField var ki: KEYBDINPUT = KEYBDINPUT()
    @JvmField var hi: HARDWAREINPUT = HARDWAREINPUT()
}

class INPUT : Structure() {
    @JvmField var type: Int = 0
    @JvmField var u: INPUTUNION = INPUTUNION()
    override fun getFieldOrder() = listOf("type", "u")

    fun setKeyboard(wVk: Short, wScan: Short, flags: Int) {
        type = INPUT_KEYBOARD
        u.setType("ki")
        u.ki.wVk = wVk
        u.ki.wScan = wScan
        u.ki.dwFlags = flags
        u.ki.time = 0
        u.ki.dwExtraInfo = BaseTSD.ULONG_PTR(0)
        write()
    }
}

/**
 * 键盘注入器：主路径 `SendInput(KEYEVENTF_UNICODE)`，兜底剪贴板 + Ctrl+V。
 * 对应 macOS 的 `CGEventKeyboardSetUnicodeString` + 剪贴板兜底。
 */
object KeyboardInjector {
    private const val CHUNK_SIZE = 20
    private const val INJECT_DELAY_MS = 8L
    private const val PASTE_DELAY_MS = 50L

    /** 优先走剪贴板（Ctrl+V）而非 unicode 注入，用于 VS Code / 微信等注入不生效的 App。 */
    @Volatile
    var preferClipboard: Boolean = cn.aclin.typebridge.windows.storage.SettingsStore.preferClipboard

    /**
     * `pressEnter=true` 时在文本末尾敲回车。
     * `allowClipboard=false` 用于加密内容：绝不经剪贴板，只允许直接键盘注入。
     */
    fun inject(text: String, pressEnter: Boolean, allowClipboard: Boolean = true): Boolean {
        if (text.isNotEmpty()) {
            if (allowClipboard && preferClipboard) {
                pasteViaClipboard(text)
            } else {
                val ok = injectViaUnicode(text)
                if (!ok) {
                    if (allowClipboard) {
                        pasteViaClipboard(text)
                    } else {
                        // 加密内容：注入失败也不走剪贴板
                        return false
                    }
                }
            }
        }
        if (pressEnter) {
            Thread.sleep(20)
            pressReturn()
        }
        return true
    }

    /** 返回 false 表示 SendInput 被阻断（如 UIPI 提权窗口），需要走剪贴板兜底。 */
    private fun injectViaUnicode(text: String): Boolean {
        val utf16 = text.toCharArray()
        var i = 0
        while (i < utf16.size) {
            val ch = utf16[i]
            // 换行必须单独发按键：用 Shift+Return 作为软换行，避免触发提交/发送
            if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < utf16.size && utf16[i + 1] == '\n') {
                    pressSoftReturn(); Thread.sleep(INJECT_DELAY_MS); i += 2; continue
                }
                pressSoftReturn(); Thread.sleep(INJECT_DELAY_MS); i += 1; continue
            }

            var end = minOf(i + CHUNK_SIZE, utf16.size)
            for (j in i until end) {
                if (utf16[j] == '\n' || utf16[j] == '\r') { end = j; break }
            }
            if (end <= i) { i += 1; continue }

            for (j in i until end) {
                if (!sendUnicodeChar(utf16[j])) return false
            }
            i = end
            Thread.sleep(INJECT_DELAY_MS)
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    private fun sendUnicodeChar(ch: Char): Boolean {
        val code = ch.code
        // JNA 的 Structure 数组参数必须是连续内存（toArray 创建），
        // 普通 arrayOf(...) 会导致 "bad backing address at Structure array index 1"。
        val inputs = INPUT().toArray(2) as Array<INPUT>
        inputs[0].setKeyboard(0, code.toShort(), KEYEVENTF_UNICODE)
        inputs[1].setKeyboard(0, code.toShort(), KEYEVENTF_UNICODE or KEYEVENTF_KEYUP)
        val sent = User32.INSTANCE.SendInput(2, inputs, inputs[0].size())
        return sent == 2
    }

    fun pressReturn() {
        User32.INSTANCE.keybd_event(VK_RETURN, 0, 0, BaseTSD.ULONG_PTR(0))
        User32.INSTANCE.keybd_event(VK_RETURN, 0, KEYEVENTF_KEYUP, BaseTSD.ULONG_PTR(0))
    }

    fun pressSoftReturn() {
        User32.INSTANCE.keybd_event(VK_SHIFT, 0, 0, BaseTSD.ULONG_PTR(0))
        User32.INSTANCE.keybd_event(VK_RETURN, 0, 0, BaseTSD.ULONG_PTR(0))
        User32.INSTANCE.keybd_event(VK_RETURN, 0, KEYEVENTF_KEYUP, BaseTSD.ULONG_PTR(0))
        User32.INSTANCE.keybd_event(VK_SHIFT, 0, KEYEVENTF_KEYUP, BaseTSD.ULONG_PTR(0))
    }

    private fun pasteViaClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val saved = runCatching { clipboard.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
        clipboard.setContents(StringSelection(text), null)
        pressCtrlV()
        Thread.sleep(PASTE_DELAY_MS)
        if (saved != null) {
            runCatching { clipboard.setContents(StringSelection(saved), null) }
        }
    }

    private fun pressCtrlV() {
        User32.INSTANCE.keybd_event(VK_CONTROL, 0, 0, BaseTSD.ULONG_PTR(0))
        User32.INSTANCE.keybd_event(VK_V, 0, 0, BaseTSD.ULONG_PTR(0))
        User32.INSTANCE.keybd_event(VK_V, 0, KEYEVENTF_KEYUP, BaseTSD.ULONG_PTR(0))
        User32.INSTANCE.keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, BaseTSD.ULONG_PTR(0))
    }
}
