package org.fieldnotes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val ControlShape=RoundedCornerShape(8.dp)
/** Quiet surfaces, clear selected controls and coordinated light/dark palettes. */
private val Dark = darkColorScheme(
    primary=Color(0xffa8b7ff), onPrimary=Color(0xff15245c), primaryContainer=Color(0xff2b3c69), onPrimaryContainer=Color(0xffdfe5ff),
    secondary=Color(0xffa6c8dd), onSecondary=Color(0xff173449), secondaryContainer=Color(0xff2b455b), onSecondaryContainer=Color(0xffd9eefa),
    tertiary=Color(0xffa8d8c5), onTertiary=Color(0xff173449), tertiaryContainer=Color(0xff284d43), onTertiaryContainer=Color(0xffd7f4e8),
    background=Color(0xff101722), onBackground=Color(0xffe8eef8), surface=Color(0xff1a2432), onSurface=Color(0xffe8eef8),
    surfaceVariant=Color(0xff283445), onSurfaceVariant=Color(0xffb8c6da), outline=Color(0xff7b8da8), outlineVariant=Color(0xff35465e), surfaceTint=Color(0xffa8b7ff),
    inverseSurface=Color(0xffe8eef8), inverseOnSurface=Color(0xff1d293e), inversePrimary=Color(0xff4d61c9),
    surfaceDim=Color(0xff101722), surfaceBright=Color(0xff354257), surfaceContainerLowest=Color(0xff0b111b), surfaceContainerLow=Color(0xff141e2b), surfaceContainer=Color(0xff1a2432), surfaceContainerHigh=Color(0xff253244), surfaceContainerHighest=Color(0xff2e3d52),
)
private val Paper = lightColorScheme(
    primary=Color(0xff4d61c9), onPrimary=Color.White, primaryContainer=Color(0xffe0e7ff), onPrimaryContainer=Color(0xff20336f),
    secondary=Color(0xff4d6d86), onSecondary=Color.White, secondaryContainer=Color(0xffe2edf5), onSecondaryContainer=Color(0xff244259),
    tertiary=Color(0xff427262), onTertiary=Color.White, tertiaryContainer=Color(0xffdef3e9), onTertiaryContainer=Color(0xff204d3d),
    background=Color(0xfff5f7fc), onBackground=Color(0xff1d293e), surface=Color(0xffffffff), onSurface=Color(0xff1d293e),
    surfaceVariant=Color(0xffedf1f8), onSurfaceVariant=Color(0xff62728a), outline=Color(0xff8a9ab2), outlineVariant=Color(0xffdbe2ef), surfaceTint=Color(0xff4d61c9),
    inverseSurface=Color(0xff24344c), inverseOnSurface=Color(0xfff5f7fc), inversePrimary=Color(0xffa8b7ff),
    surfaceDim=Color(0xffdce3f0), surfaceBright=Color(0xffffffff), surfaceContainerLowest=Color.White, surfaceContainerLow=Color(0xfff1f4fb), surfaceContainer=Color(0xffedf1f8), surfaceContainerHigh=Color(0xffe8edf7), surfaceContainerHighest=Color(0xffe1e8f4),
)
@Composable fun FieldNotesTheme(mode:String="system",content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=if(mode=="dark" || mode=="system"&&isSystemInDarkTheme()) Dark else Paper,
        shapes=Shapes(extraSmall=ControlShape,small=ControlShape,medium=ControlShape,large=RoundedCornerShape(12.dp),extraLarge=RoundedCornerShape(16.dp)),content=content)
}
