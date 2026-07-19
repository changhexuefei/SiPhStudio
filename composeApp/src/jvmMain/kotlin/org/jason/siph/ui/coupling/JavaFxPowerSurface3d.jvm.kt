package org.jason.siph.ui.coupling

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color as ComposeColor
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.AmbientLight
import javafx.scene.Group
import javafx.scene.PerspectiveCamera
import javafx.scene.PointLight
import javafx.scene.Scene
import javafx.scene.SceneAntialiasing
import javafx.scene.SubScene
import javafx.scene.canvas.Canvas
import javafx.scene.control.Label
import javafx.scene.image.WritableImage
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color as FxColor
import javafx.scene.paint.PhongMaterial
import javafx.scene.shape.Box
import javafx.scene.shape.CullFace
import javafx.scene.shape.DrawMode
import javafx.scene.shape.MeshView
import javafx.scene.shape.Sphere
import javafx.scene.shape.TriangleMesh
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.Text
import javafx.scene.transform.Rotate
import kotlin.math.roundToInt

/**
 * JavaFX 后端保持一个长期存活的 Scene/SubScene/Camera。
 * Compose 更新只替换 TriangleMesh 数据节点，不再重建整棵 JavaFX Scene Graph。
 */
@Composable
internal actual fun JavaFxPowerSurface3d(
    mesh: SurfaceMesh?,
    modifier: Modifier
) {
    SwingPanel(
        modifier = modifier,
        factory = { JavaFxSurfaceHost(CouplingSurfaceRenderSpec) },
        update = { host -> host.updateMesh(mesh) }
    )
}

private class JavaFxSurfaceHost(
    private val spec: SurfaceRenderSpec
) : JFXPanel() {

    @Volatile
    private var renderer: PersistentJavaFxSurfaceRenderer? = null

    @Volatile
    private var pendingMesh: SurfaceMesh? = null

    init {
        Platform.setImplicitExit(false)
        Platform.runLater {
            val created = PersistentJavaFxSurfaceRenderer(spec)
            renderer = created
            scene = created.scene
            created.updateMesh(pendingMesh)
        }
    }

    fun updateMesh(mesh: SurfaceMesh?) {
        pendingMesh = mesh
        Platform.runLater {
            renderer?.updateMesh(pendingMesh)
        }
    }
}

