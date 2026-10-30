# 内置文件工作区

目标：在 VeyTerm 中内置双列文件管理和编辑工作区。以 [MT 双窗口操作](https://mt.cc/guide/)、[文件工具](https://mt.cc/guide/file/file-comparator.html)、[DEX 编辑](https://mt.cc/guide/reverse/dex.html) 和 [高级功能](https://mt.cc/vip/) 为功能对照；“存在菜单入口”不等于功能完成。

入口是终端顶部的文件夹按钮。左右列分别保留路径、历史、筛选、排序和选择；复制与移动的目标是另一列。短窗口与横屏使用紧凑工具栏，名称筛选仍可从列菜单或 Ctrl+F 打开。

## 模块接口

应用代码继续使用 `com.termux.app` 内部命名空间，应用 ID 和文件前缀仍为 `com.veyterm`。文件模块统一放在 `com.termux.app.files`；新的界面不修改终端模拟器和 bootstrap。

### 存储层（主线程之外执行）

`FileLocation`：不可变位置，`String uri` 表示本地 file URI、SAF content URI 或保存连接的远程位置。显示名称与 URI 分开。

`FileEntry`：`FileLocation location; String name; boolean directory; boolean symbolicLink; long size; long modified;`。

`FileAccess`：列目录、属性、父子位置、流读写、创建、改名、删除和包含关系。创建与改名返回存储提供者的真实位置，不能从文件名猜测文档 URI。本地与 SAF 使用同一接口；操作引擎负责跨后端复制/移动、取消、冲突处理和进度。删除符号链接不得递归进入目标；移动必须先完成目标再删除源。

### 压缩模块

`ArchiveTools` 是独立 Java 类，使用本地 `File` 与流。提供检测、列目录、创建、解压、测试、添加/替换、删除、重命名；支持取消和进度。ZIP/APK 可以浏览和修改，支持 ZIP 加密；其他格式按真实能力声明读写范围。不得用空方法或假成功回调占位。

### 编辑器模块

`TextEditorActivity`、`HexEditorActivity`、`FileViewerActivity`、`FileCompareActivity` 接受 Intent extra `file_path`（应用已可访问的本地文件）。编辑完成 RESULT_OK；文本/Hex 必须实际保存文件并保护未保存修改；不可静默截断大文件或伪装解码。共用 `EditorIo` 提供编码、行尾与原子保存，以及可在 JVM 验证的文本/二进制算法。

### APK 模块

`ApkToolsActivity` 接受 `file_path`。`ApkTools` 提供真实 APK 信息、签名验证/重签、安装包提取、ZIP 内容和差异分析；DEX/Smali、AXML/ARSC 使用许可兼容的开源实现进行解析和回编，所有不支持的操作必须显式报错。不得复制 MT 专有代码，不使用外部网站上传用户 APK。

### 网络模块

`RemoteFiles` 独立 Java 层，提供连接类型、连接/关闭、列目录、下载、上传、目录与文件操作，覆盖 SFTP、FTP/FTPS、WebDAV、SMB。凭据不得出现在显示 URI、日志或版本库。另提供可选的本地 HTTP 文件传输服务，必须由用户显式启动，默认仅绑定 localhost，支持停止和认证，不自动对外开放。

各模块的依赖、许可、格式范围和验证命令记录在对应的 `docs/file-manager-*-dependencies.md` 中。

## 功能与验收矩阵

| 功能 | 验收要求 | 状态 |
| --- | --- | --- |
| 双列浏览 | 竖屏、横屏同时显示两列；左右独立历史、路径与排序；同步/交换；状态恢复 | 已实现；交互预览通过，Android 验收中 |
| 文件管理 | 新建、多选、全选/反选/同类选择、跨列复制/移动、改名、批量改名、删除、书签、隐藏文件、属性、校验、权限、终端联动 | 已实现；核心本地测试通过，Android 验收中 |
| 存储访问 | 应用目录、共享存储、授权 SAF；权限失败可恢复，不假装 root | 已实现；Android 跨 UID SAF 验收中 |
| 搜索 | 名称/正则/内容搜索，取消、后台执行；错误不当成空目录 | 已实现；核心本地测试通过 |
| 压缩包 | ZIP/APK 浏览与修改，压缩/解压/加密/测试；拒绝路径逃逸；失败保留原文件 | 已实现；Android 本地格式往返及六组加密 RAR 通过；SAF 多卷验收中 |
| 文本 | 编码、行尾、行号、查找替换/正则、跳行、撤销重做、保存、未保存保护 | 已实现；编辑器 50 项本地测试通过 |
| Hex | 分页读取、搜索、偏移跳转、真实写回，不将整个大文件载入内存 | 已实现；编辑器本地测试通过 |
| 对比/预览 | 文本和二进制/ZIP/目录差异、图片、音视频、字体、脚本与终端打开 | 已实现；算法本地测试通过，媒体设备验收中 |
| APK/DEX/资源 | 信息、证书、签名验证/重签、安装/提取、DEX/Smali、AXML/ARSC 解析与可验证回编 | 已实现；Android 核心 49 项断言通过；53 项安装提交断言、系统确认、成功回调及安装后字节比对通过 |
| 网络与传输 | 协议客户端真实文件往返、凭据保护、远程服务显式启动/认证/停止 | 已实现；2330 项宿主往返断言通过，Android 六端点验收中 |

只有代码和对应验证都完成，才把状态标为通过。Root/Shizuku、不同 Android 版本和实体机依赖实际授权与设备条件，分别记录，不能用模拟器或只读解析替代。

格式范围与详细用例分别见 [压缩模块](file-manager-archives-dependencies.md)、
[RAR 完整解码器](file-manager-rar-complete.md)、[APK 模块](file-manager-apk-dependencies.md)、
[反编译](file-manager-decompiler-dependencies.md)、[远程 Android 验收](file-manager-remote-device.md)。

RAR 为读取、测试和解压，不提供 RAR 创建。独立 UnRAR 命令及完整许可文本随 APK
分别打包，未链接进 GPL JNI 库。源代码内部保留 `com.termux` 以兼容已有 JNI；
应用名称、配套插件名称、默认欢迎文字和报错使用 VeyTerm，不覆盖用户自定义 MOTD。

## 数据与输出保护

文件先写临时对象，再发布；覆盖前保留旧文件，失败尝试恢复。源文件在读取后及覆盖发布前复核；清理失败作为附加错误保留，不覆盖原始错误。同一目录的路径别名不会被当成移动目标而删除源文件；“保留两者”会生成新名称。

SAF 的文档 ID 与授权树路径分开比较。旧文档 ID 被提供者拒绝时，只有成功列出父目录并确认该 ID 缺失，才返回文件不存在；撤销授权始终作为访问错误报告。远程记录名称不同也会比较实际服务器、账户与目录范围。

编辑 SAF、远程或归档中的文件时使用私有缓存及 SHA-256 冲突检测。APK、签名旁文件、Smali 和 Java 导出默认放在可写的另一列本地目录或 VeyTerm Home；不会把用户输出留在待清理的编辑缓存里。具体规则见 [工具输出目录](file-manager-tool-outputs.md) 与 [多卷 RAR 缓存](file-manager-archive-volumes.md)。

## Android 安装验证

2026-10-30，在独立 API 33 x86_64 模拟器生成 APK，使用真实 PackageInstaller 和本应用的结果接收器。提交后收到系统确认请求；允许本测试应用安装来源、确认安装后，接收器记录 `STATUS_SUCCESS`，PackageManager 中存在同一测试包，安装后的 APK 与签名样本逐字节相同。随后仅卸载该随机测试包。此过程没有操作已连接的实体手机。
