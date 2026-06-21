package org.jason.siph.ui.coupling


import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.input.pointer.pointerInput
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_FILL
import org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK
import org.lwjgl.opengl.GL11.GL_LEQUAL
import org.lwjgl.opengl.GL11.GL_LINES
import org.lwjgl.opengl.GL11.GL_LINE_LOOP
import org.lwjgl.opengl.GL11.GL_LINE_STRIP
import org.lwjgl.opengl.GL11.GL_MODELVIEW
import org.lwjgl.opengl.GL11.GL_POINTS
import org.lwjgl.opengl.GL11.GL_PROJECTION
import org.lwjgl.opengl.GL11.GL_QUADS
import org.lwjgl.opengl.GL11.GL_SMOOTH
import org.lwjgl.opengl.GL11.GL_TRIANGLES
import org.lwjgl.opengl.GL11.glBegin
import org.lwjgl.opengl.GL11.glClear
import org.lwjgl.opengl.GL11.glClearColor
import org.lwjgl.opengl.GL11.glColor3f
import org.lwjgl.opengl.GL11.glDepthFunc
import org.lwjgl.opengl.GL11.glEnable
import org.lwjgl.opengl.GL11.glEnd
import org.lwjgl.opengl.GL11.glFrustum
import org.lwjgl.opengl.GL11.glLineWidth
import org.lwjgl.opengl.GL11.glLoadIdentity
import org.lwjgl.opengl.GL11.glMatrixMode
import org.lwjgl.opengl.GL11.glPointSize
import org.lwjgl.opengl.GL11.glPolygonMode
import org.lwjgl.opengl.GL11.glRotatef
import org.lwjgl.opengl.GL11.glScalef
import org.lwjgl.opengl.GL11.glShadeModel
import org.lwjgl.opengl.GL11.glTranslatef
import org.lwjgl.opengl.GL11.glVertex3f
import org.lwjgl.opengl.GL11.glViewport
import org.lwjgl.opengl.awt.AWTGLCanvas
import org.lwjgl.opengl.awt.GLData
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.NULL
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.AWTEvent
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Component
import java.awt.event.AWTEventListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.absoluteValue

@Composable
internal actual fun LwjglPowerSurface3d(
    mesh: SurfaceMesh?,
    modifier: Modifier
) {
    var yaw by remember { mutableStateOf(-34f) }
    var pitch by remember { mutableStateOf(62f) }
    val zoom by remember { mutableStateOf(1.0f) }

    /*
     * 不要在 mesh == null 时直接 return Compose Placeholder。
     *
     * 否则 Compose 会销毁 SwingPanel / AWTGLCanvas，
     * 在 Windows + Compose Hot Reload / Live Edit 下容易触发：
     *
     * JAWTDrawingSurface ds is null
     */
    SwingPanel(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                yaw += dragAmount.x * 0.48f
                pitch = (pitch + dragAmount.y * 0.48f).coerceIn(12f, 88f)
            }
        },
        factory = {
            LwjglSurfacePanel()
        },
        update = { panel ->
            panel.setMesh(mesh)
            panel.setView(
                yaw = yaw,
                pitch = pitch,
                zoom = zoom
            )
        }
    )
}

/**
 * Swing 侧稳定容器。
 *
 * Compose 只管理这个 JPanel。
 * mesh == null 时，只在 Swing 内部显示 placeholder。
 * mesh != null 时，懒创建 LwjglSurfaceCanvas。
 */
