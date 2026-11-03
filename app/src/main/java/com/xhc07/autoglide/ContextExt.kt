package com.xhc07.autoglide

// Modified for AutoGlide by @XHCinfoG_Adm (https://github.com/xhc07). See MODIFICATIONS.md.

// 修改版说明：新增手势持续时间、像素速度与自定义数值输入，详见 MODIFICATIONS.md。

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import com.xhc07.autoglide.service.AutoSlideService

/**
 * 判断当前应用⌈无障碍服务权限⌋是否已启用
 *
 * @return ⌈无障碍服务权限⌋是否已启用
 */
fun Context.isAccessibilityServicePermissionEnabled(): Boolean {
    // 检查全局无障碍总开关是否开启
    val enabled = runCatching {
        Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    }.getOrDefault(0)
    if (enabled != 1) {
        return false
    }
    // 获取当前已启用的无障碍服务列表
    val services = Settings.Secure.getString(
        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val targetComponent = ComponentName(this, AutoSlideService::class.java)
    return services.split(":").any {
        ComponentName.unflattenFromString(it.trim()) == targetComponent
    }
}

/**
 * 清除指定方向的自定义手势轨迹并广播事件
 *
 * @param direction 方向字符串
 */
fun Context.clearCustomTrajectory(direction: String) {
    val key = getTrajectoryKey(direction) ?: return
    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
        remove(key)
    }
    SlideEventHub.sendEvent(SlideEvent.CustomTrajectoryCleared)
}

/**
 * 获取指定方向的自定义轨迹字符串
 *
 * @param direction 方向字符串
 * @return 自定义轨迹字符串
 */
fun Context.getCustomTrajectory(direction: String): String? {
    val key = getTrajectoryKey(direction) ?: return null
    val value = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(key, null)
    return if (value.isNullOrBlank()) {
        null
    } else {
        value
    }
}

/**
 * 判断指定方向是否存在自定义轨迹
 *
 * @param direction 方向字符串
 * @return 是否存在有效自定义轨迹
 */
fun Context.hasCustomTrajectory(direction: String): Boolean {
    return getCustomTrajectory(direction) != null
}

/**
 * 从本地配置文件读取滑动配置
 *
 * @return 滑动配置数据对象
 */
fun Context.getSlideConfig(): SlideConfig {
    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // 新版本没有 gestureDuration 时，把旧版 1-100 速度换算成毫秒，避免升级后丢失设置
    val gestureDuration = if (prefs.contains(KEY_GESTURE_DURATION)) {
        prefs.getInt(KEY_GESTURE_DURATION, DEFAULT_GESTURE_DURATION_MS)
    } else {
        legacySpeedToDurationMs(prefs.getInt(KEY_SPEED, DEFAULT_SPEED))
    }
    val speedUnified = prefs.getBoolean(KEY_SPEED_UNIFIED, DEFAULT_SPEED_UNIFIED)
    val fixedVertical = prefs.getInt(
        KEY_PIXELS_PER_SECOND_VERTICAL, DEFAULT_PIXELS_PER_SECOND_VERTICAL
    ).coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND)
    val fixedHorizontal = if (speedUnified) {
        fixedVertical
    } else {
        prefs.getInt(
            KEY_PIXELS_PER_SECOND_HORIZONTAL, DEFAULT_PIXELS_PER_SECOND_HORIZONTAL
        ).coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND)
    }
    val verticalMin = prefs.getInt(
        KEY_PIXELS_PER_SECOND_VERTICAL_MIN, DEFAULT_PIXELS_PER_SECOND_VERTICAL_MIN
    ).coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND)
    val verticalMax = prefs.getInt(
        KEY_PIXELS_PER_SECOND_VERTICAL_MAX, DEFAULT_PIXELS_PER_SECOND_VERTICAL_MAX
    ).coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND)
    val horizontalMin = if (speedUnified) {
        verticalMin
    } else {
        prefs.getInt(
            KEY_PIXELS_PER_SECOND_HORIZONTAL_MIN, DEFAULT_PIXELS_PER_SECOND_HORIZONTAL_MIN
        ).coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND)
    }
    val horizontalMax = if (speedUnified) {
        verticalMax
    } else {
        prefs.getInt(
            KEY_PIXELS_PER_SECOND_HORIZONTAL_MAX, DEFAULT_PIXELS_PER_SECOND_HORIZONTAL_MAX
        ).coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND)
    }
    return SlideConfig(
        gestureDurationMode = prefs.getInt(
            KEY_GESTURE_DURATION_MODE, MOTION_MODE_FIXED
        ).coerceIn(MOTION_MODE_FIXED, MOTION_MODE_RANDOM),
        gestureDurationMs = gestureDuration.coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS),
        gestureDurationMin = prefs.getInt(
            KEY_GESTURE_DURATION_MIN, DEFAULT_GESTURE_DURATION_MIN
        ).coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS),
        gestureDurationMax = prefs.getInt(
            KEY_GESTURE_DURATION_MAX, DEFAULT_GESTURE_DURATION_MAX
        ).coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS),
        pixelSpeedMode = prefs.getInt(
            KEY_PIXEL_SPEED_MODE, MOTION_MODE_FIXED
        ).coerceIn(MOTION_MODE_FIXED, MOTION_MODE_RANDOM),
        pixelsPerSecondVertical = fixedVertical,
        pixelsPerSecondHorizontal = fixedHorizontal,
        pixelsPerSecondVerticalMin = verticalMin,
        pixelsPerSecondVerticalMax = verticalMax,
        pixelsPerSecondHorizontalMin = horizontalMin,
        pixelsPerSecondHorizontalMax = horizontalMax,
        speedUnified = speedUnified,
        pauseMode = prefs.getInt(KEY_PAUSE_MODE, PAUSE_MODE_NONE),
        pauseTime = prefs.getInt(KEY_PAUSE_TIME, DEFAULT_PAUSE_TIME).coerceAtLeast(1),
        minPauseTime = prefs.getInt(KEY_MIN_PAUSE_TIME, DEFAULT_MIN_PAUSE_TIME).coerceAtLeast(1),
        maxPauseTime = prefs.getInt(KEY_MAX_PAUSE_TIME, DEFAULT_MAX_PAUSE_TIME).coerceAtLeast(1)
    )
}

