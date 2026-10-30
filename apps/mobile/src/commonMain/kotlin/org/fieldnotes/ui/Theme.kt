package org.fieldnotes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Explicit container tokens keep navigation, switches, date pickers and sheets
 * in the same charcoal/paper palette, instead of inheriting Material's violet. */
private val Dark = darkColorScheme(
    primary=Color(0xff74c7bb), onPrimary=Color(0xff102f28), primaryContainer=Color(0xff24483f), onPrimaryContainer=Color(0xffb7e5d7),
    secondary=Color(0xffa6bbb5), onSecondary=Color(0xff20342e), secondaryContainer=Color(0xff30443d), onSecondaryContainer=Color(0xffd9e6dc),
    tertiary=Color(0xffb2c7bf), onTertiary=Color(0xff20342e), tertiaryContainer=Color(0xff37483f), onTertiaryContainer=Color(0xffdae8df),
    background=Color(0xff171b1b), onBackground=Color(0xffe3e6df), surface=Color(0xff202626), onSurface=Color(0xffe3e6df),
    surfaceVariant=Color(0xff293130), onSurfaceVariant=Color(0xffb2bdb4), outline=Color(0xff73837b), outlineVariant=Color(0xff3b4741), surfaceTint=Color(0xff74c7bb),
    inverseSurface=Color(0xffe3e6df), inverseOnSurface=Color(0xff202927), inversePrimary=Color(0xff226d63),
    surfaceDim=Color(0xff171b1b), surfaceBright=Color(0xff343d37), surfaceContainerLowest=Color(0xff121615), surfaceContainerLow=Color(0xff1c2220), surfaceContainer=Color(0xff202626), surfaceContainerHigh=Color(0xff29302c), surfaceContainerHighest=Color(0xff323c36),
)
private val Paper = lightColorScheme(
    primary=Color(0xff226d63), onPrimary=Color.White, primaryContainer=Color(0xffdcece3), onPrimaryContainer=Color(0xff153e32),
    secondary=Color(0xff526c65), onSecondary=Color.White, secondaryContainer=Color(0xffe1e9df), onSecondaryContainer=Color(0xff293d32),
    tertiary=Color(0xff596e5c), onTertiary=Color.White, tertiaryContainer=Color(0xffe1e9d9), onTertiaryContainer=Color(0xff2e402d),
    background=Color(0xfff4f4ed), onBackground=Color(0xff202927), surface=Color(0xfffafaf5), onSurface=Color(0xff202927),
    surfaceVariant=Color(0xffe8ece5), onSurfaceVariant=Color(0xff5c6d64), outline=Color(0xff7b8b80), outlineVariant=Color(0xffd4dcd1), surfaceTint=Color(0xff226d63),
    inverseSurface=Color(0xff27312c), inverseOnSurface=Color(0xfff4f4ed), inversePrimary=Color(0xff74c7bb),
    surfaceDim=Color(0xffdce2d7), surfaceBright=Color(0xfffafaf5), surfaceContainerLowest=Color.White, surfaceContainerLow=Color(0xfff2f4eb), surfaceContainer=Color(0xffedf0e6), surfaceContainerHigh=Color(0xffe8ece1), surfaceContainerHighest=Color(0xffe1e7db),
)
@Composable fun FieldNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme=if(isSystemInDarkTheme()) Dark else Paper,
        shapes=Shapes(extraSmall=RoundedCornerShape(4.dp),small=RoundedCornerShape(6.dp),medium=RoundedCornerShape(8.dp),large=RoundedCornerShape(10.dp),extraLarge=RoundedCornerShape(12.dp)),content=content)
}