private class LwjglSurfacePanel : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()

    private val placeholder = createPlaceholder()

    private val titleLabel = JLabel("Output 3D Diagram", SwingConstants.CENTER).apply {
        isOpaque = true
        background = Color(248, 250, 252)
        foreground = Color(31, 41, 55)
        font = Font(Font.SANS_SERIF, Font.BOLD, 12)
        border = BorderFactory.createEmptyBorder(6, 0, 3, 0)
    }

    private val colorBar = PowerColorBarPanel()

    private val resetButton = JButton("Reset").apply {
        isFocusable = false
        font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        background = Color(241, 245, 249)
        foreground = Color(51, 65, 85)
    }

    private val openGlfwButton = JButton("Open GLFW").apply {
        isFocusable = false
        font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        background = Color(226, 232, 240)
        foreground = Color(15, 23, 42)
    }

    private val controlPanel = JPanel(BorderLayout()).apply {
        isOpaque = true
        background = Color(248, 250, 252)
        border = BorderFactory.createEmptyBorder(4, 8, 5, 8)
        preferredSize = Dimension(1, 34)
        add(
            JLabel("Rotate", SwingConstants.CENTER).apply {
                foreground = Color(71, 85, 105)
                font = Font(Font.SANS_SERIF, Font.BOLD, 11)
            },
            BorderLayout.CENTER
        )
        add(openGlfwButton, BorderLayout.WEST)
        add(resetButton, BorderLayout.EAST)
    }

    private var canvas: LwjglSurfaceCanvas? = null
    private val glfwWindow = GlfwSurfaceWindow()
    private var yaw = -34f
    private var pitch = 62f
    private var zoom = 1.0f
    private var currentMesh: SurfaceMesh? = null
    private var lastDragScreenX = 0
    private var lastDragScreenY = 0

    private val cardPanel = JPanel(cardLayout).apply {
        add(placeholder, CARD_PLACEHOLDER)
    }

    init {
        minimumSize = Dimension(64, 64)
        preferredSize = Dimension(720, 420)
        border = BorderFactory.createLineBorder(Color(210, 218, 230), 1)

        add(titleLabel, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(colorBar, BorderLayout.EAST)
        add(controlPanel, BorderLayout.SOUTH)

        listOf<Component>(titleLabel, colorBar, controlPanel).forEach(::installViewControls)
        resetButton.addActionListener {
            yaw = -34f
            pitch = 62f
            zoom = 1.0f
            applyView()
        }
        openGlfwButton.addActionListener {
            currentMesh?.let { mesh ->
                glfwWindow.open(mesh)
            }
        }

        cardLayout.show(cardPanel, CARD_PLACEHOLDER)
    }

    fun setMesh(mesh: SurfaceMesh?) {
        runOnEdt {
            if (mesh == null) {
                currentMesh = null
                canvas?.setMesh(null)
                colorBar.setRange(null, null)

                cardLayout.show(cardPanel, CARD_PLACEHOLDER)
                revalidate()
                repaint()

                return@runOnEdt
            }

            val activeCanvas = ensureCanvas()
            currentMesh = mesh
            activeCanvas.setMesh(mesh)
            glfwWindow.updateMesh(mesh)
            colorBar.setRange(mesh.minPower, mesh.maxPower)

            cardLayout.show(cardPanel, CARD_CANVAS)
            revalidate()
            repaint()
        }
    }

    fun setView(
        yaw: Float,
        pitch: Float,
        zoom: Float
    ) {
        runOnEdt {
            this.yaw = yaw
            this.pitch = pitch
            this.zoom = zoom
            applyView()
        }
    }

    private fun ensureCanvas(): LwjglSurfaceCanvas {
        val existing = canvas

        if (existing != null && !existing.isDisposed) {
            return existing
        }

        if (existing != null) {
            cardPanel.remove(existing)
        }

        val created = LwjglSurfaceCanvas()
        canvas = created

        cardPanel.add(created, CARD_CANVAS)

        return created
    }

    private fun installViewControls(component: Component) {
        component.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                lastDragScreenX = event.xOnScreen
                lastDragScreenY = event.yOnScreen
            }

            override fun mouseWheelMoved(event: MouseWheelEvent) {
                zoom = (zoom - event.preciseWheelRotation.toFloat() * 0.08f)
                    .coerceIn(0.58f, 1.95f)
                applyView()
            }
        }
        component.addMouseListener(mouseAdapter)
        component.addMouseWheelListener(mouseAdapter)
        component.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                val dx = event.xOnScreen - lastDragScreenX
                val dy = event.yOnScreen - lastDragScreenY

                yaw += dx * 0.48f
                pitch = (pitch + dy * 0.48f).coerceIn(12f, 88f)
                lastDragScreenX = event.xOnScreen
                lastDragScreenY = event.yOnScreen

                applyView()
            }
        })
    }

    private fun applyView() {
        canvas?.setView(
            yaw = yaw,
            pitch = pitch,
            zoom = zoom
        )
    }

    override fun removeNotify() {
        glfwWindow.close()
        super.removeNotify()
    }

    companion object {
        private const val CARD_PLACEHOLDER = "placeholder"
        private const val CARD_CANVAS = "canvas"

        private fun createPlaceholder(): JLabel {
            return JLabel(
                """
                <html>
                    <div style='text-align:center; padding: 18px;'>
                        <div style='font-size: 15px; font-weight: bold;'>LWJGL</div>
                        <div style='font-size: 11px; margin-top: 6px;'>
                            Need at least three non-collinear samples to build a surface.
                        </div>
                    </div>
                </html>
                """.trimIndent(),
                SwingConstants.CENTER
            ).apply {
                isOpaque = true
                background = Color(241, 245, 249)
                foreground = Color(78, 86, 102)
                font = Font(Font.SANS_SERIF, Font.PLAIN, 13)
            }
        }
    }
}

