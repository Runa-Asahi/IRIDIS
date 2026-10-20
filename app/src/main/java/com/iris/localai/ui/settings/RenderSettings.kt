package com.iris.localai.ui.settings

import androidx.compose.runtime.Composable
import com.iris.localai.data.AppSettings
import com.iris.localai.ui.components.Divider
import com.iris.localai.ui.components.SectionTitle
import com.iris.localai.ui.components.SettingsGroup
import com.iris.localai.ui.components.SwitchRow
import com.iris.localai.ui.icons.IrisIcons

/** 2.2 外观 › 聊天内容: rich rendering switches (kept in their own file so 外观 only gains one call). */
object RenderSettingsText {
    const val SECTION = "聊天内容"
    const val HTML = "渲染网页代码"
    const val HTML_SUB = "回复里的 HTML / SVG 代码显示为可预览的卡片"
    const val IMAGES = "加载网络图片"
    const val IMAGES_SUB = "显示回复里的网络图片；关闭后只显示本地图片"
}

@Composable
fun RenderSettingsSection(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    SectionTitle(RenderSettingsText.SECTION)
    SettingsGroup {
        SwitchRow(RenderSettingsText.HTML, s.renderHtml, subtitle = RenderSettingsText.HTML_SUB, icon = IrisIcons.Code) { on -> update { it.copy(renderHtml = on) } }
        Divider()
        SwitchRow(RenderSettingsText.IMAGES, s.loadNetworkImages, subtitle = RenderSettingsText.IMAGES_SUB, icon = IrisIcons.Image) { on -> update { it.copy(loadNetworkImages = on) } }
    }
}
