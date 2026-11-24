package net.jejer.hipda.ui.designsystem

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage

/**
 * Roborazzi 截图测试公共配置，供设计系统与后续页面卡复用。
 *
 * - 录制基线：`./gradlew :hipda:recordRoborazziDebug`
 * - 校验基线：`./gradlew :hipda:verifyRoborazziDebug`
 */
object HiScreenshotTestSupport {
    const val SNAPSHOT_DIR: String = "src/test/snapshots/images"

    /** 标准截图宽度限定符（与 product-design.md §10.1 一致）。 */
    object Qualifiers {
        const val PHONE_411: String = "w411dp-h915dp-xhdpi"
        const val MEDIUM_700: String = "w700dp-h840dp-xhdpi"
        const val EXPANDED_900: String = "w900dp-h720dp-xhdpi"
        const val TABLET_1280: String = "w1280dp-h800dp-xhdpi"
        const val LANDSCAPE_891x411: String = "w891dp-h411dp-xhdpi"
        const val NARROW_340: String = "w340dp-h800dp-xhdpi"
    }

    fun snapshotPath(name: String): String = "$SNAPSHOT_DIR/$name.png"
}

fun SemanticsNodeInteraction.captureHiSnapshot(
    name: String,
    roborazziOptions: RoborazziOptions = RoborazziOptions(),
) {
    captureRoboImage(
        filePath = HiScreenshotTestSupport.snapshotPath(name),
        roborazziOptions = roborazziOptions,
    )
}
