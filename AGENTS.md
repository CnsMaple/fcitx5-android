# AGENTS.md — fcitx5-android（个人 fork）

> 本文件面向在此仓库工作的 AI agent，汇总构建、架构、模拟器与踩坑约定。交互语言用简体中文，代码注释用英文。

## 项目概况
- fcitx5-android 的个人 fork（origin=CnsMaple，另有 upstream / bibi 远端）。相对上游的历史已 squash 成单个提交（`git reset --soft <merge-base>` 后一次 commit）。
- 多模块 Gradle 工程：`app`（输入法主体）、`lib/*`（fcitx5 及插件基础）、`plugin/*`（独立输入法插件 APK）。
- **本 fork 已将 clipsync / cloudvoice / rime 合并进 `:app`**：WebDAV 与云语音走 app 内本地 provider（`LocalClipSyncProvider`/`LocalVoiceProvider`，`Stub` 子类同进程实现）；rime 引擎作为内置组件（C++ 源在 `app/src/main/cpp/rime`，5 个子模块），不再产这三个独立插件 APK。
- 版本号来自 `git describe`，因此**未提交的工作区改动不会体现在产物版本名里**（新旧包可能同名，易装混）。需要区分时先 commit。

## 构建与签名
- 主体：`.\gradlew.bat :app:assembleDebug|assembleRelease -PbuildABI=<abi>`
  - 模拟器用 `x86_64`；真机 release 用 `arm64-v8a`。
  - debug 走内置 debug 签名，无需 key。
- release 需签名参数：
  ```
  -PsignKeyFile=D:\Code\kotlin\keystore\fcitx5-android\fcitx5-android.jks
  -PsignKeyPwd=fcitx5-android -PsignKeyAlias=fcitx5-android
  ```
- 其余引擎插件（anthy/unikey/chewing 等）仍独立：`:plugin:xxx:assembleRelease` 同 `-PbuildABI`。clipsync/cloudvoice/rime 已内置进 `:app`，不再单独打包。
- 产物：`<module>/build/outputs/apk/{debug,release}/*.apk`，文件名含版本与 ABI。
- **PowerShell 里不要用 `| Select-Object -First N` 截断 gradle 输出**：取够行数会提前终止上游 gradle 进程，导致构建被中断（表现为部分模块没产出）。改为重定向到文件再 grep：
  ```
  .\gradlew.bat ... --console=plain > build.log 2>&1
  ```

## 输入法架构要点
- IME 窗口本就是全屏透明容器（`FcitxInputMethodService.setInputView` 把 inputArea/InputView 撑成 MATCH_PARENT），靠 `onComputeInsets` 裁剪可触摸区。
- 键盘视图层级：`InputView`（全屏 ConstraintLayout）→ `keyboardView`（卡片）→ `windowManager.view`（键盘主体，高=`keyboardHeightPx`）+ `kawaiiBar`（工具栏）+ 各 `InputWindow`（KeyboardWindow/Picker/StatusArea 等）。
- 设置页是 DSL 自动渲染：在 `AppPrefs` 的某个 `ManagedPreferenceCategory` 里加 `enumList/int/switch`，对应 `ManagedPreferenceFragment` 子页自动出现该项，无需写 UI。

### 悬浮键盘（本 fork 新增）
- 开关：`AppPrefs.keyboard.floatingKeyboardMode`（`Off/Landscape/Always`，默认 `Landscape`）、`floatingKeyboardWidth`（40–92%，默认 75，语义为**等比缩放因子**）。
- 位置记忆：`AppPrefs.internal.floatingKeyboardX/Y`（px，-1=未设）。
- 实现：`InputView.applyCardMode()` 把 `keyboardView` 切成固定宽、圆角+阴影卡片，顶部 `dragHandle` 拖动改 `leftMargin/topMargin`；`updateKeyboardSize()` 里键盘主体高 = `keyboardHeightPx * scale`（与宽同因子，等比不变形）。
- `onComputeInsets` 悬浮分支用 `TOUCHABLE_INSETS_REGION` 只框住卡片，`contentTopInsets==visibleTopInsets`（App 内容不收缩）。
- 「横屏使用竖屏大小」开关（默认开）：`usePortraitSizeInLandscape`，令横屏时高度基准取长边、卡片宽基准取短边。
- 状态栏工具窗格（`StatusAreaWindow`）有「悬浮键盘」入口，一键 Off↔Always 切换。
- 空格滑动移动拼音组合光标：`CommonKeyActionListener` 在把 `Left/Right` 交给引擎前按 preedit 边界钳制（`FormattedText.cursor/length`），避免到末尾再滑回到开头。

