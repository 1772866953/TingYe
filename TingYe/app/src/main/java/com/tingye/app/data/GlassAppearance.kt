package com.tingye.app.data

/** Percentages are stored as floats: changing the optics never quantizes a drag. */
data class GlassAppearance(
    val transparency: Float = 0f,
    val edgeShadow: Float = 0f,
    val refraction: Float = 0f
) {
    fun normalized() = GlassAppearance(
        glassPercent(transparency), glassPercent(edgeShadow), glassPercent(refraction))
    // 0 is the existing default appearance, including its 10% stronger shadow.
    val veil: Float get() = (100f - legacyGlassPercent(transparency, 70f)) / 30f
    val shadow: Float get() = glassPercent(edgeShadow).let{if(it<=0f)legacyGlassPercent(it,55f)/50f else 1.1f+2.9f*it/100f}
    val bend: Float get() = legacyGlassPercent(refraction, 50f) / 50f
}

fun glassPercent(value: Float): Float = if (value.isFinite()) value.coerceIn(-100f, 100f) else 0f

/** Preserve earlier 0..100 preferences/backups without changing their appearance. */
fun legacyGlassAdjustment(value:Float,default:Float):Float {
    val safe=if(value.isFinite())value.coerceIn(0f,100f)else default
    return if(safe<=default)(safe-default)/default*100f else (safe-default)/(100f-default)*100f
}
fun legacyGlassPercent(adjustment:Float,default:Float):Float {
    val value=glassPercent(adjustment)/100f
    return default+value*(if(value<0f)default else 100f-default)
}
