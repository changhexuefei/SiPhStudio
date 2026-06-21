package org.jason.siph.ui.coupling

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.AmbientLight
import javafx.scene.canvas.Canvas
import javafx.scene.Group
import javafx.scene.PerspectiveCamera
import javafx.scene.PointLight
import javafx.scene.Scene
import javafx.scene.SceneAntialiasing
import javafx.scene.SubScene
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.paint.PhongMaterial
import javafx.scene.shape.Box
import javafx.scene.shape.CullFace
import javafx.scene.shape.DrawMode
import javafx.scene.shape.MeshView
import javafx.scene.shape.TriangleMesh
import javafx.scene.text.Font
import javafx.scene.text.Text as FxText
import javafx.scene.transform.Rotate
import javafx.geometry.Insets
import javafx.geometry.Pos
import kotlin.math.absoluteValue

@Composable
internal actual fun JavaFxPowerSurface3d(
    mesh: SurfaceMesh?,
    modifier: Modifier
) {
    if (mesh == null) {
        JavaFxSurfacePlaceholder(modifier)
        return
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            JFXPanel().also { panel ->
                Platform.setImplicitExit(false)
                Platform.runLater {
                    panel.scene = createPowerSurfaceScene(mesh)
                }
            }
        },
        update = { panel ->
            Platform.runLater {
                panel.scene = createPowerSurfaceScene(mesh)
            }
        }
    )
}

@Composable
private fun JavaFxSurfacePlaceholder(
    modifier: Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
                shape = MaterialTheme.shapes.small
            ),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "JavaFX 3D",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Need at least three non-collinear samples to build a surface.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun createPowerSurfaceScene(
    mesh: SurfaceMesh
): Scene {
    val xRotate = Rotate(-62.0, Rotate.X_AXIS)
    val yRotate = Rotate(-32.0, Rotate.Y_AXIS)

    val world = Group().apply {
        children += createPlotBox()
        children += createContourProjection(mesh)
        children += createSurfaceGroup(mesh)
        children += createWireframe(mesh)
        children += createPeakMarker(mesh)
        children += AmbientLight(Color.rgb(255, 255, 255, 0.92))
        children += PointLight(Color.WHITE).apply {
            translateX = -180.0
            translateY = -260.0
            translateZ = -320.0
        }
        translateY = 92.0
        transforms += xRotate
        transforms += yRotate
    }

    val camera = PerspectiveCamera(true).apply {
        translateZ = -720.0
        nearClip = 0.1
        farClip = 2000.0
        fieldOfView = 34.0
    }
    var dragStartX = 0.0
    var dragStartY = 0.0
    var dragStartXAngle = xRotate.angle
    var dragStartYAngle = yRotate.angle

    val subScene = SubScene(world, 900.0, 430.0, true, SceneAntialiasing.BALANCED).apply {
        fill = Color.rgb(248, 250, 252)
        this.camera = camera
        setOnMousePressed { event ->
            dragStartX = event.sceneX
            dragStartY = event.sceneY
            dragStartXAngle = xRotate.angle
            dragStartYAngle = yRotate.angle
        }
        setOnMouseDragged { event ->
            yRotate.angle = dragStartYAngle + (event.sceneX - dragStartX) * 0.42
            xRotate.angle = (dragStartXAngle - (event.sceneY - dragStartY) * 0.42)
                .coerceIn(-88.0, -18.0)
        }
        setOnScroll { event ->
            camera.translateZ = (camera.translateZ + event.deltaY * 0.55)
                .coerceIn(-1050.0, -420.0)
        }
    }
    val container = StackPane(subScene, createColorBar(mesh)).apply {
        padding = Insets(8.0, 18.0, 8.0, 8.0)
    }
    StackPane.setAlignment(subScene, Pos.CENTER)
    StackPane.setAlignment(container.children[1], Pos.CENTER_RIGHT)
    subScene.widthProperty().bind(container.widthProperty())
    subScene.heightProperty().bind(container.heightProperty())

    return Scene(container, 900.0, 430.0, Color.rgb(248, 250, 252))
}

