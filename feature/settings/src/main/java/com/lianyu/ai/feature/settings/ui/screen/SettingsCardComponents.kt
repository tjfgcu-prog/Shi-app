package com.lianyu.ai.feature.settings.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.feature.settings.R




@Composable
fun ProviderLogo(
    provider: ApiProvider,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp
) {
    val logoRes = when (provider) {
        ApiProvider.OPENAI -> R.drawable.logo_openai
        ApiProvider.ANTHROPIC -> R.drawable.logo_anthropic
        ApiProvider.GEMINI -> R.drawable.logo_gemini
        ApiProvider.DEEPSEEK -> R.drawable.logo_deepseek
        ApiProvider.DASHSCOPE -> R.drawable.logo_qwen
        ApiProvider.KIMI -> R.drawable.logo_kimi
        ApiProvider.XIAOMI -> R.drawable.logo_xiaomi
        ApiProvider.ZHIPU -> R.drawable.logo_zhipu
        ApiProvider.SILICONFLOW -> R.drawable.logo_siliconflow
        ApiProvider.OPENROUTER -> R.drawable.logo_openrouter
        ApiProvider.GROQ -> R.drawable.logo_groq
        else -> null
    }

    if (logoRes != null) {
        Image(
            painter = painterResource(id = logoRes),
            contentDescription = provider.displayName,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit
        )
    } else {
        val (text, bgColor, textColor) = when (provider) {
            ApiProvider.PARTNER -> Triple("C", Color(0xFF00ACC1), Color.White)
            ApiProvider.IFLYTEK -> Triple("讯", Color(0xFF1677FF), Color.White)
            ApiProvider.CUSTOM -> Triple("?", Color(0xFF888888).copy(alpha = 0.15f), Color(0xFF888888))
            else -> Triple("?", Color(0xFF888888).copy(alpha = 0.15f), Color(0xFF888888))
        }

        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = if (text.length > 1) (size.value * 0.35).sp else (size.value * 0.45).sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}