private class PersistentJavaFxSurfaceRenderer(
    private val spec: SurfaceRenderSpec
) {

    private val elevationRotate = Rotate(initialElevationAngle(), Rotate.X_AXIS)
    private val azimuthRotate = Rotate(spec.initialAzimuthDegrees.toDouble(), Rotate.Y_AXIS)
    private val dataGroup = Group()
    private val world = Group()
    private val camera = PerspectiveCamera(true)
    private val statusLabel = Label(
        "NO JAVAFX SURFACE DATA\nAt least three samples spanning X, Y and power are required."
    )
    private val colorBar = Canvas(COLOR_BAR_WIDTH, COLOR_BAR_HEIGHT)
    private val subScene: SubScene
    private val root: StackPane

    private var dragStartX = 0.0
    private var dragStartY = 0.0
    private var dragStartElevation = elevationRotate.angle
    private var dragStartAzimuth = azimuthRotate.angle

    val scene: Scene

    init {
        world.children.setAll(
            createPlotBox(spec),
            createAxes(spec),
            dataGroup,
            AmbientLight(FxColor.color(0.72, 0.78, 0.86, 0.68)),
            PointLight(FxColor.color(0.82, 0.91, 1.0, 1.0)).apply {
                translateX = -230.0
                translateY = -300.0
                translateZ = -360.0
            },
            PointLight(FxColor.color(0.30, 0.58, 0.96, 1.0)).apply {
                translateX = 280.0
                translateY = -120.0
                translateZ = 220.0
            }
        )
        world.translateY = 92.0
        world.transforms.setAll(elevationRotate, azimuthRotate)

        camera.apply {
            translateZ = cameraTranslateZ(spec.initialDistance)
            nearClip = 0.1
            farClip = 2_500.0
            fieldOfView = spec.fieldOfViewDegrees.toDouble()
        }

        subScene = SubScene(
            world,
            DEFAULT_WIDTH,
            DEFAULT_HEIGHT,
            true,
            SceneAntialiasing.BALANCED
        ).apply {
            fill = spec.backgroundColor.toFxColor()
            camera = this@PersistentJavaFxSurfaceRenderer.camera
        }
        installCameraControls()

        statusLabel.apply {
            alignment = Pos.CENTER
            textFill = spec.textColor.toFxColor()
            font = Font.font("Monospaced", FontWeight.SEMI_BOLD, 12.0)
            style = "-fx-background-color: rgba(8,13,20,0.86);" +
                "-fx-border-color: rgba(117,188,255,0.35);" +
                "-fx-border-radius: 5; -fx-background-radius: 5;" +
                "-fx-padding: 16 20 16 20; -fx-text-alignment: center;"
        }
        colorBar.isMouseTransparent = true

        root = StackPane(subScene, statusLabel, colorBar).apply {
            padding = Insets(8.0, 16.0, 8.0, 8.0)
            style = "-fx-background-color: #080D14;"
        }
        StackPane.setAlignment(statusLabel, Pos.CENTER)
        StackPane.setAlignment(colorBar, Pos.CENTER_RIGHT)
        subScene.widthProperty().bind(root.widthProperty())
        subScene.heightProperty().bind(root.heightProperty())

        scene = Scene(
            root,
            DEFAULT_WIDTH,
            DEFAULT_HEIGHT,
            spec.backgroundColor.toFxColor()
        )
    }

    fun updateMesh(mesh: SurfaceMesh?) {
        check(Platform.isFxApplicationThread()) {
            "JavaFX surface updates must run on the JavaFX application thread"
        }

        dataGroup.children.clear()
        statusLabel.isVisible = mesh == null
        statusLabel.isManaged = mesh == null
        colorBar.isVisible = mesh != null

        if (mesh == null) {
            drawColorBar(null)
            return
        }

        val triangleMesh = createJavaFxTriangleMesh(mesh)
        val surfaceMaterial = PhongMaterial().apply {
            diffuseMap = createPaletteTexture()
            specularColor = FxColor.color(0.70, 0.82, 0.96, 0.48)
            specularPower = 42.0
        }
        val surface = MeshView(triangleMesh).apply {
            material = surfaceMaterial
            cullFace = CullFace.NONE
            drawMode = DrawMode.FILL
        }
        val wireframe = MeshView(triangleMesh).apply {
            material = PhongMaterial(spec.wireframeColor.toFxColor())
            cullFace = CullFace.NONE
            drawMode = DrawMode.LINE
        }
        val floorProjection = MeshView(createJavaFxTriangleMesh(mesh, flattened = true)).apply {
            material = PhongMaterial(spec.gridColor.copy(alpha = 0.44f).toFxColor())
            cullFace = CullFace.NONE
            drawMode = DrawMode.LINE
        }

        dataGroup.children.setAll(
            floorProjection,
            surface,
            wireframe,
            createPeakMarker(mesh, spec)
        )
        drawColorBar(mesh)
    }

    private fun installCameraControls() {
        subScene.setOnMousePressed { event ->
            dragStartX = event.sceneX
            dragStartY = event.sceneY
            dragStartElevation = elevationRotate.angle
            dragStartAzimuth = azimuthRotate.angle
        }
        subScene.setOnMouseDragged { event ->
            azimuthRotate.angle = dragStartAzimuth + (event.sceneX - dragStartX) * 0.42
            elevationRotate.angle = (dragStartElevation - (event.sceneY - dragStartY) * 0.42)
                .coerceIn(-82.0, -18.0)
        }
        subScene.setOnScroll { event ->
            camera.translateZ = (camera.translateZ + event.deltaY * 0.62)
                .coerceIn(cameraTranslateZ(6.5f), cameraTranslateZ(2.45f))
        }
        subScene.setOnMouseClicked { event ->
            if (event.clickCount >= 2) resetCamera()
        }
    }

    private fun resetCamera() {
        elevationRotate.angle = initialElevationAngle()
        azimuthRotate.angle = spec.initialAzimuthDegrees.toDouble()
        camera.translateZ = cameraTranslateZ(spec.initialDistance)
    }

    private fun initialElevationAngle(): Double =
        -(90.0 - spec.initialElevationDegrees.toDouble())

    private fun cameraTranslateZ(distance: Float): Double =
        -distance.toDouble() * CAMERA_DISTANCE_SCALE

    private fun drawColorBar(mesh: SurfaceMesh?) {
        val graphics = colorBar.graphicsContext2D
        graphics.clearRect(0.0, 0.0, colorBar.width, colorBar.height)
        if (mesh == null) return

        val barX = 10.0
        val barY = 24.0
        val barWidth = 15.0
        val barHeight = colorBar.height - 54.0
        val steps = barHeight.roundToInt().coerceAtLeast(1)

        for (index in 0..steps) {
            val ratio = 1f - index / steps.toFloat()
            graphics.stroke = AerospaceSurfaceColorScale.colorAt(ratio).toFxColor()
            graphics.strokeLine(
                barX,
                barY + index,
                barX + barWidth,
                barY + index
            )
        }
        graphics.stroke = spec.axisColor.toFxColor()
        graphics.strokeRect(barX, barY, barWidth, barHeight)
        graphics.fill = spec.textColor.toFxColor()
        graphics.font = Font.font("Monospaced", 10.0)
        graphics.fillText(formatPower(mesh.maxPower), barX + barWidth + 6.0, barY + 4.0)
        graphics.fillText(formatPower(mesh.minPower), barX + barWidth + 6.0, barY + barHeight)
        graphics.fillText("dBm", barX + 3.0, barY + barHeight + 18.0)
    }
}