/**
 * 真正的 OpenGL Canvas。
 *
 * 关键保护：
 * 1. mesh 可为空。
 * 2. repaint 前检查 displayable / size。
 * 3. removeNotify 捕获 lwjgl3-awt 在 Windows 下偶发的 dispose NPE。
 * 4. dispose NPE 只打印一次，避免控制台刷屏。
 */
private class PowerColorBarPanel : JPanel() {

    private var minPower: Double? = null
    private var maxPower: Double? = null

    init {
        preferredSize = Dimension(58, 1)
        minimumSize = Dimension(48, 1)
        isOpaque = true
        background = Color(248, 250, 252)
        font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
    }

    fun setRange(
        minPower: Double?,
        maxPower: Double?
    ) {
        this.minPower = minPower
        this.maxPower = maxPower
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)

        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val barX = 8
        val barY = 12
        val barWidth = 16
        val barHeight = (height - 34).coerceAtLeast(40)

        for (i in 0..barHeight) {
            val ratio = 1f - i / barHeight.toFloat()
            g.color = surfaceAwtColor(ratio)
            g.drawLine(barX, barY + i, barX + barWidth, barY + i)
        }

        g.color = Color(100, 116, 139)
        g.drawRect(barX, barY, barWidth, barHeight)

        val min = minPower
        val max = maxPower

        listOf(1f, 0.75f, 0.5f, 0.25f, 0f).forEach { ratio ->
            val y = barY + ((1f - ratio) * barHeight).toInt()
            g.color = Color(100, 116, 139)
            g.drawLine(barX + barWidth, y, barX + barWidth + 5, y)

            if (min != null && max != null) {
                val value = min + (max - min) * ratio
                g.color = Color(71, 85, 105)
                g.drawString(String.format("%.1f", value), barX + barWidth + 8, y + 4)
            }
        }
    }
}

private class GlfwSurfaceWindow {

    @Volatile
    private var mesh: SurfaceMesh? = null

    @Volatile
    private var windowHandle: Long = NULL

