package talq.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import talq.app.appContainer
import talq.app.diag.Check
import talq.app.diag.DiagAction
import talq.app.diag.DiagReport
import talq.app.diag.Diagnostics
import talq.app.diag.Health
import talq.app.theme.TalqTheme
import talq.core.repo.TalkSession

/** Holds one run of the checks across rotations; a new run replaces the old one. */
class DiagnosticsViewModel(private val diagnostics: Diagnostics) : ViewModel() {
    var checks by mutableStateOf<List<Check>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun run(session: TalkSession?) {
        job?.cancel()
        running = true
        job = viewModelScope.launch {
            try {
                diagnostics.run(session) { list -> checks = list }
            } finally {
                running = false
            }
        }
    }
}

/**
 * Settings > Diagnostics: what the app can and cannot reach (server, push, call signaling, each
 * STUN/TURN address), with the system pages that fix a red line, and a report to share with
 * whoever supports the app. The checks run when the screen opens and on Run again, never in the
 * background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(session: TalkSession?, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = TalqTheme.colors
    val vm: DiagnosticsViewModel = viewModel { DiagnosticsViewModel(Diagnostics(context.applicationContext, context.appContainer)) }
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { if (vm.checks.isEmpty()) vm.run(session) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { vm.run(session) }, enabled = !vm.running) { Text(if (vm.running) "Checking…" else "Run again") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            var group = ""
            for (check in vm.checks) {
                if (check.group != group) {
                    group = check.group
                    val label = group
                    item(key = "g-$label") {
                        Text(
                            label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
                        )
                    }
                }
                item(key = "c-${check.group}-${check.title}") { CheckRow(check) { openFix(context, it) } }
            }
            item(key = "share") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp)) {
                    Text(
                        "Everything above plus the recent log, without passwords.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = !sharing,
                        onClick = {
                            sharing = true
                            scope.launch {
                                try {
                                    DiagReport.share(context, DiagReport.text(vm.checks, DiagReport.recentLog(), context.appContainer.calls.timeline.text()))
                                } finally {
                                    sharing = false
                                }
                            }
                        },
                    ) { Text("Share report") }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: Check, onFix: (DiagAction) -> Unit) {
    val colors = TalqTheme.colors
    val disc: Color = when (check.health) {
        Health.Good -> colors.online
        Health.Warn -> colors.amber
        Health.Bad -> colors.danger
        Health.Info -> colors.accent
        Health.Running -> colors.textMuted
    }
    val word = when (check.health) {
        Health.Good -> "OK"
        Health.Warn -> "Warning"
        Health.Bad -> "Problem"
        Health.Info -> "Info"
        Health.Running -> "Checking"
    }
    val action = check.action
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (action != null) Modifier.clickable { onFix(action) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // A painted disc, a colour for every state (never a text bullet).
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(disc)
                .semantics { contentDescription = word },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(check.title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            if (action != null) {
                Text("Tap to open the system setting", style = MaterialTheme.typography.labelSmall, color = colors.accentText)
            }
        }
    }
}

private fun openFix(context: Context, action: DiagAction) {
    val intent = when (action) {
        DiagAction.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        DiagAction.Battery -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        DiagAction.FullScreenCalls ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // The app's details page is the last resort on phones without that page.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