private fun createJavaFxTriangleMesh(
    mesh: SurfaceMesh,
    flattened: Boolean = false
): TriangleMesh {
    val triangleMesh = TriangleMesh()
    val rows = mesh.points
    val rowSize = mesh.columnCount

    rows.forEach { row ->
        row.forEach { point ->
            val world = point.toJavaFxPoint(flattened)
            triangleMesh.points.addAll(world[0], world[1], world[2])
            val ratio = normalizePower(point.power, mesh.minPower, mesh.maxPower)
            triangleMesh.texCoords.addAll(ratio, 0.5f)
        }
    }

    for (yIndex in 0 until rows.lastIndex) {
        for (xIndex in 0 until rowSize - 1) {
            val p0 = yIndex * rowSize + xIndex
            val p1 = p0 + 1
            val p2 = p0 + rowSize + 1
            val p3 = p0 + rowSize
            triangleMesh.faces.addAll(
                p0, p0, p1, p1, p2, p2,
                p0, p0, p2, p2, p3, p3
            )
        }
    }
    return triangleMesh
}

private fun SurfacePoint.toJavaFxPoint(flattened: Boolean): FloatArray =
    floatArrayOf(
        (x * PLOT_HALF).toFloat(),
        if (flattened) FLOOR_LIFT.toFloat() else (-z * POWER_SIZE).toFloat(),
        (y * PLOT_HALF).toFloat()
    )

private fun createPaletteTexture(): WritableImage {
    val image = WritableImage(PALETTE_TEXTURE_WIDTH, 1)
    val writer = image.pixelWriter
    for (index in 0 until PALETTE_TEXTURE_WIDTH) {
        val ratio = index / (PALETTE_TEXTURE_WIDTH - 1).toFloat()
        writer.setColor(index, 0, AerospaceSurfaceColorScale.colorAt(ratio).toFxColor())
    }
    return image
}

private fun createPeakMarker(mesh: SurfaceMesh, spec: SurfaceRenderSpec): Sphere {
    val peak = mesh.points.asSequence().flatten().maxBy { it.power }
    val point = peak.toJavaFxPoint(flattened = false)
    return Sphere(7.0).apply {
        translateX = point[0].toDouble()
        translateY = point[1].toDouble()
        translateZ = point[2].toDouble()
        material = PhongMaterial(spec.peakColor.toFxColor()).apply {
            specularColor = FxColor.WHITE
            specularPower = 64.0
        }
    }
}

