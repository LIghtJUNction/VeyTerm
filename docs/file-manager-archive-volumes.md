# 非本地 RAR 多卷缓存

`FileStaging.openArchive` 将 SAF、远程或归档中的 RAR 首卷和同级兄弟卷缓存到独立私有目录，供真实解码器读取。RAR 缓存只读；本地文件直接借用。

## 接口与行为

调用 `FileStaging.openArchive(origin, monitor)`。
它先使用普通 `open` 创建 lease；local borrowed 立即原样返回，不访问后端，不 chmod、不删除本地源文件。
owned 文件调用现有 `ArchiveTools.detect(File)`，只有 `Format.RAR` 才列举和复制兄弟卷。
非 RAR 的正常归档保持单文件 stage；无法检测的损坏归档报错并清理本次 lease。

支持从首卷打开：

- `name.part1.rar` / `name.part01.rar` / 更多首卷补零，数字自然增长。
- `name.rar` + `name.r00` ... `name.r99`，以及后续传统 `s00` ... `z99`。
- `name.001` + `name.002` ...。

使用 `FileAccess.list(parent)` 返回的真实 entry location，不能根据显示名称拼 SAF URI。
只接受名称匹配、同级、stat 非目录且非 symbolicLink 的 entry；复制前后重新 stat。
非首卷 `part2.rar` 不被误认为传统 `part2.r00` 家族入口。原有大小写与补零方式保留；不猜其他拼写。
中间缺卷、重复编号、重复 location、外部 parent、symlink、目录或列表访问失败均显式报错。
单独授权、没有可访问 parent 的 SAF 文件返回只读单文件 lease，缺卷由解码层报告。

上限：1024 卷（含首卷）、单卷 4 GiB、总缓存 16 GiB、遍历列表 100000 entry。
已知 size 在兄弟卷复制前检查；未知/虚假 size 仍按实际流字节检查。
首卷普通 `open` 通过 bounded monitor 限制，可在超过上限的一个 64 KiB block 后中断并清理；
兄弟卷在写入前检查。`FileAccess.list` 是整体返回 List，无法在本层限制后端内部列表分配和阻塞时长。
源 read/list/stat 自身若阻塞，取消须等后端调用返回；每个复制/验证 block 和循环边界检查 monitor。

每卷先复制并计算 SHA-256，再对整组源进行有界重读和 hash 比较，覆盖 size/mtime 相同的内容变化。
这会额外读取一遍源卷；没有性能提升声明。调用方应序列化同一个 lease 的操作，
并避免源在返回后继续并发变化。接口没有通用 inode/版本号或快照锁；FileEntry 的
非目录/非链接标记及 FileAccess.read 的常规文件语义是跨后端可取得的类型证据。

## 恢复与清理

RAR lease 的 `.origin.json` 新增 `rarReadOnly: true` 和 `volumes: [兄弟卷 basename...]`。
每个兄弟卷用 `createNewFile` 排他创建成功后才加入 manifest，且在首次读取源流之前落盘。
因此已记录的不完整卷能在重启后经 `restore/recoverable/discard` 清理；未创建的兄弟卷不在 manifest。
首卷与完成的兄弟卷设置文件只读；`commit` 对此类 lease（包括重启恢复）明确拒绝，
任何兄弟卷都没有 origin 映射或写回代码。没有后端 write/delete/rename 调用。

`restore` 校验字段类型、digest、卷名、编号范围和唯一性，拒绝路径逃逸；旧单文件 lease 仍兼容。
`recoverable` 只返回 manifest 的主文件，兄弟卷和未知输出不被当作独立 edit lease。
坏 metadata、不可读或不安全的单个 lease 跳过且原样保留，不阻断其他正常 lease；直接 restore/discard 仍报错。
路径约束拒绝 lease/data/main 的 symlink 逃逸，metadata 也要求是真实常规文件。

`discard` 在任何删除前检查整个 lease：只允许主文件和 manifest 记录的卷，
未知输出、未知 `.origin.pending`、坏 metadata、metadata 被改变或任何链接均报错并保留文件。
删除不递归。`save` 使用排他创建的 pending，检查原 metadata 内容仍与本 Stage 一致，
不会截断未知 pending 或替换坏 metadata；普通 commit 也在传输前执行这些检查。
正常取消/异常只清理本 Stage 实际创建且允许删除的文件。

创建卷与保存 manifest 之间、pending rename 前发生真正进程死亡/掉电，可能留下未记录卷或 pending。
恢复时把它们视为未知并保留，不猜测所有权，不自动删除。目录 fsync/掉电原子恢复不在 Java-only 保证内。
manifest 是应用私有 cache 中的所有权记录，不是防同 UID 恶意并发篡改的认证格式。

## 本地验证

宿主验证：

```sh
python3 app/src/test/java/com/termux/app/files/fs/run-volume-host.py \
  --parent "$PWD"
```

结果：SDK36 `android.jar` 编译通过，**40 项宿主行为测试通过**。
同一测试源位于 `app/src/sharedTest`，也由 Android `FileWorkspaceSmoke` 调用。

测试使用真实 Android-derived JSONObject/JSONArray（Vaadin android-json，下载只在临时目录，
SHA-256 `dfb7bae2f404cfe0b72b4d23944698cb716b7665171812a0a4d0f5926c0fac79`）。
仅 Context/ContextWrapper 的 cache getter 在 host 启动时由极小适配替身提供；
生产类和测试类先对真实 Android SDK 编译，FileStaging、ArchiveTools.detect、JSON、文件 IO 都实际执行。
后端用 opaque `content://` location 的 FakeFileAccess，拒绝全部写调用；每例只使用随机独立测试 cache。

11 个原始 uuencoded fixture 都先验证测试资源 SHA256SUMS，再解码到测试临时目录。
真实 RAR4 三卷、RAR5 八卷逐字节比对；传统和 `.001` 场景只重命名这些真实字节验证 staging 命名，
并未宣称重命名后已通过实际解压。其他用例覆盖：local borrowed、非 RAR、普通 open/旧 metadata、
补零、无关文件、缺卷、重复、限额、取消、读取异常、size/mtime/内容变化、恢复后只读、
模拟复制中途进程丢失后的不完整卷清理、未知输出、未知 pending、metadata 损坏/类型错误/路径逃逸、
目标冲突、缓存卷 symlink、悬空 metadata symlink、lease 目录 symlink、普通 commit 的写前保护。
没有实际写出超过 4 GiB / 16 GiB 的测试文件；大值限额用伪造 declared size 验证，
流限额的相应算术路径另由源码检查。真实后端并发、权限/网络失败细节及实际解压分别在 Android 集成套件验收。

Android runner 调用 `ArchiveVolumeSmoke.run(context, decodedFixturesDirectory)`，并另外通过跨 UID DocumentsProvider 实际打开 RAR4 三卷和 RAR5 八卷、测试并解压内容。设备结果见 [功能矩阵](FILE_MANAGER.md)。

宿主脚本退出时自动删除下载 jar、解码样本、编译类和随机测试缓存。
