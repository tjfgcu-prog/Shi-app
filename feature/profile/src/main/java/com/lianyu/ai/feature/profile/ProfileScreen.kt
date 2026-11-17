package com.lianyu.ai.feature.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.feature.profile.R
import androidx.compose.ui.geometry.Size
import com.lianyu.ai.database.model.BannerTransform



/**
 * 个人中心主页面 — 固定为 3 个卡片，位置不再随设置项数量变化：
 *   1. 顶部封面图大白框（点击上传，仅压缩尺寸不损画质）
 *   2. 个人资料卡（头像 + 昵称）
 *   3. 总设置入口
 *
 * 记忆管理、上下文记忆、API 设置、主题模式、聊天背景等次要项
 * 全部收纳进"总设置"页（见 GeneralSettingsScreen），本页不再直接承载。
 */
@Composable
fun ProfileScreen(
    // 总设置
    onGeneralSettingsClick: () -> Unit,

    viewModel: ProfileViewModel = viewModel()
) {
    val colorScheme = MaterialTheme.colorScheme

    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val userBanner by viewModel.userBanner.collectAsState()
    val bannerTransform by viewModel.bannerTransform.collectAsState()
    var editorUri by remember { mutableStateOf<String?>(null) }
    var bannerImageSize by remember(userBanner) { mutableStateOf(Size.Zero) }
    val bannerHeight = rememberBannerHeight()
    var isEditingName by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userName) }
    var isVisible by remember { mutableStateOf(true) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.updateUserAvatar(it.toString()) }
    }

    val bannerPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { editorUri = it.toString() }
        }

    

    Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
    // ===  1 封面图框（固定高度，可调整图片大小和位置）===
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                        .height(bannerHeight)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colorScheme.surface)
                        .clickable {
                            if (userBanner == null) bannerPicker.launch("image/*")
                            else editorUri = userBanner
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (userBanner != null) {
                        BannerImage(
                            model = userBanner,
                            transform = bannerTransform,
                            imageSize = bannerImageSize,
                            onImageSize = { bannerImageSize = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Image,
                                contentDescription = stringResource(R.string.profile_banner),
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.profile_banner_hint),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

        Spacer(modifier = Modifier.height(12.dp))

        // === 卡片 2：个人资料卡（头像 + 昵称） ===
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(200, delayMillis = 60)) + slideInVertically(tween(200, delayMillis = 60)) { it / 4 }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colorScheme.surfaceVariant)
                    .clickable { imagePicker.launch("image/*") }
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5E5E5))
                            .clickable { imagePicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (userAvatar != null) {
                            AsyncImage(
                                model = userAvatar,
                                contentDescription = stringResource(R.string.profile_avatar),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = stringResource(R.string.profile_avatar),
                                tint = Color(0xFFAAAAAA),
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isEditingName) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        modifier = Modifier.fillMaxWidth(0.8f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF07C160),
                            unfocusedBorderColor = colorScheme.outline,
                            focusedContainerColor = colorScheme.surface,
                            unfocusedContainerColor = colorScheme.surface
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (editName.isNotBlank()) viewModel.updateUserName(editName.trim())
                                isEditingName = false
                            }) {
                                Icon(Icons.Filled.Edit, stringResource(R.string.profile_save), tint = Color(0xFF07C160))
                            }
                        }
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { isEditingName = true; editName = userName }
                    ) {
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                            color = colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Filled.Edit, stringResource(R.string.profile_edit), tint = colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.profile_avatar_hint),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // === 卡片 3：总设置入口 — 记忆、API、主题/背景等次要项均已收纳进此页 ===
        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Settings, stringResource(R.string.general_settings), stringResource(R.string.general_settings_desc), onGeneralSettingsClick)
            ),
            isVisible = isVisible, delayMillis = 120
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
    editorUri?.let { uri ->
        val isNewImage = uri != userBanner
        BannerEditorDialog(
            imageUri = uri,
            initial = if (isNewImage) BannerTransform() else bannerTransform,
            onConfirm = { t ->
                viewModel.saveBanner(if (isNewImage) uri else null, t)
                editorUri = null
            },
            onReplace = { bannerPicker.launch("image/*") },
            onDismiss = { editorUri = null }
        )
            }
}

// ============================================================================
// 通用菜单组件
// ============================================================================

internal data class MenuItemData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val emojiIcon: String? = null
)

@Composable
internal fun SolidMenuGroup(items: List<MenuItemData>, isVisible: Boolean, delayMillis: Int) {
    val colorScheme = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(200, delayMillis = delayMillis)) +
                slideInVertically(tween(200, delayMillis = delayMillis)) { it / 4 }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items.forEachIndexed { index, item ->
                SolidMenuItem(item.icon, item.title, item.subtitle, item.onClick, index < items.size - 1, item.emojiIcon)
            }
        }
    }
}

@Composable
 internal fun SolidMenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, showDivider: Boolean, emojiIcon: String? = null) {
    val colorScheme = MaterialTheme.colorScheme
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (emojiIcon != null) {
    Text(emojiIcon, fontSize = 20.sp, modifier = Modifier.size(24.dp))
} else {
    Icon(icon, title, Modifier.size(24.dp), tint = Color(0xFF07C160))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp), color = colorScheme.onSurface)
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colorScheme.outline).padding(start = 36.dp))
        }
    }
 }
