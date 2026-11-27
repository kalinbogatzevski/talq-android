package talq.app.nav

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import talq.app.AppContainer
import talq.app.MainActivity
import talq.app.SessionState
import talq.app.push.NotificationPermissionPrompt
import talq.app.ui.adaptive.LocalTwoPane
import talq.app.ui.login.OrgLinkHost
import talq.app.ui.adaptive.TwoPane
import talq.app.ui.chat.LocalAttachPickers
import talq.app.ui.chat.rememberAttachPickers
import androidx.lifecycle.viewmodel.compose.viewModel
import talq.app.ui.chatlist.ChatListScreen
import talq.app.ui.chatlist.listShows
import talq.app.ui.chatlist.NewConversationModel
import talq.app.ui.common.LoadingScreen
import talq.app.ui.common.LocalPicturesFromPhone
import talq.app.ui.common.MessagePane
import talq.app.ui.common.UnreachableScreen
import talq.app.ui.files.SharedFilesScreen
import talq.app.ui.login.LoginScreen
import talq.app.ui.room.RoomHostScreen
import talq.app.ui.room.RoomTab
import talq.app.ui.room.RoomWaitingScreen
import talq.app.ui.room.TabRequest
import talq.app.ui.settings.DiagnosticsScreen
import talq.app.ui.settings.SettingsScreen
import talq.core.repo.TalkSession

/** How often the account behind the saved list is checked again while the server is not reached, in front. */
private const val RECHECK_MILLIS = 15_000L

/**
 * The whole app below the theme: the navigation graph of the open session, or, until it is open, of the account
 * as the last run of the app left it (its saved list, `AppContainer.saved`). Without one it waits for the session.
 */
@Composable
fun TalqRoot(container: AppContainer) {
    val state by container.sessionState.collectAsStateWithLifecycle()
    val saved by container.saved.collectAsStateWithLifecycle()
    val loggingOut by container.loggingOut.collectAsStateWithLifecycle()
    val current = state
    val session = if (current is SessionState.SignedIn) current.session else saved?.session
    // Here, whatever is on screen: a photo taken while Android had ended the app is not lost with the screen it was
    // asked for on.
    val pickers = rememberAttachPickers(container)
    when {
        current is SessionState.SignedOut || session != null -> {
            CompositionLocalProvider(LocalPicturesFromPhone provides (session?.checked == false), LocalAttachPickers provides pickers) {
                TalqNavHost(container, session.takeIf { current !is SessionState.SignedOut })
            }
            // Android 13+: asks once, after sign-in, whether notifications may be shown.
            if (current is SessionState.SignedIn) NotificationPermissionPrompt()
            // A talq://organisation link while signed in (the login screen takes it while signed out).
            if (current is SessionState.SignedIn) OrgLinkHost(container, current.session)
        }
        current is SessionState.Unreachable -> {
            val code by container.organisation.pendingCode.collectAsStateWithLifecycle()
            val context = LocalContext.current
            LaunchedEffect(code) {
                if (code == null) return@LaunchedEffect
                container.organisation.pendingCode.value = null
                Toast.makeText(context, "Open the organisation's link again once you are connected", Toast.LENGTH_LONG).show()
            }
            UnreachableScreen(
                error = current.error,
                loggingOut = loggingOut,
                onRetry = { container.restore() },
                // On the container's scope: turning the phone must not cancel the sign-out.
                onLogout = { container.scope.launch { container.logout() } },
            )
        }
        else -> LoadingScreen()
    }
    // The network coming back and the app coming to the front check at once; nothing else would while the
    // server alone is away.
    if (current is SessionState.Unreachable && session != null) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(RECHECK_MILLIS)
                    container.recheck()?.join()
                }
            }
        }
    }
}

