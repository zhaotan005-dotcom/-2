# ZFrame / 全军出击 history (moved from project skill 2026-11-15 19:20)
See project skill v27 text (archived below in summary). Key RE facts live in /workspace/work/qjcj/re/report.md.

## 19:13 round
- User: only 无敌 works; GM/给枪/刷车/人机/训练营 no effect. Wants shorter plane wait, start kit (三级套+M4+配件), bots at start, doors.
- RE: exec thunks GiveItem 0xe40c58, TestSpawnVehicle 0xe3df3c, AddBot 0xe43e04, SpawnPickupBox/EnterTraining 0xe43f54 all call 0xb63a30 (bx lr) = stripped. ShowGMPanel vtable +0xb54, QuickStartMatch +0xe14 unverified.
- Engine CheatManager alive (God works). SET/GETALL/OPEN/TRAVEL strings present (utf-32 TCHAR).
- Props: NZBRGameState {WaitingPlayersCountDownTime, StartMatchCountDownTime, WaitingSkydivingCountDownTime, NumPlayersBeforeCountDown}; NZGameMode {bEnableGiveBotEquip, bEnableAIDefaultWeap, bEnableGridMap, DoorManager}; NZBattleRoyaleGame {MaxBotLimit, BotArray, BotSpawnPointActorArray}; NZSceneDoorManager {StandaloneUnloadAllDoor}.
- v1.11 delivered (versionCode 12, CORE_VER 12).
- Plane route bug (19:03): CalcAirLineByRestrictedAreaInfo random center unclamped; v1.10 added teleport-to-circle (now BugItGo).

## 19:28 round → v1.12 (versionCode 13, CORE_VER 13)
- User: teleport + God work; Fly no; wants Chinese toasts; wait still 43s, no countdown first tens of seconds; no sound.
- Log (19:04 session): phase0 WaitingPlayers 60s (no UI), phase1 StartMatch 45s. ResetStartMatchCountDownTime at GameState+0x73c (GameMode code ~0x100cb04, GameState = GameMode+0x908).
- Training: engine "map ... BR-Training_Mobile_Main does not exist"; TrainingIsland pak 11 MB = pieces only → main map missing. Button removed.
- Sound: Wwise plugin mounted, "Wwise(R) SDK Version" init line never printed → AkAudio device not initialised. Open.
- LogCap bug: same-named PUBGM.log from several dirs overwrote each other; now tagged app/int/sd and only files <2h old.
- v1.12: Patches.shortenWait heap scan (+0x73c==5.0, 60/45 within 0x71c–0x760 → 3.0), auto after NZBRGameState.cpp:304 line; button 缩短本局等待; Chinese toasts; removed 飞行/落地/进训练营/重发开局设置.

## 19:40 round → v1.13 (versionCode 14, CORE_VER 14)
- Sound RE: FAkAudioDevice::Init = 0x3c32e24 (StartupModule 0x3c3a730 calls wrapper 0x3c32c00; on fail deletes device). Gates: -nosound (not on cmdline), two GOT bools, static bInitialized byte at 0x53b5d50 (written 1 at 0x3c3309c on success). LogAkAudio category verbosity byte at 0x53b5d58; the "Wwise(R) SDK Version" log needs >=5, so missing line ≠ failed init (category likely muted).
- v1.13: Patches.audioProbe forces 0x53b5d58=5 for 30 s from lib load, then logs flag (ZFrame log line "声音: Wwise 标志=…"); menu shows 声音 status. TWEAKS add "log LogAkAudio Log"/"log LogAudio Log".
- FPS: game ApplyFrameRateOption max option 3 = 40 fps. v1.13 menu 60/90/120/默认 → t.MaxFPS N + window preferredDisplayModeId; saved in prefs "zframe" fps; re-applied after each ApplyFrameRateOption line and at tweaks.

