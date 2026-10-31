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
    private val defaultSpeedKey = floatPreferencesKey("default_speed")
    private val hapticStrengthKey = intPreferencesKey("haptic_strength")
    private val headphonePreviousKey = stringPreferencesKey("headphone_previous")
    private val headphoneNextKey = stringPreferencesKey("headphone_next")
    private val volumeLevelingKey = booleanPreferencesKey("volume_leveling")
    private val progressUndoSecondsKey = intPreferencesKey("progress_undo_seconds")
    private val wallpaperKey=stringPreferencesKey("glass_wallpaper_uri")
    private val wallpaperBlurKey=intPreferencesKey("glass_wallpaper_blur")
    private val glassTransparencyKey=floatPreferencesKey("glass_transparency_adjustment")
    private val glassShadowKey=floatPreferencesKey("glass_edge_shadow_adjustment")
    private val glassRefractionKey=floatPreferencesKey("glass_refraction_adjustment")
    private val legacyTransparencyKey=floatPreferencesKey("glass_transparency")
    private val legacyShadowKey=floatPreferencesKey("glass_edge_shadow")
    private val legacyRefractionKey=floatPreferencesKey("glass_refraction")
    val values = context.dataStore.data.map { p ->
        ListeningSettings(p[themeKey] ?: "system", p[rewindKey] ?: 15, p[forwardKey] ?: 30,
            p[resumeBackKey] ?: 3, p[introKey] ?: 0, p[outroKey] ?: 0,
            (p[transitionSpeedKey] ?: 1f).coerceIn(0.5f, 2f),
            (p[defaultSpeedKey] ?: 1f).coerceIn(0.5f, 3f),
            (p[hapticStrengthKey] ?: 2).coerceIn(0, 3),
            validHeadphoneAction(p[headphonePreviousKey]), validHeadphoneAction(p[headphoneNextKey]),
            p[volumeLevelingKey] ?: false, (p[progressUndoSecondsKey] ?: 12).coerceIn(1, 12),
            p[wallpaperKey],(p[wallpaperBlurKey]?:28).coerceIn(0,100),
            GlassAppearance(
                p[glassTransparencyKey]?:legacyGlassAdjustment(p[legacyTransparencyKey]?:70f,70f),
                p[glassShadowKey]?:legacyGlassAdjustment(p[legacyShadowKey]?:55f,55f),
                p[glassRefractionKey]?:legacyGlassAdjustment(p[legacyRefractionKey]?:50f,50f)).normalized())
    }
    suspend fun glass(value:GlassAppearance){ val safe=value.normalized();context.dataStore.edit{
        it[glassTransparencyKey]=safe.transparency;it[glassShadowKey]=safe.edgeShadow;it[glassRefractionKey]=safe.refraction
    } }
    suspend fun wallpaper(uri:String?){context.dataStore.edit{if(uri==null)it.remove(wallpaperKey)else it[wallpaperKey]=uri}}
    suspend fun wallpaperBlur(value:Int){context.dataStore.edit{it[wallpaperBlurKey]=value.coerceIn(0,100)}}
    suspend fun theme(value: String) { context.dataStore.edit { it[themeKey] = value } }
    suspend fun volumeLeveling(value: Boolean) { context.dataStore.edit { it[volumeLevelingKey] = value } }
    suspend fun progressUndoSeconds(value: Int) { context.dataStore.edit { it[progressUndoSecondsKey] = value.coerceIn(1, 12) } }
    suspend fun skips(back: Int, forward: Int) { context.dataStore.edit { it[rewindKey] = back; it[forwardKey] = forward } }
    suspend fun resumeBack(value: Int) { context.dataStore.edit { it[resumeBackKey] = value } }
    suspend fun skipEdges(intro: Int, outro: Int) { context.dataStore.edit { it[introKey] = intro; it[outroKey] = outro } }
    suspend fun transitionSpeed(value: Float) { context.dataStore.edit { it[transitionSpeedKey] = value.coerceIn(0.5f, 2f) } }
    suspend fun defaultSpeed(value: Float) { context.dataStore.edit { it[defaultSpeedKey] = value.coerceIn(0.5f, 3f) } }
    suspend fun hapticStrength(value: Int) { context.dataStore.edit { it[hapticStrengthKey] = value.coerceIn(0, 3) } }
    suspend fun headphoneAction(previous: Boolean, value: String) { context.dataStore.edit {
        it[if (previous) headphonePreviousKey else headphoneNextKey] = validHeadphoneAction(value)
    } }
    suspend fun restore(settings: ListeningSettings) { context.dataStore.edit { p ->
        p[themeKey] = settings.theme; p[rewindKey] = settings.rewind; p[forwardKey] = settings.forward
        p[resumeBackKey] = settings.resumeBack; p[introKey] = settings.intro; p[outroKey] = settings.outro
        p[transitionSpeedKey] = settings.transitionSpeed
        p[defaultSpeedKey] = settings.defaultSpeed
        p[hapticStrengthKey] = settings.hapticStrength.coerceIn(0, 3)
        p[headphonePreviousKey] = validHeadphoneAction(settings.headphonePrevious)
        p[headphoneNextKey] = validHeadphoneAction(settings.headphoneNext)
        p[volumeLevelingKey] = settings.volumeLeveling
        p[progressUndoSecondsKey] = settings.progressUndoSeconds.coerceIn(1, 12)
        if(settings.wallpaperUri==null)p.remove(wallpaperKey)else p[wallpaperKey]=settings.wallpaperUri
        p[wallpaperBlurKey]=settings.wallpaperBlur.coerceIn(0,100)
        val glass=settings.glass.normalized()
        p[glassTransparencyKey]=glass.transparency;p[glassShadowKey]=glass.edgeShadow;p[glassRefractionKey]=glass.refraction
    } }
}

data class ListeningSettings(val theme: String = "system", val rewind: Int = 15, val forward: Int = 30,
    val resumeBack: Int = 3, val intro: Int = 0, val outro: Int = 0,
    val transitionSpeed: Float = 1f, val defaultSpeed: Float = 1f, val hapticStrength: Int = 2,
    val headphonePrevious: String = "chapter", val headphoneNext: String = "chapter",
    val volumeLeveling: Boolean = false, val progressUndoSeconds: Int = 12,
    val wallpaperUri:String?=null,val wallpaperBlur:Int=28,val glass:GlassAppearance=GlassAppearance())

fun validHeadphoneAction(value: String?): String = value?.takeIf { it in setOf("chapter", "rewind", "forward", "none") } ?: "chapter"
