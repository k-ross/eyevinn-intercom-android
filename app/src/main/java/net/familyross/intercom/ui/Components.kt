package net.familyross.intercom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardShape = RoundedCornerShape(10.dp)
private val ButtonShape = RoundedCornerShape(6.dp)

/** "Open Intercom" bar; the user name on the right opens the settings. */
@Composable
fun AppHeader(username: String?, onOpenSettings: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IntercomIcons.Headset, contentDescription = null, tint = IntercomColors.Accent, modifier = Modifier.size(22.dp))
        Text(
            text = "Open Intercom",
            color = IntercomColors.Text,
            fontSize = 20.sp,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp),
        )
        Box(Modifier.weight(1f))
        if (username != null && onOpenSettings != null) {
            Row(
                modifier = Modifier
                    .clip(ButtonShape)
                    .clickable(onClickLabel = "Open settings", role = Role.Button, onClick = onOpenSettings)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = username,
                    color = IntercomColors.Text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                Icon(
                    IntercomIcons.UserSettings,
                    contentDescription = "Settings",
                    tint = IntercomColors.Accent,
                    modifier = Modifier.padding(start = 5.dp).size(25.dp),
                )
            }
        }
    }
}

@Composable
fun PageTitle(text: String) {
    Text(
        text = text,
        color = IntercomColors.Text,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** A message box in the style of the web client's call cards. */
@Composable
fun InfoCard(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color = IntercomColors.Text,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(IntercomColors.Card)
            .border(1.dp, IntercomColors.CardBorder, CardShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = titleColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun BodyText(text: String, color: Color = IntercomColors.TextMuted) {
    Text(text, color = color, fontSize = 15.sp, lineHeight = 21.sp)
}

/** The web client's green primary button. */
@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(ButtonShape)
            .background(IntercomColors.Action)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = IntercomColors.OnAction, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ConfirmDialog(
    text: String,
    detail: String? = null,
    confirmLabel: String = "Yes",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, color = IntercomColors.Text)
                if (detail != null) Text(detail)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
