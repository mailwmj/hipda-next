package net.jejer.hipda.feature.compose.emoji

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dagger.Binds
import dagger.Module
import java.io.File
import javax.inject.Singleton
import net.jejer.hipda.R
import net.jejer.hipda.di.ComposeEmojiModule
import net.jejer.hipda.emoji.EmojiMap
import net.jejer.hipda.feature.compose.QuickReplyEmojiPanelRenderer
import net.jejer.hipda.feature.compose.calculateEmojiPanelHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E7 纯函数与契约单测：
 * - §7.4 回退限高全边界点（891 / 600 / 480 / 479 / 411dp）与记忆高度优先；
 * - 净软键盘高扣除导航栏 inset；
 * - 自适应列数与每格 `≥ 48dp` 数学不变量；
 * - `KeyboardHeightTracker` 防退场中间帧污染、实时锁存与切窗分桶；
 * - 闭合 `C1b#3`（映射 `:hipda` `strings_emoji.xml`、源码 0 消费 `EmojiGroup.name`）与 `ComposeEmojiModule` Hilt 单例绑定。
 */
class EmojiPanelHeightPolicyTest {

    @Test
    fun resolveEmojiPanelHeight_matchesSection74FallbackBreakpointsExactly() {
        assertEquals(280f, resolveEmojiPanelHeight(0.dp, 891.dp).value, 0.001f)
        assertEquals(270f, resolveEmojiPanelHeight(0.dp, 600.dp).value, 0.001f)
        assertEquals(216f, resolveEmojiPanelHeight(0.dp, 480.dp).value, 0.001f)
        assertEquals(167.65f, resolveEmojiPanelHeight(0.dp, 479.dp).value, 0.01f)
        assertEquals(143.85f, resolveEmojiPanelHeight(0.dp, 411.dp).value, 0.01f)
        assertEquals(0f, resolveEmojiPanelHeight(0.dp, 0.dp).value, 0.001f)

        // 沿用记忆键盘高度时直接等高返回，兼容 QuickReplySheet.calculateEmojiPanelHeight 入口
        assertEquals(260f, resolveEmojiPanelHeight(260.dp, 891.dp).value, 0.001f)
        assertEquals(310f, resolveEmojiPanelHeight(310.dp, 600.dp).value, 0.001f)
        assertEquals(167.65f, calculateEmojiPanelHeight(0.dp, 479.dp).value, 0.01f)
        assertEquals(143.85f, calculateEmojiPanelHeight(0.dp, 411.dp).value, 0.01f)
    }

    @Test
    fun netKeyboardHeightAndTotalHeight_subtractsNavBarAndRespectsFallbackCap() {
        val density = Density(2f)
        assertEquals(0, calculateNetKeyboardHeightPx(0, 48))
        assertEquals(0, calculateNetKeyboardHeightPx(40, 48))
        assertEquals(520, calculateNetKeyboardHeightPx(568, 48))
        assertEquals(260f, calculateNetKeyboardHeightDp(568, 48, density).value, 0.01f)

        // 记忆净高 260dp + 导航栏 24dp = 284dp（与系统 WindowInsets.ime 对齐实现底缘零位移）
        assertEquals(284f, resolveEmojiPanelTotalHeight(260.dp, 24.dp, 891.dp).value, 0.01f)
        assertEquals(24f, resolveEmojiPanelInnerNavPadding(284.dp, 260.dp, 24.dp).value, 0.01f)

        // 无键盘高度回退时总高严守 §7.4（411dp 矮屏不得因 24dp 导航栏超 143.85dp）
        assertEquals(143.85f, resolveEmojiPanelTotalHeight(0.dp, 24.dp, 411.dp).value, 0.01f)
        assertEquals(24f, resolveEmojiPanelInnerNavPadding(143.85.dp, 0.dp, 24.dp).value, 0.01f)
    }

