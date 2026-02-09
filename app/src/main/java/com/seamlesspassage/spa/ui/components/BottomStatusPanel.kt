package com.seamlesspassage.spa.ui.components

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

@Composable
fun BottomStatusPanel(
    modifier: Modifier = Modifier,
    statusTitle: String,
    statusSubtitle: String?,
    statusType: StatusType,
) {
    val h = LocalConfiguration.current.screenHeightDp
    val panelHeight = (h * 0.25f).dp

    val context = LocalContext.current
    val versionName = remember {
        try {
            val pm = context.packageManager
            val pkg = context.packageName
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
            pInfo.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    val currentYear = remember {
        Calendar.getInstance().get(Calendar.YEAR)
    }

    val bgColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(panelHeight)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val iconTint = when (statusType) {
                    StatusType.Success -> Color(0xFF2E7D32)
                    StatusType.Warning -> Color(0xFFF9A825)
                    StatusType.Error -> Color(0xFFC62828)
                    StatusType.Info -> MaterialTheme.colorScheme.primary
                }
                val icon = when (statusType) {
                    StatusType.Success -> Icons.Default.CheckCircle
                    StatusType.Warning -> Icons.Default.Info
                    StatusType.Error -> Icons.Default.Close
                    StatusType.Info -> Icons.Default.Info
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = statusTitle,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (!statusSubtitle.isNullOrBlank()) {
                Text(
                    text = statusSubtitle!!,
                    fontSize = 33.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (versionName.isNotBlank()) {
                val footerText = "博库信息技术 © $currentYear v$versionName"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = footerText,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        }
    }
}

enum class StatusType { Success, Warning, Error, Info }
