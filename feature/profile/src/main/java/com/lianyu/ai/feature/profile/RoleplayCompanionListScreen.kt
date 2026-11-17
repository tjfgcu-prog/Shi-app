package com.lianyu.ai.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.database.model.CompanionEntity

/**
 * 总设置 → 角色扮演设置：列出所有角色的头像，点头像进入该角色的角色扮演设置
 * （剧情/恋爱比例、回复长度、状态卡、世界书）。
 * 「角色扮演模式」总开关在各角色的聊天详情页里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleplayCompanionListScreen(
    onNavigateBack: () -> Unit,
    onCompanionClick: (Long) -> Unit,
    viewModel: SelfProfileViewModel = viewModel()
) {
    val colorScheme = MaterialTheme.colorScheme
    val companions by viewModel.companions.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize().background(colorScheme.background).windowInsetsPadding(WindowInsets.statusBars),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("角色扮演设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = if (companions.isEmpty()) {
                    "还没有角色，先创建一个角色。"
                } else {
                    "点头像，设置该角色的剧情/恋爱比例、回复长度、状态卡和世界书。「角色扮演模式」开关在各角色的聊天详情页里。"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 84.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                items(companions, key = { it.id }) { c ->
                    RoleplayCompanionItem(c) { onCompanionClick(c.id) }
                }
            }
        }
    }
}

@Composable
private fun RoleplayCompanionItem(companion: CompanionEntity, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Color(0xFFE5E5E5)),
            contentAlignment = Alignment.Center
        ) {
            if (companion.avatarUrl != null) {
                AsyncImage(
                    model = companion.avatarUrl,
                    contentDescription = companion.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(Icons.Filled.Person, companion.name, tint = Color(0xFFAAAAAA), modifier = Modifier.size(32.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            companion.name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = colorScheme.onSurface
        )
    }
}
