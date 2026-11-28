package talq.app.ui.login

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import talq.core.org.shapeInviteCode
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import talq.app.AppContainer
import talq.app.Brand
import talq.app.org.LookUp
import talq.app.org.OrgCard
import talq.app.org.OrganisationController
import talq.app.theme.TalqTheme
import talq.core.org.BrandPayload
import talq.core.org.sameServer
import talq.core.repo.TalkSession
import java.io.File

/**
 * Organisation mode's screens (spec: "Login screen", "Deep link"):
 * - [OrganisationCodeSheet]: the code field, then the card. Nothing is applied before the tap on the
 *   card's button. The typed code lives in [OrgCodeModel] only, never in saved state, so it does not
 *   outlive the process; the field takes no autocorrect and a password keyboard (no learning).
 * - [OrgLinkHost]: a `talq://organisation` link while signed in. Applied only to the organisation's
 *   own server, after a tap; otherwise one line.
 */
class OrgCodeModel(private val organisation: OrganisationController) : ViewModel() {
    var input by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var line by mutableStateOf<String?>(null)
        private set
    var card by mutableStateOf<OrgCard?>(null)
        private set

    /** Whether the sheet is open: here, not in the screen, so turning the phone keeps it (never saved). */
    var open by mutableStateOf(false)

    /** A tap on the card's button is being stored. */
    var saving by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun onInput(value: String) {
        input = value.take(40)
        line = null
    }

    /** Looks up [input]; an older look-up still running is dropped. */
    fun lookUp() {
        job?.cancel()
        busy = true
        line = null
        card = null
        val typed = input
        job = viewModelScope.launch {
            try {
                when (val r = organisation.lookUp(typed)) {
                    is LookUp.Found -> card = r.card
                    is LookUp.Refused -> line = r.line
                }
            } finally {
                busy = false
            }
        }
    }

    /** A code from a link: filled in and looked up at once. */
    fun start(code: String) {
        input = shapeInviteCode(code, code.length).text.take(40)
        lookUp()
    }

    fun reset() {
        job?.cancel()
        input = ""
        busy = false
        line = null
        card = null
        open = false
        saving = false
    }

    /** Stores [card] through [organisation]; closes on success, says so on failure. */
    fun use(card: OrgCard, then: () -> Unit = {}) {
        if (saving) return
        saving = true
        organisation.use(card) { ok ->
            saving = false
            if (ok) {
                reset()
                then()
            } else {
                line = "Couldn't save the organisation. Try again."
            }
        }
    }

    override fun onCleared() {
        input = ""
    }
}

/** A brand logo for the screen: bounds checked before decoding, at most 1024 px a side. */
fun decodeOrgLogo(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

/**
 * The organisation's logo for a screen: read and decoded once per version, off the main thread, and
 * kept for the process, so the list's header shows it at once every time it comes back.
 */
@Composable
fun rememberOrgLogo(file: File, issued: Long): ImageBitmap? =
    produceState(initialValue = OrgLogoCache.get(file, issued), file, issued) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { runCatching { decodeOrgLogo(file.readBytes()) }.getOrNull() }
                ?.also { OrgLogoCache.put(file, issued, it) }
        }
    }.value

/** One decoded logo, the organisation's current one. */
private object OrgLogoCache {
    @Volatile private var entry: Triple<String, Long, ImageBitmap>? = null

    fun get(file: File, issued: Long): ImageBitmap? =
        entry?.takeIf { it.first == file.path && it.second == issued }?.third

    fun put(file: File, issued: Long, bitmap: ImageBitmap) {
        entry = Triple(file.path, issued, bitmap)
    }
}

/**
 * The host with its registrable part in bold, so a look-alike sub-domain stands out. The registrable
 * part is the last two labels, or three under a short second-level label (`co.za`, `com.au`): an
 * approximation of the public suffix list, enough to make the important part visible.
 */
