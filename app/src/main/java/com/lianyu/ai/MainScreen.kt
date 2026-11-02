package com.lianyu.ai

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lianyu.ai.common.BatteryOptimizationHelper
import com.lianyu.ai.common.YandereModeManager
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.feature.chat.ui.screen.ChatDetailScreen
import com.lianyu.ai.feature.chat.ui.screen.ChatScreen

import com.lianyu.ai.feature.companion.ui.screen.ContactsScreen
import com.lianyu.ai.feature.companion.ui.screen.CreateCompanionScreen
import com.lianyu.ai.feature.groupchat.ui.CreateGroupScreen
import com.lianyu.ai.feature.groupchat.ui.GroupChatScreen
import com.lianyu.ai.feature.groupchat.ui.GroupDetailScreen
import com.lianyu.ai.feature.memory.MemoryScreen
import com.lianyu.ai.feature.profile.*
import com.lianyu.ai.feature.settings.ui.screen.*


import com.lianyu.ai.feature.backup.BackupScreen


import com.lianyu.ai.uicommon.theme.ThemeViewModel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonOutline
import kotlinx.coroutines.launch

/**
 * 主界面 — NavHost + Pager + Scaffold 编排。
 *
 * 路由: Home, Contacts, Profile, Chat(id), ChatDetail(id),
 *       GroupChat(id), GroupDetail(id), CreateGroup, CreateCompanion, EditCompanion(id),
 *       Settings, TtsSettings, TokenUsage, Theme, FrameRate, YandereMode,
 *       GeneralSettings, Memory, ContextMemory, SelfProfile, CompanionSelfProfile(id), DataBackup
 *   差分方程: S[k+1] = f(S[k], E[k])
 *   验证: 所有状态出度 ≥ 1 (popBackStack 保证)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(mainActivity: Activity) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val context = LocalContext.current
    val valActivity = context as ComponentActivity

    

    fun openCompanionChat(companionId: Long) {
        navController.navigate(MainRoute.Chat(companionId).route)
    }

    // 深度链接：通知点击后直接打开对应单聊
    LaunchedEffect(Unit) {
        val intent = valActivity.intent
        if (intent.getBooleanExtra("open_chat", false)) {
            val companionId = intent.getLongExtra("companion_id", -1L)
            if (companionId != -1L) {
                openCompanionChat(companionId)
                intent.removeExtra("open_chat")
                intent.removeExtra("companion_id")
            }
        }
    }

    // 引导弹窗
    MainScreenDialogs(
        onAutoStartSettings = { BatteryOptimizationHelper.openAutoStartSettings(context) },
        onBatterySettings = { BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context) }
    )

    val bottomNavItems = listOf(
        BottomNavItem(stringResource(R.string.nav_love), Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, "home"),
        BottomNavItem(stringResource(R.string.nav_contacts), Icons.Filled.Group, Icons.Outlined.Group, "contacts"),
        BottomNavItem(stringResource(R.string.nav_profile), Icons.Filled.Person, Icons.Outlined.PersonOutline, "profile")
    )

    val mainTabRoutes = bottomNavItems.map { it.route }
    val showBottomBar = currentRoute in mainTabRoutes

    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    // 记住离开 tab 页面前的 pager 位置，返回时恢复（用 rememberSaveable 防止 NavHost 过渡重建丢失）
    var lastTabPage by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(currentRoute) {
        if (currentRoute !in mainTabRoutes) {
            // 离开 tab 页面（进入聊天等）—— 记住当前位置
            lastTabPage = pagerState.currentPage
            return@LaunchedEffect
        }
        // 返回 tab 页面 —— 恢复到离开前的位置，而非强制定位到首页
        val targetPage = when (currentRoute) {
            "contacts" -> 1
            "profile" -> 2
            else -> lastTabPage
        }
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    // 横向滑动冲突处理: 不阻断横向, 让 pager 和子层各自处理各自轴
    val angleNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity.Zero
        }
    }

    val themeViewModel: ThemeViewModel = viewModel()
    val isDark by themeViewModel.isDarkTheme.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                FloatingGlassBottomNav(
    items = bottomNavItems,
    currentIndex = pagerState.currentPage,
                    onItemClick = { index ->
                        lastTabPage = index
                        coroutineScope.launch { pagerState.scrollToPage(index) }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = "home",
                enterTransition = {
                    fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing), initialOffsetX = { it / 4 })
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                        slideOutHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing), targetOffsetX = { -it / 6 })
                },
                popEnterTransition = {
                    fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing), initialOffsetX = { -it / 4 })
                },
                popExitTransition = {
                    fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                        slideOutHorizontally(animationSpec = tween(300, easing = FastOutSlowInEasing), targetOffsetX = { it / 4 })
                }
            ) {
                // === 主页 Pager ===
                composable(MainRoute.Home.route) {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = 0.4f),
                        modifier = Modifier.fillMaxSize()
                            .nestedScroll(angleNestedScrollConnection)
                    ) { page ->
                        // 🔒 性能修复：原来这里在 Pager 自带的滑动位移之上，又叠加了一层
                        // 淡入淡出+缩放动画，且每一帧滚动都要重新计算三个页面各自的动画状态，
                        // 快速来回切换时这部分计算叠加起来就会卡顿。Pager 的滑动本身已经足够
                        // 表达"切换"这个动作，这里去掉多余的那层动画，只保留是否渲染的判断。
                        if (page == pagerState.currentPage ||
                            page == pagerState.currentPage - 1 ||
                            page == pagerState.currentPage + 1
                        ) {
                            when (page) {
                                0 -> HomeScreen(
                                    onCompanionClick = { openCompanionChat(it) },
                                    onGroupClick = { navController.navigate(MainRoute.GroupChat(it).route) },
                                    onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                                    onCreateGroupClick = { navController.navigate(MainRoute.CreateGroup.route) }
                                )
                                1 -> ContactsScreen(
                                    onCompanionClick = { openCompanionChat(it) },
                                    onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                                    onEditClick = { navController.navigate(MainRoute.EditCompanion(it).route) },
                                    onGroupClick = { navController.navigate(MainRoute.GroupChat(it).route) },
                                    onCreateGroupClick = { navController.navigate(MainRoute.CreateGroup.route) }
                                )
                                2 -> ProfileScreen(
                                    // 总设置
                                    onGeneralSettingsClick = { navController.navigate(MainRoute.GeneralSettings.route) }
                                )
                            }
                        }
                    }
                }

                // === 伴侣创建/编辑 ===
                composable(MainRoute.CreateCompanion.route) { CreateCompanionScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.EditCompanion(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    CreateCompanionScreen(companionId = companionId, onNavigateBack = { navController.popBackStack() })
                }

                // === 聊天 ===
                composable(MainRoute.Chat(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    ChatScreen(companionId = companionId, onNavigateBack = { navController.popBackStack() }, onNavigateToDetail = { navController.navigate("chat_detail/$it") })
                }
                composable(MainRoute.ChatDetail(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val detailCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    // 从聊天页进入详情页时，复用聊天页的 ChatViewModel（拿不到就自己建，行为同前）
                    val chatEntry = remember(backStackEntry) {
                        runCatching {
                            navController.getBackStackEntry(MainRoute.Chat(0).route.replace("0", "{companionId}"))
                        }.getOrNull()?.takeIf { it.arguments?.getLong("companionId") == detailCompanionId }
                    }
                    ChatDetailScreen(
                        companionId = detailCompanionId,
                        onNavigateBack = { navController.popBackStack() },
                        chatViewModelStoreOwner = chatEntry
                    )
                }
                

                // === 群聊 ===
                composable(MainRoute.GroupChat(0).route.replace("0", "{groupId}"), arguments = listOf(navArgument("groupId") { type = NavType.LongType })) { backStackEntry ->
                    val groupId = backStackEntry.arguments?.getLong("groupId") ?: 0L
                    GroupChatScreen(groupId = groupId, onNavigateBack = { navController.popBackStack() }, onNavigateToDetail = { navController.navigate("group_detail/$it") })
                }
                composable(MainRoute.GroupDetail(0).route.replace("0", "{groupId}"), arguments = listOf(navArgument("groupId") { type = NavType.LongType })) { backStackEntry ->
                    val groupId = backStackEntry.arguments?.getLong("groupId") ?: 0L
                    GroupDetailScreen(groupId = groupId, onNavigateBack = { navController.popBackStack() }, onGroupDeleted = { navController.popBackStack("group_chat/$groupId", inclusive = true) })
                }
                composable(MainRoute.CreateGroup.route) { CreateGroupScreen(onNavigateBack = { navController.popBackStack() }) }

                // === 设置 ===
                composable(MainRoute.Settings.route) { SettingsScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.TtsSettings.route) { TtsSettingsScreen(onNavigateBack = { navController.popBackStack() }, isDarkTheme = isDark) }
                composable(MainRoute.TokenUsage.route) { TokenUsageScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.Memory.route) { MemoryScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.Theme.route) { ThemeScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity) }
                composable(MainRoute.FrameRate.route) { FrameRateScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity) }
                composable(MainRoute.YandereMode.route) {
                    val manager = ServiceRegistry.get(YandereModeManager::class.java)
                    if (manager != null) {
                        YandereModeScreen(onNavigateBack = { navController.popBackStack() }, yandereModeManager = manager)
                    }
                }
                
                composable(MainRoute.ContextMemory.route) { ContextMemoryScreen(onNavigateBack = { navController.popBackStack() }) }
                    composable(MainRoute.SelfProfile.route) {
                    SelfProfileScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onCompanionClick = { id -> navController.navigate(MainRoute.CompanionSelfProfile(id).route) }
                    )
                                }
                composable("companion_self_profile/{companionId}", arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    CompanionSelfProfileScreen(companionId = companionId, onNavigateBack = { navController.popBackStack() })
                }
                composable(MainRoute.GeneralSettings.route) {
                    GeneralSettingsScreen(
                        onNavigateBack = { navController.popBackStack() },
                        // 自己设定
                        onSelfProfileClick = { navController.navigate(MainRoute.SelfProfile.route) },
                        
                        // 记忆与管理
                        onMemoryClick = { navController.navigate(MainRoute.Memory.route) },
                        onContextMemoryClick = { navController.navigate(MainRoute.ContextMemory.route) },
                        // AI与外观
                        onSettingsClick = { navController.navigate(MainRoute.Settings.route) },
                        onThemeClick = { navController.navigate(MainRoute.Theme.route) },
                        onFrameRateClick = { navController.navigate(MainRoute.FrameRate.route) },
                        onTtsSettingsClick = { navController.navigate(MainRoute.TtsSettings.route) },
                        onTokenUsageClick = { navController.navigate(MainRoute.TokenUsage.route) },
                        
                        onDataBackupClick = { navController.navigate(MainRoute.DataBackup.route) },
                        
                        onYandereModeClick = { navController.navigate(MainRoute.YandereMode.route) }
                    )
                }
                
                composable(MainRoute.DataBackup.route) { BackupScreen(onNavigateBack = { navController.popBackStack() }) }
                
            }

            
        }
    }
}