## 19:42 → v1.14 (versionCode 15, CORE_VER 15)
- v1.13 crashed every launch right after Tencent splash. Likely cause: forcing LogAkAudio verbosity byte (0x53b5d58=5) → previously-muted Wwise log path crashes, or byte isn't verbosity. v1.14 removes the write and the "log LogAkAudio/LogAudio" commands; audioProbe only reads flag after 25 s. Lesson: don't unmute LogAkAudio.

## 19:45 → v1.15 (versionCode 16, CORE_VER 16)
- User: v1.14 still crashes after Tencent splash. Suspect v1.12 shortenWait: triggered by any LogNet Browse (lobby load too), loose pattern (+0x73c==5.0 with 60/45 nearby) could hit random heap and write 3.0 → crash. v1.15: trigger only on NZBRGameState.cpp:304 line; require vtable at obj+0 inside libUE4 range and 120.0 + 1.0 in window. If still crashing, ask for newest Download/ZFrame logs; v1.11 is last known-good.

## 19:50 crash analysis → v1.16 (versionCode 17, CORE_VER 17)
- ZFrame.zip (120 files): runs 194138–194212 (core 15) and 194443–194501 (core 16) died <10 s (logcat only 26 lines, UE log never opened). 194428 (core 16) reached UE: layers fixed on 1st scan, then UE log ends 19:44:41 at APP_EVENT_STATE_ON_PAUSE (identical to good 19:22:53 run up to there); died ~4 s after UE start.
- Earlier "闪退两次" sessions had the same ~104 KB truncated UE logs → same crash, now persistent. No signal/backtrace captured (sync every 10 s). TerSafe active (TERSAFE:HandleAntiData) → hypothesis: TP kill, possibly persisted after v1.13's rapid /proc/self/mem writes. Unconfirmed.
- v1.16: LogCap syncs every 1 s for first 40 s; header lists game files modified in last 2 h. Asked user to try v1.11 first (A/B: ours vs persistent state).
- 19:56: user's "v1.16 不闪退" run actually used built-in core 5 (provider com.zframe.mods not found; ZFrame app missing after uninstall). Old core does not crash → crash is in newer core features (v1.12+). Asked the user to install and open v1.16, then send the 1 s-sync logcat if it crashes.
- 19:59 v1.16 logs (core 17): 3 runs die ~10 s after start with no fatal line (silent exit → likely TerSafe kill). No "补丁" note synced, so death happens right when libUE4 shows up (late load) = the moment layerFix starts reading /proc/self/mem. In 194428, libUE4 was found at 0.9 s and that run lived longer. Hypothesis: TP watches /proc/self/mem access.
- v1.17 (versionCode 18, CORE_VER 18): safe mode Patches.MEM=false (no audioProbe, layerFix or scanWait). findBase polls every 200 ms. Console features (teleport, God, set tweaks, FPS) kept.
- 20:02 v1.17 safe mode STILL crashed (~6 s) → memory access was not the cause. New suspect: Con watcher read the previous session's PUBGM.log, so engineSeenAt was set at once and runTweaks (set commands plus t.MaxFPS and a display-mode change from the saved fps pref) fired about 8 s in, before the engine was up. This also explains the old occasional "闪退两次".
- v1.18 (versionCode 19, CORE_VER 19): the watcher skips logs older than process start. Tweaks fire 10 s after "LogLoad: Took" in the new log. MEM still false.
- GitHub connected (zhaotan005-dotcom), but the integration can't create repos (403 "Resource not accessible by integration"). Asked the user to create an empty private repo zframe-pubgm.

