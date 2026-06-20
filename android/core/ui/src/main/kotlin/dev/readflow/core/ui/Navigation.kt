package dev.readflow.core.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 自适应导航（设计文档 §3.1 / §三 BottomNav/NavRail）。
 * Compact: 底部文字导航，选中加墨点。
 * Medium/Expanded: 转侧轨 NavigationRail。
 *
 * Phase 1 只有 library 路由；Phase 2+ 加 reader/settings。这里是通用组件，
 * 实际 navItems 由调用方传入。
 */

data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * Compact 底部导航（手机竖屏）。
 */
@Composable
fun ReadflowBottomNav(
    items: List<NavItem>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette

    NavigationBar(
        modifier = modifier,
        containerColor = palette.paper,
        contentColor = palette.ink,
    ) {
        items.forEach { item ->
            ReadflowBottomNavItem(
                item = item,
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
                palette = palette,
            )
        }
    }
}

@Composable
private fun RowScope.ReadflowBottomNavItem(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
    palette: ReadflowPalette,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(item.icon, contentDescription = item.label) },
        label = { Text(item.label, style = ReadflowType.ui) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = palette.ink,
            selectedTextColor = palette.ink,
            unselectedIconColor = palette.inkSoft,
            unselectedTextColor = palette.inkSoft,
            indicatorColor = palette.paperDeep,
        ),
    )
}

/**
 * Medium/Expanded 侧轨导航（平板/横屏）。
 */
@Composable
fun ReadflowNavRail(
    items: List<NavItem>,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = readflowPalette

    NavigationRail(
        modifier = modifier,
        containerColor = palette.paper,
        contentColor = palette.ink,
    ) {
        items.forEach { item ->
            NavigationRailItem(
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
                icon = { Icon(item.icon, contentDescription = item.label) },
                label = { Text(item.label, style = ReadflowType.ui) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = palette.ink,
                    selectedTextColor = palette.ink,
                    unselectedIconColor = palette.inkSoft,
                    unselectedTextColor = palette.inkSoft,
                    indicatorColor = palette.paperDeep,
                ),
            )
        }
    }
}
