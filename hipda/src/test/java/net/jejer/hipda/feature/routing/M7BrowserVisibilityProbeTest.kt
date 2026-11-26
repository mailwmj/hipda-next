package net.jejer.hipda.feature.routing

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager

/**
 * R-E16 S1 浏览器可见性对抗探针（对应 V-M7-1 探针 3）：
 * 1. 验证第三方浏览器仅声明 `ACTION_VIEW + CATEGORY_BROWSABLE + https`（未声明 `CATEGORY_APP_BROWSER`）
 *    且本应用同样匹配 `4d4y.com` 边缘 URL 时，`AndroidExternalLinkOpener` 准确拉起外部浏览器且零自环；
 * 2. 直接锁定 `PostLinkDispatcher.kt` 新增的 `https://` `BROWSABLE` 探测分支（当 URL 本身只匹配自身或 `http://` 场景，
 *    外部浏览器仅通过 `https:// + BROWSABLE` 探测返回时，撤回该探测立即失败）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class M7BrowserVisibilityProbeTest {

    @Test
    fun probe3_browsableOnlyExternalBrowserLaunchedWhenSelfMatchesEdgeUrl() {
        val appContext = ApplicationProvider.getApplicationContext<Application>()
        val shadowPm = Shadows.shadowOf(appContext.packageManager)

        val selfEdgeComponent = ComponentName(appContext.packageName, "net.jejer.hipda.ng.IntentActivity")
        registerComponentWithFilter(
            shadowPm = shadowPm,
            component = selfEdgeComponent,
            exported = true,
            filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                addDataAuthority("www.4d4y.com", null)
                addDataPath("/forum/space.php", android.os.PatternMatcher.PATTERN_PREFIX)
            },
        )

        val thirdPartyBrowser = ComponentName("com.vendor.browsableonly", "com.vendor.browsableonly.BrowserActivity")
        registerComponentWithFilter(
            shadowPm = shadowPm,
            component = thirdPartyBrowser,
            exported = true,
            filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("https")
                // 故意不注册 CATEGORY_APP_BROWSER
            },
        )

        val launched = mutableListOf<Intent>()
        val opener = AndroidExternalLinkOpener(appContext) { launched += it }
        val edgeUrl = "https://www.4d4y.com/forum/space.php?username=edge_probe"
        assertTrue(opener.open(edgeUrl))
        assertEquals(1, launched.size)
        val launchedIntent = launched.single()
        assertEquals(thirdPartyBrowser, launchedIntent.component)
        assertEquals(edgeUrl, launchedIntent.dataString)
        assertFalse("Must never self-launch", launchedIntent.component?.packageName == appContext.packageName)
    }

    @Test
    fun probe3_httpsBrowsableFallbackProbeRecoversBrowserWithoutAppBrowserCategory() {
        val appContext = ApplicationProvider.getApplicationContext<Application>()
        val selfComponent = ComponentName(appContext.packageName, "net.jejer.hipda.ng.IntentActivity")
        val externalBrowser = ComponentName("com.vendor.onlyhttpsbrowsable", "com.vendor.onlyhttpsbrowsable.Main")

        // 模拟真实设备/过滤场景：针对具体边缘 URL 仅返回本应用（selfMatches=true, externalTargets 空），
        // 且 CATEGORY_APP_BROWSER 为空，只有新增的 https:// + BROWSABLE 探测能返回第三方浏览器。
        val fakePm = object : DelegatingPackageManager(appContext.packageManager) {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
                return when {
                    intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_APP_BROWSER) ->
                        mutableListOf()
                    intent.action == Intent.ACTION_VIEW &&
                        intent.hasCategory(Intent.CATEGORY_BROWSABLE) &&
                        intent.dataString == "https://" ->
                        mutableListOf(resolveInfoFor(externalBrowser, exported = true))
                    intent.action == Intent.ACTION_VIEW &&
                        intent.data?.host == "www.4d4y.com" ->
                        mutableListOf(resolveInfoFor(selfComponent, exported = true))
                    else -> mutableListOf()
                }
            }
        }
        val wrappedContext = object : ContextWrapper(appContext) {
            override fun getPackageManager(): PackageManager = fakePm
        }

        val launched = mutableListOf<Intent>()
        val opener = AndroidExternalLinkOpener(wrappedContext) { launched += it }
        val edgeUrl = "https://www.4d4y.com/forum/redirect.php?goto=nextnewset"
        assertTrue("https:// BROWSABLE probe must recover external browser", opener.open(edgeUrl))
        assertEquals(1, launched.size)
        assertEquals(externalBrowser, launched.single().component)
        assertEquals(edgeUrl, launched.single().dataString)
    }

    @Test
    fun probe3_unexportedHandlerRejectedAndNoSelfLoopWhenNoBrowserExists() {
        val appContext = ApplicationProvider.getApplicationContext<Application>()
        val selfComponent = ComponentName(appContext.packageName, "net.jejer.hipda.ng.IntentActivity")
        val unexportedThirdParty = ComponentName("com.vendor.private", "com.vendor.private.InternalBrowser")

        val fakePm = object : DelegatingPackageManager(appContext.packageManager) {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
                return when {
                    intent.action == Intent.ACTION_VIEW && intent.data?.host == "www.4d4y.com" ->
                        mutableListOf(
                            resolveInfoFor(selfComponent, exported = true),
                            resolveInfoFor(unexportedThirdParty, exported = false),
                        )
                    intent.action == Intent.ACTION_VIEW && intent.dataString == "https://" ->
                        mutableListOf(resolveInfoFor(unexportedThirdParty, exported = false))
                    else -> mutableListOf()
                }
            }
        }
        val wrappedContext = object : ContextWrapper(appContext) {
            override fun getPackageManager(): PackageManager = fakePm
        }

        val launched = mutableListOf<Intent>()
        val opener = AndroidExternalLinkOpener(wrappedContext) { launched += it }
        assertFalse(opener.open("https://www.4d4y.com/forum/space.php?username=isolated"))
        assertTrue("Zero launch attempts allowed when only self or unexported targets exist", launched.isEmpty())
    }

    @Test
    fun probe3_deduplicatesBrowserMatchedByBothAppBrowserAndHttpsBrowsable() {
        val appContext = ApplicationProvider.getApplicationContext<Application>()
        val selfComponent = ComponentName(appContext.packageName, "net.jejer.hipda.ng.IntentActivity")
        val dualBrowser = ComponentName("com.vendor.dual", "com.vendor.dual.Browser")

        val fakePm = object : DelegatingPackageManager(appContext.packageManager) {
            @Suppress("OVERRIDE_DEPRECATION")
            override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
                return when {
                    intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_APP_BROWSER) ->
                        mutableListOf(resolveInfoFor(dualBrowser, exported = true))
                    intent.action == Intent.ACTION_VIEW && intent.dataString == "https://" ->
                        mutableListOf(resolveInfoFor(dualBrowser, exported = true))
                    intent.action == Intent.ACTION_VIEW && intent.data?.host == "www.4d4y.com" ->
                        mutableListOf(resolveInfoFor(selfComponent, exported = true))
                    else -> mutableListOf()
                }
            }
        }
        val wrappedContext = object : ContextWrapper(appContext) {
            override fun getPackageManager(): PackageManager = fakePm
        }

        val launched = mutableListOf<Intent>()
        val opener = AndroidExternalLinkOpener(wrappedContext) { launched += it }
        assertTrue(opener.open("https://www.4d4y.com/forum/redirect.php?goto=nextnewset"))
        assertEquals(1, launched.size)
        assertEquals(
            "Single deduplicated browser must launch directly instead of chooser",
            dualBrowser,
            launched.single().component,
        )
        assertNotNull(launched.single().data)
    }

    private fun registerComponentWithFilter(
        shadowPm: ShadowPackageManager,
        component: ComponentName,
        exported: Boolean,
        filter: IntentFilter,
    ) {
        val info = ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            this.exported = exported
        }
        shadowPm.addOrUpdateActivity(info)
        shadowPm.addIntentFilterForActivity(component, filter)
    }

    private fun resolveInfoFor(component: ComponentName, exported: Boolean): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            this.exported = exported
        }
    }

    private abstract class DelegatingPackageManager(private val base: PackageManager) : PackageManager() {
        @Deprecated("Deprecated in Java")
        override fun getPackageInfo(packageName: String, flags: Int) = base.getPackageInfo(packageName, flags)
        @Deprecated("Deprecated in Java")
        override fun getPackageInfo(versionedPackage: android.content.pm.VersionedPackage, flags: Int) =
            base.getPackageInfo(versionedPackage, flags)
        override fun currentToCanonicalPackageNames(packageNames: Array<out String>) =
            base.currentToCanonicalPackageNames(packageNames)
        override fun canonicalToCurrentPackageNames(packageNames: Array<out String>) =
            base.canonicalToCurrentPackageNames(packageNames)
        override fun getLaunchIntentForPackage(packageName: String) = base.getLaunchIntentForPackage(packageName)
        override fun getLeanbackLaunchIntentForPackage(packageName: String) =
            base.getLeanbackLaunchIntentForPackage(packageName)
        @Deprecated("Deprecated in Java")
        override fun getPackageGids(packageName: String) = base.getPackageGids(packageName)
        @Deprecated("Deprecated in Java")
        override fun getPackageGids(packageName: String, flags: Int) = base.getPackageGids(packageName, flags)
        override fun getPackageUid(packageName: String, flags: Int) = base.getPackageUid(packageName, flags)
        @Deprecated("Deprecated in Java")
        override fun getPermissionInfo(permName: String, flags: Int) = base.getPermissionInfo(permName, flags)
        @Deprecated("Deprecated in Java")
        override fun queryPermissionsByGroup(permissionGroup: String?, flags: Int) =
            base.queryPermissionsByGroup(permissionGroup, flags)
        @Deprecated("Deprecated in Java")
        override fun getPermissionGroupInfo(groupName: String, flags: Int) = base.getPermissionGroupInfo(groupName, flags)
        @Deprecated("Deprecated in Java")
        override fun getAllPermissionGroups(flags: Int) = base.getAllPermissionGroups(flags)
        @Deprecated("Deprecated in Java")
        override fun getApplicationInfo(packageName: String, flags: Int) = base.getApplicationInfo(packageName, flags)
        @Deprecated("Deprecated in Java")
        override fun getActivityInfo(component: ComponentName, flags: Int) = base.getActivityInfo(component, flags)
        @Deprecated("Deprecated in Java")
        override fun getReceiverInfo(component: ComponentName, flags: Int) = base.getReceiverInfo(component, flags)
        @Deprecated("Deprecated in Java")
        override fun getServiceInfo(component: ComponentName, flags: Int) = base.getServiceInfo(component, flags)
        @Deprecated("Deprecated in Java")
        override fun getProviderInfo(component: ComponentName, flags: Int) = base.getProviderInfo(component, flags)
        @Deprecated("Deprecated in Java")
        override fun getInstalledPackages(flags: Int) = base.getInstalledPackages(flags)
        @Deprecated("Deprecated in Java")
        override fun getPackagesHoldingPermissions(permissions: Array<out String>, flags: Int) =
            base.getPackagesHoldingPermissions(permissions, flags)
        override fun checkPermission(permName: String, packageName: String) = base.checkPermission(permName, packageName)
        override fun isPermissionRevokedByPolicy(permName: String, packageName: String) =
            base.isPermissionRevokedByPolicy(permName, packageName)
        override fun addPermission(info: android.content.pm.PermissionInfo) = base.addPermission(info)
        override fun addPermissionAsync(info: android.content.pm.PermissionInfo) = base.addPermissionAsync(info)
        override fun removePermission(permName: String) = base.removePermission(permName)
        override fun checkSignatures(packageName1: String, packageName2: String) =
            base.checkSignatures(packageName1, packageName2)
        override fun checkSignatures(uid1: Int, uid2: Int) = base.checkSignatures(uid1, uid2)
        override fun getPackagesForUid(uid: Int) = base.getPackagesForUid(uid)
        override fun getNameForUid(uid: Int) = base.getNameForUid(uid)
        @Deprecated("Deprecated in Java")
        override fun getInstalledApplications(flags: Int) = base.getInstalledApplications(flags)
        override fun isInstantApp() = base.isInstantApp()
        override fun isInstantApp(packageName: String) = base.isInstantApp(packageName)
        override fun getInstantAppCookieMaxBytes() = base.getInstantAppCookieMaxBytes()
        override fun getInstantAppCookie() = base.getInstantAppCookie()
        override fun clearInstantAppCookie() = base.clearInstantAppCookie()
        override fun updateInstantAppCookie(cookie: ByteArray?) = base.updateInstantAppCookie(cookie)
        override fun getSystemSharedLibraryNames() = base.getSystemSharedLibraryNames()
        @Deprecated("Deprecated in Java")
        override fun getSharedLibraries(flags: Int) = base.getSharedLibraries(flags)
        override fun getChangedPackages(sequenceNumber: Int) = base.getChangedPackages(sequenceNumber)
        override fun getSystemAvailableFeatures() = base.getSystemAvailableFeatures()
        override fun hasSystemFeature(featureName: String) = base.hasSystemFeature(featureName)
        override fun hasSystemFeature(featureName: String, version: Int) = base.hasSystemFeature(featureName, version)
        @Deprecated("Deprecated in Java")
        override fun resolveActivity(intent: Intent, flags: Int) = base.resolveActivity(intent, flags)
        @Deprecated("Deprecated in Java")
        override fun queryIntentActivityOptions(
            caller: ComponentName?,
            specifics: Array<out Intent>?,
            intent: Intent,
            flags: Int,
        ) = base.queryIntentActivityOptions(caller, specifics, intent, flags)
        @Deprecated("Deprecated in Java")
        override fun queryBroadcastReceivers(intent: Intent, flags: Int) = base.queryBroadcastReceivers(intent, flags)
        @Deprecated("Deprecated in Java")
        override fun resolveService(intent: Intent, flags: Int) = base.resolveService(intent, flags)
        @Deprecated("Deprecated in Java")
        override fun queryIntentServices(intent: Intent, flags: Int) = base.queryIntentServices(intent, flags)
        @Deprecated("Deprecated in Java")
        override fun queryIntentContentProviders(intent: Intent, flags: Int) =
            base.queryIntentContentProviders(intent, flags)
        @Deprecated("Deprecated in Java")
        override fun resolveContentProvider(authority: String, flags: Int) = base.resolveContentProvider(authority, flags)
        @Deprecated("Deprecated in Java")
        override fun queryContentProviders(processName: String?, uid: Int, flags: Int) =
            base.queryContentProviders(processName, uid, flags)
        override fun getInstrumentationInfo(className: ComponentName, flags: Int) =
            base.getInstrumentationInfo(className, flags)
        override fun queryInstrumentation(targetPackage: String, flags: Int) =
            base.queryInstrumentation(targetPackage, flags)
        override fun getDrawable(packageName: String, resid: Int, appInfo: android.content.pm.ApplicationInfo?) =
            base.getDrawable(packageName, resid, appInfo)
        override fun getActivityIcon(activityName: ComponentName) = base.getActivityIcon(activityName)
        override fun getActivityIcon(intent: Intent) = base.getActivityIcon(intent)
        override fun getActivityBanner(activityName: ComponentName) = base.getActivityBanner(activityName)
        override fun getActivityBanner(intent: Intent) = base.getActivityBanner(intent)
        override fun getDefaultActivityIcon() = base.getDefaultActivityIcon()
        override fun getApplicationIcon(info: android.content.pm.ApplicationInfo) = base.getApplicationIcon(info)
        override fun getApplicationIcon(packageName: String) = base.getApplicationIcon(packageName)
        override fun getApplicationBanner(info: android.content.pm.ApplicationInfo) = base.getApplicationBanner(info)
        override fun getApplicationBanner(packageName: String) = base.getApplicationBanner(packageName)
        override fun getActivityLogo(activityName: ComponentName) = base.getActivityLogo(activityName)
        override fun getActivityLogo(intent: Intent) = base.getActivityLogo(intent)
        override fun getApplicationLogo(info: android.content.pm.ApplicationInfo) = base.getApplicationLogo(info)
        override fun getApplicationLogo(packageName: String) = base.getApplicationLogo(packageName)
        override fun getUserBadgedIcon(drawable: android.graphics.drawable.Drawable, user: android.os.UserHandle) =
            base.getUserBadgedIcon(drawable, user)
        override fun getUserBadgedDrawableForDensity(
            drawable: android.graphics.drawable.Drawable,
            user: android.os.UserHandle,
            badgeLocation: android.graphics.Rect?,
            badgeDensity: Int,
        ) = base.getUserBadgedDrawableForDensity(drawable, user, badgeLocation, badgeDensity)
        override fun getUserBadgedLabel(label: CharSequence, user: android.os.UserHandle) =
            base.getUserBadgedLabel(label, user)
        override fun getText(packageName: String, resid: Int, appInfo: android.content.pm.ApplicationInfo?) =
            base.getText(packageName, resid, appInfo)
        override fun getXml(packageName: String, resid: Int, appInfo: android.content.pm.ApplicationInfo?) =
            base.getXml(packageName, resid, appInfo)
        override fun getApplicationLabel(info: android.content.pm.ApplicationInfo) = base.getApplicationLabel(info)
        override fun getResourcesForActivity(activityName: ComponentName) = base.getResourcesForActivity(activityName)
        override fun getResourcesForApplication(app: android.content.pm.ApplicationInfo) =
            base.getResourcesForApplication(app)
        override fun getResourcesForApplication(packageName: String) = base.getResourcesForApplication(packageName)
        override fun verifyPendingInstall(id: Int, verificationCode: Int) = base.verifyPendingInstall(id, verificationCode)
        override fun extendVerificationTimeout(id: Int, verificationCodeAtTimeout: Int, millisecondsAfterTimeout: Long) =
            base.extendVerificationTimeout(id, verificationCodeAtTimeout, millisecondsAfterTimeout)
        override fun setInstallerPackageName(targetPackage: String, installerPackageName: String?) =
            base.setInstallerPackageName(targetPackage, installerPackageName)
        @Deprecated("Deprecated in Java")
        override fun getInstallerPackageName(packageName: String) = base.getInstallerPackageName(packageName)
        @Deprecated("Deprecated in Java")
        override fun addPackageToPreferred(packageName: String) = base.addPackageToPreferred(packageName)
        @Deprecated("Deprecated in Java")
        override fun removePackageFromPreferred(packageName: String) = base.removePackageFromPreferred(packageName)
        @Deprecated("Deprecated in Java")
        override fun getPreferredPackages(flags: Int) = base.getPreferredPackages(flags)
        @Deprecated("Deprecated in Java")
        override fun addPreferredActivity(
            filter: IntentFilter,
            match: Int,
            set: Array<out ComponentName>?,
            activity: ComponentName,
        ) = base.addPreferredActivity(filter, match, set, activity)
        @Deprecated("Deprecated in Java")
        override fun clearPackagePreferredActivities(packageName: String) = base.clearPackagePreferredActivities(packageName)
        @Deprecated("Deprecated in Java")
        override fun getPreferredActivities(
            outFilters: MutableList<IntentFilter>,
            outActivities: MutableList<ComponentName>,
            packageName: String?,
        ) = base.getPreferredActivities(outFilters, outActivities, packageName)
        override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) =
            base.setComponentEnabledSetting(componentName, newState, flags)
        override fun getComponentEnabledSetting(componentName: ComponentName) =
            base.getComponentEnabledSetting(componentName)
        override fun setApplicationEnabledSetting(packageName: String, newState: Int, flags: Int) =
            base.setApplicationEnabledSetting(packageName, newState, flags)
        override fun getApplicationEnabledSetting(packageName: String) = base.getApplicationEnabledSetting(packageName)
        override fun isSafeMode() = base.isSafeMode()
        override fun setApplicationCategoryHint(packageName: String, categoryHint: Int) =
            base.setApplicationCategoryHint(packageName, categoryHint)
        override fun getPackageInstaller() = base.getPackageInstaller()
        override fun canRequestPackageInstalls() = base.canRequestPackageInstalls()
    }
}