## AIDL 协议（lib/common: org.fcitx.fcitx5.android.common.ipc）
- `IVoiceInputProvider/Callback`、`IClipSyncProvider`、`IClipSyncProgress` 等。
- **transaction code 按方法声明顺序位置分配——绝不重排已有方法，新增只能追加**（合并后 clipsync/cloudvoice 由 app 内 `Stub` 子类同进程实现，不再跨进程；接口顺序约束保留以防将来拆分）。
- Binder 单次事务约 1MB 上限：大二进制必须用 `ParcelFileDescriptor` 传，不要在 `Bundle` 里塞 `byte[]`。

## 进程与跨进程偏好
- 合并后剪贴板/云语音 provider 在 IME 进程内（本地 `Stub`），不再 `bindService` 外部插件；但**设置页 Activity 在 app 主进程、IME 在 IME 进程**，两者共享同一 SharedPreferences 文件——**跨进程 SharedPreferences 不实时同步**：设置页写入的值，IME 进程要冷启动才读到；直接改 prefs 文件会被运行中进程用内存旧值覆盖。验证默认值改动要先清该 key 再冷启动。
- 剪贴板 WebDAV 配置存 app 的 `clipsync` 私有 sp（`ClipSettings`）。自动同步只拉/推文本，非文本仅提示手动获取；文本与本机相同不覆盖。图片/文件手动获取后走全屏操作面板（复制/发送/分享/取消），发送用 `commitContent`（目标不支持则回退复制）。
- 第三方引擎插件（anthy/unikey 等）仍是独立 APK，OEM 冻结/自启动限制对它们适用。

## 模拟器环境（AVD: Medium_Phone）
- 启动：`emulator -avd Medium_Phone -gpu swiftshader_indirect -no-boot-anim -dns-server 8.8.8.8,1.1.1.1`
  - `swiftshader_indirect` 修掉 `angle_indirect` 下文件管理器黑屏。
  - 启动很久没 boot：`adb kill-server` → `adb start-server` 并**立刻** `Start-Process emulator ...`（两步要紧挨着，间隔久了 emulator 常连不上）。
- debug 包 applicationId 带 `.debug` 后缀（与正式版可共存），IME id：`org.fcitx.fcitx5.android.debug/...FcitxInputMethodService`。
- 常见不稳：磁盘易满（`pm trim-caches`）、adb 掉线（`adb kill-server` 后重启 emulator）、`uiautomator` 对**输入法窗口返回 null root**（无法 dump 键盘节点，只能靠截图坐标）。
- swiftshader 软件渲染慢：fcitx 键盘首帧可达数秒，截图要 `sleep` 足够久否则一片空白。
- 直接改 prefs 文件注入配置易被 IME 进程覆盖；可靠做法：`ime disable` + `force-stop` → 改文件 → `ime enable/set` → 冷启动读取。

## 已知代码坑
- `String.format` 模板里的字面 `%` 会被当转换符（进度文案先单独格式化数字）。
- 回车换行用 `commitText("\n")`，不要用 `sendDownUpKeyEvents(ENTER)`（会绕回 fcitx 的 handleReturnKey）。
- `registerForActivityResult` 必须在 fragment 初始化期（作为字段）注册，不能在点击回调里。
- `addTextChangedListener { }` 尾随 lambda 有歧义，用 `doAfterTextChanged { }`。
- `Bundle` 取 Parcelable 用被弃用的 `getParcelable(name) as? T` + `@Suppress("DEPRECATION")`。
- `FileDescriptor` 无 `.inputStream()`，用 `FileInputStream(fd)`。
- 编辑文件偶发 `ReplaceFileW EIO (Win32 1175)`：瞬时错误，重试同一 edit；不要并行编辑同一文件。

## 交付约定
- **每次改动收尾默认动作**（除非用户另有说明）：
  1. `assembleDebug -PbuildABI=x86_64` 并 `adb -s emulator-5554 install -r` 装到模拟器验证；
  2. `assembleRelease -PbuildABI=arm64-v8a` 复制到桌面的 `apk_install`（clipsync/cloudvoice/rime 已内置主体，无需单独打；其余引擎插件按需）；
  3. 放桌面前先**覆盖同名旧 apk 并删除旧版本名残留**，保持目录里是当前版本的一套包。
- 提交遵循 conventional-commit，一般英文；除非另有要求，**commit 但不 push**，push 前确认。
- 涉及新增/删除文件、批量不可逆改动、外部命令、密钥：先与用户确认。
- 临时脚本/验证截图用完即删，勿混入提交。
- 真机（Honor/Android 16）是主要验证环境；模拟器仅能覆盖部分自动化，UI/手势类改动常需真机确认。
