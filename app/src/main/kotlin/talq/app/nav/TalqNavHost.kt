package talq.app.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import talq.app.AppContainer
import talq.app.SessionState
import talq.app.push.NotificationPermissionPrompt
import talq.app.ui.chatlist.ChatListScreen
import talq.app.ui.common.LoadingScreen
import talq.app.ui.common.UnreachableScreen
import talq.app.ui.files.SharedFilesScreen
import talq.app.ui.login.LoginScreen
import talq.app.ui.room.RoomHostScreen
import talq.app.ui.room.RoomTab
import talq.app.ui.settings.SettingsScreen

/** The whole app below the theme: waits for the session state, then hosts the navigation graph. */
@Composable
fun TalqRoot(container: AppContainer) {
    val state by container.sessionState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    when (val current = state) {
        SessionState.Loading -> LoadingScreen()
        is SessionState.Unreachable -> UnreachableScreen(
            error = current.error,
            onRetry = container::restore,
            onLogout = { scope.launch { container.logout() } },
        )
        SessionState.SignedOut, is SessionState.SignedIn -> {
            TalqNavHost(container, signedIn = current is SessionState.SignedIn)
            // Android 13+: asks once, after sign-in, whether notifications may be shown.
            if (current is SessionState.SignedIn) NotificationPermissionPrompt()
        }
    }
}

/**
 * Routes (see Routes.kt): Login, ChatList, Settings, Room(token, tab, messageId?).
 * Signing in or out swaps the whole back stack; a pending
 * [DeepLink] is opened on top of the chat list as soon as a session is open.
 */
@Composable
fun TalqNavHost(container: AppContainer, signedIn: Boolean) {
    val nav = rememberNavController()
    val start: Any = remember { if (signedIn) ChatListRoute else LoginRoute }
    val pending by container.pendingDeepLink.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()

    // The stack must match the session: Login alone when signed out, never Login when signed
    // in. Compared against what is really on screen, not a remembered flag: a back stack
    // restored after the process was killed can be a signed-in one while the session is gone.
    LaunchedEffect(signedIn, pending, current) {
        val destination = current?.destination ?: return@LaunchedEffect
        if (destination.hasRoute<LoginRoute>() == signedIn) {
            nav.navigate(if (signedIn) ChatListRoute else LoginRoute) {
                popUpTo(nav.graph.id) { inclusive = true }
            }
            return@LaunchedEffect
        }
        val link = pending
        if (signedIn && link != null) {
            container.pendingDeepLink.value = null
            nav.openDeepLink(link)
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable<LoginRoute> { LoginScreen() }

        composable<ChatListRoute> { entry ->
            val session = container.session ?: return@composable
            ChatListScreen(
                session = session,
                onOpenRoom = { token -> if (entry.isResumed()) nav.openRoom(token) },
                onOpenTopic = { token, topicId -> if (entry.isResumed()) nav.openRoom(token, RoomTab.Topic(topicId)) },
                onOpenSettings = { if (entry.isResumed()) nav.navigate(SettingsRoute) },
                onUnauthorized = container::onUnauthorized,
            )
        }

        composable<SettingsRoute> { entry ->
            val session = container.session ?: return@composable
            SettingsScreen(
                session = session,
                push = container.pushRegistrar,
                onBack = { nav.back(entry) },
                // On the container's scope: leaving this screen must not cancel the sign-out.
                onLogout = { container.scope.launch { container.logout() } },
            )
        }

        composable<RoomRoute> { entry ->
            val session = container.session ?: return@composable
            RoomHostScreen(
                session = session,
                route = entry.toRoute<RoomRoute>(),
                onBack = { nav.back(entry) },
                onOpenSharedFiles = { if (entry.isResumed()) nav.navigate(SharedFilesRoute(entry.toRoute<RoomRoute>().token)) },
            )
        }

        composable<SharedFilesRoute> { entry ->
            val session = container.session ?: return@composable
            val token = entry.toRoute<SharedFilesRoute>().token
            SharedFilesScreen(
                session = session,
                token = token,
                onBack = { nav.back(entry) },
                // The message's own tab: its topic, or General (the whole chat without topics).
                onShowInChat = { m ->
                    if (entry.isResumed()) {
                        nav.openRoom(token, if (m.topicId > 0) RoomTab.Topic(m.topicId) else RoomTab.General, m.id)
                    }
                },
            )
        }
    }
}

/**
 * The one way into a conversation, for the chat list, notification links and anything else.
 * [tab] null opens the tab last open there; a given tab becomes the remembered one (the room
 * host records it). The room always sits directly on the chat list: back leaves it in one press.
 */
internal fun NavHostController.openRoom(token: String, tab: RoomTab? = null, messageId: Long? = null) {
    navigate(RoomRoute(token, tab?.key ?: RoomRoute.REMEMBERED, messageId)) {
        popUpTo<ChatListRoute> { inclusive = false }
    }
}

/**
 * A notification about a topic opens that topic; one about a message outside the topics opens
 * General (the whole chat when the room has no topics); a bare conversation link opens the
 * remembered tab.
 */
private fun NavHostController.openDeepLink(link: DeepLink) {
    val threadId = link.threadId?.takeIf { it > 0 }
    val tab = when {
        threadId != null -> RoomTab.Topic(threadId)
        link.messageId != null -> RoomTab.General
        else -> null
    }
    openRoom(link.token, tab, link.messageId)
}

/** Back from [entry]'s screen. A second tap during the transition must not pop a second screen. */
private fun NavHostController.back(entry: NavBackStackEntry) {
    if (entry.isResumed() && previousBackStackEntry != null) popBackStack()
}

/** Guards against a double tap navigating twice while the first transition is running. */
private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