    private val running = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)

    fun open(initialMesh: SurfaceMesh) {
        mesh = initialMesh

        if (!running.compareAndSet(false, true)) {
            val handle = windowHandle
            if (handle != NULL) {
                GLFW.glfwShowWindow(handle)
                GLFW.glfwFocusWindow(handle)
            }
            return
        }

        closeRequested.set(false)

        Thread(
            {
                renderLoop()
            },
            "LWJGL-GLFW-PowerSurface"
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun updateMesh(nextMesh: SurfaceMesh) {
        mesh = nextMesh
    }

    fun close() {
        closeRequested.set(true)
        val handle = windowHandle
        if (handle != NULL) {
            GLFW.glfwSetWindowShouldClose(handle, true)
        }
    }

    private fun renderLoop() {
        var errorCallback: GLFWErrorCallback? = null
        var yaw = -34f
        var pitch = 62f
        var zoom = 1.0f
        var dragging = false
        var lastX = 0.0
        var lastY = 0.0

        try {
            errorCallback = GLFWErrorCallback.createPrint(System.err).also {
                GLFW.glfwSetErrorCallback(it)
            }

            if (!GLFW.glfwInit()) {
                throw IllegalStateException("Unable to initialize GLFW")
            }

            GLFW.glfwDefaultWindowHints()
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3)
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2)
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE)
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE)
            GLFW.glfwWindowHint(GLFW.GLFW_SAMPLES, 4)

            val window = GLFW.glfwCreateWindow(
                980,
                680,
                "LWJGL GLFW Power Surface",
                NULL,
                NULL
            )

            if (window == NULL) {
                throw IllegalStateException("Unable to create GLFW window")
            }

            windowHandle = window

            GLFW.glfwSetMouseButtonCallback(window) { handle, button, action, _ ->
                if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@glfwSetMouseButtonCallback

                dragging = action == GLFW.GLFW_PRESS

                if (dragging) {
                    MemoryStack.stackPush().use { stack ->
                        val x = stack.mallocDouble(1)
                        val y = stack.mallocDouble(1)
                        GLFW.glfwGetCursorPos(handle, x, y)
                        lastX = x[0]
                        lastY = y[0]
                    }
                }
            }

            GLFW.glfwSetCursorPosCallback(window) { _, x, y ->
                if (!dragging) return@glfwSetCursorPosCallback

                yaw += ((x - lastX) * 0.48).toFloat()
                pitch = (pitch + ((y - lastY) * 0.48).toFloat()).coerceIn(12f, 88f)
                lastX = x
                lastY = y
            }

            GLFW.glfwSetScrollCallback(window) { _, _, yOffset ->
                zoom = (zoom + yOffset.toFloat() * 0.08f).coerceIn(0.58f, 1.95f)
            }

            GLFW.glfwMakeContextCurrent(window)
            GLFW.glfwSwapInterval(1)
            GLFW.glfwShowWindow(window)

            GL.createCapabilities()
            initSharedGlState()

            while (!GLFW.glfwWindowShouldClose(window) && !closeRequested.get()) {
                MemoryStack.stackPush().use { stack ->
                    val width = stack.mallocInt(1)
                    val height = stack.mallocInt(1)
                    GLFW.glfwGetFramebufferSize(window, width, height)
                    renderPowerSurface(
                        mesh = mesh,
                        viewportWidth = width[0].coerceAtLeast(1),
                        viewportHeight = height[0].coerceAtLeast(1),
                        yaw = yaw,
                        pitch = pitch,
                        zoom = zoom
                    )
                }

                GLFW.glfwSwapBuffers(window)
                GLFW.glfwPollEvents()
            }

            GLFW.glfwDestroyWindow(window)
            windowHandle = NULL
        } catch (e: Throwable) {
            e.printStackTrace(System.err)
        } finally {
            running.set(false)
            closeRequested.set(false)
            windowHandle = NULL
            GLFW.glfwTerminate()
            errorCallback?.free()
        }
    }
}

