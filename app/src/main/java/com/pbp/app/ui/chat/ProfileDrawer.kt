package com.pbp.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pbp.app.data.CharacterProfile
import com.pbp.app.ui.common.Avatar
import com.pbp.app.ui.common.ManagerRow
import com.pbp.app.ui.common.dashedBorder
import com.pbp.app.ui.theme.Pbp
import com.pbp.app.ui.theme.PbpDimens

/**
 * 프로필 전환 사이드바 (시안 ②) — 상단 바의 현재 프로필 아바타로 연다.
 *
 * M3 ModalNavigationDrawer는 좌측 고정이라 쓸 수 없어 커스텀 오버레이로 만든다.
 * 입력줄에 있던 프로필 스트립을 걷어내고 **전환·편집·추가를 여기 한 곳으로 모았다** —
 * 같은 동작이 두 자리에 있으면 프로필이 늘 때마다 입력 영역만 두꺼워진다.
 */
@Composable
internal fun ProfileDrawer(
    visible: Boolean,
    profiles: List<CharacterProfile>,
    activeId: Long?,
    onSwitch: (CharacterProfile) -> Unit,
    onEditProfile: (CharacterProfile) -> Unit,
    onAddProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = Pbp.colors
    if (!visible) return
    Box(Modifier.fillMaxSize()) {
        // 바깥을 탭하면 닫힌다. 딤은 다이얼로그와 같은 값 (§2 — 하드코딩 금지)
        Box(
            Modifier
                .fillMaxSize()
                .background(tokens.scrim)
                .clickable(onClick = onDismiss)
        )
        AnimatedVisibility(
            visible = true,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier
                    .width(PbpDimens.drawerWidth)
                    .fillMaxHeight()
                    .clip(
                        RoundedCornerShape(
                            topStart = PbpDimens.rSheet,
                            bottomStart = PbpDimens.rSheet,
                        )
                    )
                    .background(tokens.panel)
                    // 드로어 안을 탭해도 닫히지 않게 스크림의 클릭을 가로챈다
                    .clickable(enabled = false) {}
                    // 화면 전체를 덮는 오버레이라 상태 바·내비 바를 직접 피해야 한다 —
                    // 그러지 않으면 제목이 시계 아래로 들어간다
                    .systemBarsPadding()
                    .padding(vertical = PbpDimens.gap5, horizontal = PbpDimens.gap4),
            ) {
                Text(
                    "프로필 전환",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = tokens.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "지금 말하는 캐릭터를 고르세요",
                    fontSize = 11.sp,
                    color = tokens.inkDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(PbpDimens.gap4))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    profiles.forEach { profile ->
                        val on = profile.id == activeId
                        ManagerRow(
                            label = profile.name.ifBlank { "이름 없음" },
                            sub = when {
                                profile.isGm -> "GM · 명조 서술"
                                on -> "캐릭터 · 사용 중"
                                else -> "캐릭터"
                            },
                            selected = on,
                            onClick = {
                                onSwitch(profile)
                                onDismiss()
                            },
                            onLongClick = {
                                onEditProfile(profile)
                                onDismiss()
                            },
                        ) {
                            Avatar(
                                emoji = profile.emoji,
                                imagePath = profile.imagePath,
                                size = PbpDimens.avatarStrip,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(PbpDimens.gap2))
                Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.line))
                Spacer(Modifier.height(PbpDimens.gap2))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(PbpDimens.rCell))
                        .clickable {
                            onAddProfile()
                            onDismiss()
                        }
                        .padding(PbpDimens.gap3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(PbpDimens.avatarStrip)
                            .dashedBorder(tokens.line, PbpDimens.avatarStrip / 2),
                        contentAlignment = Alignment.Center,
                    ) { Text("＋", fontSize = 15.sp, color = tokens.inkDim) }
                    Spacer(Modifier.width(PbpDimens.gap3))
                    Text(
                        "프로필 추가",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = tokens.signatureInk,
                    )
                }
                Spacer(Modifier.height(PbpDimens.gap2))
                Text(
                    "행을 길게 누르면 프로필 편집 · 바깥을 탭하면 닫기",
                    fontSize = 10.sp,
                    color = tokens.inkDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
