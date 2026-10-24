# VeyTerm UI design

VeyTerm 的第一版界面以 Kitty 的工作区思路为参考：终端画布保持最大面积，会话管理集中在一个窄而清晰的抽屉，顶部只显示当前会话和少量高频操作。

## 当前实现

- 顶部 48dp 会话栏：打开会话、当前会话名称、运行状态、新建会话和设置。
- 304dp 会话抽屉：应用标题、会话列表、运行状态点、当前会话高亮、键盘和新建会话操作。
- 会话行使用两行信息：会话名称和 shell 标题；退出会话显示退出码，异常退出使用错误色。
- 日间和夜间主题使用同一组语义颜色，避免把颜色直接散落在布局和 Java 代码中。
- `ai_context_panel` 保留在终端内容 FrameLayout 内，默认隐藏。真正接入 AI 前不会显示按钮，也不会自动执行命令或保存原始终端记录。

## 设计令牌

### Dark

| Token | Value |
| --- | --- |
| terminal surface | `#0B0F14` |
| panel | `#111821` |
| elevated | `#17212C` |
| selected | `#1B3542` |
| border | `#263445` |
| primary text | `#F3F6FA` |
| secondary text | `#9AA8B8` |
| accent | `#79D6FF` |
| success | `#73E2A8` |
| error | `#FF7A90` |

### Light

| Token | Value |
| --- | --- |
| terminal surface | `#F6F8FB` |
| panel | `#FFFFFF` |
| elevated | `#EEF2F6` |
| selected | `#E3F4FA` |
| border | `#D8E0EA` |
| primary text | `#17202A` |
| secondary text | `#536273` |
| accent | `#006D8F` |
| success | `#18794E` |
| error | `#B3261E` |

## 后续切片

1. 为顶部栏和抽屉补充触摸反馈、动态颜色和横屏窄屏检查。
2. 在不改变终端核心的前提下，加入可选的 pane/workspace 管理。
3. 设计 AI 面板的显式上下文选择、取消和权限边界，再接入真实 provider。

布局改动必须保留 `TermuxActivityRootView` 的层级和底部 sentinel；这两个边界负责 Android 输入法和 Gboard 的安全区处理。
