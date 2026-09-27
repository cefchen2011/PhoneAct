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
