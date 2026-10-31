package com.tingye.app

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Compact presets share geometry and an explicit selected state. Optical
 * material and text deform together; the touch target stays at least 48dp. */
@Composable internal fun GlassChoice(selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier,
    contentPadding:PaddingValues=PaddingValues(horizontal=8.dp,vertical=10.dp),
    content:@Composable RowScope.()->Unit){
    val interaction=remember{MutableInteractionSource()};val haptics=LocalAppHaptics.current
    val shape=RoundedCornerShape(13.dp)
    val ink=MaterialTheme.colorScheme.onSurface
    val selectedAlpha=animateFloatAsState(if(selected).10f else 0f,tween(110),label="预设选中渐变")
    TextButton(onClick={if(acceptLiquidClick(interaction))haptics.click(onClick)},
        modifier=liquidPress(modifier.heightIn(min=48.dp).semantics{this.selected=selected;role=Role.RadioButton},
            interaction,speed=LocalMotionSpeed.current,captureDrag=false)
            .liquidGlass(13.dp,interaction=interaction)
            .drawBehind{drawRoundRect(ink.copy(alpha=selectedAlpha.value),cornerRadius=CornerRadius(13.dp.toPx()))},
        shape=shape,interactionSource=interaction,contentPadding=contentPadding,
        colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurface),content=content)
}

@Composable internal fun GlassSectionLabel(title:String,subtitle:String?=null){
    Column(Modifier.padding(top=8.dp,bottom=2.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
        if(subtitle!=null)Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An action row is distinct from a preset or a primary confirmation. */
@Composable internal fun GlassActionRow(title:String,onClick:()->Unit,modifier:Modifier=Modifier,
    detail:String?=null,destructive:Boolean=false){
    MotionTextButton(onClick,modifier.fillMaxWidth().heightIn(min=52.dp)){
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
            Text(title,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,
                color=if(destructive)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if(detail!=null)Text(detail,style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Default.ChevronRight,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