/**
 * Routes (see Routes.kt): Login, ChatList, Settings, Room(token, tab, messageId?), SharedFiles.
 * Signing in or out swaps the whole back stack; a pending
 * [DeepLink] is opened on top of the chat list as soon as a session is open.
 *
 * [session]: the account on screen, null when nobody is signed in. Not `checked`: the server has not answered for
 * it yet (TalqRoot). The list and Settings are there then, with what they can do; a conversation is its name and
 * why it waits ([RoomWaitingScreen]), and its own screen takes that place when the session is open.
 *
 * On a wide window ([TwoPane]) the conversation list is drawn beside the navigation host, and
 * the chat-list destination inside it is an empty "Select a conversation" pane. The back stack
 * is the same in both layouts, so a resize changes nothing in it.
 */
@Composable
fun TalqNavHost(container: AppContainer, session: TalkSession?) {
    val signedIn = session != null
    val checked = session?.checked == true
    val nav = rememberNavController()
    val start: Any = remember { if (signedIn) ChatListRoute else LoginRoute }
    val pending by container.pendingDeepLink.collectAsStateWithLifecycle()
    val current by nav.currentBackStackEntryAsState()
    // A topic picked in the wide layout's list for the conversation already open: switched to
    // in place (reopening the conversation would lose its scroll positions; drafts are kept).
    val tabRequest = remember { mutableStateOf<TabRequest?>(null) }
    // The conversation list's search text, one holder for both places the list is drawn (beside
    // the rooms, or as the first screen), so a resize keeps it. Emptied at sign-out.
    val listSearch = rememberSaveable { mutableStateOf("") }
    // The list's "+" sheet model, likewise one for both places (here: the activity's store), so a
    // resize while a request runs keeps its answer. Forgotten at sign-out.
    val newChat: NewConversationModel = viewModel(key = "new-conversation")
    LaunchedEffect(signedIn) {
        if (!signedIn) {
            listSearch.value = ""
            newChat.signedOut()
        }
    }

    // The stack must match the session: Login alone when signed out, never Login when signed
    // in. Compared against what is really on screen, not a remembered flag: a back stack
    // restored after the process was killed can be a signed-in one while the session is gone.
    LaunchedEffect(signedIn, checked, pending, current) {
        val destination = current?.destination ?: return@LaunchedEffect
        if (destination.hasRoute<LoginRoute>() == signedIn) {
            nav.navigate(if (signedIn) ChatListRoute else LoginRoute) {
                popUpTo(nav.graph.id) { inclusive = true }
            }
            return@LaunchedEffect
        }
        val link = pending
        if (checked && link != null) {
            container.pendingDeepLink.value = null
            // A conversation in a closed folder opens that folder (desktop does so for a chat that
            // is opened), so it is in view when the list is back. Only for one the list shows ("Show"
            // may hide it: it opens all the same); after a cold start from a notification the list has
            // not answered yet, so the folders wait for its first answer. Nothing happens while folders
            // are not shown.
            container.session?.let { s ->
                container.folders.revealWhenListed(s, link.token) { listShows(s, container.listSort.filter.value, link.token) }
            }
            nav.openDeepLink(link, tabRequest)
        }
    }

    // A conversation left from the list's menu while it is open beside the list: its screens (shared
    // files over it too) close, the pane is empty again.
    if (session != null) {
        val left by session.roomActions.left.collectAsStateWithLifecycle()
        LaunchedEffect(left) {
            val token = left ?: return@LaunchedEffect
            if (nav.openRoomToken() == token) nav.popBackStack<RoomRoute>(inclusive = true)
            session.roomActions.leftHandled(token)
        }
    }
    // Read inside the destinations, which go on with the open session once it takes the saved account's place.
    val shown = rememberUpdatedState(session)
    TwoPane(
        // Not while the login screen is the one in front (signed in, the stack is switched to the
        // list at once; without a fade, so the list opens straight into two panes).
        enabled = session != null && current?.destination?.hasRoute<LoginRoute>() != true,
        store = container.appearance,
        list = {
            if (session != null) {
                ChatListScreen(
                    session = session,
                    search = listSearch,
                    newChat = newChat,
                    selectedToken = current?.openToken() ?: current?.takeIf { it.destination.hasRoute<SettingsRoute>() }
                        ?.let { nav.previousBackStackEntry?.openToken() },
                    onOpenRoom = { token, draftTab ->
                        if (nav.topIsResumed()) {
                            container.chosen(session, token)
                            val open = nav.openRoomToken() == token
                            nav.selectRoom(token, draftTab)
                            if (open) {
                                // The topic being started is no tab (its "Not sent:" line shows beside it too).
                                val newTopic = draftTab == RoomTab.KEY_NEW_TOPIC
                                val tab = RoomTab.fromKey(draftTab)
                                if (newTopic || tab != null) tabRequest.value = TabRequest(token, tab, newTopic = newTopic)
                            }
                        }
                    },
                    onOpenTopic = { token, topicId ->
                        if (nav.topIsResumed()) {
                            container.chosen(session, token)
                            if (nav.openRoomToken() == token) {
                                nav.selectRoom(token)
                                tabRequest.value = TabRequest(token, RoomTab.Topic(topicId))
                            } else {
                                nav.openRoom(token, RoomTab.Topic(topicId))
                            }
                        }
                    },
                    onOpenSettings = {
                        if (nav.topIsResumed()) {
                            container.chosen(session)
                            nav.navigate(SettingsRoute()) { launchSingleTop = true }
                        }
                    },
                    onOpenPushSettings = { if (nav.topIsResumed()) nav.navigate(SettingsRoute(notifications = true)) { launchSingleTop = true } },
                    onUnauthorized = container::onUnauthorized,
                )
            }
        },
    ) {
        TalqGraph(container, shown, nav, start, tabRequest, listSearch, newChat)
    }
}