    @Test
    fun calculateEmojiGridColumns_guaranteesMinimum48dpCellWidthAcrossWidths() {
        assertEquals(1, calculateEmojiGridColumns(0.dp))
        assertEquals(1, calculateEmojiGridColumns(40.dp))
        assertEquals(7, calculateEmojiGridColumns(340.dp))
        assertEquals(8, calculateEmojiGridColumns(384.dp))
        assertEquals(8, calculateEmojiGridColumns(411.dp))
        assertEquals(14, calculateEmojiGridColumns(680.dp))
        assertEquals(18, calculateEmojiGridColumns(891.dp))

        for (width in 48..1480 step 7) {
            val cols = calculateEmojiGridColumns(width.dp)
            assertTrue("宽度 ${width}dp 计算 $cols 列导致单格小于 48dp", width.toFloat() / cols >= 48f)
        }
    }

    @Test
    fun keyboardHeightTracker_preservesPeakAcrossImeHide_capturesOnSwitch_andIsolatesWindowBuckets() {
        val density = Density(2f)
        val tracker = KeyboardHeightTracker(rootView = null, initialWindowHeightBucketDp = 891)

        // 正常升起：0 -> 120 -> 260 -> 310dp
        tracker.observeInsets(0, 0, density, freezeUpdates = false)
        assertEquals(0.dp, tracker.rememberedNetHeightDp)
        tracker.observeInsets(240, 0, density, freezeUpdates = false)
        assertEquals(120.dp, tracker.rememberedNetHeightDp)
        tracker.observeInsets(520, 0, density, freezeUpdates = false)
        assertEquals(260.dp, tracker.rememberedNetHeightDp)
        tracker.observeInsets(620, 0, density, freezeUpdates = false)
        assertEquals(310.dp, tracker.rememberedNetHeightDp)

        // 退场动画途中（310 -> 200 -> 80 -> 0）不得污染峰值 310dp
        tracker.observeInsets(400, 0, density, freezeUpdates = false)
        assertEquals(310.dp, tracker.rememberedNetHeightDp)
        assertEquals(200.dp, tracker.pendingSmallerNetHeightDp)
        tracker.observeInsets(160, 0, density, freezeUpdates = false)
        assertEquals(310.dp, tracker.rememberedNetHeightDp)
        tracker.observeInsets(0, 0, density, freezeUpdates = false)
        assertEquals(310.dp, tracker.rememberedNetHeightDp)
        assertEquals(null, tracker.pendingSmallerNetHeightDp)

        // 若稳定停留在更矮键盘（如 260dp）超过防抖窗口，则提交新高度
        tracker.observeInsets(520, 0, density, freezeUpdates = false)
        tracker.commitPendingSmallerIfMatches(260.dp)
        assertEquals(260.dp, tracker.rememberedNetHeightDp)

        // 冻结期（表情面板已打开）忽略所有 insets 变化
        tracker.observeInsets(700, 0, density, freezeUpdates = true)
        assertEquals(260.dp, tracker.rememberedNetHeightDp)

        // 切换瞬间即时锁存当前净键盘高（扣除 24dp 导航栏：648px - 48px = 600px = 300dp）
        assertEquals(300.dp, tracker.captureCurrentIme(648, 48, density))
        assertEquals(300.dp, tracker.rememberedNetHeightDp)

        // 切至分屏/横屏新窗口高度桶后清零旧高度，触发 §7.4 回退限高
        tracker.syncWindowHeight(411.dp)
        assertEquals(0.dp, tracker.rememberedNetHeightDp)
    }

