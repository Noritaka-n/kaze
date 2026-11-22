package app.kaze.ui

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import java.awt.Frame
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame





object WindowCorners {
    private interface User32 : Library {
        fun FindWindowW(className: WString?, windowName: WString?): Pointer?
        fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
        fun SetWindowRgn(hwnd: Pointer, region: Pointer?, redraw: Boolean): Int
        fun ShowWindow(hwnd: Pointer, command: Int): Boolean
        fun SetForegroundWindow(hwnd: Pointer): Boolean
    }

    private interface Gdi32 : Library {
        fun CreateRoundRectRgn(x1: Int, y1: Int, x2: Int, y2: Int, w: Int, h: Int): Pointer?
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left: Int = 0
        @JvmField var top: Int = 0
        @JvmField var right: Int = 0
        @JvmField var bottom: Int = 0
    }

    private val user32: User32? = runCatching {
        Native.load("user32", User32::class.java)
    }.getOrNull()

    private val gdi32: Gdi32? = runCatching {
        Native.load("gdi32", Gdi32::class.java)
    }.getOrNull()

    fun attach(window: JFrame) {
        val update = { apply(window) }
        window.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = update()
        })
        window.addWindowStateListener(object : WindowAdapter() {
            override fun windowStateChanged(event: WindowEvent) = update()
        })
        update()
    }

    fun focusExisting(title: String = "Kaze") {
        val api = user32 ?: return
        val hwnd = runCatching { api.FindWindowW(null, WString(title)) }.getOrNull() ?: return
        runCatching { api.ShowWindow(hwnd, 9) }
        runCatching { api.SetForegroundWindow(hwnd) }
    }

    fun apply(window: JFrame) {
        val user = user32 ?: return
        val gdi = gdi32 ?: return
        val hwnd = runCatching { user.FindWindowW(null, WString(window.title)) }.getOrNull() ?: return

        
        if (window.extendedState == Frame.MAXIMIZED_BOTH) {
            runCatching { user.SetWindowRgn(hwnd, null, true) }
            return
        }

        val rect = Rect()
        if (!user.GetWindowRect(hwnd, rect)) return
        val width = rect.right - rect.left
        val height = rect.bottom - rect.top
        if (width <= 0 || height <= 0) return

        val scale = runCatching {
            window.graphicsConfiguration.defaultTransform.scaleX
        }.getOrDefault(1.0)
        val radius = (18 * scale).toInt().coerceAtLeast(12)
        val region = runCatching {
            gdi.CreateRoundRectRgn(0, 0, width + 1, height + 1, radius * 2, radius * 2)
        }.getOrNull() ?: return
        runCatching { user.SetWindowRgn(hwnd, region, true) }
    }
}
