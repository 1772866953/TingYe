package com.tingye.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "listening")

class ListeningPreferences(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val rewindKey = intPreferencesKey("rewind")
    private val forwardKey = intPreferencesKey("forward")
    private val resumeBackKey = intPreferencesKey("resume_back")
    private val introKey = intPreferencesKey("skip_intro")
    private val outroKey = intPreferencesKey("skip_outro")
    private val transitionSpeedKey = floatPreferencesKey("transition_speed")
    private val wallpaperKey = stringPreferencesKey("wallpaper")
    private val wallpaperUriKey = stringPreferencesKey("wallpaper_uri")
    private val wallpaperBlurKey = floatPreferencesKey("wallpaper_blur")
    private val defaultSpeedKey = floatPreferencesKey("default_speed")
    private val hapticStrengthKey = intPreferencesKey("haptic_strength")
    private val headphonePreviousKey = stringPreferencesKey("headphone_previous")
    private val headphoneNextKey = stringPreferencesKey("headphone_next")
    private val volumeLevelingKey = booleanPreferencesKey("volume_leveling")
    private val progressUndoSecondsKey = intPreferencesKey("progress_undo_seconds")
    val values = context.dataStore.data.map { p ->
        ListeningSettings(p[themeKey] ?: "system", p[rewindKey] ?: 15, p[forwardKey] ?: 30,
            p[resumeBackKey] ?: 3, p[introKey] ?: 0, p[outroKey] ?: 0,
            (p[transitionSpeedKey] ?: 1f).coerceIn(0.5f, 2f), p[wallpaperKey] ?: "sage", p[wallpaperUriKey],
            (p[wallpaperBlurKey] ?: 0f).coerceIn(0f, 24f),
            (p[defaultSpeedKey] ?: 1f).coerceIn(0.5f, 3f),
            (p[hapticStrengthKey] ?: 2).coerceIn(0, 3),
            validHeadphoneAction(p[headphonePreviousKey]), validHeadphoneAction(p[headphoneNextKey]),
            p[volumeLevelingKey] ?: false, (p[progressUndoSecondsKey] ?: 12).coerceIn(1, 12))
    }
    suspend fun theme(value: String) { context.dataStore.edit { it[themeKey] = value } }
    suspend fun volumeLeveling(value: Boolean) { context.dataStore.edit { it[volumeLevelingKey] = value } }
    suspend fun progressUndoSeconds(value: Int) { context.dataStore.edit { it[progressUndoSecondsKey] = value.coerceIn(1, 12) } }
    suspend fun skips(back: Int, forward: Int) { context.dataStore.edit { it[rewindKey] = back; it[forwardKey] = forward } }
    suspend fun resumeBack(value: Int) { context.dataStore.edit { it[resumeBackKey] = value } }
    suspend fun skipEdges(intro: Int, outro: Int) { context.dataStore.edit { it[introKey] = intro; it[outroKey] = outro } }
    suspend fun transitionSpeed(value: Float) { context.dataStore.edit { it[transitionSpeedKey] = value.coerceIn(0.5f, 2f) } }
    suspend fun wallpaper(value: String) { context.dataStore.edit { it[wallpaperKey] = value } }
    suspend fun customWallpaper(uri: String) { context.dataStore.edit { it[wallpaperUriKey] = uri; it[wallpaperKey] = "custom" } }
    suspend fun wallpaperBlur(value: Float) { context.dataStore.edit { it[wallpaperBlurKey] = value.coerceIn(0f, 24f) } }
    suspend fun defaultSpeed(value: Float) { context.dataStore.edit { it[defaultSpeedKey] = value.coerceIn(0.5f, 3f) } }
    suspend fun hapticStrength(value: Int) { context.dataStore.edit { it[hapticStrengthKey] = value.coerceIn(0, 3) } }
    suspend fun headphoneAction(previous: Boolean, value: String) { context.dataStore.edit {
        it[if (previous) headphonePreviousKey else headphoneNextKey] = validHeadphoneAction(value)
    } }
    suspend fun restore(settings: ListeningSettings) { context.dataStore.edit { p ->
        p[themeKey] = settings.theme; p[rewindKey] = settings.rewind; p[forwardKey] = settings.forward
        p[resumeBackKey] = settings.resumeBack; p[introKey] = settings.intro; p[outroKey] = settings.outro
        p[transitionSpeedKey] = settings.transitionSpeed; p[wallpaperKey] = settings.wallpaper
        if (settings.wallpaperUri != null) p[wallpaperUriKey] = settings.wallpaperUri else p.remove(wallpaperUriKey)
        p[wallpaperBlurKey] = settings.wallpaperBlur; p[defaultSpeedKey] = settings.defaultSpeed
        p[hapticStrengthKey] = settings.hapticStrength.coerceIn(0, 3)
        p[headphonePreviousKey] = validHeadphoneAction(settings.headphonePrevious)
        p[headphoneNextKey] = validHeadphoneAction(settings.headphoneNext)
        p[volumeLevelingKey] = settings.volumeLeveling
        p[progressUndoSecondsKey] = settings.progressUndoSeconds.coerceIn(1, 12)
    } }
}

data class ListeningSettings(val theme: String = "system", val rewind: Int = 15, val forward: Int = 30,
    val resumeBack: Int = 3, val intro: Int = 0, val outro: Int = 0,
    val transitionSpeed: Float = 1f, val wallpaper: String = "sage", val wallpaperUri: String? = null,
    val wallpaperBlur: Float = 0f, val defaultSpeed: Float = 1f, val hapticStrength: Int = 2,
    val headphonePrevious: String = "chapter", val headphoneNext: String = "chapter",
    val volumeLeveling: Boolean = false, val progressUndoSeconds: Int = 12)

fun validHeadphoneAction(value: String?): String = value?.takeIf { it in setOf("chapter", "rewind", "forward", "none") } ?: "chapter"
