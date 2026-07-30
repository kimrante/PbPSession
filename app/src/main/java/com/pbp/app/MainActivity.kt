package com.pbp.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 키보드가 올라와도 창이 줄어들지 않게(배경 고정) — IME는 인셋으로 전달받아
        // 내용(imePadding)만 밀어올린다
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        com.pbp.app.ui.theme.AppFonts.load(this)
        com.pbp.app.data.OwnerProfile.load(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            PbpTheme {
                AppNav()
            }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.ROOMS) {
        composable(Routes.ROOMS) { RoomListScreen(nav) }
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
