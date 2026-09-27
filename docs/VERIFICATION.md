# 验证记录（真机实测）

设备：一台 Android 16 / API 36 / arm64-v8a 真机（Magisk root，LSPosed(zygisk) 已安装）。

构建：`gradle :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`（BUILD SUCCESSFUL）。
安装：`adb install -r` → Success。

---

## 1. 识别（分块 + 颜色不一致 + 文字 → 屏幕坐标）

```
I PhoneAct: 识别 #2 块=3 元素=60 耗时=725ms 截屏=50ms/a11y 总=730ms
```

`screen_recognize` 返回（1280×2772 屏幕）：

| 分块 | 边界 | 元素数 | 含文字 |
| --- | --- | --- | --- |
| status_bar | [0,0,1280,152] | 4 | 2（`17:02`、电量等） |
| main | [0,152,1280,2720] | 55 | 37 |
| nav_bar | [0,2720,1280,2772] | 1 | 0 |

元素样例：

```json
{"id":"e124","text":"PhoneAct·总览","center":[302,254],"delta":85,"source":"ocr",
 "bounds":{"left":..,"top":..,"right":..,"bottom":..},"position":"top-center"}
{"id":"e130","text":"三条通道自动择优:无障碍 → Magisk su → Xposed 增强","center":[591,566],"delta":58,"source":"ocr"}
```

- **分块**：状态栏 / 主体 / 导航栏三块边界与系统 `status_bar_height`(152px)、`navigation_bar_height`(52px) 一致。
- **颜色不一致 + 有文字**：`delta` 即该元素前景色与局部背景色的最大通道色差；默认阈值 42。
- **每次屏幕更新都识别一次**：无障碍事件置脏 + 32×32 灰度帧差分双触发，实测连续识别间隔 ≈ 0.9s（含 OCR）。
- **截屏通道自动择优**：`a11y (takeScreenshot)` 33–80ms 明显快于 `root (screencap)` 200–670ms，AUTO 模式已自动选用前者。

## 2. 操作（无障碍 / root）

| 调用 | 结果 |
| --- | --- |
| `ui_tap {x:640,y:1200}` | `{"ok":true,"backend":"a11y"}` |
| `ui_swipe {640,1600 → 640,900}` | `{"ok":true,"backend":"a11y"}` |
| `ui_scroll {down,400}` | `{"ok":true,"backend":"a11y"}` |
| `ui_global {back}` | `{"ok":true,"backend":"a11y"}` |
| `ui_tap_text {"设置"}` | 命中无障碍节点「无障碍设置」→ 真实跳转到系统设置界面 |
| `shell_exec {getprop ro.build.version.release}` | `{"exitCode":0,"stdout":"16\n","ms":47}` |
| `app_current` | `{"package":"com.dsh.phoneact","activity":"...MainActivity"}` |
| `ui_dump_tree` | 正常返回无障碍节点树 |

**完整闭环验证**：`ui_tap_text("设置")` → 系统设置被打开 → `screen_wait_update` 返回
`{"updated":true,"packageName":"com.android.settings"}` → 新界面被重新识别。识别→定位→点击→等待→再识别 全链路在真机跑通。

## 3. GUI（MD3）

`ui/theme/Theme.kt` 使用 Compose Material3 + Android 12+ 动态取色，实测截图：
总览页（能力卡片 + 状态圆点 + 实时概览）、识别页（画面 + 标注框 + 元素列表）、
MCP 页（服务开关 / 连接参数 / 26 个工具清单 / 运行日志）、设置页（Switch + Slider + FilterChip）。
底部 NavigationBar 四页签，卡片统一 `surfaceContainerHigh`。

## 4. Xposed / LSPosed

LSPosed 日志确认模块被注入并执行：

```
I/LSPosedFramework: [PhoneAct] initZygote path=/data/app/.../base.apk systemServer=false bridge=102
```

`device_status.xposed`：

```json
{
  "active": true,
  "frameworkDetected": true,
  "appProcessActive": true,
  "bridgeVersion": 102,
  "hooks": [
    "initZygote",
    "Activity#onResume(com.dsh.phoneact)",
    "ViewRootImpl#performTraversals(com.dsh.phoneact)"
  ]
}
```

`frameHub.xposedSignals` 持续增长 → `ViewRootImpl#performTraversals` 的内容更新信号已实时送达宿主。

### 实机踩到的三个坑（已在代码中修正）

