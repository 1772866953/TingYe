package com.tingye.app.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.tingye.app.GlassSheet
import com.tingye.app.SheetDismiss

@Composable internal fun ReaderSheet(onDismiss:()->Unit,content:@Composable ColumnScope.(SheetDismiss)->Unit){
    GlassSheet(onDismiss,inReader=true,content=content)
}