private class LwjglSurfaceCanvas : AWTGLCanvas(
    GLData().apply {
        /*
         * 当前代码使用固定管线：
         * glBegin / glMatrixMode / glFrustum / glShadeModel
         *
         * 所以必须使用兼容模式。
         *
         * 如果客户机器显卡较旧，可以尝试改成：
         * majorVersion = 2
         * minorVersion = 1
         * profile 不设置
         */
        majorVersion = 3
        minorVersion = 2
        profile = GLData.Profile.COMPATIBILITY
        samples = 4
        swapInterval = 1
    }
) {

    @Volatile
    private var mesh: SurfaceMesh? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var disposed = false

    val isDisposed: Boolean
        get() = disposed

    private var yaw = -34f
    private var pitch = 62f
    private var zoom = 1.0f

    private var lastX = 0
    private var lastY = 0
    private var dragStartedInCanvas = false
    private var globalMouseListenerInstalled = false

    private val globalMouseListener = AWTEventListener { event ->
        handleGlobalMouseEvent(event)
    }

    init {
        minimumSize = Dimension(64, 64)
        preferredSize = Dimension(720, 420)
        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        isFocusable = true
        enableEvents(
            AWTEvent.MOUSE_EVENT_MASK or
                    AWTEvent.MOUSE_MOTION_EVENT_MASK or
                    AWTEvent.MOUSE_WHEEL_EVENT_MASK
        )
    }

    fun setMesh(nextMesh: SurfaceMesh?) {
        if (disposed) return

        mesh = nextMesh
        repaintSafely()
    }

    fun setView(
        yaw: Float,
        pitch: Float,
        zoom: Float
    ) {
        if (disposed) return

        this.yaw = yaw
        this.pitch = pitch
        this.zoom = zoom
        repaintSafely()
    }

    override fun processMouseEvent(event: MouseEvent) {
        super.processMouseEvent(event)

        if (disposed || event.isConsumed) return

        if (event.id == MouseEvent.MOUSE_PRESSED) {
            requestFocusInWindow()
            lastX = event.x
            lastY = event.y
            event.consume()
        }
    }

    override fun processMouseMotionEvent(event: MouseEvent) {
        super.processMouseMotionEvent(event)

        if (disposed || event.isConsumed) return

        if (event.id == MouseEvent.MOUSE_DRAGGED) {
            rotateBy(event.x - lastX, event.y - lastY)
            lastX = event.x
            lastY = event.y
            event.consume()
        }
    }

    override fun processMouseWheelEvent(event: MouseWheelEvent) {
        super.processMouseWheelEvent(event)

        if (disposed) return

        zoomBy(event.preciseWheelRotation.toFloat())
        event.consume()
    }

    override fun addNotify() {
        super.addNotify()
        installGlobalMouseListener()
    }

    override fun initGL() {
        if (disposed || initialized) return

        GL.createCapabilities()

        initialized = true

        initSharedGlState()
    }

    override fun paintGL() {
        if (
            disposed ||
            !initialized ||
            !isDisplayable ||
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        renderPowerSurface(
            mesh = mesh,
            viewportWidth = width.coerceAtLeast(1),
            viewportHeight = height.coerceAtLeast(1),
            yaw = yaw,
            pitch = pitch,
            zoom = zoom
        )

        swapBuffers()
    }

    override fun removeNotify() {
        disposed = true
        initialized = false
        dragStartedInCanvas = false
        uninstallGlobalMouseListener()

        try {
            super.removeNotify()
        } catch (e: NullPointerException) {
            if (disposeWarningPrinted.compareAndSet(false, true)) {
                System.err.println(
                    "Ignored LWJGL AWTGLCanvas dispose NPE once: ${e.message}"
                )
            }
        }
    }

    private fun repaintSafely() {
        if (disposed) return

        runOnEdt {
            if (
                !disposed &&
                isDisplayable &&
                width > 0 &&
                height > 0
            ) {
                repaint()
            }
        }
    }

    private fun handleGlobalMouseEvent(event: AWTEvent) {
        if (disposed || !isShowing || event !is MouseEvent) return

        val source = event.source as? java.awt.Component ?: return
        val localPoint = try {
            SwingUtilities.convertPoint(source, event.point, this)
        } catch (_: RuntimeException) {
            return
        }
        val insideCanvas = localPoint.x in 0 until width && localPoint.y in 0 until height

        when (event.id) {
            MouseEvent.MOUSE_PRESSED -> {
                if (!insideCanvas) {
                    dragStartedInCanvas = false
                    return
                }

                requestFocusInWindow()
                dragStartedInCanvas = true
                lastX = localPoint.x
                lastY = localPoint.y
                event.consume()
            }

            MouseEvent.MOUSE_DRAGGED -> {
                if (!dragStartedInCanvas) return

                rotateBy(localPoint.x - lastX, localPoint.y - lastY)
                lastX = localPoint.x
                lastY = localPoint.y
                event.consume()
            }

            MouseEvent.MOUSE_RELEASED,
            MouseEvent.MOUSE_EXITED -> {
                dragStartedInCanvas = false
            }

            MouseEvent.MOUSE_WHEEL -> {
                if (!insideCanvas || event !is MouseWheelEvent) return

                zoomBy(event.preciseWheelRotation.toFloat())
                event.consume()
            }
        }
    }

    private fun installGlobalMouseListener() {
        if (globalMouseListenerInstalled) return

        Toolkit.getDefaultToolkit().addAWTEventListener(
            globalMouseListener,
            AWTEvent.MOUSE_EVENT_MASK or
                    AWTEvent.MOUSE_MOTION_EVENT_MASK or
                    AWTEvent.MOUSE_WHEEL_EVENT_MASK
        )
        globalMouseListenerInstalled = true
    }

    private fun uninstallGlobalMouseListener() {
        if (!globalMouseListenerInstalled) return

        Toolkit.getDefaultToolkit().removeAWTEventListener(globalMouseListener)
        globalMouseListenerInstalled = false
    }

    private fun rotateBy(
        dx: Int,
        dy: Int
    ) {
        yaw += dx * 0.48f
        pitch = (pitch + dy * 0.48f).coerceIn(12f, 88f)
        repaintSafely()
    }

    private fun zoomBy(
        wheelRotation: Float
    ) {
        zoom = (zoom - wheelRotation * 0.08f).coerceIn(0.58f, 1.95f)
        repaintSafely()
    }

    private fun setupProjection(
        viewportWidth: Int,
        viewportHeight: Int
    ) {
        val aspect = viewportWidth.toDouble() / viewportHeight.toDouble()

        glMatrixMode(GL_PROJECTION)
        glLoadIdentity()

        glFrustum(
            -aspect * 0.62,
            aspect * 0.62,
            -0.62,
            0.62,
            1.2,
            12.0
        )
    }

    companion object {
        private val disposeWarningPrinted = AtomicBoolean(false)
    }
}

private fun initSharedGlState() {
    glClearColor(0.985f, 0.989f, 0.994f, 1f)
    glEnable(GL_DEPTH_TEST)
    glDepthFunc(GL_LEQUAL)
    glShadeModel(GL_SMOOTH)
}

private fun renderPowerSurface(
    mesh: SurfaceMesh?,
    viewportWidth: Int,
    viewportHeight: Int,
    yaw: Float,
    pitch: Float,
    zoom: Float
) {
    glViewport(0, 0, viewportWidth, viewportHeight)
    glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

    val currentMesh = mesh

    if (currentMesh == null || currentMesh.points.isEmpty()) {
        return
    }

    setupSharedProjection(viewportWidth, viewportHeight)

    glMatrixMode(GL_MODELVIEW)
    glLoadIdentity()

    glTranslatef(0f, -0.18f, -3.48f)
    glScalef(zoom, zoom, zoom)
    glRotatef(pitch, 1f, 0f, 0f)
    glRotatef(yaw, 0f, 1f, 0f)

    drawFloorPanel()
    drawFloorHeatmap(currentMesh)
    drawPlotBox()
    drawGrid()
    drawProjection(currentMesh)
    drawSurface(currentMesh)
    drawWireframe(currentMesh)
    drawAxes()
    drawPeak(currentMesh)
}

private fun setupSharedProjection(
    viewportWidth: Int,
    viewportHeight: Int
) {
    val aspect = viewportWidth.toDouble() / viewportHeight.toDouble()

    glMatrixMode(GL_PROJECTION)
    glLoadIdentity()

    glFrustum(
        -aspect * 0.62,
        aspect * 0.62,
        -0.62,
        0.62,
        1.2,
        12.0
    )
}

private fun drawSurface(
    mesh: SurfaceMesh
) {
    val rows = mesh.points
    if (rows.size < 2) return

    val powerSpan = (mesh.maxPower - mesh.minPower)
        .takeIf { it.absoluteValue > 1e-9 }
        ?: 1.0

    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)

    glBegin(GL_TRIANGLES)

    for (yIndex in 0 until rows.lastIndex) {
        val currentRow = rows[yIndex]
        val nextRow = rows[yIndex + 1]

        val cellCount = minOf(currentRow.size, nextRow.size) - 1
        if (cellCount <= 0) continue

        for (xIndex in 0 until cellCount) {
            val p0 = currentRow[xIndex]
            val p1 = currentRow[xIndex + 1]
            val p2 = nextRow[xIndex + 1]
            val p3 = nextRow[xIndex]

            emitVertex(p0, powerRatio(p0, mesh.minPower, powerSpan))
            emitVertex(p1, powerRatio(p1, mesh.minPower, powerSpan))
            emitVertex(p2, powerRatio(p2, mesh.minPower, powerSpan))

            emitVertex(p0, powerRatio(p0, mesh.minPower, powerSpan))
            emitVertex(p2, powerRatio(p2, mesh.minPower, powerSpan))
            emitVertex(p3, powerRatio(p3, mesh.minPower, powerSpan))
        }
    }

    glEnd()
}

