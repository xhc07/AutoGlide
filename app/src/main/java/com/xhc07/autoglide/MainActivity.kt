package com.xhc07.autoglide

// Modified for AutoGlide by @XHCinfoG_Adm (https://github.com/xhc07). See MODIFICATIONS.md.

// 修改版说明：新增手势持续时间、像素速度与自定义数值输入，详见 MODIFICATIONS.md。

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.xhc07.autoglide.databinding.ActivityMainBinding
import com.xhc07.autoglide.service.AutoSlideService
import com.xhc07.autoglide.service.FloatingWindowService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.Locale
import android.R as AndroidR
import com.google.android.material.R as MaterialR

/**
 * 应用主界面
 *
 * @author tianxing
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: SharedPreferences
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    /* 全局常量 */
    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 100
        // 无障碍授权方式选项常量
        private const val OPTION_MANUAL = 0
        private const val OPTION_SHIZUKU = 1
        private const val OPTION_ADB = 2
    }

    /**
     * Shizuku命令执行结果数据类
     *
     * @param exitCode 命令执行退出码
     * @param stdout 命令执行标准输出
     * @param stderr 命令执行标准错误输出
     */
    private data class ShizukuCommandResult(
        val exitCode: Int, val stdout: String, val stderr: String
    )

    /* Shizuku授权待执行的回调 */
    private var pendingShizukuOnGranted: (() -> Unit)? = null
    private var pendingShizukuOnFailed: (() -> Unit)? = null

    /* Shizuku权限请求监听器 */
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            pendingShizukuOnGranted?.invoke()
        } else {
            Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
            pendingShizukuOnFailed?.invoke()
        }
        pendingShizukuOnGranted = null
        pendingShizukuOnFailed = null
    }

    /* 无障碍服务权限开关监听器 */
    private val accessibilitySwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) {
            onAccessibilityServicePermissionSwitchEnabled()
        } else {
            onAccessibilityServicePermissionSwitchDisabled()
        }
    }

    /* 悬浮窗权限开关监听器 */
    private val overlaySwitchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        updateOverlaySwitchState(!isChecked)
    }

    /**
     * 更新权限开关状态
     *
     * @param switch 目标开关
     * @param checked 开关状态
     * @param listener 监听器
     */
    private fun updateSwitchState(
        switch: CompoundButton, checked: Boolean, listener: CompoundButton.OnCheckedChangeListener
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = checked
        switch.jumpDrawablesToCurrentState()
        switch.setOnCheckedChangeListener(listener)
    }

    /**
     * 更新无障碍服务权限开关状态
     *
     * @param checked 开关状态
     */
    private fun updateAccessibilitySwitchState(checked: Boolean) {
        updateSwitchState(binding.accessibilityServicePermissionSwitch, checked, accessibilitySwitchListener)
    }

    /**
     * 更新悬浮窗权限开关状态
     *
     * @param checked 开关状态
     */
    private fun updateOverlaySwitchState(checked: Boolean) {
        updateSwitchState(binding.overlayPermissionSwitch, checked, overlaySwitchListener)
    }

    /**
     * 初始化Activity界面布局和事件绑定
     *
     * @param savedInstanceState 系统恢复状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 初始化SharedPreferences用于本地配置存储
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        restoreSettings()
        setupPauseControls()
        setupMotionControls()
        setupTransparencyControl()
        markValueTextClickable(
            binding.gestureDurationValueText,
            binding.gestureDurationRangeValueText,
            binding.pixelSpeedUnifiedValueText,
            binding.pixelSpeedVerticalValueText,
            binding.pixelSpeedHorizontalValueText,
            binding.pixelSpeedUnifiedRangeValueText,
            binding.pixelSpeedVerticalRangeValueText,
            binding.pixelSpeedHorizontalRangeValueText,
            binding.transparencyValueText,
            binding.pauseTimeValueText
        )
        binding.accessibilityServicePermissionSwitch.setOnCheckedChangeListener(accessibilitySwitchListener)
        binding.overlayPermissionSwitch.setOnCheckedChangeListener(overlaySwitchListener)
        setupStartButton()
        setupPageNavigation()
        setupAboutPage()
        // 注册Shizuku监听器
        binding.root.post {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
    }

    /* 活动恢复时检查⌈无障碍服务权限⌋并同步开关状态 */
    override fun onResume() {
        super.onResume()
        // 使用协程异步检查权限并同步开关状态
        lifecycleScope.launch(ioDispatcher) {
            val hasWriteSecure = hasWriteSecureSettingsPermission()
            val isAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
            // 有⌈写入安全设置权限⌋时直接开启⌈无障碍服务权限⌋
            if (hasWriteSecure && !isAccessibilityEnabled) {
                changeAccessibilityServicePermissionState(enable = true)
            }
            // 获取最终的权限状态
            val finalAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
            val finalCanDrawOverlays = Settings.canDrawOverlays(this@MainActivity)
            // 回到主线程同步开关状态并应用无动画过渡
            withContext(mainDispatcher) {
                if (!isFinishing && !isDestroyed) {
                    updateAccessibilitySwitchState(finalAccessibilityEnabled)
                    updateOverlaySwitchState(finalCanDrawOverlays)
                }
            }
        }
    }

    /* 活动销毁时移除Shizuku权限请求监听器 */
    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
    }

    /* 恢复上次配置 */
    private fun restoreSettings() {
        val config = getSlideConfig()

        // 单次手势持续时间：固定/随机
        binding.gestureDurationModeGroup.check(
            if (config.gestureDurationMode == MOTION_MODE_RANDOM) {
                R.id.btnGestureDurationRandom
            } else {
                R.id.btnGestureDurationFixed
            }
        )
        binding.gestureDurationSlider.valueTo = maxOf(5000, config.gestureDurationMs).toFloat()
        binding.gestureDurationSlider.value = config.gestureDurationMs.toFloat()
        binding.gestureDurationValueText.text =
            getString(R.string.gesture_duration_format, config.gestureDurationMs)
        val durationRangeMax = maxOf(
            5000, config.gestureDurationMin, config.gestureDurationMax
        )
        binding.gestureDurationRangeSlider.valueTo = durationRangeMax.toFloat()
        binding.gestureDurationRangeSlider.values = listOf(
            config.gestureDurationMin.toFloat(), config.gestureDurationMax.toFloat()
        ).sorted()
        updateDurationRangeLabel(
            minOf(config.gestureDurationMin, config.gestureDurationMax),
            maxOf(config.gestureDurationMin, config.gestureDurationMax)
        )
        updateDurationVisibility(config.gestureDurationMode)
        binding.gestureDurationSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.gestureDurationRangeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)

        // 像素速度：固定/随机
        binding.pixelSpeedModeGroup.check(
            if (config.pixelSpeedMode == MOTION_MODE_RANDOM) {
                R.id.btnPixelSpeedRandom
            } else {
                R.id.btnPixelSpeedFixed
            }
        )
        binding.speedUnifiedSwitch.isChecked = config.speedUnified
        val maxPixelSpeed = maxOf(
            10000,
            config.pixelsPerSecondVertical,
            config.pixelsPerSecondHorizontal,
            config.pixelsPerSecondVerticalMin,
            config.pixelsPerSecondVerticalMax,
            config.pixelsPerSecondHorizontalMin,
            config.pixelsPerSecondHorizontalMax
        )
        binding.pixelSpeedUnifiedSlider.valueTo = maxPixelSpeed.toFloat()
        binding.pixelSpeedUnifiedSlider.value = config.pixelsPerSecondVertical.toFloat()
        binding.pixelSpeedVerticalSlider.valueTo = maxPixelSpeed.toFloat()
        binding.pixelSpeedVerticalSlider.value = config.pixelsPerSecondVertical.toFloat()
        binding.pixelSpeedHorizontalSlider.valueTo = maxPixelSpeed.toFloat()
        binding.pixelSpeedHorizontalSlider.value = config.pixelsPerSecondHorizontal.toFloat()
        updatePixelSpeedLabels(config.pixelsPerSecondVertical, config.pixelsPerSecondHorizontal)

        binding.pixelSpeedUnifiedRangeSlider.valueTo = maxPixelSpeed.toFloat()
        binding.pixelSpeedUnifiedRangeSlider.values = listOf(
            config.pixelsPerSecondVerticalMin.toFloat(),
            config.pixelsPerSecondVerticalMax.toFloat()
        ).sorted()
        binding.pixelSpeedVerticalRangeSlider.valueTo = maxPixelSpeed.toFloat()
        binding.pixelSpeedVerticalRangeSlider.values = listOf(
            config.pixelsPerSecondVerticalMin.toFloat(),
            config.pixelsPerSecondVerticalMax.toFloat()
        ).sorted()
        binding.pixelSpeedHorizontalRangeSlider.valueTo = maxPixelSpeed.toFloat()
        binding.pixelSpeedHorizontalRangeSlider.values = listOf(
            config.pixelsPerSecondHorizontalMin.toFloat(),
            config.pixelsPerSecondHorizontalMax.toFloat()
        ).sorted()
        updatePixelRangeLabels(
            config.pixelsPerSecondVerticalMin,
            config.pixelsPerSecondVerticalMax,
            config.pixelsPerSecondHorizontalMin,
            config.pixelsPerSecondHorizontalMax
        )
        updatePixelSpeedVisibility(config.pixelSpeedMode, config.speedUnified)
        binding.pixelSpeedUnifiedSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.pixelSpeedVerticalSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.pixelSpeedHorizontalSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.pixelSpeedUnifiedRangeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.pixelSpeedVerticalRangeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.pixelSpeedHorizontalRangeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)

        val transparency = preferences.getInt(KEY_FLOATING_TRANSPARENCY, DEFAULT_FLOATING_TRANSPARENCY)
        binding.transparencySlider.value = transparency.toFloat()
        binding.transparencyValueText.text = getString(R.string.transparency_percentage_format, transparency)
        binding.transparencySlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        when (config.pauseMode) {
            PAUSE_MODE_NONE -> binding.pauseModeToggleGroup.check(R.id.btnNoPause)
            PAUSE_MODE_FIXED -> binding.pauseModeToggleGroup.check(R.id.btnFixedPause)
            PAUSE_MODE_RANDOM -> binding.pauseModeToggleGroup.check(R.id.btnRandomPause)
        }
        // 动态调整固定停顿时间滑块的最大值
        binding.pauseTimeSlider.valueTo = maxOf(10, config.pauseTime).toFloat()
        binding.pauseTimeSlider.value = config.pauseTime.toFloat()
        // 动态调整随机停顿范围滑块的最大值并排序赋值
        binding.randomPauseTimeSlider.valueTo = maxOf(10, config.minPauseTime, config.maxPauseTime).toFloat()
        binding.randomPauseTimeSlider.values = listOf(config.minPauseTime.toFloat(), config.maxPauseTime.toFloat()).sorted()
        binding.pauseTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        binding.randomPauseTimeSlider.setCustomThumbDrawable(R.drawable.slider_thumb_circular)
        updatePauseTimeVisibility(config.pauseMode)
    }

    /* 绑定停顿相关控件事件并持久化用户设置 */
    private fun setupPauseControls() {
        binding.pauseModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val pauseMode = when (checkedId) {
                    R.id.btnNoPause -> PAUSE_MODE_NONE
                    R.id.btnFixedPause -> PAUSE_MODE_FIXED
                    R.id.btnRandomPause -> PAUSE_MODE_RANDOM
                    else -> PAUSE_MODE_NONE
                }
                updatePauseTimeVisibility(pauseMode)
                preferences.edit { putInt(KEY_PAUSE_MODE, pauseMode) }
                // 更新停顿配置
                syncPauseConfigToService()
            }
        }
        // 停顿时间文本绑定点击事件：固定模式输入单值，随机模式输入范围
        binding.pauseTimeValueText.setOnClickListener {
            when (preferences.getInt(KEY_PAUSE_MODE, PAUSE_MODE_NONE)) {
                PAUSE_MODE_FIXED -> showCustomPauseTimeDialog()
                PAUSE_MODE_RANDOM -> showCustomPauseRangeDialog()
            }
        }
        // 绑定停顿时长滑块事件
        binding.pauseTimeSlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.pauseTimeValueText.text = String.format(Locale.ROOT, "%d", progress)
            if (fromUser) {
                preferences.edit { putInt(KEY_PAUSE_TIME, progress) }
            }
        }
        // 绑定随机停顿时长范围滑块事件
        binding.randomPauseTimeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            val min = values[0].toInt()
            val max = values[1].toInt()
            binding.pauseTimeValueText.text = getString(R.string.pause_time_range_format, min, max)
            if (fromUser) {
                preferences.edit {
                    putInt(KEY_MIN_PAUSE_TIME, min)
                    putInt(KEY_MAX_PAUSE_TIME, max)
                }
            }
        }
        // 绑定停顿时长滑块触摸松开事件
        binding.pauseTimeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                syncPauseConfigToService()
            }
        })
        // 绑定随机停顿时长范围滑块触摸松开事件
        binding.randomPauseTimeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) {
                syncPauseConfigToService()
            }
        })
    }

    /* 同步最新停顿配置到自动滑动服务 */
    private fun syncPauseConfigToService() {
        AutoSlideService.getInstance()?.updatePauseConfig(getSlideConfig())
    }

    /* 显示自定义停顿时间输入对话框 */
    private fun showCustomPauseTimeDialog() {
        showNumberInputDialog(
            title = getString(R.string.custom_pause_time),
            initial = binding.pauseTimeSlider.value.toInt(),
            range = MIN_PAUSE_TIME_SECONDS..MAX_CUSTOM_PAUSE_TIME_SECONDS
        ) { value ->
            preferences.edit { putInt(KEY_PAUSE_TIME, value) }
            binding.pauseTimeSlider.valueTo = maxOf(10, value).toFloat()
            binding.pauseTimeSlider.value = value.toFloat()
            binding.pauseTimeValueText.text = String.format(Locale.ROOT, "%d", value)
            syncPauseConfigToService()
        }
    }

    /* 显示自定义随机停顿范围输入对话框 */
    private fun showCustomPauseRangeDialog() {
        val values = binding.randomPauseTimeSlider.values
        if (values.size < 2) {
            return
        }
        showRangeInputDialog(
            title = getString(R.string.custom_pause_time_range),
            initialMin = values[0].toInt(),
            initialMax = values[1].toInt(),
            range = MIN_PAUSE_TIME_SECONDS..MAX_CUSTOM_PAUSE_TIME_SECONDS
        ) { min, max ->
            preferences.edit {
                putInt(KEY_MIN_PAUSE_TIME, min)
                putInt(KEY_MAX_PAUSE_TIME, max)
            }
            binding.randomPauseTimeSlider.valueTo = maxOf(10, max).toFloat()
            binding.randomPauseTimeSlider.values =
                listOf(min.toFloat(), max.toFloat()).sorted()
            binding.pauseTimeValueText.text =
                getString(R.string.pause_time_range_format, min, max)
            syncPauseConfigToService()
        }
    }

    /* 绑定手势持续时间与像素速度控件事件 */
    private fun setupMotionControls() {
        // 持续时间模式：固定/随机
        binding.gestureDurationModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val mode = if (checkedId == R.id.btnGestureDurationRandom) {
                MOTION_MODE_RANDOM
            } else {
                MOTION_MODE_FIXED
            }
            preferences.edit { putInt(KEY_GESTURE_DURATION_MODE, mode) }
            updateDurationVisibility(mode)
            syncMotionConfigToService()
        }

        // 固定持续时间
        binding.gestureDurationSlider.addOnChangeListener { _, value, fromUser ->
            val duration = value.toInt()
            binding.gestureDurationValueText.text =
                getString(R.string.gesture_duration_format, duration)
            if (fromUser) {
                preferences.edit { putInt(KEY_GESTURE_DURATION, duration) }
            }
        }
        binding.gestureDurationSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                syncMotionConfigToService()
            }
        })
        binding.gestureDurationValueText.setOnClickListener {
            showNumberInputDialog(
                title = getString(R.string.custom_gesture_duration),
                initial = binding.gestureDurationSlider.value.toInt(),
                range = MIN_GESTURE_DURATION_MS..MAX_GESTURE_DURATION_MS
            ) { value -> applyGestureDuration(value) }
        }

        // 随机持续时间范围
        binding.gestureDurationRangeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            if (values.size >= 2) {
                val min = values[0].toInt()
                val max = values[1].toInt()
                updateDurationRangeLabel(min, max)
                if (fromUser) {
                    preferences.edit {
                        putInt(KEY_GESTURE_DURATION_MIN, min)
                        putInt(KEY_GESTURE_DURATION_MAX, max)
                    }
                }
            }
        }
        binding.gestureDurationRangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) {
                syncMotionConfigToService()
            }
        })
        binding.gestureDurationRangeValueText.setOnClickListener {
            val values = binding.gestureDurationRangeSlider.values
            if (values.size >= 2) {
                showRangeInputDialog(
                    title = getString(R.string.custom_gesture_duration_range),
                    initialMin = values[0].toInt(),
                    initialMax = values[1].toInt(),
                    range = MIN_GESTURE_DURATION_MS..MAX_GESTURE_DURATION_MS
                ) { min, max -> applyDurationRange(min, max) }
            }
        }

        // 像素速度模式：固定/随机
        binding.pixelSpeedModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val mode = if (checkedId == R.id.btnPixelSpeedRandom) {
                MOTION_MODE_RANDOM
            } else {
                MOTION_MODE_FIXED
            }
            preferences.edit { putInt(KEY_PIXEL_SPEED_MODE, mode) }
            updatePixelSpeedVisibility(mode, binding.speedUnifiedSwitch.isChecked)
            syncMotionConfigToService()
        }

        // 统一调整开关：开启时把上下、左右设置为同一个值/范围
        binding.speedUnifiedSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.edit { putBoolean(KEY_SPEED_UNIFIED, isChecked) }
            if (isChecked) {
                val fixedValue = binding.pixelSpeedVerticalSlider.value.toInt()
                val verticalRange = binding.pixelSpeedVerticalRangeSlider.values
                preferences.edit {
                    putInt(KEY_PIXELS_PER_SECOND_VERTICAL, fixedValue)
                    putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL, fixedValue)
                    if (verticalRange.size >= 2) {
                        putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MIN, verticalRange[0].toInt())
                        putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MAX, verticalRange[1].toInt())
                        putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MIN, verticalRange[0].toInt())
                        putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MAX, verticalRange[1].toInt())
                    }
                }
                setFixedPixelSpeedValues(fixedValue)
                if (verticalRange.size >= 2) {
                    setRangeSliderValues(
                        binding.pixelSpeedUnifiedRangeSlider,
                        verticalRange[0].toInt(),
                        verticalRange[1].toInt()
                    )
                    setRangeSliderValues(
                        binding.pixelSpeedHorizontalRangeSlider,
                        verticalRange[0].toInt(),
                        verticalRange[1].toInt()
                    )
                }
                updatePixelSpeedLabels(fixedValue, fixedValue)
                updatePixelRangeLabels(
                    binding.pixelSpeedVerticalRangeSlider.values[0].toInt(),
                    binding.pixelSpeedVerticalRangeSlider.values[1].toInt(),
                    binding.pixelSpeedVerticalRangeSlider.values[0].toInt(),
                    binding.pixelSpeedVerticalRangeSlider.values[1].toInt()
                )
            }
            updatePixelSpeedVisibility(
                if (binding.pixelSpeedModeGroup.checkedButtonId == R.id.btnPixelSpeedRandom) {
                    MOTION_MODE_RANDOM
                } else {
                    MOTION_MODE_FIXED
                },
                isChecked
            )
            syncMotionConfigToService()
        }

        // 固定统一像素速度
        binding.pixelSpeedUnifiedSlider.addOnChangeListener { _, value, fromUser ->
            val speed = value.toInt()
            binding.pixelSpeedUnifiedValueText.text =
                getString(R.string.pixel_speed_format, speed)
            if (fromUser) {
                preferences.edit {
                    putInt(KEY_PIXELS_PER_SECOND_VERTICAL, speed)
                    putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL, speed)
                }
                binding.pixelSpeedVerticalSlider.value = speed.toFloat()
                binding.pixelSpeedHorizontalSlider.value = speed.toFloat()
                updatePixelSpeedLabels(speed, speed)
            }
        }
        binding.pixelSpeedUnifiedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                syncMotionConfigToService()
            }
        })
        binding.pixelSpeedUnifiedValueText.setOnClickListener {
            showNumberInputDialog(
                title = getString(R.string.custom_pixel_speed),
                initial = binding.pixelSpeedUnifiedSlider.value.toInt(),
                range = MIN_PIXELS_PER_SECOND..MAX_PIXELS_PER_SECOND
            ) { value -> applyUnifiedPixelSpeed(value) }
        }

        // 固定上下像素速度
        binding.pixelSpeedVerticalSlider.addOnChangeListener { _, value, fromUser ->
            val speed = value.toInt()
            binding.pixelSpeedVerticalValueText.text =
                getString(R.string.pixel_speed_format, speed)
            if (fromUser) {
                preferences.edit { putInt(KEY_PIXELS_PER_SECOND_VERTICAL, speed) }
            }
        }
        binding.pixelSpeedVerticalSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                syncMotionConfigToService()
            }
        })
        binding.pixelSpeedVerticalValueText.setOnClickListener {
            showNumberInputDialog(
                title = getString(R.string.custom_pixel_speed),
                initial = binding.pixelSpeedVerticalSlider.value.toInt(),
                range = MIN_PIXELS_PER_SECOND..MAX_PIXELS_PER_SECOND
            ) { value -> applyPixelSpeed(value, vertical = true) }
        }

        // 固定左右像素速度
        binding.pixelSpeedHorizontalSlider.addOnChangeListener { _, value, fromUser ->
            val speed = value.toInt()
            binding.pixelSpeedHorizontalValueText.text =
                getString(R.string.pixel_speed_format, speed)
            if (fromUser) {
                preferences.edit { putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL, speed) }
            }
        }
        binding.pixelSpeedHorizontalSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                syncMotionConfigToService()
            }
        })
        binding.pixelSpeedHorizontalValueText.setOnClickListener {
            showNumberInputDialog(
                title = getString(R.string.custom_pixel_speed),
                initial = binding.pixelSpeedHorizontalSlider.value.toInt(),
                range = MIN_PIXELS_PER_SECOND..MAX_PIXELS_PER_SECOND
            ) { value -> applyPixelSpeed(value, vertical = false) }
        }

        // 随机统一像素速度范围
        binding.pixelSpeedUnifiedRangeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            if (values.size >= 2) {
                val min = values[0].toInt()
                val max = values[1].toInt()
                updatePixelRangeLabels(min, max, min, max)
                if (fromUser) {
                    preferences.edit {
                        putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MIN, min)
                        putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MAX, max)
                        putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MIN, min)
                        putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MAX, max)
                    }
                    setRangeSliderValues(binding.pixelSpeedVerticalRangeSlider, min, max)
                    setRangeSliderValues(binding.pixelSpeedHorizontalRangeSlider, min, max)
                }
            }
        }
        binding.pixelSpeedUnifiedRangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) {
                syncMotionConfigToService()
            }
        })
        binding.pixelSpeedUnifiedRangeValueText.setOnClickListener {
            val values = binding.pixelSpeedUnifiedRangeSlider.values
            if (values.size >= 2) {
                showRangeInputDialog(
                    title = getString(R.string.custom_pixel_speed_range),
                    initialMin = values[0].toInt(),
                    initialMax = values[1].toInt(),
                    range = MIN_PIXELS_PER_SECOND..MAX_PIXELS_PER_SECOND
                ) { min, max -> applyUnifiedPixelRange(min, max) }
            }
        }

        // 随机上下像素速度范围
        binding.pixelSpeedVerticalRangeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            if (values.size >= 2) {
                val min = values[0].toInt()
                val max = values[1].toInt()
                binding.pixelSpeedVerticalRangeValueText.text =
                    getString(R.string.pixel_speed_range_format, min, max)
                if (fromUser) {
                    preferences.edit {
                        putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MIN, min)
                        putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MAX, max)
                    }
                }
            }
        }
        binding.pixelSpeedVerticalRangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) {
                syncMotionConfigToService()
            }
        })
        binding.pixelSpeedVerticalRangeValueText.setOnClickListener {
            val values = binding.pixelSpeedVerticalRangeSlider.values
            if (values.size >= 2) {
                showRangeInputDialog(
                    title = getString(R.string.custom_pixel_speed_range),
                    initialMin = values[0].toInt(),
                    initialMax = values[1].toInt(),
                    range = MIN_PIXELS_PER_SECOND..MAX_PIXELS_PER_SECOND
                ) { min, max -> applyPixelRange(true, min, max) }
            }
        }

        // 随机左右像素速度范围
        binding.pixelSpeedHorizontalRangeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            if (values.size >= 2) {
                val min = values[0].toInt()
                val max = values[1].toInt()
                binding.pixelSpeedHorizontalRangeValueText.text =
                    getString(R.string.pixel_speed_range_format, min, max)
                if (fromUser) {
                    preferences.edit {
                        putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MIN, min)
                        putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MAX, max)
                    }
                }
            }
        }
        binding.pixelSpeedHorizontalRangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) = Unit
            override fun onStopTrackingTouch(slider: RangeSlider) {
                syncMotionConfigToService()
            }
        })
        binding.pixelSpeedHorizontalRangeValueText.setOnClickListener {
            val values = binding.pixelSpeedHorizontalRangeSlider.values
            if (values.size >= 2) {
                showRangeInputDialog(
                    title = getString(R.string.custom_pixel_speed_range),
                    initialMin = values[0].toInt(),
                    initialMax = values[1].toInt(),
                    range = MIN_PIXELS_PER_SECOND..MAX_PIXELS_PER_SECOND
                ) { min, max -> applyPixelRange(false, min, max) }
            }
        }
    }

    /* 应用自定义手势持续时间 */
    private fun applyGestureDuration(value: Int) {
        preferences.edit { putInt(KEY_GESTURE_DURATION, value) }
        if (value > binding.gestureDurationSlider.valueTo) {
            binding.gestureDurationSlider.valueTo = value.toFloat()
        }
        binding.gestureDurationSlider.value = value.toFloat()
        binding.gestureDurationValueText.text =
            getString(R.string.gesture_duration_format, value)
        syncMotionConfigToService()
    }

    /* 应用自定义持续时间范围 */
    private fun applyDurationRange(min: Int, max: Int) {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        preferences.edit {
            putInt(KEY_GESTURE_DURATION_MIN, lo)
            putInt(KEY_GESTURE_DURATION_MAX, hi)
        }
        setRangeSliderValues(binding.gestureDurationRangeSlider, lo, hi)
        updateDurationRangeLabel(lo, hi)
        syncMotionConfigToService()
    }

    /* 应用自定义像素速度(统一调整) */
    private fun applyUnifiedPixelSpeed(value: Int) {
        preferences.edit {
            putInt(KEY_PIXELS_PER_SECOND_VERTICAL, value)
            putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL, value)
        }
        setFixedPixelSpeedValues(value)
        updatePixelSpeedLabels(value, value)
        syncMotionConfigToService()
    }

    /* 应用自定义像素速度(上下或左右) */
    private fun applyPixelSpeed(value: Int, vertical: Boolean) {
        val slider = if (vertical) {
            preferences.edit { putInt(KEY_PIXELS_PER_SECOND_VERTICAL, value) }
            binding.pixelSpeedVerticalSlider
        } else {
            preferences.edit { putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL, value) }
            binding.pixelSpeedHorizontalSlider
        }
        if (value > slider.valueTo) {
            slider.valueTo = value.toFloat()
        }
        slider.value = value.toFloat()
        updatePixelSpeedLabels(
            binding.pixelSpeedVerticalSlider.value.toInt(),
            binding.pixelSpeedHorizontalSlider.value.toInt()
        )
        syncMotionConfigToService()
    }

    /* 应用自定义统一像素速度范围 */
    private fun applyUnifiedPixelRange(min: Int, max: Int) {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        preferences.edit {
            putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MIN, lo)
            putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MAX, hi)
            putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MIN, lo)
            putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MAX, hi)
        }
        setRangeSliderValues(binding.pixelSpeedUnifiedRangeSlider, lo, hi)
        setRangeSliderValues(binding.pixelSpeedVerticalRangeSlider, lo, hi)
        setRangeSliderValues(binding.pixelSpeedHorizontalRangeSlider, lo, hi)
        updatePixelRangeLabels(lo, hi, lo, hi)
        syncMotionConfigToService()
    }

    /* 应用自定义上下/左右像素速度范围 */
    private fun applyPixelRange(vertical: Boolean, min: Int, max: Int) {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        if (vertical) {
            preferences.edit {
                putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MIN, lo)
                putInt(KEY_PIXELS_PER_SECOND_VERTICAL_MAX, hi)
            }
            setRangeSliderValues(binding.pixelSpeedVerticalRangeSlider, lo, hi)
        } else {
            preferences.edit {
                putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MIN, lo)
                putInt(KEY_PIXELS_PER_SECOND_HORIZONTAL_MAX, hi)
            }
            setRangeSliderValues(binding.pixelSpeedHorizontalRangeSlider, lo, hi)
        }
        updatePixelRangeLabels(
            binding.pixelSpeedVerticalRangeSlider.values[0].toInt(),
            binding.pixelSpeedVerticalRangeSlider.values[1].toInt(),
            binding.pixelSpeedHorizontalRangeSlider.values[0].toInt(),
            binding.pixelSpeedHorizontalRangeSlider.values[1].toInt()
        )
        syncMotionConfigToService()
    }

    /* 设置固定像素速度的三个滑块值 */
    private fun setFixedPixelSpeedValues(value: Int) {
        if (value > binding.pixelSpeedUnifiedSlider.valueTo) {
            binding.pixelSpeedUnifiedSlider.valueTo = value.toFloat()
        }
        if (value > binding.pixelSpeedVerticalSlider.valueTo) {
            binding.pixelSpeedVerticalSlider.valueTo = value.toFloat()
        }
        if (value > binding.pixelSpeedHorizontalSlider.valueTo) {
            binding.pixelSpeedHorizontalSlider.valueTo = value.toFloat()
        }
        binding.pixelSpeedUnifiedSlider.value = value.toFloat()
        binding.pixelSpeedVerticalSlider.value = value.toFloat()
        binding.pixelSpeedHorizontalSlider.value = value.toFloat()
    }

    /* 安全设置 RangeSlider 的范围和值 */
    private fun setRangeSliderValues(slider: RangeSlider, min: Int, max: Int) {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        if (hi > slider.valueTo) {
            slider.valueTo = hi.toFloat()
        }
        slider.values = listOf(lo.toFloat(), hi.toFloat()).sorted()
    }

    /* 更新持续时间范围数值文本 */
    private fun updateDurationRangeLabel(min: Int, max: Int) {
        binding.gestureDurationRangeValueText.text =
            getString(R.string.duration_range_format, minOf(min, max), maxOf(min, max))
    }

    /* 更新像素速度数值文本 */
    private fun updatePixelSpeedLabels(vertical: Int, horizontal: Int) {
        binding.pixelSpeedUnifiedValueText.text = getString(R.string.pixel_speed_format, vertical)
        binding.pixelSpeedVerticalValueText.text = getString(R.string.pixel_speed_format, vertical)
        binding.pixelSpeedHorizontalValueText.text = getString(R.string.pixel_speed_format, horizontal)
    }

    /* 更新像素速度范围数值文本 */
    private fun updatePixelRangeLabels(
        verticalMin: Int,
        verticalMax: Int,
        horizontalMin: Int,
        horizontalMax: Int
    ) {
        val vLo = minOf(verticalMin, verticalMax)
        val vHi = maxOf(verticalMin, verticalMax)
        val hLo = minOf(horizontalMin, horizontalMax)
        val hHi = maxOf(horizontalMin, horizontalMax)
        binding.pixelSpeedUnifiedRangeValueText.text =
            getString(R.string.pixel_speed_range_format, vLo, vHi)
        binding.pixelSpeedVerticalRangeValueText.text =
            getString(R.string.pixel_speed_range_format, vLo, vHi)
        binding.pixelSpeedHorizontalRangeValueText.text =
            getString(R.string.pixel_speed_range_format, hLo, hHi)
    }

    /* 根据持续时间模式切换固定/随机面板 */
    private fun updateDurationVisibility(mode: Int) {
        binding.gestureDurationFixedContainer.isVisible = mode == MOTION_MODE_FIXED
        binding.gestureDurationRandomContainer.isVisible = mode == MOTION_MODE_RANDOM
    }

    /* 根据模式和统一开关切换像素速度面板 */
    private fun updatePixelSpeedVisibility(mode: Int, unified: Boolean) {
        val fixed = mode == MOTION_MODE_FIXED
        binding.pixelSpeedUnifiedContainer.isVisible = fixed && unified
        binding.pixelSpeedVerticalContainer.isVisible = fixed && !unified
        binding.pixelSpeedHorizontalContainer.isVisible = fixed && !unified
        binding.pixelSpeedUnifiedRangeContainer.isVisible = !fixed && unified
        binding.pixelSpeedVerticalRangeContainer.isVisible = !fixed && !unified
        binding.pixelSpeedHorizontalRangeContainer.isVisible = !fixed && !unified
    }

    /* 同步运动参数到自动滑动服务 */
    private fun syncMotionConfigToService() {
        AutoSlideService.getInstance()?.updateMotionConfig(getSlideConfig())
    }

    /**
     * 显示单个整数输入对话框
     *
     * @param title 对话框标题
     * @param initial 初始值
     * @param range 允许范围
     * @param onValue 确认回调
     */
    private fun showNumberInputDialog(
        title: String,
        initial: Int,
        range: IntRange,
        onValue: (Int) -> Unit
    ) {
        val textInputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = title
        }
        val editText = TextInputEditText(textInputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial.toString())
            setSelection(text?.length ?: 0)
        }
        textInputLayout.addView(editText)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, padding / 3)
            addView(textInputLayout)
        }
        AlertDialog.Builder(this).setTitle(title).setView(container)
            .setPositiveButton(AndroidR.string.ok) { _, _ ->
                val value = editText.text.toString().toIntOrNull()
                if (value != null && value in range) {
                    onValue(value)
                } else {
                    Toast.makeText(this, R.string.invalid_input_number, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * 显示两个整数输入对话框(用于范围设置)
     *
     * @param title 对话框标题
     * @param initialMin 初始最小值
     * @param initialMax 初始最大值
     * @param range 允许范围
     * @param onValue 确认回调(最小值, 最大值)
     */
    private fun showRangeInputDialog(
        title: String,
        initialMin: Int,
        initialMax: Int,
        range: IntRange,
        onValue: (Int, Int) -> Unit
    ) {
        val minInputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.min_value)
        }
        val minEditText = TextInputEditText(minInputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialMin.toString())
            setSelection(text?.length ?: 0)
        }
        minInputLayout.addView(minEditText)
        val maxInputLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.max_value)
        }
        val maxEditText = TextInputEditText(maxInputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialMax.toString())
            setSelection(text?.length ?: 0)
        }
        maxInputLayout.addView(maxEditText)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 3)
            addView(minInputLayout)
            addView(
                maxInputLayout,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            )
        }
        AlertDialog.Builder(this).setTitle(title).setView(container)
            .setPositiveButton(AndroidR.string.ok) { _, _ ->
                val min = minEditText.text.toString().toIntOrNull()
                val max = maxEditText.text.toString().toIntOrNull()
                if (min == null || max == null || min !in range || max !in range) {
                    Toast.makeText(this, R.string.invalid_input_number, Toast.LENGTH_SHORT).show()
                } else if (min > max) {
                    Toast.makeText(this, R.string.invalid_range, Toast.LENGTH_SHORT).show()
                } else {
                    onValue(min, max)
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /* 绑定悬浮窗底板透明度滑块事件 */
    private fun setupTransparencyControl() {
        binding.transparencySlider.addOnChangeListener { _, value, fromUser ->
            val progress = value.toInt()
            binding.transparencyValueText.text = getString(R.string.transparency_percentage_format, progress)
            if (fromUser) {
                preferences.edit { putInt(KEY_FLOATING_TRANSPARENCY, progress) }
                SlideEventHub.sendEvent(SlideEvent.FloatingTransparencyChanged(progress))
            }
        }
        binding.transparencyValueText.setOnClickListener {
            showNumberInputDialog(
                title = getString(R.string.custom_transparency),
                initial = binding.transparencySlider.value.toInt(),
                range = 0..100
            ) { value ->
                preferences.edit { putInt(KEY_FLOATING_TRANSPARENCY, value) }
                binding.transparencySlider.value = value.toFloat()
                binding.transparencyValueText.text =
                    getString(R.string.transparency_percentage_format, value)
                SlideEventHub.sendEvent(SlideEvent.FloatingTransparencyChanged(value))
            }
        }
    }

    /* 处理⌈无障碍服务权限⌋开关打开动作 */
    private fun onAccessibilityServicePermissionSwitchEnabled() {
        if (tryEnableAccessibilityViaSecureSettings()) {
            return
        }
        // 展示⌈无障碍服务权限⌋选项弹窗
        showAccessibilityServicePermissionOptionDialog()
    }

    /* 处理⌈无障碍服务权限⌋开关关闭动作 */
    private fun onAccessibilityServicePermissionSwitchDisabled() {
        lifecycleScope.launch(ioDispatcher) {
            val isEnabled = isAccessibilityServicePermissionEnabled()
            withContext(mainDispatcher) {
                if (!isEnabled) {
                    return@withContext
                }
                // 有⌈写入安全设置权限⌋时直接关闭⌈无障碍服务权限⌋
                if (hasWriteSecureSettingsPermission()) {
                    changeAccessibilityServicePermissionState(enable = false)
                    Toast.makeText(this@MainActivity, R.string.accessibility_service_disabled, Toast.LENGTH_SHORT)
                        .show()
                    return@withContext
                }
                // 打开⌈无障碍⌋设置页
                openAppAccessibilitySettings()
            }
        }
    }

    /* 展示⌈无障碍服务权限⌋选项弹窗 */
    private fun showAccessibilityServicePermissionOptionDialog() = with(AlertDialog.Builder(this)) {
        // 选项数组
        val options = arrayOf(
            getString(R.string.manual_enable),
            getString(R.string.shizuku_authorization),
            getString(R.string.adb_authorization)
        )
        // 设置标题
        setTitle(R.string.choose_enable_method)
        // 设置选项监听器
        setItems(options) { _, which ->
            when (which) {
                OPTION_MANUAL -> openAppAccessibilitySettings()
                OPTION_SHIZUKU -> handleShizukuAuthorization(
                    onGranted = { grantPermissionViaShizuku() },
                    onFailed = { updateAccessibilitySwitchState(false) })
                OPTION_ADB -> {
                    showAdbCommandDialog()
                    updateAccessibilitySwitchState(false)
                }
            }
        }
        // 设置取消监听器
        setOnCancelListener { updateAccessibilitySwitchState(false) }
        // 展示对话框
        show()
    }

    /* 处理Shizuku授权(通用) */
    private fun handleShizukuAuthorization(onGranted: () -> Unit, onFailed: () -> Unit) {
        // 检查Shizuku是否运行
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_SHORT).show()
            onFailed()
            return
        }
        // 检查Shizuku权限是否已授权
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            pendingShizukuOnGranted = onGranted
            pendingShizukuOnFailed = onFailed
            try {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            } catch (exception: IllegalStateException) {
                Log.e(TAG, "Failed to request Shizuku permission", exception)
                Toast.makeText(this, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
                onFailed()
            }
        }
    }

    /* 通过Shizuku授权⌈写入安全设置⌋权限 */
    private fun grantPermissionViaShizuku() {
        runShizukuCommand(
            command = arrayOf(
                "/system/bin/pm", "grant", packageName, Manifest.permission.WRITE_SECURE_SETTINGS
            ), onSuccess = {
                binding.accessibilityServicePermissionSwitch.post {
                    if (!tryEnableAccessibilityViaSecureSettings()) {
                        Log.e(TAG, "WRITE_SECURE_SETTINGS still missing after Shizuku grant")
                        Toast.makeText(this, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                        updateAccessibilitySwitchState(false)
                    }
                }
            }, onFailure = { updateAccessibilitySwitchState(false) })
    }

    /**
     * 执行Shizuku命令
     *
     * @param command 要执行的命令数组
     * @param onSuccess 命令执行成功时的回调
     * @param onFailure 命令执行失败时的回调
     */
    private fun runShizukuCommand(command: Array<String>, onSuccess: () -> Unit, onFailure: () -> Unit) {
        lifecycleScope.launch(ioDispatcher) {
            val result = runCatching { executeShizukuCommand(*command) }
            withContext(mainDispatcher) {
                if (!isFinishing && !isDestroyed) {
                    result.onSuccess { res ->
                        if (res.exitCode != 0) {
                            Log.e(
                                TAG,
                                "Shizuku command failed: cmd=${command.joinToString(" ")}, exitCode=${res.exitCode}, stdout=${res.stdout}, stderr=${res.stderr}"
                            )
                            Toast.makeText(this@MainActivity, R.string.shizuku_auth_failed, Toast.LENGTH_SHORT).show()
                            onFailure()
                        } else {
                            onSuccess()
                        }
                    }.onFailure { exception ->
                        Log.e(
                            TAG, "Shizuku command execution failed: cmd=${command.joinToString(" ")}", exception
                        )
                        Toast.makeText(this@MainActivity, R.string.shizuku_exception, Toast.LENGTH_SHORT).show()
                        onFailure()
                    }
                }
            }
        }
    }

    /**
     * 执行Shizuku命令
     *
     * @param command 要执行的命令数组
     * @return 命令执行结果
     */
    private fun executeShizukuCommand(vararg command: String): ShizukuCommandResult {
        val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply {
            isAccessible = true
        }
        val process = newProcessMethod.invoke(null, command, null, null) as ShizukuRemoteProcess
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
        val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
        process.destroy()
        return ShizukuCommandResult(exitCode, stdout, stderr)
    }

    /* 检查是否具备⌈写入安全设置⌋权限 */
    private fun hasWriteSecureSettingsPermission() =
        checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * 尝试通过写入安全设置权限直接开启无障碍服务
     *
     * @param toastResId 成功时展示的提示文案资源ID
     * @return 是否拥有权限并已触发开启
     */
    private fun tryEnableAccessibilityViaSecureSettings(
        toastResId: Int = R.string.accessibility_service_enabled
    ): Boolean {
        if (!hasWriteSecureSettingsPermission()) {
            return false
        }
        lifecycleScope.launch {
            changeAccessibilityServicePermissionState(enable = true)
            Toast.makeText(this@MainActivity, toastResId, Toast.LENGTH_SHORT).show()
        }
        return true
    }


    /**
     * 更新⌈无障碍服务权限⌋状态
     *
     * @param enable 是否启用⌈无障碍服务权限⌋
     */
    private suspend fun changeAccessibilityServicePermissionState(enable: Boolean) = withContext(ioDispatcher) {
        // 获取当前已启用的无障碍服务列表
        val targetComponent = ComponentName(this@MainActivity, AutoSlideService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.split(":")?.filter { it.isNotBlank() }?.mapNotNull {
            ComponentName.unflattenFromString(it.trim())
        }?.toMutableSet() ?: mutableSetOf()
        // 更新无障碍服务列表
        if (enable) {
            enabledServices.add(targetComponent)
            Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } else {
            enabledServices.remove(targetComponent)
            if (enabledServices.isEmpty()) {
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }
        }
        // 更新无障碍服务列表字符串
        val newSettingString = enabledServices.joinToString(":") { it.flattenToString() }
        Settings.Secure.putString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newSettingString
        )
        val finalAccessibilityEnabled = isAccessibilityServicePermissionEnabled()
        // 更新无障碍服务权限开关状态并应用无动画过渡
        withContext(mainDispatcher) {
            if (!isFinishing && !isDestroyed) {
                updateAccessibilitySwitchState(finalAccessibilityEnabled)
            }
        }
    }

    /* 显示ADB授权所需的命令弹窗 */
    private fun showAdbCommandDialog() {
        val command = "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
        val content = createAdbDialogContent(command)
        // 展示ADB命令弹窗
        AlertDialog.Builder(this).setTitle(R.string.authorize_via_adb).setView(content)
            .setPositiveButton(R.string.copy_command) { _, _ ->
                copyToClipboard(command)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, R.string.command_copied, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * 从主题中获取颜色
     *
     * @param attrId 属性ID
     * @return 颜色
     */
    private fun getColorFromAttr(attrId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrId, typedValue, true)
        return typedValue.data
    }

    /**
     * 构造ADB指令展示视图
     *
     * @param command 需要展示的ADB命令
     * @return 包含ADB命令展示的线性布局视图
     */
    private fun createAdbDialogContent(command: String): LinearLayout {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val codePadding = (12 * resources.displayMetrics.density).toInt()
        val textColorPrimary = getColorFromAttr(AndroidR.attr.textColorPrimary)
        val textColorSecondary = getColorFromAttr(AndroidR.attr.textColorSecondary)
        val colorSurfaceVariant = getColorFromAttr(MaterialR.attr.colorSurfaceVariant)
        // 提示用户连接电脑并在终端运行以下ADB命令
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.adb_command_instruction)
                    textSize = 16f
                    setTextColor(textColorPrimary)
                })
            // 展示ADB命令
            addView(
                TextView(this@MainActivity).apply {
                    text = command
                    typeface = Typeface.MONOSPACE
                    setBackgroundColor(colorSurfaceVariant)
                    setPadding(codePadding, codePadding, codePadding, codePadding)
                    textSize = 13f
                    setTextColor(textColorSecondary)
                    setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
                    }
                })
        }
    }

    /**
     * 把文本写入系统剪贴板
     *
     * @param text 需要复制的文本
     */
    private fun copyToClipboard(text: String) {
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = ClipData.newPlainText("ADB Command", text)
        clipboardManager.setPrimaryClip(clipData)
    }

    /* 打开系统⌈无障碍⌋设置页并定位到当前服务 */
    private fun openAppAccessibilitySettings() {
        val serviceName = "$packageName/${AutoSlideService::class.java.canonicalName}"
        val bundle = Bundle().apply {
            putString(":settings:fragment_args_key", serviceName)
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(":settings:fragment_args_key", serviceName)
            putExtra(":settings:show_fragment_args", bundle)
        }
        startActivity(intent)
    }

    /**
     * 确保⌈无障碍服务权限⌋已启用
     *
     * @return 无障碍服务权限是否已启用
     */
    private fun ensureAccessibilityPermission(): Boolean {
        if (isAccessibilityServicePermissionEnabled()) {
            return true
        }
        if (tryEnableAccessibilityViaSecureSettings(R.string.accessibility_service_auto_enabled)) {
            return true
        }
        AlertDialog.Builder(this).setTitle(R.string.permission_required)
            .setMessage(R.string.accessibility_service_description).setPositiveButton(R.string.go_to_open) { _, _ ->
                showAccessibilityServicePermissionOptionDialog()
            }.setNegativeButton(R.string.cancel, null).show()
        return false
    }

    /**
     * 确保⌈悬浮窗权限⌋已启用
     *
     * @return 悬浮窗权限是否已启用
     */
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) {
            return true
        }
        AlertDialog.Builder(this).setTitle(R.string.permission_required)
            .setMessage(R.string.overlay_permission_required).setPositiveButton(R.string.go_to_open) { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }.setNegativeButton(R.string.cancel, null).show()
        return false
    }

    /* 绑定⌈开始⌋按钮点击事件并执行运行前权限校验 */
    private fun setupStartButton() {
        // 绑定⌈开始⌋按钮点击事件
        binding.startButton.setOnClickListener {
            if (!ensureAccessibilityPermission()) return@setOnClickListener
            if (!ensureOverlayPermission()) return@setOnClickListener
            // 校验通过后启动悬浮窗服务并把应用退到后台
            val floatingIntent = Intent(this, FloatingWindowService::class.java)
            startService(floatingIntent)
            moveTaskToBack(true)
        }
    }

    /* 绑定主页面和设置子页面切换 */
    private fun setupPageNavigation() {
        binding.mainTabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val permissionsSelected = checkedId == R.id.btnTabPermissions
            val settingsSelected = checkedId == R.id.btnTabSettings
            binding.permissionsPage.isVisible = permissionsSelected
            binding.settingsPage.isVisible = settingsSelected
            binding.aboutPage.isVisible = checkedId == R.id.btnTabAbout
            when {
                permissionsSelected -> binding.permissionsPage.scrollTo(0, 0)
                settingsSelected -> binding.durationPage.scrollTo(0, 0)
                else -> binding.aboutPage.scrollTo(0, 0)
            }
        }

        binding.settingsTabGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            binding.durationPage.isVisible = checkedId == R.id.btnSettingsDuration
            binding.speedPage.isVisible = checkedId == R.id.btnSettingsSpeed
            binding.pausePage.isVisible = checkedId == R.id.btnSettingsPause
            when (checkedId) {
                R.id.btnSettingsSpeed -> binding.speedPage.scrollTo(0, 0)
                R.id.btnSettingsPause -> binding.pausePage.scrollTo(0, 0)
                else -> binding.durationPage.scrollTo(0, 0)
            }
        }
    }

    /* 显示应用版本信息 */
    private fun setupAboutPage() {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        binding.aboutVersionText.text = getString(R.string.about_version_format, versionName)
    }

    /**
     * 给数值文本添加下划线并启用点击，提示可自定义输入
     *
     * @param views 需要处理的文本控件
     */
    private fun markValueTextClickable(vararg views: TextView) {
        views.forEach { view ->
            view.paintFlags = view.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            view.isClickable = true
        }
    }

    /**
     * 更新⌈停顿时间⌋面板的可见性
     *
     * @param pauseMode 停顿模式
     */
    private fun updatePauseTimeVisibility(pauseMode: Int) {
        binding.pauseTimeContainer.isVisible = pauseMode != PAUSE_MODE_NONE
        if (pauseMode == PAUSE_MODE_FIXED) {
            binding.pauseTimeLabel.setText(R.string.pause_time)
            binding.pauseTimeSlider.isVisible = true
            binding.randomPauseTimeSlider.isVisible = false
            // 启用下划线及点击事件
            binding.pauseTimeValueText.paintFlags = binding.pauseTimeValueText.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            binding.pauseTimeValueText.isClickable = true
            binding.pauseTimeValueText.text = String.format(Locale.ROOT, "%d", binding.pauseTimeSlider.value.toInt())
        } else if (pauseMode == PAUSE_MODE_RANDOM) {
            binding.pauseTimeLabel.setText(R.string.random_pause_time_range)
            binding.pauseTimeSlider.isVisible = false
            binding.randomPauseTimeSlider.isVisible = true
            // 启用下划线及点击事件，允许自定义范围
            binding.pauseTimeValueText.paintFlags =
                binding.pauseTimeValueText.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            binding.pauseTimeValueText.isClickable = true
            val values = binding.randomPauseTimeSlider.values
            if (values.size >= 2) {
                val min = values[0].toInt()
                val max = values[1].toInt()
                binding.pauseTimeValueText.text = getString(R.string.pause_time_range_format, min, max)
            }
        }
    }
}
