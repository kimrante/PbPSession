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
    NavHost(navController = nav, startDestination = "rooms") {
        composable("rooms") { RoomListScreen(nav) }
        composable(
            "chat/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.LongType }),
        ) { entry ->
            ChatScreen(nav, entry.arguments!!.getLong("roomId"))
        }
        composable(
            "profile/{profileId}",
            arguments = listOf(navArgument("profileId") { type = NavType.LongType }),
        ) { entry ->
            ProfileEditScreen(nav, entry.arguments!!.getLong("profileId"))
        }
        composable(
            "settings/{roomId}",
            arguments = listOf(navArgument("roomId") { type = NavType.LongType }),
        ) { entry ->
            RoomSettingsScreen(nav, entry.arguments!!.getLong("roomId"))
        }
    }
}
