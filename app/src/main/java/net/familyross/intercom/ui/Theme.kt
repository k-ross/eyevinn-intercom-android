package net.familyross.intercom.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The web client's palette (see its styled-components). */
object IntercomColors {
    val Background = Color(0xFF242424)
    val Text = Color(0xDEFFFFFF)
    val TextMuted = Color(0x99FFFFFF)
    val Accent = Color(0xFF59CBE8)
    val Danger = Color(0xFFF96C6C)
    val Warning = Color(0xFFEBCA6A)
    val On = Color(0xFF6FD84F)
    val Action = Color(0xFF1DB954)
    val OnAction = Color(0xFF1A1A1A)

    val Card = Color(0x6632383B)
    val ProgramCard = Color(0x3349437C)
    val CardBorder = Color(0x4D6D6D6D)
    val Control = Color(0xFF32383B)
    val ControlBorder = Color(0xFF6D6D6D)

    val SliderTrack = Color(0xFFE0E0E0)
    val Count = Color(0xFF9E9E9E)
    val CountActive = Color(0xFF73D16D)

    val ListBorder = Color(0xFF434343)
    val ListDivider = Color(0xFF464646)
    val Row = Color(0xFF1A1A1A)
    val RowSelf = Color(0xFF353434)
    val RowText = Color(0xFFDDDDDD)
    val Online = Color(0xFF7BE27B)
    val Inactive = Color(0xFFC7C7C7)
    val AvatarGlyph = Color(0xFF4D4D4D)
    val RowButton = Color(0xFF302B2B)
    val RowButtonBorder = Color(0xFF707070)

    val TalkPulseStart = Color(0xFF4ADA1E)
    val TalkPulseEnd = Color(0xC7FFFFFF)
}

private val ColorScheme = darkColorScheme(
    primary = IntercomColors.Accent,
    onPrimary = IntercomColors.OnAction,
    background = IntercomColors.Background,
    onBackground = IntercomColors.Text,
    surface = IntercomColors.Background,
    onSurface = IntercomColors.Text,
    surfaceContainerHigh = Color(0xFF383838),
    onSurfaceVariant = IntercomColors.TextMuted,
    error = IntercomColors.Danger,
    outline = IntercomColors.ControlBorder,
)

@Composable
fun IntercomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, content = content)
}
