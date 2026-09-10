package com.lazydoglab.zisee.ar.spatial

import com.lazydoglab.zisee.ar.annotation.VideoPoint
import kotlin.math.abs
import kotlin.math.sqrt

/** Metres in the local AR session's world, never geographic coordinates. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    init { require(x.isFinite() && y.isFinite() && z.isFinite()) }
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Float) = Vec3(x * scale, y * scale, z * scale)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun length() = sqrt(dot(this))
    fun normalized(): Vec3 { val length = length(); require(length > 0f); return this * (1f / length) }
}

data class Rotation(val x: Float, val y: Float, val z: Float, val w: Float) {
    init {
        require(listOf(x, y, z, w).all { it.isFinite() })
        require(abs(x * x + y * y + z * z + w * w - 1f) < 0.001f)
    }
    fun rotate(point: Vec3): Vec3 {
        val q = Vec3(x, y, z)
        val t = q.cross(point) * 2f
        return point + t * w + q.cross(t)
    }
    companion object { val IDENTITY = Rotation(0f, 0f, 0f, 1f) }
}

data class WorldPose(val position: Vec3, val rotation: Rotation = Rotation.IDENTITY) {
    fun transform(point: Vec3) = position + rotation.rotate(point)
}

/** Intrinsics of the unrotated CPU camera image, not display-oriented intrinsics. */
data class CameraIntrinsics(val width: Int, val height: Int, val fx: Float, val fy: Float, val cx: Float, val cy: Float) {
    init {
        require(width > 0 && height > 0)
        require(listOf(fx, fy, cx, cy).all { it.isFinite() } && fx > 0 && fy > 0)
        require(cx in 0f..width.toFloat() && cy in 0f..height.toFloat())
    }
    /** ARCore camera looks along -Z; image Y points down, camera Y points up. */
    fun cameraRay(point: VideoPoint) = Vec3((point.x * width - cx) / fx, -(point.y * height - cy) / fy, -1f)
}

/** Copied, bounded plane polygon in plane-local X/Z coordinates (Y=0). */
class PlaneSnapshot(val pose: WorldPose, polygon: List<Vec3>) {
    val polygon: List<Vec3> = java.util.Collections.unmodifiableList(polygon.toList())
    init { require(polygon.size in 3..128 && polygon.all { abs(it.y) < 0.0001f }) }

    fun intersect(origin: Vec3, direction: Vec3, margin: Float = 0f): Vec3? {
        val normal = pose.rotation.rotate(Vec3(0f, 1f, 0f))
        val denominator = direction.dot(normal)
        if (abs(denominator) < 0.00001f) return null
        val distance = (pose.position - origin).dot(normal) / denominator
        if (distance <= 0f) return null
        val hit = origin + direction * distance
        val inverse = Rotation(-pose.rotation.x, -pose.rotation.y, -pose.rotation.z, pose.rotation.w)
        val local = inverse.rotate(hit - pose.position)
        var positive = false
        var negative = false
        polygon.indices.forEach { i ->
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val cross = (b.x - a.x) * (local.z - a.z) - (b.z - a.z) * (local.x - a.x)
            if (cross > 0.00001f) positive = true
            if (cross < -0.00001f) negative = true
        }
        if (!(positive && negative)) return hit
        // A freshly detected plane is a small patch of a larger real surface; a tap just past its
        // edge nearly always means the same surface. Accept that only within [margin].
        return hit.takeIf { margin > 0f && edgeDistance(local.x, local.z) <= margin }
    }

    private fun edgeDistance(x: Float, z: Float): Float = polygon.indices.minOf { i ->
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        val dx = b.x - a.x
        val dz = b.z - a.z
        val t = (((x - a.x) * dx + (z - a.z) * dz) / (dx * dx + dz * dz).coerceAtLeast(1e-12f)).coerceIn(0f, 1f)
        val ex = x - (a.x + dx * t)
        val ez = z - (a.z + dz * t)
        sqrt(ex * ex + ez * ez)
    }
}

/** Axial depth in millimetres, aligned to normalized CPU-image coordinates. Zero means unknown.
 * Owns a defensive copy; no Android Image/native frame survives capture. At most 38.4 KB/frame.
 */
class DepthSnapshot(val width: Int, val height: Int, millimetres: ShortArray) {
    private val samples = millimetres.copyOf()
    init { require(width in 1..160 && height in 1..120 && samples.size == width * height) }
    fun metresAt(point: VideoPoint): Float? {
        val x = (point.x * width).toInt().coerceAtMost(width - 1)
        val y = (point.y * height).toInt().coerceAtMost(height - 1)
        sample(x, y)?.let { return it }
        // Holes (edges, glare, low texture) are usually a few cells, not the surface itself: take
        // the median of the valid cells around the tap instead of giving up on depth entirely.
        val nearby = buildList {
            for (dy in -HOLE_RADIUS..HOLE_RADIUS) for (dx in -HOLE_RADIUS..HOLE_RADIUS) {
                val sx = x + dx
                val sy = y + dy
                if (sx in 0 until width && sy in 0 until height) sample(sx, sy)?.let { add(it) }
            }
        }.sorted()
        return nearby.getOrNull(nearby.size / 2)
    }

    private fun sample(x: Int, y: Int): Float? =
        (samples[y * width + x].toInt() and 0xffff).takeIf { it > 0 }?.div(1000f)

    private companion object { const val HOLE_RADIUS = 2 }
}
