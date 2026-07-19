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
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.CornerRadii
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
 *
 * Compose 更新时：
 * - 相同的 SurfaceMesh 引用直接跳过；
 * - 数据变化时只替换 TriangleMesh；
 * - MeshView、材质、调色板纹理、相机、灯光和交互器均长期复用。
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
    private val frameGroup = Group()
    private val dataGroup = Group()
    private val world = Group()
    private val camera = PerspectiveCamera(true)
    private val statusLabel = Label(
        "NO JAVAFX SURFACE DATA\nAt least three samples spanning X, Y and power are required."
    )
    private val colorBar = Canvas(COLOR_BAR_WIDTH, COLOR_BAR_HEIGHT)

    private val paletteTexture = createPaletteTexture()
    private val surfaceMaterial = PhongMaterial().apply {
        diffuseMap = paletteTexture
        specularColor = FxColor.color(0.70, 0.82, 0.96, 0.48)
        specularPower = 42.0
    }
    private val wireframeMaterial = PhongMaterial(spec.wireframeColor.toFxColor())
    private val floorMaterial = PhongMaterial(spec.gridColor.copy(alpha = 0.44f).toFxColor())
    private val peakMaterial = PhongMaterial(spec.peakColor.toFxColor()).apply {
        specularColor = FxColor.WHITE
        specularPower = 64.0
    }

    private val surfaceView = MeshView().apply {
        material = surfaceMaterial
        cullFace = CullFace.NONE
        drawMode = DrawMode.FILL
    }
    private val wireframeView = MeshView().apply {
        material = wireframeMaterial
        cullFace = CullFace.NONE
        drawMode = DrawMode.LINE
    }
    private val floorProjectionView = MeshView().apply {
        material = floorMaterial
        cullFace = CullFace.NONE
        drawMode = DrawMode.LINE
    }
    private val peakMarker = Sphere(7.0).apply {
        material = peakMaterial
        isVisible = false
    }

    private val subScene: SubScene
    private val root: StackPane

    private var dragStartX = 0.0
    private var dragStartY = 0.0
    private var dragStartElevation = elevationRotate.angle
    private var dragStartAzimuth = azimuthRotate.angle
    private var renderedMesh: SurfaceMesh? = null
    private var meshInitialized = false

    val scene: Scene

    init {
        dataGroup.children.setAll(
            floorProjectionView,
            surfaceView,
            wireframeView,
            peakMarker
        )
        updateFrame(aspect = 1f)

        world.children.setAll(
            frameGroup,
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
            background = Background(
                BackgroundFill(
                    spec.plotAreaColor.toFxColor(),
                    CornerRadii.EMPTY,
                    Insets.EMPTY
                )
            )
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
        if (meshInitialized && renderedMesh === mesh) return

        meshInitialized = true
        renderedMesh = mesh

        val hasData = mesh != null
        statusLabel.isVisible = !hasData
        statusLabel.isManaged = !hasData
        colorBar.isVisible = hasData
        surfaceView.isVisible = hasData
        wireframeView.isVisible = hasData
        floorProjectionView.isVisible = hasData
        peakMarker.isVisible = hasData

        if (mesh == null) {
            surfaceView.mesh = null
            wireframeView.mesh = null
            floorProjectionView.mesh = null
            drawColorBar(null)
            updateFrame(aspect = 1f)
            return
        }

        val aspect = mesh.spatialAspectRatio()
        val triangleMesh = createJavaFxTriangleMesh(mesh, aspect = aspect)

        surfaceView.mesh = triangleMesh
        wireframeView.mesh = triangleMesh
        floorProjectionView.mesh = createJavaFxTriangleMesh(
            mesh = mesh,
            aspect = aspect,
            flattened = true
        )
        updatePeakMarker(mesh, aspect)
        updateFrame(aspect)
        drawColorBar(mesh)
    }

    private fun updatePeakMarker(mesh: SurfaceMesh, aspect: Float) {
        val peak = mesh.points.asSequence().flatten().maxBy { it.power }
        val point = peak.toJavaFxPoint(aspect = aspect, flattened = false)
        peakMarker.translateX = point[0].toDouble()
        peakMarker.translateY = point[1].toDouble()
        peakMarker.translateZ = point[2].toDouble()
    }

    private fun updateFrame(aspect: Float) {
        frameGroup.children.setAll(
            createPlotBox(spec, aspect),
            createFloorGrid(spec, aspect),
            createAxes(spec, aspect)
        )
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
    aspect: Float,
    flattened: Boolean = false
): TriangleMesh {
    val triangleMesh = TriangleMesh()
    val rows = mesh.points
    val rowSize = mesh.columnCount

    rows.forEach { row ->
        row.forEach { point ->
            val world = point.toJavaFxPoint(aspect, flattened)
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

private fun SurfacePoint.toJavaFxPoint(
    aspect: Float,
    flattened: Boolean
): FloatArray = floatArrayOf(
    (x * PLOT_HALF).toFloat(),
    if (flattened) FLOOR_LIFT.toFloat() else (-z * POWER_SIZE).toFloat(),
    (y * PLOT_HALF * aspect).toFloat()
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

private fun createPlotBox(spec: SurfaceRenderSpec, aspect: Float): Group {
    val depth = PLOT_SIZE * aspect
    val frame = Box(PLOT_SIZE, POWER_SIZE, depth).apply {
        translateY = -POWER_SIZE / 2.0
        material = PhongMaterial(spec.gridColor.copy(alpha = 0.50f).toFxColor())
        drawMode = DrawMode.LINE
        cullFace = CullFace.NONE
    }
    return Group(frame)
}

private fun createFloorGrid(spec: SurfaceRenderSpec, aspect: Float): Group {
    val grid = Group()
    val depth = PLOT_SIZE * aspect
    val halfDepth = depth / 2.0
    val divisions = (spec.axisTickCount - 1).coerceAtLeast(1)
    val color = spec.gridColor.copy(alpha = 0.36f).toFxColor()

    for (index in 0..divisions) {
        val fraction = index.toDouble() / divisions.toDouble()
        val x = -PLOT_HALF + PLOT_SIZE * fraction
        val z = -halfDepth + depth * fraction

        grid.children += axisBar(
            length = depth,
            thickness = GRID_LINE_THICKNESS,
            color = color,
            x = x,
            y = FLOOR_LIFT,
            z = 0.0,
            rotateY = 90.0
        )
        grid.children += axisBar(
            length = PLOT_SIZE,
            thickness = GRID_LINE_THICKNESS,
            color = color,
            x = 0.0,
            y = FLOOR_LIFT,
            z = z
        )
    }
    return grid
}

private fun createAxes(spec: SurfaceRenderSpec, aspect: Float): Group {
    val depth = PLOT_SIZE * aspect
    val halfDepth = depth / 2.0
    val originX = -PLOT_HALF - 20.0
    val originY = 0.0
    val originZ = halfDepth + 20.0
    val axisColor = spec.axisColor.toFxColor()

    return Group().apply {
        children += axisBar(
            length = PLOT_SIZE + 54.0,
            thickness = AXIS_THICKNESS,
            color = axisColor,
            x = originX + (PLOT_SIZE + 54.0) / 2.0,
            y = originY,
            z = originZ
        )
        children += axisBar(
            length = depth + 54.0,
            thickness = AXIS_THICKNESS,
            color = axisColor,
            x = originX,
            y = originY,
            z = originZ - (depth + 54.0) / 2.0,
            rotateY = 90.0
        )
        children += axisBar(
            length = POWER_SIZE + 54.0,
            thickness = AXIS_THICKNESS,
            color = axisColor,
            x = originX,
            y = originY - (POWER_SIZE + 54.0) / 2.0,
            z = originZ,
            rotateZ = 90.0
        )
        children += axisLabel(
            spec.xAxisLabel,
            spec.textColor.toFxColor(),
            PLOT_HALF + 48.0,
            16.0,
            originZ
        )
        children += axisLabel(
            spec.yAxisLabel,
            spec.textColor.toFxColor(),
            originX,
            16.0,
            -halfDepth - 48.0
        )
        children += axisLabel(
            spec.zAxisLabel,
            spec.textColor.toFxColor(),
            originX - 70.0,
            -POWER_SIZE - 48.0,
            originZ
        )
    }
}

private fun axisBar(
    length: Double,
    thickness: Double,
    color: FxColor,
    x: Double,
    y: Double,
    z: Double,
    rotateY: Double = 0.0,
    rotateZ: Double = 0.0
): Box = Box(length, thickness, thickness).apply {
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
private const val AXIS_THICKNESS = 3.2
private const val GRID_LINE_THICKNESS = 0.72
