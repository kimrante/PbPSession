package com.pbp.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import com.pbp.app.notify.MessageNotifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pbp.app.ui.chat.ChatScreen
import com.pbp.app.ui.profile.ProfileEditScreen
import com.pbp.app.ui.roomlist.RoomListScreen
import com.pbp.app.ui.roomsettings.RoomSettingsScreen
import com.pbp.app.ui.theme.PbpTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * 알림을 탭해 열린 방(Firestore 문서 ID). 화면이 준비되면 소비하고 비운다.
     * 앱이 떠 있는 상태로 탭하면 [onNewIntent]로, 꺼진 상태면 [onCreate]로 들어온다.
     */
    private var pendingRemoteRoomId by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeRoomIntent(intent)
    }

    private fun consumeRoomIntent(intent: Intent?) {
        intent?.getStringExtra(MessageNotifier.EXTRA_REMOTE_ROOM_ID)?.let {
            pendingRemoteRoomId = it
            // 화면 회전 등으로 같은 인텐트를 다시 읽어 방이 또 열리지 않게 제거
            intent.removeExtra(MessageNotifier.EXTRA_REMOTE_ROOM_ID)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 키보드가 올라와도 창이 줄어들지 않게(배경 고정) — IME는 인셋으로 전달받아
        // 내용(imePadding)만 밀어올린다
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        com.pbp.app.ui.theme.AppFonts.load(this)
        com.pbp.app.data.OwnerProfile.load(this)
        com.pbp.app.data.RecentColors.load(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        consumeRoomIntent(intent)
        setContent {
            PbpTheme {
                AppNav(
                    pendingRemoteRoomId = pendingRemoteRoomId,
                    onRoomOpened = { pendingRemoteRoomId = null },
                )
            }
        }
    }
}

@Composable
private fun AppNav(
    pendingRemoteRoomId: String? = null,
    onRoomOpened: () -> Unit = {},
) {
    val nav = rememberNavController()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as PbpApp
    // 알림 탭 → 해당 방으로. 원격 문서 ID를 로컬 방 ID로 바꿔 채팅 화면을 연다.
    // 방을 찾지 못하면(아직 참여 전 등) 아무것도 하지 않고 방 목록에 머문다.
    LaunchedEffect(pendingRemoteRoomId) {
        val remoteId = pendingRemoteRoomId ?: return@LaunchedEffect
        val roomId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.database.roomDao().findByRemoteId(remoteId)?.id
        }
        onRoomOpened()
        if (roomId != null) {
            nav.navigate(Routes.chat(roomId)) {
                // 알림으로 들어온 방은 목록 위에 한 장만 — 연달아 탭해도 쌓이지 않는다
                popUpTo(Routes.ROOMS)
                launchSingleTop = true
            }
        }
    }
    NavHost(navController = nav, startDestination = Routes.ROOMS) {
        composable(Routes.ROOMS) { RoomListScreen(nav) }
        composable(Routes.OWNER) { com.pbp.app.ui.profile.OwnerProfileScreen(nav) }
        composable(
            Routes.CHAT_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_ROOM_ID) { type = NavType.LongType }),
        ) { entry ->
            ChatScreen(nav, entry.arguments!!.getLong(Routes.ARG_ROOM_ID))
        }
        composable(
            Routes.PROFILE_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_PROFILE_ID) { type = NavType.LongType }),
        ) { entry ->
            ProfileEditScreen(nav, entry.arguments!!.getLong(Routes.ARG_PROFILE_ID))
        }
        composable(
            Routes.SETTINGS_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_ROOM_ID) { type = NavType.LongType }),
        ) { entry ->
            RoomSettingsScreen(nav, entry.arguments!!.getLong(Routes.ARG_ROOM_ID))
        }
    }
}
