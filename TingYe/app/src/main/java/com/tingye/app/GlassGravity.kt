package com.tingye.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal val LocalGlassLighting=staticCompositionLocalOf<State<GlassLightDirection>>{mutableStateOf(GlassLightDirection())}

@Composable internal fun rememberGravityLighting():State<GlassLightDirection>{
    val context=LocalContext.current.applicationContext
    val view=LocalView.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val light=remember{mutableStateOf(GlassLightDirection())}
    DisposableEffect(context,view,lifecycle){
        val manager=context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor=manager.getDefaultSensor(Sensor.TYPE_GRAVITY)?:manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val response=GlassGravityMotion()
        val listener=object:SensorEventListener {
            override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
            override fun onSensorChanged(event:SensorEvent){
                if(event.values.size<3)return
                if(response.sample(event.values[0],event.values[1],event.values[2],view.display?.rotation?:0,event.timestamp))
                    light.value=response.direction
            }
        }
        var registered=false
        fun start(){if(!registered && sensor!=null)registered=manager.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_UI)}
        fun stop(){if(registered){manager.unregisterListener(listener);registered=false}}
        val observer=LifecycleEventObserver{_,event->when(event){
            Lifecycle.Event.ON_START->start()
            Lifecycle.Event.ON_STOP->stop()
            else->Unit
        }}
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))start()
        onDispose{stop();lifecycle.removeObserver(observer)}
    }
    return light
}
