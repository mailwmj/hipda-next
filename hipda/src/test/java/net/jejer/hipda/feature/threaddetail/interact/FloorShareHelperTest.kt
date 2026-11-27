package net.jejer.hipda.feature.threaddetail.interact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 覆盖 M7-P2 c 项（R-M7-G3 S2）：非回环主机分享链接一律输出 `https://`（含非 80 端口），回环地址行为不变。 */
class FloorShareHelperTest {

    @Test
    fun buildSafeFloorShareUrl_upgradesAllNonLoopbackHttpIncludingCustomPorts_andKeepsLoopbackUnchanged() {
        // 非回环自定义端口 http://example.com:8080 -> https://example.com:8080
        assertEquals(
            "https://example.com:8080/forum/viewthread.php?tid=2602293",
            buildSafeFloorShareUrl("http://example.com:8080", "2602293", 1, "1001"),
        )
        assertEquals(
            "https://example.com:8080/forum/redirect.php?goto=findpost&ptid=2602293&pid=9988",
            buildSafeFloorShareUrl("http://example.com:8080/forum/", "2602293", 5, "9988"),
        )

        // 非回环默认 80 端口 http://www.4d4y.com -> https://www.4d4y.com（省略默认端口）
        assertEquals(
            "https://www.4d4y.com/forum/viewthread.php?tid=2602293",
            buildSafeFloorShareUrl("http://www.4d4y.com", "2602293", 1, "1001"),
        )
        assertEquals(
            "https://www.4d4y.com/forum/redirect.php?goto=findpost&ptid=2602293&pid=9988",
            buildSafeFloorShareUrl("http://www.4d4y.com:80/forum", "2602293", 5, "9988"),
        )

        // 回环地址保持 http 不变
        assertEquals(
            "http://127.0.0.1:8080/forum/viewthread.php?tid=2602293",
            buildSafeFloorShareUrl("http://127.0.0.1:8080", "2602293", 1, "1001"),
        )
        assertEquals(
            "http://localhost:8080/forum/redirect.php?goto=findpost&ptid=2602293&pid=9988",
            buildSafeFloorShareUrl("http://localhost:8080/forum", "2602293", 5, "9988"),
        )
        assertEquals(
            "http://[::1]:8080/forum/viewthread.php?tid=2602293",
            buildSafeFloorShareUrl("http://[::1]:8080", "2602293", 1, "1001"),
        )
        assertEquals(
            "http://127.0.0.1/forum/viewthread.php?tid=2602293",
            buildSafeFloorShareUrl("http://127.0.0.1", "2602293", 1, "1001"),
        )

        // 危险 scheme 仍然全部拦截
        for (bad in listOf("javascript:alert(1)", "intent://evil/#Intent;end", "file:///etc/passwd", "content://a/b", "data:text/html,x", "mailto:a@b.com")) {
            assertNull("必须拦截 $bad", buildSafeFloorShareUrl(bad, "2602293", 1, "1001"))
        }
    }
}
