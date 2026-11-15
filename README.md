# ZFrame + 全军出击单机版2.0 修复项目（交接文档）

更新时间：2026-11-16 00:10（北京时间）

## 目标
在 **荣耀 X70（MTN-AN00，Android 16，没有 root）** 上，用自制的免 Root 注入框架 **ZFrame** 让「全军出击单机版2.0」（包名 com.tencent.tmgp.pubgm，UE4 armv7，2025 年的改版，带 libfucktp / libMczi）能单机正常玩。要做的事：门、物资、载具、人机、声音、训练营、开局装备（三级套+M4+配件）、缩短飞机等待、开局就有人机、跳伞、帧率高于 40。

## 用户要求（必须遵守）
- 用简体中文回复，直接给结果。每次只做小改动，用户在手机上实测。
- 不推荐第三方工具或虚拟机类 App。游戏内只保留 Z 悬浮菜单，不要内存修改器界面。
- 不删枪、不删门。原作者 libMczi 窗口已按用户要求关闭（桩替换）。面板里不显示声音/QCC 开关、原作者字样、控制台或自定义ID输入；提示弹窗要短。
- 只做单机，不做联机。
- 已拒绝的请求：解密腾讯 pak、绕过王者荣耀保护、联网外挂。

## 目录
- `zframe/`：ZFrame 源码。`build.sh` 用于构建（JDK21，签名 zframe.jks / 密码 zframe123 / 别名 zf）。每次发版都要把 AndroidManifest 的 versionCode 和 `src/com/zframe/inject/ZInit.java` 里的 CORE_VER 加 1。
  - `inject/ZInit.java`：注入入口。游戏里装的是 v1.4 的桩，会通过 ContentProvider `content://com.zframe.mods/core` 热加载 ZFrame App 里最新的内核 dex，所以更新内核不用重装游戏。如果连不上 ZFrame App，就用游戏里自带的老内核 5（界面是内存修改器）。
  - `Con.java`：通过 GameActivity.nativeConsoleCommand（反射调用）向 UE4 控制台发命令；监视游戏日志 PUBGM.log（加密时是 XOR 0x73）；自动发送 SET 设置；自动 StreamLevelIn。
  - `Patches.java`：通过 /proc/self/mem 改内存（图层解锁、缩短等待、声音探测）。**目前用 MEM=false 关闭了。**
  - `LogCap.java`：把日志同步到手机的 下载/ZFrame 目录。启动后 40 秒内每 1 秒同步一次，之后每 10 秒一次。日志头会列出最近改过的游戏文件。
  - `GameOverlay.java`：游戏内的 Z 悬浮球和菜单。
- `apk/`：各版本 ZFrame APK（v1.0 到 v1.29，最新 v1.29）。
- `notes/history.md`：每一轮的详细记录和逆向发现（最重要，先读这个）。
- `game/x/`：游戏 APK 解包（dex、lib、assets）。`game/re/`：逆向脚本，ue.py 提供 disf / func_of / XR / callers 等（需要 numpy 和 capstone）；report.md 是逆向报告；pkl 是交叉引用缓存。`game/logs/`：历次游戏日志。
- 游戏原包、注入后的包和 obb（每个约 1.6–1.7 GB）超过了 git 的大小限制，放在 Releases 里。

## 当前状态（v1.29）
- **最新版 v1.29（versionCode 30，CORE_VER 30），还没实测。** App 名「艾挽补丁更新」，只有一个更新页（更新 + 启动游戏）。
- 已实测可用：
  - 声音（v1.20 起）：修补 libUE4.so 偏移 0x45e7104，把 L"SoftwareOcclusionCulling" 改成 L"AkAudio"，让 AkAudio 模块加载。
  - 艾挽面板（地图：海岛 / 海岛2 / 雨林 / 进入大厅）。
  - 原作者窗口已关闭。
  - 刷物品（v1.28 起）：走 PlayerController 的 `AddAvaItem P<id>x<n>x...`，配合 libUE4 cave（cave_giveitem.s），结果=1。
- v1.29 新增（未测）：
  - 刷车：getall 找出载具类，再 EnableCheats + Summon。
  - 全套配件（12 个配件 ID）。
  - 日志会记录「身上装备编号」，用来找三级头、三级甲和黑色连帽风衣的 ID。
- 已知问题：
  - 头盔 30304000003 是二级，护甲 30305000002 是一级。
  - 风衣 ID 30315000061 和 30315000050 都不对。
  - 大厅人物灰色是原包自带的，用户不要求修。
- 不要写 0x53b5d58；Patches.MEM 保持 false。
- 详细逆向记录见 notes/history.md。

## 下一步
1. 用户实测 v1.29：刷车、配件、更新提示。
2. 从「身上装备编号」日志里取三级头、三级甲和风衣的 ID。
3. 配件如果刷不出来，改成加进装备列表。
4. 海岛2 的出生岛刷枪（BP_NZBRIslandGameMode_C）。