1. **XposedBridge API 包结构**：`XC_LoadPackage` 位于 `de.robv.android.xposed.callbacks`，
   而不是 `IXposedHookLoadPackage` 的内部类。用错会得到
   `AbstractMethodError: abstract method IXposedHookLoadPackage.handleLoadPackage(XC_LoadPackage$LoadPackageParam)`，
   因为桩类不在 APK 中，运行时方法描述符无法与真实接口匹配。
2. **模块与宿主不共享 ClassLoader**：LSPosed 用独立 ClassLoader 加载模块代码，
   模块里写 `object` 的静态字段宿主读不到。已改为通过显式广播回传状态（`pa_type=alive`）。
3. **MIUI/HyperOS 的 `com.miui.contentcatcher` 会覆写 Intent 的 `package` extra**：
   实测上报的 `pkg` 变成了 `com.miui.contentcatcher`。所有 extra 已统一加 `pa_` 前缀规避。

### 扩展作用域

当前 LSPosed 作用域为 `com.dsh.phoneact`（模块自身进程）。若要启用
「跨应用前台追踪 / 全系统内容更新信号 / 录屏授权弹窗自动确认」，需在 LSPosed 管理器中
勾选 `系统框架`、`System UI` 以及需要自动化的目标应用（模块已在清单中通过
`xposedscope` 声明推荐的默认作用域）。未勾选时全部功能自动降级到无障碍 + root，不影响使用。

> 未脚本化修改 `/data/adb/lspd/config/modules_config.db`：该库由 LSPosed 守护进程持有并带 WAL，
> 直接改写有损坏用户环境的风险，故保留为手动步骤。
---

## 6. 真机实战：自动化完成 B 站「一键三连」

任务：打开哔哩哔哩（`tv.danmaku.bili`），进入任意视频，完成 点赞 + 投币 + 收藏。

全程只通过 MCP 工具驱动，无人工干预：

| 步骤 | 调用 | 结果 |
| --- | --- | --- |
| 1 | `app_launch {package:"tv.danmaku.bili"}` | 首页加载，识别到 90 个元素（"推荐/热门/动画/影视"标签、视频卡片标题与封面坐标） |
| 2 | `ui_tap {324,981}` + `screen_wait_update` | 命中视频卡片《琵琶行》，进入播放页；识别出操作栏 `点赞@[159,1467]`、`投币@[639,1467]`、`收藏@[879,1467]`、`分享@[1120,1467]` |
| 3 | `ui_long_press {159,1467,900ms}` | 点赞按钮 `longClickable=true`，长按未触发 B 站三连，改用分步操作 |
| 4 | `ui_tap {159,1467}` | 点赞 **35044 → 35045** ✅ |
| 5 | `ui_tap {639,1467}` | 弹出投币面板（1硬币/2硬币、同时点赞内容、余额 691.3） |
| 6 | `settings_set {action_backend:"root"}` + `ui_swipe {640,2210 → 640,1900}` | 无障碍 `ACTION_CLICK` 对 B 站自绘按钮无效；改用 root `input swipe` 真实触摸事件后投币 **1409 → 1411**（投 2 币）✅ |
| 7 | `ui_tap {879,1467}` | 收藏 **22076 → 22077** ✅ |
| 8 | `settings_set {action_backend:"auto"}` | 恢复自动择优 |

截图确认：点赞/投币/收藏三个图标均变为激活态（粉色），计数 35045 / 1411 / 22077。

**这次实测暴露的两个问题（已修/已记录）：**

1. **无障碍 ACTION_CLICK 对自绘（非标准 View）按钮无效** —— B 站投币确认按钮是自定义触摸处理，
   必须用真实触摸事件。这正是 `actionBackend` 三档（auto/accessibility/root）存在的意义：
   `root` 走 `input tap/swipe`，产生内核级真实触摸，任何控件都能命中。
2. **长按三连未生效** —— B 站长按三连对按压时长/位移敏感，`dispatchGesture` 的单点长按未满足其手势判定；
   分步执行（点赞→投币→收藏）更可靠。因此 `ui_tap_text` / 分步操作被推荐为默认策略。

## 7. 综合自检

`final-verify.mjs` 覆盖 MCP 握手、工具清单、三条通道、分块识别、坐标/色差、截屏、点击/滑动/全局动作、
等待更新、应用列表、root shell、中文文本输入 —— **19 / 19 通过**。
---

## 8. 真机实战二：微信自动发消息（含中文）

任务：打开微信，给联系人 sunshine 发送 `first act test/1测`。