    @Test
    fun c1b3_emojiGroupsMapToLocalizedResources_noHardcodedGroupNameUsage_andDarkDrawablesDiffer() {
        assertEquals(3, EmojiMap.groups.size)
        assertEquals(R.string.emoji_group_default, resolveEmojiGroupLabelRes(EmojiMap.groups[0]))
        assertEquals(R.string.emoji_group_monkey, resolveEmojiGroupLabelRes(EmojiMap.groups[1]))
        assertEquals(R.string.emoji_group_dumb, resolveEmojiGroupLabelRes(EmojiMap.groups[2]))

        // 验证 3 组 61 个表情全部可解析，且酷猴与呆呆深浅色资源不同
        assertEquals(61, EmojiMap.groups.sumOf { it.codes.size })
        EmojiMap.groups.forEachIndexed { idx, group ->
            assertEquals(group.codes.first(), group.iconCode)
            group.codes.forEach { code ->
                val light = EmojiMap.drawableFor(code, dark = false)
                val dark = EmojiMap.drawableFor(code, dark = true)
                assertNotNull("亮色缺少资源: $code", light)
                assertNotNull("暗色缺少资源: $code", dark)
                if (idx > 0) assertNotEquals("深浅色应区分酷猴/呆呆资源: $code", light, dark)
            }
        }

        // 源码守卫：EmojiPickerPanel.kt 严禁直接读取 EmojiGroup.name（闭合 C1b#3）
        val moduleDir = listOf(File("."), File("hipda")).first { File(it, "src/main/java").isDirectory }
        val pickerSource = File(moduleDir, "src/main/java/net/jejer/hipda/feature/compose/emoji/EmojiPickerPanel.kt").readText()
        assertFalse("EmojiPickerPanel.kt 不得引用 group.name 硬编码文案", pickerSource.contains("group.name"))
    }

    @Test
    fun composeEmojiModule_declaresSingletonBindsForQuickReplyEmojiPanelRenderer() {
        val moduleClass = ComposeEmojiModule::class.java
        assertNotNull(moduleClass.getAnnotation(Module::class.java))
        val method = moduleClass.getDeclaredMethod("bindQuickReplyEmojiPanelRenderer", EmojiPanelRendererImpl::class.java)
        assertNotNull(method.getAnnotation(Binds::class.java))
        assertNotNull(method.getAnnotation(Singleton::class.java))
        assertEquals(QuickReplyEmojiPanelRenderer::class.java, method.returnType)

        val moduleDir = listOf(File("."), File("hipda")).first { File(it, "src/main/java").isDirectory }
        val moduleSrc = File(moduleDir, "src/main/java/net/jejer/hipda/di/ComposeEmojiModule.kt").readText()
        assertTrue("ComposeEmojiModule 应安装到 SingletonComponent", moduleSrc.contains("@InstallIn(SingletonComponent::class)"))
    }

    @Test
    fun previousEmojiOrGraphemeOffset_deletesAll61ForumCodes_chinese_surrogatePairs_andIncompleteCodes() {
        assertEquals(0, previousEmojiOrGraphemeOffset("", 0))
        assertEquals(0, previousEmojiOrGraphemeOffset("abc", 0))

        // 覆盖 :emoji C1b 全部 61 个规范论坛表情代码（含 :) 短码、:loveliness: 长码、{:2_41:} / {:3_57:}）
        EmojiMap.groups.flatMap { it.codes }.forEach { code ->
            val text = "前${code}后"
            val cursorAfterCode = 1 + code.length
            assertEquals("应整枚删除论坛表情 $code", 1, previousEmojiOrGraphemeOffset(text, cursorAfterCode))
        }

        // 残缺代码只删 1 个字符，不误删前文
        assertEquals(7, previousEmojiOrGraphemeOffset("头{:2_41:", 8))
        assertEquals(1, previousEmojiOrGraphemeOffset("头:)", 3))
        assertEquals(1, previousEmojiOrGraphemeOffset("头:", 2))

        // 普通中文、Unicode 代理对（😀 / 𠮷）与组合变音字形簇（e + combining acute）安全回删
        assertEquals(1, previousEmojiOrGraphemeOffset("你好", 2))
        val surrogatePair = "中😀𠮷"
        assertEquals(3, previousEmojiOrGraphemeOffset(surrogatePair, surrogatePair.length)) // 删 𠮷 (2 UTF-16 units)
        assertEquals(1, previousEmojiOrGraphemeOffset(surrogatePair, 3)) // 删 😀 (2 UTF-16 units)
        val combiningCluster = "ae\u0301"
        assertEquals(1, previousEmojiOrGraphemeOffset(combiningCluster, combiningCluster.length))
    }
}
