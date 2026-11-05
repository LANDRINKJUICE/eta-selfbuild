package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 界面风险闸门的判据。刻意不依赖 Robolectric：纯逻辑留在普通 JVM 单测里才真能跑绿。
 *
 * 这套判据的价值在**两边**都要准：
 * 该拦的没拦 = 误点删数据；不该拦的拦了 = 用户被无意义的确认框烦死（2026-11-03 有过教训）。
 */
class UiRiskPolicyTest {

    @Test
    fun irreversibleLabelsAskFirst() {
        listOf("删除", "清空", "卸载", "退出登录", "恢复出厂设置", "删除并退出", "确认支付", "立即购买").forEach { label ->
            assertNotNull("「$label」应该拦下来", UiRiskPolicy.evaluate(listOf(label)))
        }
        assertNotNull(UiRiskPolicy.evaluate(listOf("Delete")))
        assertNotNull(UiRiskPolicy.evaluate(listOf("uninstall")))
        assertNotNull("大写也要认", UiRiskPolicy.evaluate(listOf("DELETE ACCOUNT")))
    }

    @Test
    fun reversibleWordingIsNotBlocked() {
        listOf("清除筛选", "移除标签", "取消", "返回", "搜索", "设置", "编辑", "已删除 3 项").forEach { label ->
            assertNull("「$label」不该拦", UiRiskPolicy.evaluate(listOf(label)))
        }
    }

    @Test
    fun longExplanatoryTextDoesNotTrigger() {
        // 长说明里出现「删除」不应误拦：这类文案通常是说明而不是按钮。
        val longText = "开启后，系统会在应用删除本地缓存时提示你，可随时关闭该提醒，详细规则见帮助文档"
        assertNull(UiRiskPolicy.evaluate(listOf(longText)))
    }

    @Test
    fun blankLabelsAreSafe() {
        assertNull(UiRiskPolicy.evaluate(emptyList()))
        assertNull(UiRiskPolicy.evaluate(listOf("", "   ")))
    }

    @Test
    fun descriptionFieldAlsoCounts() {
        // 图标按钮常常只有 content-desc，所以 desc 必须一起判。
        assertNotNull(UiRiskPolicy.evaluate(listOf("", "删除")))
    }
}
