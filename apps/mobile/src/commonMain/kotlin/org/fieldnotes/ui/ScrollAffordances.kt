package org.fieldnotes.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

@Composable internal fun Modifier.plannerScroll():Modifier {
    val scroll=rememberScrollState()
    return visibleScrollbar(scroll).verticalScroll(scroll).padding(end=10.dp)
}

/** A persistent, viewport-sized rail makes overflow apparent before a gesture. */
@Composable internal fun Modifier.visibleScrollbar(state:ScrollState):Modifier {
    val rail=MaterialTheme.colorScheme.outlineVariant;val thumb=MaterialTheme.colorScheme.onSurfaceVariant
    return drawWithContent {
        drawContent()
        if(state.maxValue>0&&state.maxValue!=Int.MAX_VALUE) {
            val height=size.height;val length=(height*height/(height+state.maxValue)).coerceIn(28.dp.toPx().coerceAtMost(height),height)
            val top=(height-length)*state.value/state.maxValue
            drawRoundRect(rail,Offset(size.width-4.dp.toPx(),0f),Size(4.dp.toPx(),height),CornerRadius(4.dp.toPx()))
            drawRoundRect(thumb,Offset(size.width-4.dp.toPx(),top),Size(4.dp.toPx(),length),CornerRadius(4.dp.toPx()))
        }
    }
}
@Composable internal fun ScrollableColumn(modifier:Modifier=Modifier,verticalArrangement:Arrangement.Vertical=Arrangement.Top,content:@Composable ColumnScope.()->Unit) {
    val scroll=rememberScrollState()
    Column(modifier.heightIn(max=580.dp).visibleScrollbar(scroll).verticalScroll(scroll).padding(end=12.dp),verticalArrangement=verticalArrangement,content=content)
}
@Composable internal fun VisibleLazyColumn(modifier:Modifier=Modifier,contentPadding:PaddingValues=PaddingValues(0.dp),verticalArrangement:Arrangement.Vertical=Arrangement.Top,hideKeyboardOnDrag:Boolean=false,content:LazyListScope.()->Unit) {
    val state=rememberLazyListState();val rail=MaterialTheme.colorScheme.outlineVariant;val thumb=MaterialTheme.colorScheme.onSurfaceVariant
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(state,hideKeyboardOnDrag,focus,keyboard){
        if(hideKeyboardOnDrag)state.interactionSource.interactions.collect { interaction ->
            if(interaction is DragInteraction.Start){focus.clearFocus();keyboard?.hide()}
        }
    }
    LazyColumn(modifier.drawWithContent {
        drawContent()
        val layout=state.layoutInfo;val visible=layout.visibleItemsInfo
        if(visible.isNotEmpty()&&(state.canScrollForward||state.canScrollBackward)) {
            val count=layout.totalItemsCount.coerceAtLeast(1);val average=visible.sumOf { it.size }.toFloat()/visible.size
            val extent=count*average;val height=size.height;val length=(height*height/extent.coerceAtLeast(height)).coerceIn(28.dp.toPx().coerceAtMost(height),height)
            val position=(state.firstVisibleItemIndex+state.firstVisibleItemScrollOffset/average.coerceAtLeast(1f))/count
            val top=(position*height).coerceIn(0f,height-length)
            drawRoundRect(rail,Offset(size.width-4.dp.toPx(),0f),Size(4.dp.toPx(),height),CornerRadius(4.dp.toPx()))
            drawRoundRect(thumb,Offset(size.width-4.dp.toPx(),top),Size(4.dp.toPx(),length),CornerRadius(4.dp.toPx()))
        }
    }.padding(end=10.dp),state=state,contentPadding=contentPadding,verticalArrangement=verticalArrangement,content=content)
}
