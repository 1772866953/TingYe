package com.tingye.app.reader

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.readerStore by preferencesDataStore(name = "reader")
data class ReaderStyle(val theme: String = "eye", val font: String = "sans", val fontSize: Float = 20f,
    val spacing: Float = 1.65f, val margin: Float = 22f, val animation: String = "slide", val fontPath: String? = null)
class ReaderPreferences(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val font = stringPreferencesKey("font")
    private val size = floatPreferencesKey("font_size")
    private val spacing = floatPreferencesKey("spacing")
    private val margin = floatPreferencesKey("margin")
    private val animation = stringPreferencesKey("animation")
    private val fontPath = stringPreferencesKey("font_path")
    val values = context.readerStore.data.map { p -> ReaderStyle(p[theme] ?: "eye", p[font] ?: "sans",
        (p[size] ?: 20f).coerceIn(14f, 36f), (p[spacing] ?: 1.65f).coerceIn(1.2f, 2.2f),
        (p[margin] ?: 22f).coerceIn(12f, 42f), p[animation] ?: "slide", p[fontPath]) }
    suspend fun save(style: ReaderStyle) { context.readerStore.edit {
        it[theme] = style.theme; it[font] = style.font; it[size] = style.fontSize.coerceIn(14f,36f)
        it[spacing] = style.spacing.coerceIn(1.2f,2.2f); it[margin] = style.margin.coerceIn(12f,42f)
        it[animation] = style.animation; style.fontPath?.let { path -> it[fontPath] = path }
    } }
}
