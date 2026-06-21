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
import javafx.scene.transform.Rotate
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
    val world = Group().apply {
        children += createSurfaceGroup(mesh)
        children += createWireframe(mesh)
        children += createPeakMarker(mesh)
        children += AmbientLight(Color.rgb(255, 255, 255, 0.92))
        children += PointLight(Color.WHITE).apply {
            translateX = -180.0
            translateY = -260.0
            translateZ = -320.0
        }
        translateY = 72.0
        transforms += Rotate(-62.0, Rotate.X_AXIS)
        transforms += Rotate(-32.0, Rotate.Y_AXIS)
    }

    val camera = PerspectiveCamera(true).apply {
        translateZ = -650.0
        nearClip = 0.1
        farClip = 2000.0
        fieldOfView = 36.0
    }

    val subScene = SubScene(world, 900.0, 430.0, true, SceneAntialiasing.BALANCED).apply {
        fill = Color.rgb(248, 250, 252)
        this.camera = camera
    }
    val container = StackPane(subScene)
    subScene.widthProperty().bind(container.widthProperty())
    subScene.heightProperty().bind(container.heightProperty())

    return Scene(container, 900.0, 430.0, Color.rgb(248, 250, 252))
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

private fun SurfacePoint.toJavaFxPoint(): FloatArray {
    return floatArrayOf(
        (x * 178.0).toFloat(),
        (-z * 190.0).toFloat(),
        (y * 178.0).toFloat()
    )
}

private fun surfaceColor(
    ratio: Double
): Color {
    val low = Color.rgb(17, 24, 39)
    val mid = Color.rgb(71, 85, 105)
    val high = Color.rgb(244, 63, 94)

    return if (ratio < 0.52) {
        lerpColor(low, mid, ratio / 0.52)
    } else {
        lerpColor(mid, high, (ratio - 0.52) / 0.48)
    }
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
