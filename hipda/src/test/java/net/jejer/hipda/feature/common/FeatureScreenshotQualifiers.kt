package net.jejer.hipda.feature.common

/**
 * M5/M6 业务页面截图测试标准窗口限定符（对齐 `product-design.md` §10.1 与 `HiShellScreenshotTest`），
 * 供 `E1`/`E2a`/`E15`/`E3`/`E2b`/`E2c`/`E4` 直接复用，避免修改共享热点 `HiScreenshotTestSupport.kt`。
 */
object FeatureScreenshotQualifiers {
    const val PHONE_411_891: String = "w411dp-h891dp-xhdpi"
    const val FOLD8_PORTRAIT_559_749: String = "w559dp-h749dp-xhdpi"
    const val SIDEBAR_SINGLE_640_1024: String = "w640dp-h1024dp-xhdpi"
    const val FOLD8_LANDSCAPE_749_559: String = "w749dp-h559dp-xhdpi"
    const val EXPANDED_840_900: String = "w840dp-h900dp-xhdpi"
    const val EXPANDED_933_696: String = "w933dp-h696dp-xhdpi"
    const val TABLET_1280_800: String = "w1280dp-h800dp-xhdpi"
    const val COMPACT_891_411: String = "w891dp-h411dp-xhdpi"
    const val NARROW_340_800: String = "w340dp-h800dp-xhdpi"
}
