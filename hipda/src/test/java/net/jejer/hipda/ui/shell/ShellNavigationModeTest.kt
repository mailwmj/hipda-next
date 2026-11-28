package net.jejer.hipda.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ShoppingCart
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellNavigationModeTest {

    @Test
    fun shellNavigationMode_matchesAllSpecBreakpoints() {
        listOf(340f to 800f, 411f to 891f, 559f to 749f, 599f to 800f, 599.9f to 800f).forEach { (w, h) ->
            assertEquals(
                "Expected BottomPill for ${w}x$h",
                ShellNavigationMode.BottomPill,
                shellNavigationMode(widthDp = w, heightDp = h, hingeSeparating = false),
            )
        }

        listOf(600f to 800f, 640f to 1024f, 679f to 900f, 680f to 900f, 701f to 841f, 744f to 860f, 749f to 559f, 800f to 1280f, 839f to 900f, 839.9f to 480f)
            .forEach { (w, h) ->
                assertEquals(
                    "Expected SidebarCollapsed for ${w}x$h",
                    ShellNavigationMode.SidebarCollapsed,
                    shellNavigationMode(widthDp = w, heightDp = h, hingeSeparating = false),
                )
            }

        listOf(840f to 480f, 840f to 900f, 852f to 883f, 900f to 1120f, 933f to 696f, 1280f to 800f).forEach { (w, h) ->
            assertEquals(
                "Expected SidebarExpanded for ${w}x$h",
                ShellNavigationMode.SidebarExpanded,
                shellNavigationMode(widthDp = w, heightDp = h, hingeSeparating = false),
            )
        }

        listOf(891f to 411f, 968f to 411f, 1280f to 479.9f).forEach { (w, h) ->
            assertEquals(
                "Expected CompactPill for ${w}x$h",
                ShellNavigationMode.CompactPill,
                shellNavigationMode(widthDp = w, heightDp = h, hingeSeparating = false),
            )
        }
    }

    @Test
    fun shellNavigationMode_hingeSeparatingForcesCompactPill() {
        listOf(411f to 891f, 700f to 900f, 749f to 559f, 840f to 900f, 1280f to 800f).forEach { (w, h) ->
            assertEquals(
                ShellNavigationMode.CompactPill,
                shellNavigationMode(widthDp = w, heightDp = h, hingeSeparating = true),
            )
        }
    }

    @Test
    fun showLabelForUnselected_switchesAt360dpBoundary() {
        assertFalse(showLabelForUnselected(340f))
        assertFalse(showLabelForUnselected(359f))
        assertFalse(showLabelForUnselected(359.9f))
        assertTrue(showLabelForUnselected(360f))
        assertTrue(showLabelForUnselected(411f))
    }

    @Test
    fun calculateShellPaneLayout_singlePaneCompactAndSidebarSinglePaneHaveZeroRightPane() {
        // 矮屏（< 480dp）
        val compact = calculateShellPaneLayout(widthDp = 891f, heightDp = 411f)
        assertEquals(ShellPaneLayoutMode.CompactSinglePane, compact.layoutMode)
        assertEquals(ShellNavigationMode.CompactPill, compact.navigationMode)
        assertEquals(0, compact.sidebarWidthDp)
        assertEquals(891, compact.middlePaneWidthDp)
        assertEquals(0, compact.rightPaneWidthDp)
        assertEquals(891, compact.sidebarWidthDp + compact.middlePaneWidthDp + compact.rightPaneWidthDp)
        assertTrue(compact.middlePaneSnapPointsDp.isEmpty())

        // 窄屏单栏（< 600dp），含临界浮点 599.6f（先浮点分档 SinglePane，再 roundToInt 得 w=600）
        listOf(340f to 340, 411f to 411, 559f to 559, 599f to 599, 599.6f to 600).forEach { (w, expectedW) ->
            val layout = calculateShellPaneLayout(widthDp = w, heightDp = 800f)
            assertEquals("width=$w", ShellPaneLayoutMode.SinglePane, layout.layoutMode)
            assertEquals("width=$w", ShellNavigationMode.BottomPill, layout.navigationMode)
            assertEquals("width=$w", expectedW, layout.windowWidthDp)
            assertEquals("width=$w", 0, layout.sidebarWidthDp)
            assertEquals("width=$w", expectedW, layout.middlePaneWidthDp)
            assertEquals("width=$w", 0, layout.rightPaneWidthDp)
            assertEquals("width=$w", layout.windowWidthDp, layout.sidebarWidthDp + layout.middlePaneWidthDp + layout.rightPaneWidthDp)
        }

        // 图标轨单栏（600–679dp），含临界浮点 679.6f（先浮点分档 SidebarSinglePane，再 roundToInt 得 w=680）
        listOf(600f to 536, 640f to 576, 679f to 615, 679.6f to 616).forEach { (w, expectedContent) ->
            val layout = calculateShellPaneLayout(widthDp = w, heightDp = 900f)
            assertEquals("width=$w", ShellPaneLayoutMode.SidebarSinglePane, layout.layoutMode)
            assertEquals("width=$w", ShellNavigationMode.SidebarCollapsed, layout.navigationMode)
            assertEquals("width=$w", 64, layout.sidebarWidthDp)
            assertFalse("width=$w", layout.isSidebarExpanded)
            assertFalse("width=$w", layout.canToggleSidebarPermanent)
            assertEquals("width=$w", expectedContent, layout.middlePaneWidthDp)
            assertEquals("width=$w", 0, layout.rightPaneWidthDp)
            assertEquals("width=$w", layout.windowWidthDp, layout.sidebarWidthDp + layout.middlePaneWidthDp + layout.rightPaneWidthDp)
            assertTrue("width=$w", layout.middlePaneSnapPointsDp.isEmpty())
        }
    }

    @Test
    fun calculateShellPaneLayout_threePaneWidthsExactIntegerClosureAndSnapPoints() {
        data class ExpectedThreePane(
            val widthDp: Float,
            val leftCollapsedPref: Boolean,
            val expectedMode: ShellPaneLayoutMode,
            val expectedL: Int,
            val expectedM: Int,
            val expectedR: Int,
            val expectedSnapsWithDetail: List<Int>,
        )

        val cases = listOf(
            ExpectedThreePane(680f, false, ShellPaneLayoutMode.CompactThreePane, 64, 256, 360, listOf(0, 256)),
            ExpectedThreePane(701f, false, ShellPaneLayoutMode.CompactThreePane, 64, 256, 381, listOf(0, 256)),
            ExpectedThreePane(744f, false, ShellPaneLayoutMode.CompactThreePane, 64, 272, 408, listOf(0, 272, 320)),
            ExpectedThreePane(749f, false, ShellPaneLayoutMode.CompactThreePane, 64, 274, 411, listOf(0, 274, 325)),
            ExpectedThreePane(748.59f, false, ShellPaneLayoutMode.CompactThreePane, 64, 274, 411, listOf(0, 274, 325)),
            ExpectedThreePane(800f, false, ShellPaneLayoutMode.CompactThreePane, 64, 294, 442, listOf(0, 294, 368)),
            ExpectedThreePane(839f, false, ShellPaneLayoutMode.CompactThreePane, 64, 310, 465, listOf(0, 310, 388)),
            // 临界浮点 839.6f：先按浮点 < 840f 判为 CompactThreePane，再 roundToInt 得 w=840
            ExpectedThreePane(839.6f, false, ShellPaneLayoutMode.CompactThreePane, 64, 310, 466, listOf(0, 310, 388)),
            ExpectedThreePane(840f, false, ShellPaneLayoutMode.ExpandedThreePane, 176, 266, 398, listOf(0, 266, 304)),
            ExpectedThreePane(840f, true, ShellPaneLayoutMode.ExpandedThreePane, 64, 310, 466, listOf(0, 310, 388)),
            ExpectedThreePane(851.69f, false, ShellPaneLayoutMode.ExpandedThreePane, 176, 270, 406, listOf(0, 270, 316)),
            ExpectedThreePane(852f, false, ShellPaneLayoutMode.ExpandedThreePane, 176, 270, 406, listOf(0, 270, 316)),
            ExpectedThreePane(860f, false, ShellPaneLayoutMode.ExpandedThreePane, 176, 274, 410, listOf(0, 274, 324)),
            ExpectedThreePane(883f, false, ShellPaneLayoutMode.ExpandedThreePane, 177, 282, 424, listOf(0, 282, 346)),
            ExpectedThreePane(900f, false, ShellPaneLayoutMode.ExpandedThreePane, 180, 288, 432, listOf(0, 288, 360)),
            ExpectedThreePane(933f, false, ShellPaneLayoutMode.ExpandedThreePane, 187, 298, 448, listOf(0, 298, 373)),
            ExpectedThreePane(933f, true, ShellPaneLayoutMode.ExpandedThreePane, 64, 348, 521, listOf(0, 348, 435)),
            ExpectedThreePane(1024f, false, ShellPaneLayoutMode.ExpandedThreePane, 205, 328, 491, listOf(0, 328, 410)),
            ExpectedThreePane(1100f, false, ShellPaneLayoutMode.ExpandedThreePane, 220, 352, 528, listOf(0, 352, 440)),
            ExpectedThreePane(1280f, false, ShellPaneLayoutMode.ExpandedThreePane, 220, 380, 680, listOf(0, 380, 480)),
        )

        for (c in cases) {
            val withDetail = calculateShellPaneLayout(
                widthDp = c.widthDp,
                heightDp = 700f,
                leftCollapsedPref = c.leftCollapsedPref,
                hasDetailContent = true,
            )
            val tag = "w=${c.widthDp}, collapsed=${c.leftCollapsedPref}"
            assertEquals(tag, c.expectedMode, withDetail.layoutMode)
            assertEquals(tag, c.expectedL, withDetail.sidebarWidthDp)
            assertEquals(tag, c.expectedM, withDetail.middlePaneWidthDp)
            assertEquals(tag, c.expectedR, withDetail.rightPaneWidthDp)
            assertEquals("$tag L+M+R == w", withDetail.windowWidthDp, withDetail.sidebarWidthDp + withDetail.middlePaneWidthDp + withDetail.rightPaneWidthDp)
            assertTrue("$tag R >= 360", withDetail.rightPaneWidthDp >= SHELL_RIGHT_PANE_MIN_WIDTH_DP)
            assertEquals("$tag snaps(detail=true)", c.expectedSnapsWithDetail, withDetail.middlePaneSnapPointsDp)

            // 右栏无内容时不得包含 0dp（列表收起）档
            val withoutDetail = calculateShellPaneLayout(
                widthDp = c.widthDp,
                heightDp = 700f,
                leftCollapsedPref = c.leftCollapsedPref,
                hasDetailContent = false,
            )
            assertFalse("$tag detail=false 不得包含 0dp 档", 0 in withoutDetail.middlePaneSnapPointsDp)
            assertEquals("$tag snaps(detail=false)", c.expectedSnapsWithDetail.filter { it > 0 }, withoutDetail.middlePaneSnapPointsDp)
        }
    }

    @Test
    fun calculateShellPaneLayout_verticalHingeTwoPaneAndFallbackRules() {
        // 749dp 铰链双栏（居中 0dp 折痕：374.5 | 374.5 → leftW=375 ≥ 280, rightW=374 ≥ 360）
        val fold8Hinge = calculateShellPaneLayout(
            widthDp = 749f,
            heightDp = 559f,
            hingeBoundsDp = ShellHingeBoundsDp(left = 374.5f, right = 374.5f),
            hasDetailContent = true,
        )
        assertEquals(ShellPaneLayoutMode.HingeTwoPane, fold8Hinge.layoutMode)
        assertEquals(ShellNavigationMode.CompactPill, fold8Hinge.navigationMode)
        assertEquals(0, fold8Hinge.sidebarWidthDp)
        assertEquals(375, fold8Hinge.middlePaneWidthDp)
        assertEquals(374, fold8Hinge.rightPaneWidthDp)
        assertTrue(fold8Hinge.middlePaneWidthDp >= HINGE_LEFT_MIN_WIDTH_DP)
        assertTrue(fold8Hinge.rightPaneWidthDp >= HINGE_RIGHT_MIN_WIDTH_DP)
        assertEquals(listOf(0, 375), fold8Hinge.middlePaneSnapPointsDp)
        assertEquals(ShellPillAnchor(startDp = 0, widthDp = 375), fold8Hinge.pillAnchor)

        // 749dp 铰链双栏右栏无内容时不含 0dp 档
        val fold8HingeNoDetail = calculateShellPaneLayout(
            widthDp = 749f,
            heightDp = 559f,
            hingeBoundsDp = ShellHingeBoundsDp(left = 374.5f, right = 374.5f),
            hasDetailContent = false,
        )
        assertEquals(listOf(375), fold8HingeNoDetail.middlePaneSnapPointsDp)

        // 701dp 铰链单侧不足（350.5 < 360）退回单栏，胶囊放较宽侧（等宽取左 351 ≥ 280）
        val pixelFoldHinge = calculateShellPaneLayout(
            widthDp = 701f,
            heightDp = 841f,
            hingeBoundsDp = ShellHingeBoundsDp(left = 350.5f, right = 350.5f),
        )
        assertEquals(ShellPaneLayoutMode.HingeFallbackSinglePane, pixelFoldHinge.layoutMode)
        assertEquals(ShellNavigationMode.CompactPill, pixelFoldHinge.navigationMode)
        assertEquals(701, pixelFoldHinge.middlePaneWidthDp)
        assertEquals(0, pixelFoldHinge.rightPaneWidthDp)
        assertEquals(ShellPillAnchor(startDp = 0, widthDp = 351), pixelFoldHinge.pillAnchor)

        // 260|440dp 非对称铰链退单栏（leftW < 280），胶囊放右侧宽区 [260, 700]
        val asymmetricHinge = calculateShellPaneLayout(
            widthDp = 700f,
            heightDp = 800f,
            hingeBoundsDp = ShellHingeBoundsDp(left = 260f, right = 260f),
        )
        assertEquals(ShellPaneLayoutMode.HingeFallbackSinglePane, asymmetricHinge.layoutMode)
        assertEquals(0, asymmetricHinge.rightPaneWidthDp)
        assertEquals(ShellPillAnchor(startDp = 260, widthDp = 440), asymmetricHinge.pillAnchor)

        // 559dp 铰链两侧均 < 280dp（279.5 < 280），退单栏且胶囊整窗底部居中
        val narrowHinge = calculateShellPaneLayout(
            widthDp = 559f,
            heightDp = 749f,
            hingeBoundsDp = ShellHingeBoundsDp(left = 279.5f, right = 279.5f),
        )
        assertEquals(ShellPaneLayoutMode.HingeFallbackSinglePane, narrowHinge.layoutMode)
        assertEquals(0, narrowHinge.rightPaneWidthDp)
        assertEquals(ShellPillAnchor(startDp = 0, widthDp = 559), narrowHinge.pillAnchor)

        // 非零 12dp 物理折痕 [368, 380]：749dp → left=368 ≥ 280, right=749-380=369 ≥ 360 → HingeTwoPane
        val widePhysicalHinge = calculateShellPaneLayout(
            widthDp = 749f,
            heightDp = 559f,
            hingeBoundsDp = ShellHingeBoundsDp(left = 368f, right = 380f),
            hasDetailContent = true,
        )
        assertEquals(ShellPaneLayoutMode.HingeTwoPane, widePhysicalHinge.layoutMode)
        assertEquals(ShellNavigationMode.CompactPill, widePhysicalHinge.navigationMode)
        assertEquals(368, widePhysicalHinge.middlePaneWidthDp)
        assertEquals(369, widePhysicalHinge.rightPaneWidthDp)
        assertEquals(749, widePhysicalHinge.middlePaneWidthDp + 12 + widePhysicalHinge.rightPaneWidthDp)
        assertEquals(listOf(0, 368), widePhysicalHinge.middlePaneSnapPointsDp)
        assertEquals(ShellPillAnchor(startDp = 0, widthDp = 368), widePhysicalHinge.pillAnchor)

        // 非零 12dp 物理折痕 [368, 380] 在 735dp 窗口下右侧 735-380=355 < 360，必须退单栏（锁死用 right 而非 left）
        val tightRightPhysicalHinge = calculateShellPaneLayout(
            widthDp = 735f,
            heightDp = 600f,
            hingeBoundsDp = ShellHingeBoundsDp(left = 368f, right = 380f),
        )
        assertEquals(ShellPaneLayoutMode.HingeFallbackSinglePane, tightRightPhysicalHinge.layoutMode)
        assertEquals(0, tightRightPhysicalHinge.rightPaneWidthDp)
        assertEquals(ShellPillAnchor(startDp = 0, widthDp = 368), tightRightPhysicalHinge.pillAnchor)

        // 非零 12dp 物理折痕 [260, 272] 在 700dp 窗口下左不足退单栏，胶囊放右侧 [272, 700]（宽 428）
        val asymmetricPhysicalHinge = calculateShellPaneLayout(
            widthDp = 700f,
            heightDp = 800f,
            hingeBoundsDp = ShellHingeBoundsDp(left = 260f, right = 272f),
        )
        assertEquals(ShellPaneLayoutMode.HingeFallbackSinglePane, asymmetricPhysicalHinge.layoutMode)
        assertEquals(0, asymmetricPhysicalHinge.rightPaneWidthDp)
        assertEquals(ShellPillAnchor(startDp = 272, widthDp = 428), asymmetricPhysicalHinge.pillAnchor)
    }

    @Test
    fun sidebarEntryAndDestination_bidirectionalMappingIsDeterministic() {
        // 宽 → 窄
        assertEquals(ShellDestination.Forum, ShellSidebarEntry.TodayNew.toShellDestination())
        assertEquals(ShellDestination.Forum, ShellSidebarEntry.Forum(2).toShellDestination())
        assertEquals(ShellDestination.Messages, ShellSidebarEntry.Sms.toShellDestination())
        assertEquals(ShellDestination.Messages, ShellSidebarEntry.Notify.toShellDestination())
        assertEquals(ShellDestination.Search, ShellSidebarEntry.Search.toShellDestination())
        assertEquals(ShellDestination.Mine, ShellSidebarEntry.FavoritesAndAttention.toShellDestination())
        assertEquals(ShellDestination.Mine, ShellSidebarEntry.MyThreads.toShellDestination())
        assertEquals(ShellDestination.Mine, ShellSidebarEntry.MyReplies.toShellDestination())
        assertEquals(ShellDestination.Mine, ShellSidebarEntry.History.toShellDestination())
        assertEquals(ShellDestination.Mine, ShellSidebarEntry.Settings.toShellDestination())

        // 窄 → 宽
        assertEquals(
            ShellSidebarEntry.TodayNew,
            mapDestinationToSidebarEntry(ShellDestination.Forum, activeForumFid = null),
        )
        assertEquals(
            ShellSidebarEntry.Forum(6),
            mapDestinationToSidebarEntry(ShellDestination.Forum, activeForumFid = 6),
        )
        assertEquals(
            ShellSidebarEntry.Sms,
            mapDestinationToSidebarEntry(ShellDestination.Messages, activeMessageEntry = ShellSidebarEntry.Sms),
        )
        assertEquals(
            ShellSidebarEntry.Notify,
            mapDestinationToSidebarEntry(ShellDestination.Messages, activeMessageEntry = ShellSidebarEntry.Notify),
        )
        assertEquals(
            ShellSidebarEntry.Search,
            mapDestinationToSidebarEntry(ShellDestination.Search),
        )
        // 我的：未访问子项默认收藏与关注
        assertEquals(
            ShellSidebarEntry.FavoritesAndAttention,
            mapDestinationToSidebarEntry(ShellDestination.Mine),
        )
        // 我的：停在总菜单时优先恢复最近访问子项
        assertEquals(
            ShellSidebarEntry.History,
            mapDestinationToSidebarEntry(
                destination = ShellDestination.Mine,
                lastVisitedMineEntry = ShellSidebarEntry.History,
            ),
        )
        // 我的：正在具体子页时直达该子页
        assertEquals(
            ShellSidebarEntry.MyReplies,
            mapDestinationToSidebarEntry(
                destination = ShellDestination.Mine,
                activeMineSubPage = ShellSidebarEntry.MyReplies,
                lastVisitedMineEntry = ShellSidebarEntry.History,
            ),
        )
        // 我的：非法历史值忽略并回退默认
        assertEquals(
            ShellSidebarEntry.FavoritesAndAttention,
            mapDestinationToSidebarEntry(
                destination = ShellDestination.Mine,
                lastVisitedMineEntry = ShellSidebarEntry.Search,
            ),
        )
    }

    @Test
    fun forumMetadata_hasAll21UniqueBuiltInForumsAndHandlesCollisionAndUnknownFallback() {
        val builtIns = ForumMetadataRegistry.BuiltInForums
        assertEquals("应包含全部 21 个内置版块", 21, builtIns.size)
        assertEquals("21 个内置版块 fid 应唯一", 21, builtIns.map { it.fid }.distinct().size)
        assertEquals("21 个内置版块矢量图标应互不撞车", 21, builtIns.mapNotNull { it.icon }.distinct().size)

        // 21 个内置版块一起关注时全部走居中纯矢量图标（无下方字母/文字）
        val allDisplays = resolveCollapsedForumDisplays(builtIns)
        builtIns.forEach { forum ->
            val display = allDisplays[forum.fid]
            assertTrue("内置版块 ${forum.fullName} 不应撞车", display is CollapsedForumDisplay.IconOnly)
            assertTrue("短标长度应为 1..2 字符（供未知/撞车回退）：${forum.shortLabel}", forum.shortLabel.length in 1..2)
        }

        // 未知 fid 回退为无图标 + 首字/短标徽标
        val unknownChinese = ForumMetadataRegistry.resolve(fid = 999, fallbackName = "摄影专区")
        assertNull(unknownChinese.icon)
        assertEquals("摄", unknownChinese.badgeMonogram)

        val unknownEnglish = ForumMetadataRegistry.resolve(fid = 998, fallbackName = "Photo Club")
        assertNull(unknownEnglish.icon)
        assertEquals("PC", unknownEnglish.badgeMonogram)

        val unknownFallbackDisplay = resolveCollapsedForumDisplays(listOf(unknownChinese, unknownEnglish))
        assertEquals(CollapsedForumDisplay.MonogramBadge("摄"), unknownFallbackDisplay[999])
        assertEquals(CollapsedForumDisplay.MonogramBadge("PC"), unknownFallbackDisplay[998])

        // 图标撞车检测：两版块映射到同一图标时自动回退为短标徽标，不撞车项保持纯矢量图标
        val sharedIcon = Icons.Rounded.ShoppingCart
        val collidingA = ForumMetadata(fid = 101, fullName = "交易一区", shortLabel = "交", icon = sharedIcon)
        val collidingB = ForumMetadata(fid = 102, fullName = "二手市场", shortLabel = "二", icon = sharedIcon)
        val uniqueC = ForumMetadataRegistry.resolve(2)
        assertNotNull(uniqueC.icon)

        val collisionResult = resolveCollapsedForumDisplays(listOf(collidingA, collidingB, uniqueC))
        assertEquals(CollapsedForumDisplay.MonogramBadge("交"), collisionResult[101])
        assertEquals(CollapsedForumDisplay.MonogramBadge("二"), collisionResult[102])
        assertTrue(collisionResult[2] is CollapsedForumDisplay.IconOnly)
    }

    @Test
    fun stringsShellXml_containsNoForbiddenTerminologyPhrases() {
        val candidates = listOf(
            File("src/main/res/values/strings_shell.xml"),
            File("hipda/src/main/res/values/strings_shell.xml"),
        )
        val stringsFile = candidates.firstOrNull { it.isFile }
            ?: error("找不到 strings_shell.xml")
        val content = stringsFile.readText()
        val forbiddenPhrases = listOf(
            "回帖", "回复帖子", "发表新帖", "发表主题", "发布新帖", "发布帖子",
            "按发表排序", "按发表时间排序", "引用回帖", "私信", "站内信",
            "信息提醒", "消息提醒", "帖子提醒", "消息通知", "只看主帖作者",
            "只看该作者", "只查看该作者", "搜索帖子", "他的帖子", "版面",
            "浏览记录", "屏蔽列表", "注销", "清除账号", "退出并清除",
            "没有数据", "暂无数据", "请稍候", "正在处理",
        )
        forbiddenPhrases.forEach { phrase ->
            assertFalse("strings_shell.xml 不得包含禁用词组 '$phrase'", phrase in content)
        }
    }
}
