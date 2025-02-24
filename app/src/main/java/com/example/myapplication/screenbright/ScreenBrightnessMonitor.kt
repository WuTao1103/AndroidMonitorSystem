package com.example.myapplication.screenbright


import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class ScreenBrightnessMonitor(private val context: Context) {

    // LiveData to observe screen brightness
    private val _screenBrightness = MutableLiveData<Int>()
    val screenBrightness: LiveData<Int> get() = _screenBrightness

    init {
        updateScreenBrightness()
    }

    private fun updateScreenBrightness() {
        _screenBrightness.value = getScreenBrightness(context.contentResolver)
    }

    private fun getScreenBrightness(contentResolver: ContentResolver): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            Log.e("ScreenBrightness", "Error getting screen brightness", e)
            -1
        }
    }
} 