private fun drawWireframe(
    mesh: SurfaceMesh
) {
    val rows = mesh.points
    if (rows.isEmpty()) return

    glLineWidth(0.82f)
    glColor3f(0.30f, 0.36f, 0.46f)

    rows.forEachIndexed { index, row ->
        if (row.isEmpty()) return@forEachIndexed

        if (index % 2 == 0 || index == rows.lastIndex) {
            glBegin(GL_LINE_STRIP)

            row.forEach { point ->
                vertex(point)
            }

            glEnd()
        }
    }

    val maxRowSize = rows.maxOfOrNull { it.size } ?: return

    for (xIndex in 0 until maxRowSize step 2) {
        glBegin(GL_LINE_STRIP)

        rows.forEach { row ->
            if (xIndex < row.size) {
                vertex(row[xIndex])
            }
        }

        glEnd()
    }
}

private fun drawProjection(
    mesh: SurfaceMesh
) {
    val rows = mesh.points
    if (rows.isEmpty()) return

    glLineWidth(1.8f)

    rows.forEachIndexed { index, row ->
        if (row.isEmpty()) return@forEachIndexed

        if (index % 2 == 0 || index == rows.lastIndex) {
            glColor3f(0.86f, 0.10f, 0.14f)

            glBegin(GL_LINE_STRIP)

            row.forEach { point ->
                vertex(point, flattened = true)
            }

            glEnd()
        }
    }

    val maxRowSize = rows.maxOfOrNull { it.size } ?: return

    glColor3f(0.16f, 0.36f, 0.92f)

    for (xIndex in 0 until maxRowSize step 2) {
        glBegin(GL_LINE_STRIP)

        rows.forEach { row ->
            if (xIndex < row.size) {
                vertex(row[xIndex], flattened = true)
            }
        }

        glEnd()
    }
}

