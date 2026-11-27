package talq.app.ui.chatlist

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import talq.app.Brand
import talq.app.appContainer
import talq.app.ui.login.rememberOrgLogo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import talq.app.status.OwnStatusUi
import talq.app.theme.TalqTheme
import talq.app.ui.common.StatusAvatar
import talq.app.ui.status.OwnStatusReadout
import talq.core.repo.TalkSession

/**
 * The conversation list's header, TalQ for desktop's profile bar (MainWindow.cpp, "User profile
 * header"): your avatar with your own status disc, your name and under it the status readout.
 * Tapping any of the three opens the status sheet ([onSetStatus]), as desktop's avatar and pill
 * do. Without the server's user status app there is no readout and nothing to tap; while the account is
 * not checked yet ([onSetStatus] null) the readout waits ("…") and there is nothing to tap either.
 * [actions] are the bar's buttons on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileBar(
    session: TalkSession,
    status: OwnStatusUi,
    onSetStatus: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    val name = session.user.displayName.ifEmpty { session.user.userId }
    // Organisation mode: the organisation's logo, small, before the bar's buttons (not a button itself).
    val organisation by LocalContext.current.appContainer.organisation.state.collectAsStateWithLifecycle()
    val org = organisation.takeIf { !Brand.isBranded }
    val orgLogo = org?.let { rememberOrgLogo(it.logo, it.issued) }
    // Two lines at a large font size must not be cut by the bar's fixed height.
    val barHeight = with(LocalDensity.current) { (24 + 16).sp.toDp() + 16.dp }
        .coerceAtLeast(TopAppBarDefaults.TopAppBarExpandedHeight)
    TopAppBar(
        expandedHeight = barHeight,
        title = {
            Row(
                // The tap target reaches a little past the avatar; the offset keeps the avatar on
                // the list's left edge.
                modifier = Modifier
                    .offset(x = (-6).dp)
                    .then(
                        if (status.available && onSetStatus != null) {
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button, onClickLabel = "Set your status", onClick = onSetStatus)
                        } else {
                            Modifier
                        },
                    )
                    .padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The readout says the status in words; the disc on the avatar is not read twice.
                Box(Modifier.clearAndSetSemantics { }) {
                    StatusAvatar(
                        url = session.client.userAvatarUrl(session.user.userId, 128, !TalqTheme.colors.isLight),
                        authorization = session.client.authorizationHeader,
                        name = name,
                        ring = MaterialTheme.colorScheme.surface,
                        size = 36.dp,
                        presence = if (status.available && status.loaded) status.status.status else "",
                        everyState = true,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = TalqTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (status.available) OwnStatusReadout(status)
                }
            }
        },
        actions = {
            if (org != null && orgLogo != null) {
                Image(orgLogo, contentDescription = org.name, modifier = Modifier.size(24.dp), contentScale = ContentScale.Fit)
                Spacer(Modifier.width(8.dp))
            }
            actions()
        },
    )
}
