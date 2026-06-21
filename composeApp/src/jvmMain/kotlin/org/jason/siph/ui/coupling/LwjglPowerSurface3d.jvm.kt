package org.jason.siph.ui.coupling


import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
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
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlin.math.absoluteValue

@Composable
internal actual fun LwjglPowerSurface3d(
    mesh: SurfaceMesh?,
    modifier: Modifier
) {
    /*
     * 关键点：
     *
     * 不要在 mesh == null 时 return Compose Placeholder。
     * 否则 Compose 会把 SwingPanel / AWTGLCanvas 从树上移除，
     * AWTGLCanvas.removeNotify() 会触发 lwjgl3-awt 的 dispose，
     * 在 Windows + Hot Reload / Live Edit 下容易出现：
     *
     * JAWTDrawingSurface ds is null
     */
    SwingPanel(
        modifier = modifier,
        factory = {
            LwjglSurfacePanel()
        },
        update = { panel ->
            panel.setMesh(mesh)
        }
    )
}

/**
 * Swing 侧的稳定容器。
 *
 * Compose 只持有这个 JPanel。
 * mesh 为空时，只在 Swing 内部切换到 placeholder，
 * 不销毁 AWTGLCanvas。
 */
private class LwjglSurfacePanel : JPanel(BorderLayout()) {

    private val cardLayout = CardLayout()

    private val canvas = LwjglSurfaceCanvas()

    private val placeholder = createPlaceholder()

    private val cardPanel = JPanel(cardLayout).apply {
        add(placeholder, CARD_PLACEHOLDER)
        add(canvas, CARD_CANVAS)
    }

    init {
        minimumSize = Dimension(64, 64)
        preferredSize = Dimension(720, 420)
        border = BorderFactory.createLineBorder(Color(210, 218, 230), 1)
        add(cardPanel, BorderLayout.CENTER)

        cardLayout.show(cardPanel, CARD_PLACEHOLDER)
    }

    fun setMesh(mesh: SurfaceMesh?) {
        runOnEdt {
            if (mesh == null) {
                canvas.setMesh(null)
                cardLayout.show(cardPanel, CARD_PLACEHOLDER)
            } else {
                cardLayout.show(cardPanel, CARD_CANVAS)
                canvas.setMesh(mesh)
            }

            revalidate()
            repaint()
        }
    }

    companion object {
        private const val CARD_PLACEHOLDER = "placeholder"
        private const val CARD_CANVAS = "canvas"

        private fun createPlaceholder(): JLabel {
            return JLabel(
                """
                <html>
                    <div style='text-align:center; padding: 16px;'>
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
 * 注意：
 * 1. 允许 mesh 为 null。
 * 2. 不在 paintGL 里手动重复 initGL。
 * 3. repaint 前判断 displayable / size。
 * 4. removeNotify 里兜底处理 lwjgl3-awt 在 Windows 下的 dispose NPE。
 */
private class LwjglSurfaceCanvas : AWTGLCanvas(
    GLData().apply {
        /*
         * 你当前使用的是固定管线：
         * glBegin / glMatrixMode / glFrustum / glShadeModel
         *
         * 所以必须用兼容模式。
         *
         * 如果客户工控机显卡比较旧，可以考虑改成：
         * majorVersion = 2
         * minorVersion = 1
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

    private var yaw = -34f
    private var pitch = 58f
    private var zoom = 1.0f

    private var lastX = 0
    private var lastY = 0

    init {
        minimumSize = Dimension(64, 64)
        preferredSize = Dimension(720, 420)

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (disposed) return

                lastX = event.x
                lastY = event.y
            }
        })

        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                if (disposed) return

                val dx = event.x - lastX
                val dy = event.y - lastY

                yaw += dx * 0.45f
                pitch = (pitch + dy * 0.45f).coerceIn(18f, 86f)

                lastX = event.x
                lastY = event.y

                repaintSafely()
            }
        })

        addMouseWheelListener(MouseWheelListener { event: MouseWheelEvent ->
            if (disposed) return@MouseWheelListener

            zoom = (zoom - event.preciseWheelRotation.toFloat() * 0.08f)
                .coerceIn(0.62f, 1.85f)

            repaintSafely()
        })
    }

    fun setMesh(nextMesh: SurfaceMesh?) {
        if (disposed) return

        mesh = nextMesh
        repaintSafely()
    }

    override fun initGL() {
        if (disposed || initialized) return

        GL.createCapabilities()

        initialized = true

        glClearColor(0.972f, 0.980f, 0.988f, 1f)
        glEnable(GL_DEPTH_TEST)
        glDepthFunc(GL_LEQUAL)
        glShadeModel(GL_SMOOTH)
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

        val viewportWidth = width.coerceAtLeast(1)
        val viewportHeight = height.coerceAtLeast(1)

        glViewport(0, 0, viewportWidth, viewportHeight)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        val currentMesh = mesh
        if (currentMesh == null || currentMesh.points.isEmpty()) {
            swapBuffers()
            return
        }

        setupProjection(viewportWidth, viewportHeight)

        glMatrixMode(GL_MODELVIEW)
        glLoadIdentity()

        glTranslatef(0f, -0.18f, -3.35f)
        glScalef(zoom, zoom, zoom)
        glRotatef(pitch, 1f, 0f, 0f)
        glRotatef(yaw, 0f, 1f, 0f)

        drawPlotBox()
        drawProjection(currentMesh)
        drawSurface(currentMesh)
        drawWireframe(currentMesh)
        drawAxes()
        drawPeak(currentMesh)

        swapBuffers()
    }

    override fun removeNotify() {
        disposed = true
        initialized = false

        try {
            super.removeNotify()
        } catch (e: NullPointerException) {
            /*
             * lwjgl3-awt 在 Windows + Compose SwingPanel + Hot Reload/Live Edit
             * 场景下，dispose 时 JAWTDrawingSurface 可能为 null。
             *
             * 这里兜底吞掉，避免页面切换 / 热重载 / 关闭窗口时崩溃。
             */
            System.err.println(
                "Ignored LWJGL AWTGLCanvas dispose NPE: ${e.message}"
            )
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

    glLineWidth(1.05f)
    glColor3f(0.16f, 0.20f, 0.27f)

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

    glLineWidth(1.5f)

    rows.forEachIndexed { index, row ->
        if (row.isEmpty()) return@forEachIndexed

        if (index % 2 == 0 || index == rows.lastIndex) {
            glColor3f(0.86f, 0.15f, 0.15f)

            glBegin(GL_LINE_STRIP)
            row.forEach { point ->
                vertex(point, flattened = true)
            }
            glEnd()
        }
    }

    val maxRowSize = rows.maxOfOrNull { it.size } ?: return

    glColor3f(0.15f, 0.39f, 0.92f)

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

private fun drawPlotBox() {
    glLineWidth(1.2f)
    glColor3f(0.58f, 0.64f, 0.72f)

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

private fun drawAxes() {
    glLineWidth(3f)

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
        0.00f to floatArrayOf(0.15f, 0.39f, 0.92f),
        0.28f to floatArrayOf(0.18f, 0.83f, 0.75f),
        0.52f to floatArrayOf(0.13f, 0.77f, 0.37f),
        0.74f to floatArrayOf(0.98f, 0.80f, 0.08f),
        1.00f to floatArrayOf(0.94f, 0.27f, 0.27f)
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