private fun hostLine(server: String) = buildAnnotatedString {
    val host = server.removePrefix("https://").substringBefore('/').substringBefore(':')
    val labels = host.split('.')
    val take = if (labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2] in SHORT_SLDS) 3 else 2
    val cut = labels.size - minOf(take, labels.size)
    if (cut > 0) append(labels.take(cut).joinToString(".") + ".")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(labels.drop(cut).joinToString(".")) }
}

private val SHORT_SLDS = setOf("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne", "go")

@Composable
private fun OrgCardContent(payload: BrandPayload) {
    val logo = remember(payload) { decodeOrgLogo(payload.logoPng) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        logo?.let {
            Image(it, contentDescription = null, modifier = Modifier.heightIn(max = 72.dp).widthIn(max = 220.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.height(12.dp))
        }
        Text(payload.name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(hostLine(payload.server), style = MaterialTheme.typography.bodyMedium, color = TalqTheme.colors.textSecondary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganisationCodeSheet(model: OrgCodeModel, onDismiss: () -> Unit, onUse: (OrgCard) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val card = model.card
            if (card == null) {
                Text("Use with an organisation", style = MaterialTheme.typography.titleLarge)
                // Capitals and the code's dashes as it is typed or pasted; the caret stays where it was.
                var field by remember { mutableStateOf(TextFieldValue(model.input, TextRange(model.input.length))) }
                // A code set from outside (a link, a reset) replaces the field, caret at its end.
                LaunchedEffect(model.input) {
                    if (field.text != model.input) field = TextFieldValue(model.input, TextRange(model.input.length))
                }
                OutlinedTextField(
                    value = field,
                    onValueChange = { typed ->
                        val shaped = shapeInviteCode(typed.text, typed.selection.end, before = field.text)
                        field = TextFieldValue(shaped.text, TextRange(shaped.caret))
                        model.onInput(shaped.text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !model.busy,
                    singleLine = true,
                    label = { Text("Invite code") },
                    placeholder = { Text("XXXX-XXXX-XXXX-XXXX-X") },
                    isError = model.line != null,
                    supportingText = model.line?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { if (!model.busy) model.lookUp() }),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = model::lookUp, enabled = !model.busy && model.input.isNotBlank()) { Text("Continue") }
                    if (model.busy) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            } else {
                OrgCardContent(card.payload)
                model.line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { onUse(card) }, enabled = !model.saving, modifier = Modifier.fillMaxWidth()) { Text("Use this organisation") }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", color = TalqTheme.colors.textSecondary)
                }
            }
        }
    }
}

/** A `talq://organisation` link while signed in (spec: "Deep link"). Main screen host, one at a time. */
@Composable
fun OrgLinkHost(container: AppContainer, session: TalkSession) {
    if (Brand.isBranded) return
    val context = LocalContext.current
    val code by container.organisation.pendingCode.collectAsStateWithLifecycle()
    val model: OrgCodeModel = viewModel(key = "org-link") { OrgCodeModel(container.organisation) }
    // A card offered to this session goes with it (a sign-out, another account).
    DisposableEffect(session) { onDispose { model.reset() } }
    LaunchedEffect(code) {
        val c = code ?: return@LaunchedEffect
        container.organisation.pendingCode.value = null
        if (container.organisation.state.value != null) {
            Toast.makeText(context, "Use without an organisation first", Toast.LENGTH_SHORT).show()
        } else {
            model.start(c)
        }
    }
    val card = model.card
    val line = model.line
    LaunchedEffect(card, line) {
        if (line != null && card == null) {
            Toast.makeText(context, line, Toast.LENGTH_SHORT).show()
            model.reset()
        } else if (card != null && !sameServer(card.payload.server, session.credentials.server)) {
            Toast.makeText(context, "Sign out first to use an organisation", Toast.LENGTH_SHORT).show()
            model.reset()
        }
    }
    if (card != null && sameServer(card.payload.server, session.credentials.server)) {
        AlertDialog(
            onDismissRequest = model::reset,
            text = {
                Column {
                    OrgCardContent(card.payload)
                    model.line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = { model.use(card) }, enabled = !model.saving) { Text("Apply this organisation's look") }
            },
            dismissButton = { TextButton(onClick = model::reset) { Text("Cancel") } },
        )
    }
}
