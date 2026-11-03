package com.xhc07.autoglide

// Modified for AutoGlide by @XHCinfoG_Adm (https://github.com/xhc07). See MODIFICATIONS.md.

// 修改版说明：新增手势持续时间、像素速度与自定义数值输入，详见 MODIFICATIONS.md。

import kotlin.math.ln
import kotlin.math.roundToLong

// 配置文件名
const val PREFS_NAME = "slide_settings"

// 配置键名
// KEY_SPEED 仅用于从旧版本迁移，新版不再保存 1-100 的速度值
const val KEY_SPEED = "speed"
const val KEY_GESTURE_DURATION = "gestureDuration"
const val KEY_GESTURE_DURATION_MODE = "gestureDurationMode"
const val KEY_GESTURE_DURATION_MIN = "gestureDurationMin"
const val KEY_GESTURE_DURATION_MAX = "gestureDurationMax"
const val KEY_PIXEL_SPEED_MODE = "pixelSpeedMode"
const val KEY_PIXELS_PER_SECOND_VERTICAL = "pixelsPerSecondVertical"
const val KEY_PIXELS_PER_SECOND_HORIZONTAL = "pixelsPerSecondHorizontal"
const val KEY_PIXELS_PER_SECOND_VERTICAL_MIN = "pixelsPerSecondVerticalMin"
const val KEY_PIXELS_PER_SECOND_VERTICAL_MAX = "pixelsPerSecondVerticalMax"
const val KEY_PIXELS_PER_SECOND_HORIZONTAL_MIN = "pixelsPerSecondHorizontalMin"
const val KEY_PIXELS_PER_SECOND_HORIZONTAL_MAX = "pixelsPerSecondHorizontalMax"
const val KEY_SPEED_UNIFIED = "speedUnified"
const val KEY_PAUSE_MODE = "pauseMode"
const val KEY_PAUSE_TIME = "pauseTime"
const val KEY_MIN_PAUSE_TIME = "minPauseTime"
const val KEY_MAX_PAUSE_TIME = "maxPauseTime"
const val KEY_FLOATING_TRANSPARENCY = "floatingTransparency"

// 默认值
const val DEFAULT_SPEED = 50 // 旧版速度，仅用于迁移
const val DEFAULT_GESTURE_DURATION_MS = 450
const val DEFAULT_GESTURE_DURATION_MIN = 200
const val DEFAULT_GESTURE_DURATION_MAX = 800
const val DEFAULT_PIXELS_PER_SECOND_VERTICAL = 3200
const val DEFAULT_PIXELS_PER_SECOND_HORIZONTAL = 1900
const val DEFAULT_PIXELS_PER_SECOND_VERTICAL_MIN = 1600
const val DEFAULT_PIXELS_PER_SECOND_VERTICAL_MAX = 4000
const val DEFAULT_PIXELS_PER_SECOND_HORIZONTAL_MIN = 1000
const val DEFAULT_PIXELS_PER_SECOND_HORIZONTAL_MAX = 2500
const val DEFAULT_SPEED_UNIFIED = false
const val DEFAULT_PAUSE_TIME = 1
const val DEFAULT_MIN_PAUSE_TIME = 1
const val DEFAULT_MAX_PAUSE_TIME = 3
const val DEFAULT_FLOATING_TRANSPARENCY = 40

// 取值范围
const val MIN_GESTURE_DURATION_MS = 1
const val MAX_GESTURE_DURATION_MS = 60_000
const val MIN_PIXELS_PER_SECOND = 1
const val MAX_PIXELS_PER_SECOND = 100_000
const val MIN_PAUSE_TIME_SECONDS = 1
const val MAX_CUSTOM_PAUSE_TIME_SECONDS = 86_400

// 停顿模式
const val PAUSE_MODE_NONE = 0
const val PAUSE_MODE_FIXED = 1
const val PAUSE_MODE_RANDOM = 2

// 运动参数模式(持续时间/像素速度)
const val MOTION_MODE_FIXED = 0
const val MOTION_MODE_RANDOM = 1

/* 滑动配置数据类 */
data class SlideConfig(
    val gestureDurationMode: Int = MOTION_MODE_FIXED,
    val gestureDurationMs: Int = DEFAULT_GESTURE_DURATION_MS,
    val gestureDurationMin: Int = DEFAULT_GESTURE_DURATION_MIN,
    val gestureDurationMax: Int = DEFAULT_GESTURE_DURATION_MAX,
    val pixelSpeedMode: Int = MOTION_MODE_FIXED,
    val pixelsPerSecondVertical: Int = DEFAULT_PIXELS_PER_SECOND_VERTICAL,
    val pixelsPerSecondHorizontal: Int = DEFAULT_PIXELS_PER_SECOND_HORIZONTAL,
    val pixelsPerSecondVerticalMin: Int = DEFAULT_PIXELS_PER_SECOND_VERTICAL_MIN,
    val pixelsPerSecondVerticalMax: Int = DEFAULT_PIXELS_PER_SECOND_VERTICAL_MAX,
    val pixelsPerSecondHorizontalMin: Int = DEFAULT_PIXELS_PER_SECOND_HORIZONTAL_MIN,
    val pixelsPerSecondHorizontalMax: Int = DEFAULT_PIXELS_PER_SECOND_HORIZONTAL_MAX,
    val speedUnified: Boolean = DEFAULT_SPEED_UNIFIED,
    val pauseMode: Int = PAUSE_MODE_NONE,
    val pauseTime: Int = DEFAULT_PAUSE_TIME,
    val minPauseTime: Int = DEFAULT_MIN_PAUSE_TIME,
    val maxPauseTime: Int = DEFAULT_MAX_PAUSE_TIME
)

/**
 * 将旧版 1-100 的“滑动速度”换算成手势持续时间。
 *
 * 旧版本使用对数曲线，速度值越大手势越短。这里保留同样的映射，
 * 仅用于老用户升级后的配置迁移。
 *
 * @param speed 旧版速度值(1-100)
 * @return 手势持续时间(毫秒)
 */
fun legacySpeedToDurationMs(speed: Int): Int {
    val normalizedSpeed = speed.coerceIn(1, 100) / 100.0
    val curvedProgress = ln(1.0 + 0.7 * normalizedSpeed) / ln(1.7)
    return (900.0 - 800.0 * curvedProgress).roundToLong().toInt()
        .coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS)
}

// 滑动方向
const val DIRECTION_UP = "up"
const val DIRECTION_DOWN = "down"
const val DIRECTION_LEFT = "left"
const val DIRECTION_RIGHT = "right"

// 自定义轨迹
const val KEY_CUSTOM_TRAJECTORY_UP = "customTrajectory_up"
const val KEY_CUSTOM_TRAJECTORY_DOWN = "customTrajectory_down"
const val KEY_CUSTOM_TRAJECTORY_LEFT = "customTrajectory_left"
const val KEY_CUSTOM_TRAJECTORY_RIGHT = "customTrajectory_right"

/**
 * 获取轨迹存储键名
 *
 * @param direction 方向字符串
 * @return 轨迹存储键名
 */
fun getTrajectoryKey(direction: String): String? = when (direction) {
    DIRECTION_UP -> KEY_CUSTOM_TRAJECTORY_UP
    DIRECTION_DOWN -> KEY_CUSTOM_TRAJECTORY_DOWN
    DIRECTION_LEFT -> KEY_CUSTOM_TRAJECTORY_LEFT
    DIRECTION_RIGHT -> KEY_CUSTOM_TRAJECTORY_RIGHT
    else -> null
}
