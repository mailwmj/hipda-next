# 代码审查报告：R-M7-4b（收紧 R8 规则与 Release DEX / Mapping 守卫测试）

- **审查结论**：`APPROVE`
- **审查对象**：`origin/card/M7-4b-r8-tighten` @ `49c087646c69422dbe73f7ff9f97a62cd70b6afb`（基于 `origin/main@4c7f6a0` 开发，已无冲突合入最新 `origin/main@3fa3ea1`）
- **变更规模**：`2 files changed, 1,108 insertions(+), 50 deletions(-)`（`hipda/proguard-rules.txt` 由 72 行收紧至 45 行；新增守卫单测 `hipda/src/test/java/net/jejer/hipda/packaging/ProguardRulesGuardTest.kt` 共 1,085 行；零改动任何共享构建脚本、清单、包名、签名、`targetSdk` 或 `.github/`）
- **CI 核验**：[Android CI #45835004880](https://github.com/mailwmj/hipda-next/actions/runs/45835004880) — `headSha == 49c087646c69422dbe73f7ff9f97a62cd70b6afb`，`workflow_dispatch`（`emulator=true`），状态 `completed / success`：
  - `Build, Lint & Unit Tests`（Job `139961428395`）：`assembleDebug`、`assembleRelease`、`lintDebug`、Room schema 校验、`:core-parser:test`（100/100）+ `:hipda:testDebugUnitTest -Proborazzi.test.verify=true`（1,501/1,501，含 `ProguardRulesGuardTest` 5/5、`EmojiMapTest` 5/5、`CopyTerminologyScanTest` 3/3、`MotionTokenScanTest` 7/7；总计 **1,601/1,601 全绿**，Roborazzi **0 漂移**）、`Verify APK outputs` 全绿；
  - `Emulator Smoke Test (API 36)`（Job `139971052037`）：CI 临时调试签名 R8 full mode `hipda-ng-release-signed.apk`（`PID=3184`，`Displayed MainActivity +4s282ms`）与 `hipda-debug.apk`（`PID=2089`，`Displayed MainActivity +4s78ms`）双双通过冷启动冒烟，0 `FATAL EXCEPTION`、0 ANR。
- **只读与红线核验**：`/home/mir/Documents/hipda-next` 主目录零 checkout、零写文件、零提交、零编译；`gh release list` 为空，未私自构建正式签名包或发布 Release；本地编译严格遵守 `DISPATCH.md` §2（`flock` 串行 + 内存/worker 限制）。

---

## 1. 阻断项（Blocking Issues）

**无（0 项）**。全仓所有依赖反射、按名加载、序列化与 JNI/系统组件入口在 R8 full mode 下均已有库自带 consumer rules、AAPT2 生成规则或本仓最小定向规则完整保护，无任何需补的 `-keep` 缺口。

---

## 2. R8 规则收紧质量与守卫测试审查（对应 `M7-4b.md` 与 `R-M7-4.md` 重点 1/3/4）

### 2.1 `hipda/proguard-rules.txt`（72 → 45 行）与模块 consumer-rules 核查
1. **过宽通配与旧库死规则彻底清除**：
   - 已移除 `-keep class net.jejer.hipda.** { *; }`（原保留 2,461 个应用种子类，收紧后降至 19 个必要入口种子）与 `-keep class androidx.**` / `-keep interface androidx.**`（原保留 9,000+ AndroidX 种子类，收紧后降至 157 个）；
   - 已移除 `M7-2` 已删除旧依赖的全部死规则：Gson `TypeToken`、`net.gotev.cookiestore.**`、Glide、Android-Iconics（含过宽 `.R` / `**.R$*`）、`android-priority-jobqueue`、EventBus 3.x、Matisse/Picasso，以及 OkHttp 3/4 时代的死 `-dontwarn`（`org.openjsse.**` 等）与 `PublicSuffixDatabase` 旧规则、Jsoup 全包 public keep、重复的 `Parcelable` `CREATOR` 规则与过宽 `*Annotation*`；
   - 核查 `:emoji`（仅 `EmojiMap.kt` + 101 张 PNG）与 `:core-parser`（纯 JVM HTML 解析器，0 反射、0 `@Serializable`）：均无需额外 consumer-rules。
2. **保留规则最小化且逐段附有注释依据**：
   - `hipda/proguard-rules.txt:7-9`：`-keepattributes SourceFile,LineNumberTable`（补齐 `proguard-android-optimize.txt` 未内置的行号表，保障 `CrashLogExceptionHandler` 脱敏崩溃日志与 `mapping.txt` 堆栈还原）；
   - `hipda/proguard-rules.txt:11-45`：针对无自带 consumer rules 的 `io.github.kyant0:backdrop:2.0.1` 与 `shapes:1.2.1`，使用 `-keep,allowobfuscation` 精准保留液态玻璃核心管线的 8 个 `backdrop` 类（`AndroidRuntimeShader`、`RuntimeShaderCacheImpl`、`DrawBackdropElement`、`DrawBackdropNode`、`LayerBackdrop`、`LayerBackdropElement`、`LayerBackdropNode`、`LensKt`）与 2 个 `shapes` 类型（`RoundedRectangularShape`、`RoundedRectangularShape$Corners`）。实查 `mapping.txt` 证实：核心渲染管线与 AGSL 折射接口分发完整存活且名称正常混淆（如 `AndroidRuntimeShader -> mf`、`LensKt -> pp2`、`RoundedRectangularShape -> w84`），未使用的 `BlurKt`/`ShadersKt`/多余形状类已被 R8 内联或裁剪；`HiGlassSurface.kt:580-590` 同时保有 `IllegalArgumentException` 兜底降级。
3. **缩减成效复核（同代码 E7 基线 `83e0948` vs `49c0876`）**：
   - Release APK：`6,299,655 B` → **`4,874,190 B`**（**-1.36 MiB / -22.63%**）；
   - DEX 文件数：`2`（`classes.dex` + `classes2.dex`）→ **`1`（单 DEX `classes.dex`）**；
   - DEX 方法引用数：`70,418` → **`36,208`**（**-48.58%**）；DEX 定义类数：`19,188` → **`7,124`**（**-62.87%**）；
   - R8 `missing_rules.txt` 不存在（0 缺失规则警告）。

### 2.2 守卫测试 `ProguardRulesGuardTest.kt` 非空心核查
- **死规则扫描（`L80-111`）**：基于主源码与剔除测试依赖后的运行时 classpath 构建 `RuntimeClassIndex`（`L90-98` 自带正负双向哨兵断言防索引退化），校验规则头、超类与规则体注解/全限定类型均真实存在；
- **禁留旧库/旧类与全包通配拦截（`L114-144,525-569`）**：封禁 18 个旧库前缀与 `HiSettingsHelper`/`HiApplication`/`NotiWorker` 等旧类名，无条件禁止 `net.jejer.hipda`、`androidx`、`org.jsoup`、`okhttp3`、`coil3`、`kotlinx` 等宽泛前缀通配 keep，且断言当前全包通配数为 0；
- **负向自测（`L198-289`）**：覆盖死库规则、已删 `HiSettingsHelper`、死 `-dontwarn`、不存在的同包类、死方法注解、无注释/空行隔断通配、以及带长注释的禁留命名空间通配，证明校验器非空心；
- **Release 二进制 DEX + `mapping.txt` 实检（`L292-484`）**：在 `CI=true` 时强制断言 `hipda-ng-release.apk`、`mapping.txt`、`seeds.txt` 存在（杜绝静默跳过），直接解析 DEX 二进制 `class_defs`/`method_ids` 核验核心组件、8 个 `HiltViewModel`、5 组 `@Serializable` + `$$serializer`、E7 表情面板、7 个持久化枚举及 Kyant 类存活，且旧包零泄漏。

---

## 3. 反射与按名加载 15 项逐一实证（含 `configuration.txt` 规则依据与 `classes.dex` 字节码验证）

| # | 检查域 | 本仓实际使用点 | Release 可用性依据（consumer rules / AAPT2 / 本仓规则，行号取合并 `configuration.txt`） | `mapping.txt` 与 `classes.dex` 字节码实证 |
|---|---|---|---|---|
| 1 | **Hilt / Dagger 生成类** | `@HiltAndroidApp HiNextApplication`、`@AndroidEntryPoint MainActivity`、8 个 `@HiltViewModel`、`@EntryPoint ComposeSlotsEntryPoint`（`ComposeContracts.kt:119`） | ① `aapt_rules.txt`（`L1541-1542`）保留 `HiNextApplication` 与 `MainActivity` 未混淆类名及 `<init>()`，KSP 生成静态调用链；<br>② Hilt KSP 生成 `mergeReleaseGeneratedProguardFiles`（`L1549-1564` `-keep,allowobfuscation,allowshrinking class ...*ViewModel`）防类合并；<br>③ `dagger:2.60.1` `r8.pro`（`L1449-1451` `-identifiernamestring @dagger.internal.IdentifierNameString class ** { static java.lang.String *; }`）令 R8 同步改写 `@LazyClassKey` 映射表键名；<br>④ `hilt-android:2.60.1`（`L149-153`）保留 `@EntryPoint` 接口防合并。 | `dexdump` 实证全部 8 个 ViewModel（`k93`/`rk1`/`zb5`/`po1`/`c04`/`nu2`/`n03`/`wr4`）在 DEX 中同时存在类定义与同名 `const-string` 键（8/8 精确对齐）；`ComposeSlotsEntryPoint -> ka0` 接口存活并由 `SingletonC` 实现，`Class.cast` 安全通过。 |
| 2 | **Room 实体与 DAO** | `HiDatabase`（`HiDatabase.kt:12`）、`DatabaseModule.kt:24` `Room.databaseBuilder`、3 个 `@Entity`、3 个 `@Dao`、`HiDatabaseConverters` | `room-runtime-android:2.8.5`（`L952` `-keep class * extends androidx.room.RoomDatabase { void <init>(); }`）保留 `RoomDatabase` 子类未混淆类名与无参构造；`aapt_rules.txt`（`L1533`）保留 `MultiInstanceInvalidationService`。SQL 表/列名与游标映射均由 KSP 编译期静态生成，运行期零反射读实体字段名。 | `RoomDatabase$Builder` 拼接 `"_Impl"` 反射实例化 `net.jejer.hipda.data.local.HiDatabase_Impl`；DEX 中 `HiDatabase` 与 `HiDatabase_Impl` 类名及 `<init>()` 均未混淆保留；`HiDatabaseConverters`（`su1`）使用显式 `ListSerializer(String.serializer())` 与 `ReadingFilterMode.entries` 静态调用。 |
| 3 | **kotlinx.serialization** | 全仓共 5 个 `@Serializable`：`CredentialsSnapshot`、`SavedAccount`、`AccountNotifyReadState`、`StoredCookie`、`StoredCookieStore`（`:core-parser` 为 0 个） | `kotlinx-serialization-core-jvm:1.13.0` 自带 `kotlinx-serialization-common.pro` + `kotlinx-serialization-r8.pro`（`L1078-1158`）：保留 `@Serializable` 类的 `Companion`、`serializer(...)`、`INSTANCE`、`RuntimeVisibleAnnotations` 及 `**$$serializer.descriptor`。全仓均通过编译期静态绑定 `.serializer()` 调用，无未注册多态反射。 | 5 个数据类（`rg0`/`oa4`/`k4`/`vw4`/`yw4`）与 5 个 `$$serializer`（`og0`/`ma4`/`i4`/`tw4`/`ww4`）全部存活；`dexdump` 确认所有 JSON 属性名（`accounts`/`activeUid`/`uid`/`passwordMd5`/`cookies` 等）已编译期固化进 `$$serializer` 字符串池，不受宿主字段重命名影响。 |
| 4 | **DataStore / Proto** | 5 个 `PreferenceDataStoreFactory`（底层 `PreferencesProto`）+ 2 个 `DataStoreFactory`（Tink + JSON `Serializer`）；无独立自定义 `.proto` | `datastore-preferences-core-android:1.3.0`（`L962-964` `-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }`）保留内部 Protobuf 反射字段名；`datastore-core-android:1.3.0`（`L974-976`）保留 JNI `NativeSharedCounter`。 | `mapping.txt` 实证 `PreferencesProto$PreferenceMap` 的 `preferences_ -> preferences_`、`DEFAULT_INSTANCE`、`PARSER` 均未混淆保留，`MessageSchema`（`Lyy2;`）按字段名反射完全匹配。 |
| 5 | **Tink** | `TinkAeadProvider.kt:41,176,241-251`（`AeadConfig.register()`、`KeyTemplates.get("AES256_GCM")`、`AndroidKeysetManager`、`AeadSerializer`） | `tink-android:1.24.0` 自带 `META-INF/proguard/protobuf.pro`（`L1159-1175` `-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }`）；传递依赖 `gson:2.13.2` 自带 `gson.pro`（`L1454-1528`）。 | `mapping.txt` 实证 `Keyset.key_ -> key_`、`AesGcmKey.keyValue_ -> keyValue_` 等全部 Tink Proto 字段名原样保留；可选 Conscrypt/Unsafe 探测均带 `try/catch` 回退 Android Keystore/JCE；CI API 36 冷启动冒烟实测零异常。 |
| 6 | **WorkManager Worker** | `NotificationPollingWorker`（`@HiltWorker`，`NotificationPollingWorker.kt:41`）+ `HiNextApplication` `HiltWorkerFactory` | ① `hilt-work:1.4.0`（`L143` `-keepnames @androidx.hilt.work.HiltWorker class * extends androidx.work.ListenableWorker`）；<br>② `work-runtime:2.12.0`（`L323-336` 保留 `ListenableWorker` 子类名与 `public <init>(...)`）；<br>③ `aapt_rules.txt`（`L1535-1539`）保留 WorkManager 5 个系统组件。 | DEX 中 `Lnet/jejer/hipda/service/NotificationPollingWorker;` 未混淆保留，且 `@HiltWorker` 多重绑定 Map 字符串键精确为 `"net.jejer.hipda.service.NotificationPollingWorker"`，与 WorkManager 数据库 `WorkSpec.worker_class_name` 100% 一致。 |
| 7 | **FileProvider** | `AndroidManifest.xml:88-96` + `ImageViewerMediaActions.kt:57` `FileProvider.getUriForFile` | AAPT2 `aapt_rules.txt`（`L1531` `-keep class androidx.core.content.FileProvider { <init>(); }`）。 | DEX 中 `Landroidx/core/content/FileProvider;` 与 `<init>()` 未混淆保留；`@xml/provider_paths` 仅解析 XML 标签属性，无类名反射。 |
| 8 | **通知 PendingIntent 目标** | `NotificationPoster.kt:49-60` `PendingIntent.getActivity` → `MainActivity.createNotificationIntent` | AAPT2 `aapt_rules.txt`（`L1541` `-keep class net.jejer.hipda.feature.app.MainActivity { <init>(); }`，同时覆盖 `.ng.IntentActivity` `activity-alias` 目标）。 | 目标组件 `MainActivity` 未混淆保留，Intent 仅携带基本类型/字符串 Extra（`MainActivity.kt:108` 安全提取），无自定义 `Parcelable` 反序列化。 |
| 9 | **Coil 3 解码器** | `ImageLoaderModule.kt:31-35` 显式 `add(OkHttpNetworkFetcherFactory)` + `add(AnimatedImageDecoder.Factory / GifDecoder.Factory)`；`LocalHiImageLoader` 回退 `SingletonImageLoader` | ① 生产主路径静态构造注入，零反射；<br>② `coil-core-android:3.6.3`（`L465-475`）保留 `GenericViewTarget` 子类防 R8 9.0+ 垂直合并丢默认回调；<br>③ R8 内置 `ServiceLoaderRewriter` 自动同步改写 `META-INF/services/`。 | 实查 Release APK 内 `META-INF/services/cr1`（内容 `cr1` = `GifDecoderServiceLoaderTarget`）与 `META-INF/services/re3`（内容 `re3` = `OkHttpNetworkFetcherServiceLoaderTarget`）已与混淆类名成对同步，显式与回退双路径均安全。 |
| 10 | **OkHttp 5 / Cronet** | `okhttp-android:5.5.1`、`TransportInterceptor.kt:73-120`（`CronetProvider.getAllProviders`、`CronetProviderInstaller`、`CronetInterceptor`） | ① `okhttp-android:5.5.1`（`L979-992`）自带可选平台 `-dontwarn`，且改由 `AssetManager` 直读 `assets/PublicSuffixDatabase.list`（APK 内核实存在 `132,737 B`）；<br>② `play-services-cronet:18.1.1`（`L161-166`）、`play-services-basement:18.9.0`（`L244-255` `@KeepName`）与 `cronet:500.1.0`（`L1204-1381`）保留 `org.chromium.net.*`、`ImplVersion`、`PlayServicesCronetProvider`、`HttpEngineNativeProvider.<init>(Context)`、`JavaCronetProvider.<init>(Context)`、`@UsedByReflection` 与内部 protobuf `<fields>`。 | DEX 实证 `CronetProvider` 按名反射的全部目标类（`PlayServicesCronetProvider`、`HttpEngineNativeProvider`、`JavaCronetProvider`、`ImplVersion`）均保留原始类名与 `(Context)` 构造函数；`TransportInterceptor` 全程有 `safeCall` 兜底回退 OkHttp。 |
| 11 | **Jsoup** | `:core-parser` 与 `:hipda` 静态调用 `Jsoup.parse` / `parseBodyFragment` / `clean` / `Parser.xmlParser` | `jsoup:1.24.1` 自带 `org.jsoup_jsoup.pro`（`L1444-1446` `-dontwarn com.google.re2j.**`）；实体表已编译为字节码类 `EntitiesData`，不再按包名读 `.properties` 资源。 | 唯一反射 `org.jsoup.helper.Regex`（`Lo34;`）仅在显式开启 `jsoup.useRe2j=true` 时触发且带回退；100 项真实论坛样本解析测试全绿。 |
| 12 | **Compose SavedState Saver** | 全仓 **0 个** `@Parcelize`/`Parcelable`；复杂状态由 `NextAppStateSaver`、`ShellSidebarEntrySaver`、`FullEditorSavedStateSaver`、`QuickReplyTargetSaver` 转为基本类型/`String`/`List<String>`；另有 4 处 `rememberSaveable` 直接存枚举（`LoginOverlayMode`、`SettingsSubPage`、`SettingsDialogKind`、`AboutAssetDocument`） | ① 4 个自定义 `Saver` 纯函数读写基本类型，零反射；<br>② 4 个 `rememberSaveable` 枚举在 `Bundle` 重建时经 `ObjectInputStream.readEnum` → `Enum.valueOf(Class, String)` 反射调用枚举类 `values()`，由 `proguard-android-optimize.txt`（`L46-49` `-keepclassmembers enum * { values(); valueOf(String); }`）完整保护；<br>③ `savedstate:1.5.0` / `savedstate-compose:1.5.0`（`L772-796`）自带规则。 | `dexdump` 逐一反汇编 `Ltt2;`、`Lik4;`、`Lzi4;`、`Le;` 4 个枚举类，确认均继承 `java.lang.Enum`，完整保留 `values()`、`valueOf(String)` 及 `<clinit>` 中的原始常量名（`Login`/`AccountMenu`、`ManageForums`/`Blacklist`、`TailText`..`ErrorLogs`、`ReleaseNotes`/`Donors`/`License`），进程回收重建安全。 |
| 13 | **枚举 `valueOf` / `.name`** | 7 个持久化/状态恢复枚举：`ThemeMode`、`LineSpacing`、`PaneSplit`、`ReadingFilterMode`、`ShellDestination`、`SettingsGroup`、`QuickReplyMode` | `proguard-android-optimize.txt`（`L46-49`）保留所有存活 `enum` 的 `values()` 与 `valueOf(java.lang.String)`。 | `dexdump` 实证全部 7 个枚举（`La95;`/`Lzq2;`/`Lzk3;`/`La14;`/`Lbm4;`/`Laj4;`/`Luy3;`）虽静态字段符号混淆为短名，但传入 `Enum.<init>(String, int)` 的常量名字符串 **100% 保持原样**，`.name`、`valueOf` 与 `.entries` 跨版本读写完全一致。 |
| 14 | **反射读取的 `BuildConfig`** | 仅 3 处静态读取常量 `BuildConfig.VERSION_CODE` / `VERSION_NAME`（`NextAppHostViewModel.kt:80`、`SettingsScreens.kt:140`、`CrashLogExceptionHandler.kt:85,91`） | 编译期直接内联字面量，全仓无反射读取需求。 | 全仓源码与 `classes.dex` 27,388 条字符串池扫描确认 `"BuildConfig"` 出现 **0 次**。 |
| 15 | **Baseline Profile 与 `profileinstaller`** | `ProfileInstallReceiver`、`ProfileInstallerInitializer`、`assets/dexopt/baseline.prof`（及 `M7-4a` 的 `baseline-prof.txt` / `startup-prof.txt`） | ① `aapt_rules.txt`（`L1532,1534`）保留 `ProfileInstallReceiver` 与 `InitializationProvider`；<br>② `startup-runtime:1.2.0`（`L995-1003`）保留 `Initializer` 子类名与无参构造；<br>③ AGP `minifyReleaseWithR8` 内置 R8 ArtProfile 重写器自动将 `baseline-prof.txt` 未混淆符号同步翻译为混淆描述符并剔除已删类。 | 当前 Release APK 已含编译产物 `assets/dexopt/baseline.prof`（`10,312 B`）与 `baseline.profm`（`1,554 B`）；与 `M7-4a` 合入后应用级热点规则亦自动随 R8 映射表重写生效。<br>*注：另核查 `ChallengeActivity.kt:684-693` `@Keep @JavascriptInterface FallbackChallengeJsBridge.onChallengeEvent` 在 DEX 中类名与方法名均未混淆保留。* |

---

## 4. `mapping.txt` 重命名类与源码 / DEX 字符串交叉核查

1. **全仓主源码与 XML 字符串扫描（0 命中）**：
   - 解析 `hipda/build/outputs/mapping/release/mapping.txt` 共 `8,176` 个类，其中被重命名或移除（`orig != obf`，含 `R8$$REMOVED$$CLASS$$*`）共 **`7,889`** 个类。
   - 对 `hipda/src/main`、`core-parser/src/main`、`emoji/src/main` 下全部 `.kt`、`.java`、`.xml` 文件提取所有字符串字面量、`L...;` 描述符与 XML 类属性（含 `.` 前缀补全 `net.jejer.hipda.*`）与上述 `7,889` 个重命名/移除类做精确比对：**命中数为 `0`**。
2. **`classes.dex` 全量常量池反查（27,388 条字符串逐一归因，0 反射缺口）**：
   - 在编译后单 DEX 的 `27,388` 条字符串中，与 `7,889` 个重命名/移除原始类名重合的仅 `54` 条，逐条反汇编核对用途：
     - `5` 条本仓 `@Serializable` 类名 + `10` 条 Kotlin/Json 内置类型名：均为 Kotlinx Serialization `PluginGeneratedSerialDescriptor` 的 `serialName` 元数据标识，非 `Class.forName`；
     - `23` 条 `kotlin.jvm.functions.Function0..Function22`：Kotlin Lambda `renderLambdaToString` 诊断文本；
     - `2` 条 GMS AIDL 接口名（`IAccountAccessor`、`IObjectWrapper`）：Binder IPC `interfaceDescriptor` 令牌；
     - `9` 条 Compose 内部 `traverseKey`/View 调试名 + `4` 条协程栈恢复诊断名：纯内存键或异常文本；
     - `1` 条 `androidx.compose.ui.platform.AndroidCompositionLocals_androidKt`（位于 `savedstate-compose` `Lxs2;`）：系兼容 `Compose <= 1.7` 的 `try/catch` 探测逻辑，在当前 `Compose 1.13.0` 下直接走新 API 分支且受 `savedstate-compose` 自带条件 `-if` 规则（`configuration.txt:790-795`）管理。
   - **结论**：不存在任何因类重命名或裁剪导致的按字符串类名反射失败风险。

---

## 5. `git merge-tree` 冲突情况核验

| 合并目标分支 | 目标 HEAD SHA | `git merge-tree --write-tree` 结果 | 文本与语义兼容性分析 |
|---|---|---|---|
| **`origin/main`** | `3fa3ea1853e2c5605007d6117ea278175ec25a49` | **0 冲突**（`rc=0`，树 `7ea61ccf...`，可直接 Fast-Forward） | `49c0876` 已完整合入 `origin/main@3fa3ea1`（含 `M7-2` 与 `E7`），并已将 `E7` 表情面板核心类纳入 `ProguardRulesGuardTest` 守卫。 |
| **`origin/card/M7-4a-baseline-profile`** | `85e3de011e24c0afe5c31d315bf80b5d73970b86` | **0 冲突**（`rc=0`，树 `7929e8bc...`，自动合并 `libs.versions.toml`、`hipda/build.gradle.kts`、`settings.gradle.kts`） | **0 语义冲突**：`M7-4a` 未改 `proguard-rules.txt`，新增测试类 `ReleaseResourceAndProfileGuardTest.kt` 与本卡 `ProguardRulesGuardTest.kt` 互不冲突；`isShrinkResources = true` 下 `res/raw/keep.xml` 与存活的 `EmojiMap` 双重保住全部 101 个表情 PNG，R8 ArtProfile 重写器自动适配混淆映射并忽略旧 `HiSettingsHelper` 条目。 |
| **`origin/card/M7-3-target-sdk-36`** | `da8e984d8691c5a9fbea5fe300d66d14a3805244` | **0 冲突**（`rc=0`，树 `d3d6f2a8...`） | **0 语义冲突**：`M7-3` 已同步至 `origin/main@3fa3ea1`，未触碰 `proguard-rules.txt`；`M7-3` 新增的 `HiEdgeToEdge`、`AttachmentMediaStoreSaver`、`POST_NOTIFICATIONS` 权限与 `provider_paths.xml` 均无新增反射入口；合并后 `M7-4a` + `M7-3` + `M7-4b` 三方 `merge-tree` 同样 `rc=0` 零冲突。 |

---

## 6. 非阻断建议（Non-blocking Suggestions）

- **S1（三卡合入 `main` 后重录一次 Baseline Profile，对应 `R-M7-4a S3`）**：待 `M7-4a`、`M7-3` 与本卡 `M7-4b` 全部合入 `main` 后，建议手动触发一次 `workflow_dispatch`（`generate_baseline_profile=true`）刷新已入库的 `baseline-prof.txt` 与 `startup-prof.txt`，自然清除 `M7-4a` 在 `M7-2` 合入前录进的 48 条旧 `HiSettingsHelper` 条目，并补入 `E7` 表情面板与 `M7-3` 边到边路径热点（不重录亦不会导致构建或运行异常，R8 会自动忽略已删除类）。
- **S2（后续杂项清理卡可顺手收口的小项）**：① 仓库下遗留的 `hipda/proguard-android.txt` 系初始提交的历史未引用文件（`hipda/build.gradle.kts:49` 使用 `proguard-android-optimize.txt`），本卡受修改范围限制未删，后续可在清理卡中删除；② `M7-4a` 合入后，可将 `:baselineprofile` 目录一并加入 `ProguardRulesGuardTest.collectProjectProguardFiles` 的扫描列表。