/**
 * The user opened a conversation ([token]) or Settings. While [session] is not checked yet a tapped
 * notification still waits for it: what was chosen since is what is wanted now.
 */
private fun AppContainer.chosen(session: TalkSession?, token: String? = null) {
    if (session?.checked == false && pendingDeepLink.value?.token != token) pendingDeepLink.value = null
}

/**
 * The navigation graph. [shown] (the account on screen), [tabRequest] and [listSearch] are state holders, read
 * and written inside the destinations, and [newChat] is one stable instance: a changing value captured by the
 * builder would rebuild the graph.
 */
@Composable
private fun TalqGraph(
    container: AppContainer,
    shown: State<TalkSession?>,
    nav: NavHostController,
    start: Any,
    tabRequest: MutableState<TabRequest?>,
    listSearch: MutableState<String>,
    newChat: NewConversationModel,
) {
    NavHost(navController = nav, startDestination = start) {
        // Signing in swaps the login screen for the list without the usual fade: on a wide
        // window the list then opens straight into two panes instead of growing out of a
        // full-width one.
        composable<LoginRoute>(exitTransition = { ExitTransition.None }) { LoginScreen() }

        composable<ChatListRoute>(
            enterTransition = { if (initialState.destination.hasRoute<LoginRoute>()) EnterTransition.None else null },
        ) { entry ->
            val session = shown.value ?: return@composable
            if (LocalTwoPane.current) {
                // The list itself is beside this pane.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    MessagePane(title = "Select a conversation", detail = "Pick one from the list to open it here.")
                }
                return@composable
            }
            ChatListScreen(
                session = session,
                search = listSearch,
                newChat = newChat,
                onOpenRoom = { token, draftTab ->
                    if (entry.isResumed()) {
                        container.chosen(session, token)
                        nav.openRoom(token, tabKey = draftTab)
                    }
                },
                onOpenTopic = { token, topicId ->
                    if (entry.isResumed()) {
                        container.chosen(session, token)
                        nav.openRoom(token, RoomTab.Topic(topicId))
                    }
                },
                onOpenSettings = {
                    if (entry.isResumed()) {
                        container.chosen(session)
                        nav.navigate(SettingsRoute())
                    }
                },
                onOpenPushSettings = { if (entry.isResumed()) nav.navigate(SettingsRoute(notifications = true)) },
                onUnauthorized = container::onUnauthorized,
            )
        }

        composable<SettingsRoute> { entry ->
            val session = shown.value ?: return@composable
            SettingsScreen(
                session = session,
                push = container.pushRegistrar,
                showNotifications = entry.toRoute<SettingsRoute>().notifications,
                onBack = { nav.back(entry) },
                onOpenDiagnostics = { if (entry.isResumed()) nav.navigate(DiagnosticsRoute) },
                // On the container's scope: leaving this screen must not cancel the sign-out.
                onLogout = { container.scope.launch { container.logout() } },
            )
        }

        composable<DiagnosticsRoute> { entry ->
            DiagnosticsScreen(session = shown.value?.takeIf { it.checked }, onBack = { nav.back(entry) })
        }

        composable<RoomRoute> { entry ->
            val session = shown.value ?: return@composable
            val twoPane = LocalTwoPane.current
            val activity = LocalActivity.current
            // Beside the list, back from a conversation leaves the app, as back from the list
            // does on a phone: the list is already on screen. Composed before the room's own
            // back handlers (an open sheet, a draft topic), so those still come first. A search in
            // the list beside is emptied first, as Back does on the list itself (whichever of the
            // two handlers Android asks first).
            // Esc is not Back here: while a conversation is open beside the list it only closes what
            // Back would close first (a search, an edit, the picking of messages...) and then does
            // nothing, as on desktop. Android makes Back of an Esc nobody took, so the activity
            // takes it (not a sheet's or a dialog's: those are windows of their own).
            val escape = remember { EscapeBesideList() }
            BackHandler(enabled = twoPane) {
                if (listSearch.value.isNotEmpty()) listSearch.value = "" else if (!escape.running) activity?.moveTaskToBack(true)
            }
            DisposableEffect(activity, twoPane) {
                val main = (activity as? MainActivity)?.takeIf { twoPane }
                val take: () -> Unit = {
                    escape.running = true
                    try {
                        main?.onBackPressedDispatcher?.onBackPressed()
                    } finally {
                        escape.running = false
                    }
                }
                main?.onEscape = take
                onDispose { if (main?.onEscape === take) main.onEscape = null }
            }
            if (!session.checked) {
                val route = entry.toRoute<RoomRoute>()
                // The tab picked there is the one the room opens on, when it does; not when it was left before.
                DisposableEffect(route.token) {
                    onDispose { if (shown.value?.checked != true && tabRequest.value?.token == route.token) tabRequest.value = null }
                }
                RoomWaitingScreen(
                    session, route.token, showBack = !twoPane, onBack = { nav.back(entry) }, tab = route.tab,
                    request = tabRequest.value?.takeIf { it.token == route.token }?.tab,
                    onTab = { tabRequest.value = TabRequest(route.token, it) },
                )
                return@composable
            }
            RoomHostScreen(
                session = session,
                route = entry.toRoute<RoomRoute>(),
                showBack = !twoPane,
                tabRequest = tabRequest.value,
                onTabRequestDone = { tabRequest.value = null },
                onBack = { nav.back(entry) },
                onOpenSharedFiles = { if (entry.isResumed()) nav.navigate(SharedFilesRoute(entry.toRoute<RoomRoute>().token)) },
            )
        }

        composable<SharedFilesRoute> { entry ->
            val session = shown.value ?: return@composable
            val token = entry.toRoute<SharedFilesRoute>().token
            if (!session.checked) {
                RoomWaitingScreen(session, token, showBack = true, onBack = { nav.back(entry) }, files = true)
                return@composable
            }
            SharedFilesScreen(
                session = session,
                token = token,
                onBack = { nav.back(entry) },
                // The message's own tab: its topic, or General (the whole chat without topics).
                onShowInChat = { m ->
                    if (entry.isResumed()) {
                        nav.showInRoom(token, if (m.topicId > 0) RoomTab.Topic(m.topicId) else RoomTab.General, m.id, tabRequest)
                    }
                },
            )
        }
    }
}