private fun createPlotBox(spec: SurfaceRenderSpec): Group {
    val frame = Box(PLOT_SIZE, POWER_SIZE, PLOT_SIZE).apply {
        translateY = -POWER_SIZE / 2.0
        material = PhongMaterial(spec.gridColor.copy(alpha = 0.50f).toFxColor())
        drawMode = DrawMode.LINE
        cullFace = CullFace.NONE
    }
    return Group(frame)
}

private fun createAxes(spec: SurfaceRenderSpec): Group {
    val originX = -PLOT_HALF - 20.0
    val originY = 0.0
    val originZ = PLOT_HALF + 20.0
    val axisColor = spec.axisColor.toFxColor()

    return Group().apply {
        children += axisBar(
            length = PLOT_SIZE + 54.0,
            color = axisColor,
            x = originX + (PLOT_SIZE + 54.0) / 2.0,
            y = originY,
            z = originZ
        )
        children += axisBar(
            length = PLOT_SIZE + 54.0,
            color = axisColor,
            x = originX,
            y = originY,
            z = originZ - (PLOT_SIZE + 54.0) / 2.0,
            rotateY = 90.0
        )
        children += axisBar(
            length = POWER_SIZE + 54.0,
            color = axisColor,
            x = originX,
            y = originY - (POWER_SIZE + 54.0) / 2.0,
            z = originZ,
            rotateZ = 90.0
        )
        children += axisLabel(spec.xAxisLabel, spec.textColor.toFxColor(), PLOT_HALF + 48.0, 16.0, originZ)
        children += axisLabel(spec.yAxisLabel, spec.textColor.toFxColor(), originX, 16.0, -PLOT_HALF - 48.0)
        children += axisLabel(spec.zAxisLabel, spec.textColor.toFxColor(), originX - 70.0, -POWER_SIZE - 48.0, originZ)
    }
}

private fun axisBar(
    length: Double,
    color: FxColor,
    x: Double,
    y: Double,
    z: Double,
    rotateY: Double = 0.0,
    rotateZ: Double = 0.0
): Box = Box(length, 3.2, 3.2).apply {
    translateX = x
    translateY = y
    translateZ = z
    material = PhongMaterial(color)
    cullFace = CullFace.NONE
    if (rotateY != 0.0) transforms += Rotate(rotateY, Rotate.Y_AXIS)
    if (rotateZ != 0.0) transforms += Rotate(rotateZ, Rotate.Z_AXIS)
}

private fun axisLabel(
    value: String,
    color: FxColor,
    x: Double,
    y: Double,
    z: Double
): Text = Text(value).apply {
    fill = color
    font = Font.font("Monospaced", FontWeight.SEMI_BOLD, 13.0)
    translateX = x
    translateY = y
    translateZ = z
}

private fun normalizePower(value: Double, min: Double, max: Double): Float {
    val span = max - min
    if (!span.isFinite() || kotlin.math.abs(span) < 1e-12) return 0.5f
    return ((value - min) / span).toFloat().coerceIn(0f, 1f)
}

private fun formatPower(value: Double): String =
    String.format(java.util.Locale.US, "%.2f", value)

private fun ComposeColor.toFxColor(): FxColor =
    FxColor.color(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())

private const val DEFAULT_WIDTH = 900.0
private const val DEFAULT_HEIGHT = 430.0
private const val COLOR_BAR_WIDTH = 86.0
private const val COLOR_BAR_HEIGHT = 330.0
private const val PLOT_SIZE = 330.0
private const val PLOT_HALF = PLOT_SIZE / 2.0
private const val POWER_SIZE = 215.0
private const val FLOOR_LIFT = 1.2
private const val CAMERA_DISTANCE_SCALE = 170.0
private const val PALETTE_TEXTURE_WIDTH = 256
