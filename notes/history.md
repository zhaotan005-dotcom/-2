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
