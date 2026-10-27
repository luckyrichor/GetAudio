package com.getaudio.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 菜单强调色：统一跟随主题主色，避免与「我的」页面的紫色体系割裂。 */
@Composable
internal fun musicAccent(): Color = MaterialTheme.colorScheme.primary

/** 菜单分组小标题。 */
@Composable
internal fun MusicMenuSection(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, bottom = 4.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
    )
}

/** 菜单项：无边框行 + 图标底托，靠分组和字重区分层级，而不是给每项描边。 */
@Composable
internal fun MusicMenuAction(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    status: String? = null,
    featured: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val accent = if (destructive) colors.error else musicAccent()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (featured) accent.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.11f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = accent)
        }
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = if (featured) FontWeight.SemiBold else FontWeight.Normal,
            color = if (destructive) colors.error else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (status != null) {
            Text(
                status,
                Modifier.widthIn(max = 96.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = if (featured) accent else colors.onSurfaceVariant.copy(alpha = 0.85f),
            )
        }
        if (!destructive && status == null) {
            Icon(
                Icons.Default.ChevronRight, null, Modifier.size(16.dp),
                tint = colors.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
internal fun NewMusicPlaylist(onClick: () -> Unit) {
    val accent = musicAccent()
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.2f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).background(accent, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, null, tint = if (accent.luminance() > 0.5f) Color(0xFF102A43) else Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("新建列表", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = accent)
                Text("收藏喜欢的音频", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = accent)
        }
    }
}

@Composable
internal fun MusicPlaylistCard(
    name: String,
    count: Int,
    current: Boolean,
    onOpen: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val accent = musicAccent()
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surface,
        border = BorderStroke(1.dp, if (current) accent.copy(alpha = 0.5f) else colors.outlineVariant),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(if (current) accent.copy(alpha = 0.12f) else colors.surfaceVariant,
                    RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.LibraryMusic, null, Modifier.size(26.dp), tint = if (current) accent else colors.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurface)
                    Text("$count 首音频", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
                if (current) Surface(color = accent.copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp)) {
                    Text("当前播放", Modifier.padding(horizontal = 7.dp, vertical = 5.dp), fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, color = accent)
                } else Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
            if (onRename != null || onDelete != null) {
                HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 12.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onRename != null) TextButton(onClick = onRename) {
                        Icon(Icons.Default.Edit, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text("改名", fontSize = 13.sp, color = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    if (onDelete != null) IconButton(onClick = onDelete) {
                        Icon(Icons.Default.DeleteOutline, "删除播放列表$name", Modifier.size(21.dp), tint = colors.error.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}