private fun createPlotBox(): Group {
    val frame = Box(PLOT_SIZE, POWER_SIZE, PLOT_SIZE).apply {
        translateY = -POWER_SIZE / 2.0
        material = PhongMaterial(Color.rgb(148, 163, 184, 0.56))
        drawMode = DrawMode.LINE
        cullFace = CullFace.NONE
    }

    return Group().apply {
        children += frame
        children += axisLabel("X", Color.rgb(71, 85, 105), PLOT_SIZE / 2.0 + 28.0, 16.0, PLOT_SIZE / 2.0)
        children += axisLabel("Y", Color.rgb(71, 85, 105), -PLOT_SIZE / 2.0 - 28.0, 16.0, -PLOT_SIZE / 2.0)
        children += axisLabel("Power", Color.rgb(71, 85, 105), -PLOT_SIZE / 2.0 - 56.0, -POWER_SIZE - 10.0, PLOT_SIZE / 2.0)
    }
}

private fun createSurfaceGroup(
    mesh: SurfaceMesh
): Group {
    val group = Group()
    val rows = mesh.points
    val powerSpan = (mesh.maxPower - mesh.minPower).takeIf { it.absoluteValue > 1e-9 } ?: 1.0

    for (yIndex in 0 until rows.lastIndex) {
        for (xIndex in 0 until rows[yIndex].lastIndex) {
            val p0 = rows[yIndex][xIndex]
            val p1 = rows[yIndex][xIndex + 1]
            val p2 = rows[yIndex + 1][xIndex + 1]
            val p3 = rows[yIndex + 1][xIndex]
            val powerRatio = ((listOf(p0, p1, p2, p3).map { it.power }.average() - mesh.minPower) / powerSpan)
                .coerceIn(0.0, 1.0)

            group.children += MeshView(createQuadMesh(p0, p1, p2, p3)).apply {
                material = PhongMaterial(surfaceColor(powerRatio))
                cullFace = CullFace.NONE
                drawMode = DrawMode.FILL
            }
        }
    }

    return group
}

private fun createContourProjection(
    mesh: SurfaceMesh
): Group {
    return Group(
        MeshView(createFlattenedSurfaceMesh(mesh)).apply {
            material = PhongMaterial(Color.rgb(220, 38, 38, 0.86))
            cullFace = CullFace.NONE
            drawMode = DrawMode.LINE
        },
        MeshView(createFlattenedSurfaceMesh(mesh, sampleEvery = 2)).apply {
            material = PhongMaterial(Color.rgb(37, 99, 235, 0.72))
            cullFace = CullFace.NONE
            drawMode = DrawMode.LINE
        }
    )
}

private fun createWireframe(
    mesh: SurfaceMesh
): MeshView {
    val triangleMesh = TriangleMesh()
    val rows = mesh.points

    rows.forEach { row ->
        row.forEach { point ->
            triangleMesh.points.addAll(*point.toJavaFxPoint())
        }
    }
    triangleMesh.texCoords.addAll(0f, 0f)

    val rowSize = rows.firstOrNull()?.size ?: 0
    for (yIndex in 0 until rows.lastIndex) {
        for (xIndex in 0 until rowSize - 1) {
            val p0 = yIndex * rowSize + xIndex
            val p1 = p0 + 1
            val p2 = p0 + rowSize + 1
            val p3 = p0 + rowSize
            triangleMesh.faces.addAll(p0, 0, p1, 0, p2, 0, p0, 0, p2, 0, p3, 0)
        }
    }

    return MeshView(triangleMesh).apply {
        material = PhongMaterial(Color.rgb(30, 41, 59, 0.78))
        cullFace = CullFace.NONE
        drawMode = DrawMode.LINE
    }
}

private fun createFlattenedSurfaceMesh(
    mesh: SurfaceMesh,
    sampleEvery: Int = 1
): TriangleMesh {
    val triangleMesh = TriangleMesh()
    val rows = mesh.points

    rows.forEachIndexed { yIndex, row ->
        row.forEachIndexed { xIndex, point ->
            val shouldLift = xIndex % sampleEvery == 0 || yIndex % sampleEvery == 0
            triangleMesh.points.addAll(*point.toJavaFxPoint(flattened = true, lift = shouldLift))
        }
    }
    triangleMesh.texCoords.addAll(0f, 0f)

    val rowSize = rows.firstOrNull()?.size ?: 0
    for (yIndex in 0 until rows.lastIndex) {
        for (xIndex in 0 until rowSize - 1) {
            val p0 = yIndex * rowSize + xIndex
            val p1 = p0 + 1
            val p2 = p0 + rowSize + 1
            val p3 = p0 + rowSize
            triangleMesh.faces.addAll(p0, 0, p1, 0, p2, 0, p0, 0, p2, 0, p3, 0)
        }
    }

    return triangleMesh
}