## 20:55 round → v1.19 (versionCode 20, CORE_VER 20)
- v1.18 (core 19) logs: no crash, matches play. Sound still missing.
- Finding: 单机版 flow goes StartupMap → (after update err 107) Browse BR-Savage_Mobile_Main directly, never the lobby. UNZFrontEndAudioManager init/LoadAllBank (BGMLobbyBank, C01_UI_New_Bank) and UNZPlatformAudioHelper::OnInitialize only logged when FrontEndMap_NEW loads (20:34:55, after quitting a match). Hypothesis: lobby BP also does UAkGameplayStatics::LoadInitBank etc. → match silent when lobby skipped.
- `set NZBattleRoyaleGame MaxBotLimit` → "Unrecognized class"; class is NZBattleRoyaleGameMode (utf32 0x43b6228). Fixed in TWEAKS.
- v1.19: Con.tickAudio: after first BR LoadMap with no lobby audio init seen this process → `open /Game/Maps/Erangle_RoleHall/NEW/FrontEndMap_NEW`, wait for FrontEndAudioManager init + 4 s → `open <same BR map>`. Menu: 经大厅重进本局 button, 自动修声音 toggle (prefs zframe/audio_auto).
- AkDiag: writes [Core.Log] LogAkAudio=Verbose, LogAudio=Log into …/UE4Game/PUBGM/PUBGM/Saved/Config/Android/Engine.ini before engine start (file only). Crash guard via prefs akdiag_boot/akdiag_off. Lines containing AkAudio/Audiokinetic/Wwise(R) copied into ZFrame log head as "声音日志:".
- Patches: MEM still false, but audioProbe re-enabled read-only (1 byte at base+0x53b5d50 after 25 s) → "声音: Wwise 标志=".
- Build env here is aarch64: x86 aapt2 unusable, so v1.19 APK was assembled from v1.18 APK (binary manifest patched versionCode 19→20, versionName 1.18→1.19), new classes.dex + assets/inject.dex, signed with zframe.jks. build.sh paths updated but aapt2 step needs x86_64.
- 21:05 v1.19 test: still no sound. Game not listed in Android per-app volume panel → no audio stream opened at all. Probe: Wwise 标志=0, LogAkAudio verbosity=5. Lobby bounce ran (FrontEnd banks "loaded") but no effect.
- Root cause (RE): wrapper 0x3c32c00 logs "Audiokinetic Audio Device initialized."/"...initialization failed." via LogInit (GOT 0x5124fd0 → 0x51b8678, same category as "LogInit: Using libcurl") at Log level. Neither line ever appears → FAkAudioModule::StartupModule (0x3c3a730, vtable ref 0x50a81c8) never runs → AkAudio module is never loaded (no ModuleManager "not found" warning either, so plugin manager never asks for it). FAkAudioDevice::Get (0x3c318a0) only returns a device if module already loaded → all Wwise calls no-op.
- Init 0x3c32e24 gates: nosound (utf32 0x4730770), GOT bools 0x5125008/0x5124fe4, static bInitialized 0x53b5d50 (merged globals; LogAkAudio verbosity at +8 = 0x53b5d58).
- AkDiag useless: game reads Saved/Config/Android/Engine.ini via LoadCryptFileToString → "invalid config file" (expects Tencent-encrypted ini). Remove AkDiag + strip our lines next version.
- Proposed fix (needs user OK, breaks MEM=false): renderer fn 0x22e7f58 does once-only FModuleManager::LoadModule(FName(L"SoftwareOcclusionCulling")) (utf32 at 0x45e7104, ~2.8 s after log open, warning "not found"); result only stored at 0x4a35634, never used. Overwrite that utf32 string with L"AkAudio\0" before then → engine loads Wwise module itself. 0x101bfb8 is an existing IsModuleLoaded/LoadModule("AkAudio") helper with no callers.
- v1.20 (versionCode 21, CORE_VER 21): user allowed modifying the game package ("可以改游戏安装包，只要确保不闪退"). ZFrame app game card gets a 「声音补丁」 button: reads the installed injected base.apk, Patcher.patchSound rewrites lib/armeabi-v7a/libUE4.so at file offset 0x45e7104 (L"SoftwareOcclusionCulling" → L"AkAudio\0" + rest unchanged, 32 bytes differ). It works for both STORED and DEFLATED entries and CRC is recomputed. It re-signs with gkey and update-installs with no uninstall (aborts if the game isn't signed by gkey; on install failure the uninstall prompt is never shown). The same button restores. soundState checks the exact bytes, so an unknown libUE4 version gets no patch. Tested on the real libUE4 in the sandbox: patch → state 2, restore → md5 identical.
- Call site 0x22e7f58 = FRendererModule vtable slot 9 (BeginRenderingViewFamily, game thread), once-only, result only stored → safe target. Module missing → only a "not found" warning.
- Core: AkDiag now only cleans its old lines out of Engine.ini (deletes the file if empty); the auto lobby bounce defaults off (pref audio_auto2); the manual button stays.
- v1.21 (vc 22, core 22): the user's ZFrame app had an empty games list and they started re-injecting the already-injected game, which would add classes3 and prompt an uninstall. Fixes: patchBase refuses a manifest that already contains com.zframe.inject.ZInit; pickInstalled on a gkey-signed game just re-adds it to the list; the 声音补丁 button shows for any installed non-tool game; install() skips the uninstall prompt when the game is gkey-signed.
- 21:27 sound confirmed working: "Audiokinetic Audio Device initialized." appears in the logs.
- Map paths (from the libMczi window run, 21:30): 海岛 /Game/Maps/ErangelMobile_Runtime/BR-Erangel_Mobile_Main; 海岛2 /Game/Maps/ErangeMobileRenew_Runtime/BR-ErangelRenew_Mobile_Main (BP_NZBRIslandGameMode_C, starts on the spawn island, no guns; CachedPickupSPList=0); 雨林 Savage; lobby FrontEndMap_NEW.
- Old in-game "ZFrame 修改器" = built-in fallback core 5 (memory editor). It appears when the game can't reach the ZFrame provider (Honor 关联启动 block) → that's why "only launching from ZFrame gives the new version".
- v1.22 (vc 23, core 23): Z menu renamed 艾挽 (ball text 艾), with tabs 地图/功能/设置; map buttons 海岛/海岛2/雨林/进入大厅. A map button goes via the lobby first when lobby audio hasn't been seen yet this launch (pref audio_auto3 default on). ZInit caches the last provider core in filesDir (zcore_last.dex/.ver) and uses it when the provider fails, if it's newer than the built-in core. The app's 修补游戏 also replaces the injected ZFrame dex in the game APK (detected by the Lcom/zframe/inject/ZInit; string), so the built-in core becomes current.
- Declined: renaming/re-skinning the libMczi window and cracking its network verification.
- TODO: guns on the 海岛2 spawn island.
- v1.23 (vc 24, core 24), changes requested 21:43–21:45:
  - In-game UI is now a resident panel like the libMczi window instead of a ball + dialog: a draggable title bar ("艾挽 · 全军出击单机版"; tap it to fold ▼/▶), a left tab column (地图/功能/设置), and content on the right. View tag zframe_panel.
  - Lobby logic: on boot, ~3 s after StartupMap loads, `open FrontEndMap_NEW` runs once (audio inits in the lobby). After that, map buttons go straight to the map. The old BR→lobby→BR bounce stays only as a fallback, used if something enters a BR map before lobby audio has been seen. Pref audio_auto3.
  - FPS/quality: device level comes from Tencent APM QCC config (plain asset assets/QCC_APM_PUBGM; cached copies apm_qcc* in app data). Adreno 810 isn't in the table → level 2. Qcc.fix() at boot adds Build.MODEL to level6 filter-model "6" in the cached copies; 修补游戏 also patches the asset. The menu's FPS buttons were removed, and applyFps is disabled (the game's own setting decides).
- v1.23 test, 21:53: QCC took effect: MobileLevel went 2→5 and PipelineQuality 2→5. But the 超高 framerate option still says 「您的手机暂不支持」, and the lobby character renders as a gray mannequin (default material look). Settings log shows CalcRenderQuality InDeviceLevel:0. UE has MobileLevel2DeviceLevelMap / GetDeviceLevelByQcc / DeviceLevelForTest; the FPS gate is not found yet (probably UMG/blueprint).
- QCC algorithm, from the dex: com.tencent.hawk.bridge.QccConfig.judgeDcls tries filter-model, then gpu, soc and manu, then "and opts" (res/ram/cpufreq/cores/gpu tables).
- libMczi is loaded by System.loadLibrary("Mczi") in com.tencent.tpshell.TPShellApplication.preInitial. It has no JNI_OnLoad (init_array only) and imports EGL/GLES (the ImGui window).
- v1.24 (vc 25, core 25):
  - The QCC model bump is now off by default. Qcc.fix removes the model unless the pref qcc_up is set, and 修补游戏 also reverts the asset. The panel's 设置 tab has a 高档机型 toggle.
  - 修补游戏 is now a list: 更新内核 / 更新内核 + 关掉原作者窗口 (replaces libMczi.so in the APK with a copy of libzlib.so and backs the original up to ZFrame filesDir/libMczi.orig.so) / 恢复原作者窗口 / 去掉声音补丁.
  - User confirmed 21:53 that "他那个" (the libMczi window) should go.
- Open requests: lobby character fix, and putting all wardrobe clothes into the 仓库 (server inventory, offline). Not done; needs RE.
- 22:00 user: the gray lobby avatar was always there; it comes from the package, not from QCC. Simple ask: spawn 风衣 in-match, into the backpack or on the ground. Found that the exec functions GiveItem/GiveItemCount/SpawnPickupBox are stubbed to bx lr. ServerGiveItem (thunk 0xe67c20) calls vtable+0x378, implementation not checked yet. Asked the user whether to attempt a libUE4 patch that routes GiveItem to the real pickup/add-to-backpack path. Awaiting answer.
- 22:08: the libMczi window still showed. The 22:04 file listing has libMczi.so at its original 3866712 bytes and the lib dir is still from 21:19, so the v1.24 修补 never installed. Asked the user what happened. TPShell preInitial loads fucktp, then Mczi, then tprt from nativeLibraryDir.
- In-match outfit: ANZBRCharacter::InitAvatarProxy (0x10d7380) has no DS playerinfo offline, so it falls back to DefaultRoleClotheDataTable and gets the list 10200000000, 30302000000, 30301000001, 30306000000, 30213000003, 30303000006, then runs Give Inventory. Plan: a libUE4 patch that adds the coat ID. Asked the user for the 黑色连帽风衣 ID; known coats are 30315000050 and 30315000061. Starting guns come later.
- Gray avatar: the 'Lower Device for Original Mesh' check (0xea5b4c) is gated on quality >= 2, but the avatar stays gray even at level 5, so the cause is something else. Deprioritised.
- v1.25 (vc 26, core 26):
  - 功能 tab adds 查物品编号（只读）. It scans the heap through /proc/self/mem (read-only) for UTF-32 names containing 风衣 or 连帽, finds pointers to those names, and collects nearby int64 values in 1e10–1e11. ItemFind.java.
  - 设置 tab shows the 原作者窗口 status by comparing the libMczi.so and libzlib.so sizes in nativeLibraryDir.
  - Next: once the user sends the coat ID, patch the InitAvatarProxy default list in libUE4.
- 22:20 v1.25 test:
  - libMczi.so in the lib dir is now 67088 bytes (the stub), so the 原作者窗口 is closed.
  - ItemFind found the 风衣 names, including 黑色风衣 and 绝地风衣 (the hooded one; 脱帽版 is separate). But the "IDs" next to them are FString {ptr,Num} pairs (hi = length), not item IDs, so the tool is useless as written.
  - The user asked to remove it.
- Coat plan: only two Coat models are mounted (30315000050 F_Jacket, 30315000061), so try 30315000061 as the 绝地风衣.
- User's next ask: find the gun-spawn code and ammo. GiveItem and its siblings are stubs (0xb63a30, bx lr), so look at the pickup spawn path instead (SpawnPickup / ItemGenerator / PickupManager).