private fun drawFloorPanel() {
    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
    glColor3f(0.962f, 0.972f, 0.986f)

    glBegin(GL_QUADS)

    glVertex3f(-1f, -0.002f, -1f)
    glVertex3f(1f, -0.002f, -1f)
    glVertex3f(1f, -0.002f, 1f)
    glVertex3f(-1f, -0.002f, 1f)

    glEnd()
}

private fun drawFloorHeatmap(
    mesh: SurfaceMesh
) {
    val rows = mesh.points
    if (rows.size < 2) return

    val powerSpan = (mesh.maxPower - mesh.minPower)
        .takeIf { it.absoluteValue > 1e-9 }
        ?: 1.0

    glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)
    glBegin(GL_QUADS)

    for (yIndex in 0 until rows.lastIndex) {
        val currentRow = rows[yIndex]
        val nextRow = rows[yIndex + 1]
        val cellCount = minOf(currentRow.size, nextRow.size) - 1

        for (xIndex in 0 until cellCount) {
            val p0 = currentRow[xIndex]
            val p1 = currentRow[xIndex + 1]
            val p2 = nextRow[xIndex + 1]
            val p3 = nextRow[xIndex]
            val ratio = listOf(p0, p1, p2, p3)
                .map { powerRatio(it, mesh.minPower, powerSpan) }
                .average()
                .toFloat()
                .coerceIn(0f, 1f)
            val color = floorColor(ratio)

            glColor3f(color[0], color[1], color[2])
            floorVertex(p0)
            floorVertex(p1)
            floorVertex(p2)
            floorVertex(p3)
        }
    }

    glEnd()
}

private fun drawPlotBox() {
    glLineWidth(1.35f)
    glColor3f(0.60f, 0.66f, 0.74f)

    val min = -1f
    val max = 1f
    val top = 1.24f
    val bottom = 0f

    fun boxLoop(y: Float) {
        glBegin(GL_LINE_LOOP)

        glVertex3f(min, y, min)
        glVertex3f(max, y, min)
        glVertex3f(max, y, max)
        glVertex3f(min, y, max)

        glEnd()
    }

    boxLoop(bottom)
    boxLoop(top)

    glBegin(GL_LINES)

    listOf(
        min to min,
        min to max,
        max to min,
        max to max
    ).forEach { (x, z) ->
        glVertex3f(x, bottom, z)
        glVertex3f(x, top, z)
    }

    glEnd()
}

private fun drawGrid() {
    val min = -1f
    val max = 1f
    val top = 1.24f
    val bottom = 0f
    val steps = 5

    glLineWidth(0.62f)
    glColor3f(0.82f, 0.86f, 0.91f)

    glBegin(GL_LINES)

    for (index in 1 until steps) {
        val t = min + (max - min) * index / steps
        val y = bottom + (top - bottom) * index / steps

        glVertex3f(t, bottom, min)
        glVertex3f(t, bottom, max)

        glVertex3f(min, bottom, t)
        glVertex3f(max, bottom, t)

        glVertex3f(min, y, min)
        glVertex3f(max, y, min)

        glVertex3f(min, y, min)
        glVertex3f(min, y, max)

        glVertex3f(max, y, min)
        glVertex3f(max, y, max)
    }

    glEnd()
}

