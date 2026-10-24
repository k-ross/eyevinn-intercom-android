package intercom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import intercom.data.AppSettings
import intercom.data.CallUrl
import intercom.data.CallUrlResult
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MAX_USERNAME_LENGTH = 40

@Composable
fun SettingsScreen(
    initial: AppSettings,
    inCall: Boolean,
    onSave: (AppSettings) -> Unit,
    onCancel: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var callUrl by rememberSaveable { mutableStateOf(initial.callUrl) }
    var authUsername by rememberSaveable { mutableStateOf(initial.authUsername) }
    var authPassword by rememberSaveable { mutableStateOf(initial.authPassword) }
    var apiUrl by rememberSaveable { mutableStateOf(initial.apiUrl) }
    var autoJoin by rememberSaveable { mutableStateOf(initial.autoJoin) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(initial.apiUrl.isNotBlank()) }
    var submitted by rememberSaveable { mutableStateOf(false) }

    val usernameError = if (username.isBlank()) "Enter the name other participants will see." else null
    val callUrlError = (CallUrl.parse(callUrl) as? CallUrlResult.Invalid)?.reason
    val apiUrlError = if (apiUrl.isNotBlank() && apiUrl.trim().toHttpUrlOrNull()?.isHttps != true) {
        "Enter an https:// URL, or leave this empty."
    } else {
        null
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        AppHeader(username = null, onOpenSettings = null)
        PageTitle("User Settings")
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Field(
                label = "Username",
                value = username,
                onValueChange = { username = it.take(MAX_USERNAME_LENGTH) },
                error = usernameError.takeIf { submitted },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            )
            Field(
                label = "Call URL",
                value = callUrl,
                onValueChange = { callUrl = it },
                error = callUrlError.takeIf { submitted },
                help = "The link you were sent to join the call, for example https://intercom.example.com/calls?lines=1:2&guest=1",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                singleLine = false,
            )

            SectionLabel("Server sign-in")
            BodyText("Only needed if the server asks for a username and password before it shows the intercom.")
            Field(
                label = "Sign-in username",
                value = authUsername,
                onValueChange = { authUsername = it },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
            )
            Field(
                label = "Sign-in password",
                value = authPassword,
                onValueChange = { authPassword = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Hide" else "Show", color = IntercomColors.Accent)
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { autoJoin = !autoJoin },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Join the call when the app opens",
                    color = IntercomColors.Text,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = autoJoin,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = IntercomColors.Accent,
                        checkedThumbColor = Color.White,
                    ),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Advanced", Modifier.weight(1f))
                Icon(
                    imageVector = if (showAdvanced) IntercomIcons.ChevronUp else IntercomIcons.ChevronDown,
                    contentDescription = if (showAdvanced) "Hide advanced settings" else "Show advanced settings",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            if (showAdvanced) {
                Field(
                    label = "API server URL",
                    value = apiUrl,
                    onValueChange = { apiUrl = it },
                    error = apiUrlError,
                    help = "Leave empty unless the intercom API is hosted at a different address than the call URL.",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }

            if (inCall) {
                BodyText("Changing the name, call URL or sign-in leaves the current call.", color = IntercomColors.Warning)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(
                    text = "Save",
                    onClick = {
                        submitted = true
                        if (usernameError == null && callUrlError == null && apiUrlError == null) {
                            onSave(
                                initial.copy(
                                    username = username.trim(),
                                    callUrl = callUrl.trim(),
                                    authUsername = authUsername.trim(),
                                    authPassword = authPassword,
                                    apiUrl = apiUrl.trim(),
                                    autoJoin = autoJoin,
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = IntercomColors.Text, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = IntercomColors.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
        modifier = modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    help: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = IntercomColors.Text, fontSize = 16.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            isError = error != null,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailing,
            shape = RoundedCornerShape(5.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = IntercomColors.Control,
                unfocusedContainerColor = IntercomColors.Control,
                errorContainerColor = IntercomColors.Control,
                focusedBorderColor = IntercomColors.Accent,
                unfocusedBorderColor = IntercomColors.ControlBorder,
                cursorColor = IntercomColors.Accent,
            ),
        )
        when {
            error != null -> Text(error, color = IntercomColors.Danger, fontSize = 13.sp)
            help != null -> Text(help, color = IntercomColors.TextMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
