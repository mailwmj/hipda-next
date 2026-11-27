【审查卡 R-M7-P1】对象：`origin/card/M7-P1-image-polish` @ `fc63c2da0bffa0f18339a93dbeee0c0bedea73c8`（基线 `origin/main@0f067be`）；CI：Android CI #45949989116。任务卡：`/home/mir/Documents/hipda-next.wt/_cards/M7-P1.md`；实现报告：`/home/mir/Documents/hipda-next.wt/_reports/M7-P1.md`。通用清单：`/home/mir/Documents/hipda-next.wt/_cards/_review_common.md`。
本卡重点：
1. HD 直开阶段的 4096 上限是否与原图阶段复用同一常量/函数；宽高两轴都封顶，且不影响普通图的清晰度（小图不被缩小）。
2. 图集逐页 `floorNumber`/`author` 与坐标键：键在“同一 URL 多楼层”“同楼多图”“单图入口”三种情况下都唯一且稳定；注册表在楼层离屏回收、翻页后不残留旧坐标导致飞错位置。
3. `ImageViewerLaunchArgs`/`ImageViewerBoundsRegistry`（`feature/common/`）的接口改动是否影响其他调用方（`rg` 全部调用点）；与并行卡 M7-P2（改 `ThreadNavArgs`、`NextAppRoot.kt` Snackbar 余量）的 `git merge-tree` 冲突情况。
4. c 项 `media/` 精简是否纯重构：逐个核对被删重载/包装，确认行为零变化；被改的 4 个既有测试（`ContinuousPagingAnchorTest`、`ThreadInteractFrameAnchorTest`、`ImageLoadingPolicyTest`、`ImageScrollCompensationTest`）断言没有放宽。
5. A4 真实链路测试确实走 `NextAppRoot + ThreadDetailPane + 看图浮层`，而非替身拼装；A2/A3 测试改坏实现一行会红。