/** The Back being handled came from Esc ([running]): the app is not hidden. Not state: read in the handler only. */
private class EscapeBesideList {
    var running = false
}

/**
 * The one way into a conversation, for the chat list, notification links and anything else.
 * [tab] null opens the tab last open there; a given tab becomes the remembered one (the room
 * host records it). [tabKey] instead of a tab: where a draft waits (the topic being started is no
 * tab). The room always sits directly on the chat list: back leaves it in one press.
 */
internal fun NavHostController.openRoom(token: String, tab: RoomTab? = null, messageId: Long? = null, tabKey: Long? = tab?.key) {
    navigate(RoomRoute(token, tabKey ?: RoomRoute.REMEMBERED, messageId)) {
        popUpTo<ChatListRoute> { inclusive = false }
    }
}

/**
 * A notification about a topic opens that topic; one about a message outside the topics opens
 * General (the whole chat when the room has no topics); a bare conversation link opens the
 * remembered tab.
 */
private fun NavHostController.openDeepLink(link: DeepLink, tabRequest: MutableState<TabRequest?>) {
    val threadId = link.threadId?.takeIf { it > 0 }
    val tab = when {
        threadId != null -> RoomTab.Topic(threadId)
        link.messageId != null -> RoomTab.General
        else -> null
    }
    showInRoom(link.token, tab, link.messageId, tabRequest)
}

