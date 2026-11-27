package talq.app.ui.login

import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import talq.app.Brand
import talq.app.R
import talq.app.appContainer
import talq.app.appName
import talq.app.theme.TalqTheme

@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val container = context.appContainer
    val model: LoginViewModel = viewModel { LoginViewModel(container, Brand.appName(context.applicationContext)) }
    val state by model.state.collectAsStateWithLifecycle()
    // What the sign-out before could not do.
    val note by container.signOutNote.collectAsStateWithLifecycle()
    val appName = appName()
    // Organisation mode (0.11.0): its logo, name, welcome line and fixed server; never in a branded build.
    val organisation by container.organisation.state.collectAsStateWithLifecycle()
    val org = organisation.takeIf { !Brand.isBranded }
    val orgLogo = org?.let { rememberOrgLogo(it.logo, it.issued) }
    // A refreshed brand file with other addresses: shown here (signed out) and taken only by a tap.
    val addressChange by container.organisation.addressChange.collectAsStateWithLifecycle()
    // The code sheet: its model (open flag included) is not saved state, so a typed code never outlives
    // the process, while turning the phone keeps the sheet open.
    val codeModel: OrgCodeModel = viewModel(key = "org-code") { OrgCodeModel(container.organisation) }
    val pendingCode by container.organisation.pendingCode.collectAsStateWithLifecycle()
    LaunchedEffect(pendingCode) {
        val code = pendingCode ?: return@LaunchedEffect
        container.organisation.pendingCode.value = null
        if (Brand.isBranded) return@LaunchedEffect
        if (container.organisation.state.value != null) {
            Toast.makeText(context, "Use without an organisation first", Toast.LENGTH_SHORT).show()
        } else {
            codeModel.open = true
            codeModel.start(code)
        }
    }
    if (codeModel.open) {
        OrganisationCodeSheet(
            model = codeModel,
            onDismiss = { codeModel.reset() },
            onUse = { card ->
                // A sign-in on another server must not finish under the organisation.
                model.cancel()
                codeModel.use(card)
            },
        )
    }

    LaunchedEffect(state.browserUrl) {
        val url = state.browserUrl ?: return@LaunchedEffect
        if (openInBrowser(context, url)) model.browserOpened() else model.browserUnavailable()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 420.dp)) {
                if (Brand.isBranded) {
                    // The brand's logo, from the private resource overlay of the branded build.
                    Image(
                        painter = painterResource(R.drawable.brand_logo),
                        contentDescription = null,
                        modifier = Modifier.heightIn(max = 72.dp).widthIn(max = 220.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(20.dp))
                } else if (orgLogo != null) {
                    Image(
                        orgLogo,
                        contentDescription = null,
                        modifier = Modifier.heightIn(max = 72.dp).widthIn(max = 220.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Spacer(Modifier.height(20.dp))
                }
                Text(org?.name ?: appName, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    org?.welcome
                        ?: if (Brand.isBranded || org != null) "Sign in to open your conversations."
                        else "Sign in to your Nextcloud server to open your conversations.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                note?.let { line ->
                    Spacer(Modifier.height(16.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = TalqTheme.colors.danger)
                }
                Spacer(Modifier.height(28.dp))
                // A branded build signs in to its built-in server: no field to edit or select.
                if (Brand.isBranded || org != null) {
                    // The organisation's server, fixed: shown, not editable.
                    org?.let {
                        Text(
                            "Server: " + it.server.removePrefix("https://"),
                            style = MaterialTheme.typography.bodySmall,
                            color = TalqTheme.colors.textMuted,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    addressChange?.takeIf { org != null }?.let { change ->
                        Text(
                            if (change.oldServer == change.newServer) {
                                "The organisation's ERP address changed: " + (change.oldErp?.removePrefix("https://") ?: "none") +
                                    " → " + (change.newErp?.removePrefix("https://") ?: "none")
                            } else {
                                "The organisation moved: " + change.oldServer.removePrefix("https://") + " → " +
                                    change.newServer.removePrefix("https://")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TalqTheme.colors.amber,
                        )
                        TextButton(onClick = { container.organisation.acceptAddressChange() }, enabled = !state.busy) {
                            Text("Use the new address")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    state.error?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                } else OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = model::onServerUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    singleLine = true,
                    label = { Text("Server address") },
                    placeholder = { Text("cloud.example.com") },
                    isError = state.error != null,
                    supportingText = state.error?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { model.login() }),
                )
                Spacer(Modifier.height(16.dp))
                if (state.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = when (state.phase) {
                                LoginPhase.Contacting -> "Contacting the server…"
                                LoginPhase.WaitingForBrowser -> "Finish signing in in the browser. This screen continues by itself."
                                else -> "Opening your account…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        if (state.phase == LoginPhase.WaitingForBrowser) {
                            TextButton(onClick = { model.reopenBrowser() }) { Text("Open browser again") }
                        }
                        if (state.phase != LoginPhase.Finishing) {
                            TextButton(onClick = model::cancel) {
                                Text("Cancel", color = TalqTheme.colors.textSecondary)
                            }
                        }
                    }
                } else {
                    Button(onClick = model::login, modifier = Modifier.fillMaxWidth()) { Text("Log in") }
                    if (!Brand.isBranded) {
                        if (org == null) {
                            TextButton(onClick = { codeModel.open = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Use with an organisation")
                            }
                        } else {
                            TextButton(onClick = { container.organisation.leave() }, modifier = Modifier.fillMaxWidth()) {
                                Text("Use without an organisation", color = TalqTheme.colors.textSecondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Your browser opens for the sign-in. $appName never sees your password; " +
                            "the server gives it an app password you can revoke at any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TalqTheme.colors.textMuted,
                    )
                }
            }
        }
    }
}

/** Custom Tab if a browser supports it, else any browser. False when the phone has none. */
private fun openInBrowser(context: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        return true
    } catch (e: ActivityNotFoundException) {
        // fall through
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
