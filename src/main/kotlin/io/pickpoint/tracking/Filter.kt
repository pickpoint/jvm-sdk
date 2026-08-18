package io.pickpoint.tracking

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val HEARTBEAT_MS = 1000L
private const val MIN_MOVE_M = 2.0
private const val HEADING_JUMP = 25.0
private const val MOTION_EPS = 0.5
private const val EARTH_M = 6_371_000.0

class NoiseFilter {
    private var lastEmitted: LatLng? = null
    private var candidate: LatLng? = null
    private var lastEmitAt: Long? = null

    fun reset() {
        lastEmitted = null
        candidate = null
        lastEmitAt = null
    }

    fun push(current: LatLng, nowMs: Long = current.timestampMs ?: System.currentTimeMillis()): LatLng? {
        val stamped = current.copy(timestampMs = nowMs)
        if (lastEmitted == null) return emit(stamped, nowMs)
        if (shouldEmit(stamped, nowMs)) return emit(stamped, nowMs)
        candidate = stamped
        return null
    }

    private fun shouldEmit(current: LatLng, now: Long): Boolean {
        val last = lastEmitted ?: return true
        if (now - (lastEmitAt ?: now) >= HEARTBEAT_MS) return true
        val acc = current.accuracy ?: 0.0
        if (haversineM(last, current) >= max(MIN_MOVE_M, 2.0 * acc)) return true
        val cand = candidate
        if (cand != null) {
            val speed = current.speed ?: 0.0
            val eps = max(MIN_MOVE_M, max(acc, 0.5 * speed))
            if (perpendicularM(last, current, cand) >= eps) return true
        }
        val h0 = last.heading
        val h1 = current.heading
        if (h0 != null && h1 != null && headingDelta(h0, h1) >= HEADING_JUMP) return true
        val s0 = last.speed
        val s1 = current.speed
        if (s0 != null && s1 != null && (s0 > MOTION_EPS) != (s1 > MOTION_EPS)) return true
        return false
    }

    private fun emit(point: LatLng, now: Long): LatLng {
        lastEmitted = point
        candidate = null
        lastEmitAt = now
        return point
    }
}

fun haversineM(a: LatLng, b: LatLng): Double {
    val dlat = Math.toRadians(b.latitude - a.latitude)
    val dlon = Math.toRadians(b.longitude - a.longitude)
    val la1 = Math.toRadians(a.latitude)
    val la2 = Math.toRadians(b.latitude)
    val h = sin(dlat / 2) * sin(dlat / 2) + cos(la1) * cos(la2) * sin(dlon / 2) * sin(dlon / 2)
    return 2.0 * EARTH_M * asin(min(1.0, sqrt(h)))
}

fun perpendicularM(a: LatLng, b: LatLng, p: LatLng): Double {
    val (bx, by) = projectM(a, b)
    val (px, py) = projectM(a, p)
    val len2 = bx * bx + by * by
    if (len2 < 1e-6) return haversineM(a, p)
    val t = (px * bx + py * by) / len2
    val qx = t * bx
    val qy = t * by
    return sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
}

private fun projectM(origin: LatLng, p: LatLng): Pair<Double, Double> {
    val mPerDeg = 111_320.0
    val lat0 = Math.toRadians(origin.latitude)
    val x = (p.longitude - origin.longitude) * mPerDeg * cos(lat0)
    val y = (p.latitude - origin.latitude) * mPerDeg
    return x to y
}

private fun headingDelta(a: Double, b: Double): Double {
    var d = kotlin.math.abs(b - a) % 360.0
    if (d > 180.0) d = 360.0 - d
    return d
}

fun collapseOneCollinear(points: MutableList<LatLng>): Boolean {
    if (points.size < 3) return false
    for (i in 1 until points.size - 1) {
        val acc = points[i].accuracy ?: 0.0
        val eps = max(MIN_MOVE_M, acc)
        if (perpendicularM(points[i - 1], points[i + 1], points[i]) < eps) {
            points.removeAt(i)
            return true
        }
    }
    return false
}