| 步骤 | 调用 | 结果 |
| --- | --- | --- |
| 1 | `app_launch {package:"com.tencent.mm"}` | 未到前台，改用 `shell_exec {am start -n com.tencent.mm/.ui.LauncherUI}` 成功 |
| 2 | `screen_recognize` | 会话列表识别出 `Sunshine @[350,1905]` 及消息预览 |
| 3 | `ui_tap {350,1905}` | 进入对话（标题 `sunshine` / `wxid_9rm8ynpwnzfq22`） |
| 4 | `ui_dump_tree` | **只返回 1 个节点** —— 微信屏蔽了无障碍节点树，`ui_set_text` 不可用 |
| 5 | `ui_clipboard_set {text:"first act test/1测"}` | 写入剪贴板成功 |
| 6 | `ui_tap {576,2628}` + `ui_key {key:"279"}` | 聚焦输入框（`mInputShown=true`）后 KEYCODE_PASTE 粘贴，文本进入输入框，发送按钮出现 |
| 7 | `ui_tap {1160,1577}` | 消息发出，气泡显示 `first act test/1测` ✅ |

### 这次暴露的三个问题

1. **部分应用屏蔽无障碍树**（微信整窗只暴露 1 个节点）—— 此时 `ui_set_text` 与 `ui_tap_text` 全部失效，
   必须走「视觉识别定位 + 坐标操作」路线。这正是 PhoneAct 把视觉识别做成一等公民的原因。
2. **`root input text` 无法输入非 ASCII** —— 中文、emoji 都会失败。
   解决方案：新增 `ui_clipboard_set` + `KEYCODE_PASTE(279)`，通用且不依赖第三方输入法。
   （Android 10+ 限制的是后台应用**读**剪贴板，**写**不受限，前台应用读取正常。）
3. **坐标必须实测，不能靠大致估算** —— 首次点击输入框用的 y=2537 落在输入框上方的分隔带上，
   既没聚焦也没报错（`input tap` 只要坐标合法就返回成功）。
   把截图底部裁出来量到实际范围 y∈[2562,2695] 后一次成功。
   **教训：对"点击成功但界面没变化"的情况，要回看截图量取真实 bounds。**
---

## 9. 框体检测：让"看不见的输入框"可被自动定位

### 问题

上一节微信实战里，定位输入框是靠**人工把截图底部裁出来量像素**才拿到的坐标。
根因有两个：

1. **颜色通道的参照物是"整块主色"**：微信输入框亮度 41、工具条 30，差 **11**，
   而默认 `colorDeltaThreshold` 是 48 —— 永远进不了候选。
2. **去重逻辑把嵌套当成重复**：早期用"被包含度 = 交集 / 较小者面积"判断，
   于是"工具条包含输入框"被判定为输入框是冗余的，整块丢掉。**嵌套是合法结构，不是重复。**

### 验证方法

改算法前先把屏幕亮度导出成原始数据（`screencap` → 1280×2772 灰度 → 3.5MB raw），
在 Node 里跑算法调参，确认能命中再移植到 Kotlin —— 避免盲改加长构建-安装-试错的循环。

```
--- x=576 纵向亮度 ---
  y=2532: 17      聊天背景
  y=2540..2564: 30   工具条
  y=2572..2692: 41   输入框   ← 台阶 Δ11
  y=2700: 30      工具条
```

试过的两种思路：

| 思路 | 结果 |
| --- | --- |
| 局部均值求边缘 + 从边界洪泛找封闭区域 | ❌ 失败。对称窗口会把 Δ11 的台阶"抹平"，边界处 `|像素-局部均值|` 只有 **3** |
| 邻接平坦区域分割（相邻像素差 ≤ tol 才连通）+ 形状筛选 | ✅ 成功，tol=2 与 tol=3 结果一致 |

### 结果

```
kind=input  center=[576,2630]  bounds=[156,2568 840x124]  Δ8  clickable=true
对照：人工测量 center[576,2628] bounds[155,2562 843x133]   →  误差 ≤ 2px
```

同一画面还顺带得到：状态栏 `[0,0 1280x152]`、挖孔摄像头 `[468,32 344x104]`、
聊天气泡 `[656,508 436x128]` —— 都是颜色通道拿不到的。

调用 `ui_tap_frame {kind:"input"}` 后 `dumpsys input_method` 的 `mInputShown` 由 `false` 变 `true`，
输入框被正确聚焦，**不再需要任何手工测量**。

### 参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `detectFrames` | true | 是否启用框体检测 |
| `frameFlatTol` | 3 | 平坦区域分割的亮度容差，越小切得越细 |
| `maxFrames` | 10 | 每块最多输出多少个框体 |
