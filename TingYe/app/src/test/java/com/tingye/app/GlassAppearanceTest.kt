package com.tingye.app

import com.tingye.app.data.GlassAppearance
import com.tingye.app.data.legacyGlassAdjustment
import com.tingye.app.data.legacyGlassPercent
import org.junit.Assert.*
import org.junit.Test

class GlassAppearanceTest {
    @Test fun defaultRetainsOpticsAndStrengthensShadowTenPercent(){
        val glass=GlassAppearance()
        assertEquals(0f,glass.transparency,0f);assertEquals(0f,glass.edgeShadow,0f);assertEquals(0f,glass.refraction,0f)
        assertEquals(1f,glass.veil,.0001f)
        assertEquals(1f,glass.bend,.0001f)
        assertEquals(1.1f,glass.shadow,.0001f)
    }
    @Test fun invalidBackupValuesCannotReachGpu(){
        assertEquals(GlassAppearance(0f,-100f,100f),GlassAppearance(Float.NaN,-101f,101f).normalized())
        assertEquals(GlassAppearance(),GlassAppearance(Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NaN).normalized())
    }
    @Test fun subPercentValuesAndEndpointsArePreserved(){
        val glass=GlassAppearance(-62.125f,38.625f,-51.75f)
        assertEquals(glass,glass.normalized())
        assertEquals(0f,GlassAppearance(100f,-100f,-100f).veil,.0001f)
        assertEquals(0f,GlassAppearance(100f,-100f,-100f).shadow,.0001f)
        assertEquals(0f,GlassAppearance(100f,-100f,-100f).bend,.0001f)
        assertEquals(4f,GlassAppearance(edgeShadow=100f).shadow,.0001f)
        assertEquals(2.55f,GlassAppearance(edgeShadow=50f).shadow,.0001f)
    }
    @Test fun migrationKeepsOldDefaultsAndCustomAppearance(){
        for(default in listOf(70f,55f,50f)){
            assertEquals(0f,legacyGlassAdjustment(default,default),.0001f)
            assertEquals(-100f,legacyGlassAdjustment(0f,default),.0001f)
            assertEquals(100f,legacyGlassAdjustment(100f,default),.0001f)
            for(old in listOf(0f,12.375f,37.3f,50f,55f,70f,99.9f,100f))
                assertEquals(old,legacyGlassPercent(legacyGlassAdjustment(old,default),default),.0001f)
            assertEquals(0f,legacyGlassAdjustment(Float.NaN,default),0f)
        }
    }
}