/**
 * [openRoom], except for the conversation that is already open (a notification, a share or
 * "Show in chat" about it): that one comes back to the front as it is, switches to [tab] and
 * scrolls to [messageId] in place. Opening it afresh would make a new back-stack entry and
 * lose every tab's place (the unsent text waits in `ComposerDrafts`).
 */
private fun NavHostController.showInRoom(token: String, tab: RoomTab?, messageId: Long?, tabRequest: MutableState<TabRequest?>) {
    if (openRoomToken() == token) {
        selectRoom(token)
        if (tab != null || messageId != null) tabRequest.value = TabRequest(token, tab, messageId)
    } else {
        openRoom(token, tab, messageId)
    }
}

/** The conversation [this] entry shows, itself or as its shared files. */
private fun NavBackStackEntry.openToken(): String? = when {
    destination.hasRoute<RoomRoute>() -> toRoute<RoomRoute>().token
    destination.hasRoute<SharedFilesRoute>() -> toRoute<SharedFilesRoute>().token
    else -> null
}

/** The conversation open in the back stack (there is at most one), or null. */
private fun NavHostController.openRoomToken(): String? =
    runCatching { getBackStackEntry<RoomRoute>() }.getOrNull()?.toRoute<RoomRoute>()?.token

/**
 * The wide layout's list picked [token]. The conversation already open comes back to the front
 * as it is (from its shared files or settings over it); any other is opened like on a phone, on
 * [tabKey] when given.
 */
private fun NavHostController.selectRoom(token: String, tabKey: Long? = null) {
    if (openRoomToken() == token) popBackStack<RoomRoute>(inclusive = false) else openRoom(token, tabKey = tabKey)
}

/** The wide layout's list has no entry of its own: a tap counts once the screen in front is settled. */
private fun NavHostController.topIsResumed(): Boolean = currentBackStackEntry?.isResumed() == true

/** Back from [entry]'s screen. A second tap during the transition must not pop a second screen. */
private fun NavHostController.back(entry: NavBackStackEntry) {
    if (entry.isResumed() && previousBackStackEntry != null) popBackStack()
}

/** Guards against a double tap navigating twice while the first transition is running. */
private fun NavBackStackEntry.isResumed(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