private fun drawAxes() {
    glLineWidth(2.2f)

    glBegin(GL_LINES)

    glColor3f(0.86f, 0.15f, 0.15f)
    glVertex3f(-1.12f, 0f, 1.12f)
    glVertex3f(1.18f, 0f, 1.12f)

    glColor3f(0.15f, 0.39f, 0.92f)
    glVertex3f(-1.12f, 0f, 1.12f)
    glVertex3f(-1.12f, 0f, -1.18f)

    glColor3f(0.03f, 0.59f, 0.41f)
    glVertex3f(-1.12f, 0f, 1.12f)
    glVertex3f(-1.12f, 1.30f, 1.12f)

    glEnd()
}

private fun drawPeak(
    mesh: SurfaceMesh
) {
    val peak = mesh.points
        .asSequence()
        .flatten()
        .maxByOrNull { point ->
            point.power
        } ?: return

    val coords = pointCoords(peak)

    glPointSize(8f)
    glColor3f(0.96f, 0.25f, 0.37f)

    glBegin(GL_POINTS)

    glVertex3f(
        coords[0],
        coords[1] + 0.035f,
        coords[2]
    )

    glEnd()
}

private fun emitVertex(
    point: SurfacePoint,
    ratio: Float
) {
    val color = surfaceColor(ratio)

    glColor3f(
        color[0],
        color[1],
        color[2]
    )

    vertex(point)
}

private fun vertex(
    point: SurfacePoint,
    flattened: Boolean = false
) {
    val coords = pointCoords(point, flattened)

    glVertex3f(
        coords[0],
        coords[1],
        coords[2]
    )
}

private fun floorVertex(
    point: SurfacePoint
) {
    glVertex3f(
        point.x.toFloat(),
        0.004f,
        point.y.toFloat()
    )
}

private fun pointCoords(
    point: SurfacePoint,
    flattened: Boolean = false
): FloatArray {
    return floatArrayOf(
        point.x.toFloat(),
        if (flattened) {
            0.012f
        } else {
            (point.z * 1.24).toFloat()
        },
        point.y.toFloat()
    )
}

private fun floorColor(
    ratio: Float
): FloatArray {
    val base = surfaceColor(ratio)
    val mix = 0.34f

    return floatArrayOf(
        base[0] * mix + 0.962f * (1f - mix),
        base[1] * mix + 0.972f * (1f - mix),
        base[2] * mix + 0.986f * (1f - mix)
    )
}

private fun powerRatio(
    point: SurfacePoint,
    minPower: Double,
    powerSpan: Double
): Float {
    return ((point.power - minPower) / powerSpan)
        .toFloat()
        .coerceIn(0f, 1f)
}

private fun surfaceColor(
    ratio: Float
): FloatArray {
    val safeRatio = ratio.coerceIn(0f, 1f)

    val stops = listOf(
        0.00f to floatArrayOf(0.18f, 0.24f, 0.92f),
        0.24f to floatArrayOf(0.10f, 0.54f, 0.94f),
        0.46f to floatArrayOf(0.10f, 0.78f, 0.68f),
        0.68f to floatArrayOf(0.26f, 0.78f, 0.26f),
        0.84f to floatArrayOf(0.92f, 0.82f, 0.18f),
        1.00f to floatArrayOf(1.00f, 0.92f, 0.18f)
    )

    val rightIndex = stops
        .indexOfFirst { safeRatio <= it.first }
        .takeIf { it > 0 }
        ?: stops.lastIndex

    val left = stops[rightIndex - 1]
    val right = stops[rightIndex]

    val span = right.first - left.first

    val local = if (span.absoluteValue <= 1e-6f) {
        0f
    } else {
        ((safeRatio - left.first) / span).coerceIn(0f, 1f)
    }

    return floatArrayOf(
        left.second[0] + (right.second[0] - left.second[0]) * local,
        left.second[1] + (right.second[1] - left.second[1]) * local,
        left.second[2] + (right.second[2] - left.second[2]) * local
    )
}

private fun surfaceAwtColor(
    ratio: Float
): Color {
    val color = surfaceColor(ratio)

    return Color(
        (color[0] * 255f).toInt().coerceIn(0, 255),
        (color[1] * 255f).toInt().coerceIn(0, 255),
        (color[2] * 255f).toInt().coerceIn(0, 255)
    )
}

private inline fun runOnEdt(
    crossinline block: () -> Unit
) {
    if (EventQueue.isDispatchThread()) {
        block()
    } else {
        EventQueue.invokeLater {
            block()
        }
    }
}