private fun createPeakMarker(
    mesh: SurfaceMesh
): Box {
    val peak = mesh.points.flatten().maxBy { it.power }
    val point = peak.toJavaFxPoint()

    return Box(9.0, 9.0, 9.0).apply {
        translateX = point[0].toDouble()
        translateY = point[1].toDouble() - 10.0
        translateZ = point[2].toDouble()
        material = PhongMaterial(Color.rgb(244, 63, 94))
    }
}

private fun createQuadMesh(
    p0: SurfacePoint,
    p1: SurfacePoint,
    p2: SurfacePoint,
    p3: SurfacePoint
): TriangleMesh {
    return TriangleMesh().apply {
        points.addAll(
            *p0.toJavaFxPoint(),
            *p1.toJavaFxPoint(),
            *p2.toJavaFxPoint(),
            *p3.toJavaFxPoint()
        )
        texCoords.addAll(0f, 0f)
        faces.addAll(0, 0, 1, 0, 2, 0, 0, 0, 2, 0, 3, 0)
    }
}

private fun SurfacePoint.toJavaFxPoint(
    flattened: Boolean = false,
    lift: Boolean = true
): FloatArray {
    val projectedZ = if (flattened) {
        if (lift) -8.0 else 0.0
    } else {
        -z * POWER_SIZE
    }

    return floatArrayOf(
        (x * PLOT_HALF).toFloat(),
        projectedZ.toFloat(),
        (y * PLOT_HALF).toFloat()
    )
}

private fun surfaceColor(
    ratio: Double
): Color {
    val stops = listOf(
        0.00 to Color.rgb(37, 99, 235),
        0.28 to Color.rgb(45, 212, 191),
        0.52 to Color.rgb(34, 197, 94),
        0.74 to Color.rgb(250, 204, 21),
        1.00 to Color.rgb(239, 68, 68)
    )
    val rightIndex = stops.indexOfFirst { ratio <= it.first }.takeIf { it > 0 } ?: stops.lastIndex
    val left = stops[rightIndex - 1]
    val right = stops[rightIndex]
    val local = ((ratio - left.first) / (right.first - left.first)).coerceIn(0.0, 1.0)

    return lerpColor(left.second, right.second, local)
}

private fun axisLabel(
    text: String,
    color: Color,
    x: Double,
    y: Double,
    z: Double
): FxText {
    return FxText(text).apply {
        translateX = x
        translateY = y
        translateZ = z
        fill = color
        font = Font.font("Arial", 18.0)
        transforms += Rotate(180.0, Rotate.Y_AXIS)
    }
}

private fun createColorBar(
    mesh: SurfaceMesh
): Canvas {
    val canvas = Canvas(62.0, 288.0)
    val graphics = canvas.graphicsContext2D
    val barX = 8.0
    val barY = 16.0
    val barWidth = 18.0
    val barHeight = 236.0

    for (i in 0..235) {
        val ratio = 1.0 - i / 235.0
        graphics.stroke = surfaceColor(ratio)
        graphics.strokeLine(barX, barY + i, barX + barWidth, barY + i)
    }

    graphics.stroke = Color.rgb(71, 85, 105)
    graphics.strokeRect(barX, barY, barWidth, barHeight)
    graphics.fill = Color.rgb(51, 65, 85)
    graphics.font = Font.font("Arial", 11.0)

    val labels = listOf(
        1.0 to mesh.maxPower,
        0.75 to mesh.minPower + (mesh.maxPower - mesh.minPower) * 0.75,
        0.50 to mesh.minPower + (mesh.maxPower - mesh.minPower) * 0.50,
        0.25 to mesh.minPower + (mesh.maxPower - mesh.minPower) * 0.25,
        0.0 to mesh.minPower
    )

    labels.forEach { (ratio, value) ->
        val y = barY + (1.0 - ratio) * barHeight
        graphics.strokeLine(barX + barWidth, y, barX + barWidth + 5.0, y)
        graphics.fillText(round1(value).toString(), barX + barWidth + 8.0, y + 4.0)
    }

    return canvas
}

private fun round1(
    value: Double
): Double {
    return kotlin.math.round(value * 10.0) / 10.0
}

private fun lerpColor(
    start: Color,
    end: Color,
    fraction: Double
): Color {
    val t = fraction.coerceIn(0.0, 1.0)

    return Color.color(
        start.red + (end.red - start.red) * t,
        start.green + (end.green - start.green) * t,
        start.blue + (end.blue - start.blue) * t,
        0.96
    )
}

private const val PLOT_HALF = 205.0
private const val PLOT_SIZE = PLOT_HALF * 2.0
private const val POWER_SIZE = 245.0
