package com.pbp.app.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.pbp.app.ui.theme.GowunBatang
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens

/** 앱 전체 글꼴 선택 — 시스템 기본 / 고운 바탕(명조), 즉시 반영·유지 */
@Composable
fun FontSettingDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val tokens = Pbp.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("앱 글꼴") },
        text = {
            Column {
                listOf(
                    com.pbp.app.ui.theme.AppFonts.SYSTEM to "시스템 기본",
                    com.pbp.app.ui.theme.AppFonts.GOWUN to "고운 바탕 (명조)",
                    com.pbp.app.ui.theme.AppFonts.PRETENDARD to "프리텐다드 (고딕)",
                ).forEach { (value, label) ->
                    val selected = com.pbp.app.ui.theme.AppFonts.choice == value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PbpDimens.rCell))
                            .combinedClickable(onClick = {
                                com.pbp.app.ui.theme.AppFonts.set(context, value)
                            })
                            .padding(PbpDimens.sp3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (selected) "●" else "○",
                            color = if (selected) tokens.signature else tokens.inkDim,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.width(PbpDimens.sp2))
                        Text(
                            label,
                            fontSize = 13.sp,
                            fontFamily = when (value) {
                                com.pbp.app.ui.theme.AppFonts.GOWUN -> GowunBatang
                                com.pbp.app.ui.theme.AppFonts.PRETENDARD ->
                                    com.pbp.app.ui.theme.Pretendard
                                else -> null
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
