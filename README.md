# TaskLite - Android Task Manager (OS project)

Pure Java, **zero third-party libraries**, single Activity, UI drawn in code.
The APK is very small because there is no library code.

## Features
| Tab | What it shows | OS concept |
|---|---|---|
| Dashboard | live CPU graph, per-core bars, RAM graph (used/available/cached/swap), battery, storage, network speed, uptime | CPU scheduling load, memory management, I/O devices |
| Processes | full process list with PID, CPU %, RAM (PSS), sort, **End task** (needs Pro mode, see below) | process management, PCB, memory accounting |
| Apps | installed apps, screen time today, size, last used, **End task**, **Clean RAM**, App info | background processes, process termination |
| System | device + kernel version, **PCB of this app** (PID, PPID, state, nice, threads, VmRSS, context switches, open files), thread table | process control block, thread states, /proc file system |

## Efficiency design (good for viva)
- Sampling threads run at `THREAD_PRIORITY_BACKGROUND`, and **stop completely in onStop()** -> no sampling at all while the app is in the background.
- Hot path reads `/proc/meminfo`, `/proc/stat`, `/sys/.../scaling_cur_freq` into **reused byte buffers** (no per-tick garbage).
- Graphs are **ring buffers** (90 floats); `onDraw()` allocates nothing.
- Lists use **view recycling (ViewHolder pattern)**; rows are built in code (no XML inflation).
- Expensive work (`dumpsys meminfo`, app sizes, icons) is on background threads and only while that tab is open.
- No third-party library: nothing to initialise, tiny APK, R8-shrunk release build.

## Honest limits of Android (not bugs)
- Normal apps **cannot** read other apps' processes (`/proc` has `hidepid`) and, from Android 8, cannot read `/proc/stat` on most phones.
- Without Pro mode, CPU % is an **estimate from core clock speeds** (the app tells you which source it used).
- **Pro mode** = one-time `adb shell pm grant com.tasklite.app android.permission.DUMP`. After that the app reads `dumpsys cpuinfo` and `dumpsys meminfo` = real CPU % and RAM per process.
- "End task" uses `killBackgroundProcesses()`: it ends **background** processes only (same as every non-root task killer). Force-stop opens the App Info screen.

## Build the APK without Android Studio (easiest, from phone or PC)
1. Create a new GitHub repo, upload this whole folder (keep the `.github` folder).
2. Open the **Actions** tab -> *Build APK* -> wait ~3 min (or press *Run workflow*).
3. Open the finished run -> **Artifacts** -> download `TaskLite-apk` (zip) -> extract `app-release.apk`.

## Build with Android Studio
1. File -> Open -> select this folder, let Gradle sync.
2. Plug the phone (USB debugging ON) -> press Run.
   Or *Build -> Build APK(s)*.

## Install on the phone
1. Copy `app-release.apk` to the phone, tap it.
2. Allow "Install unknown apps" for your file manager/browser when asked.
3. Open TaskLite. Apps tab -> *Open settings* -> switch Usage access ON for TaskLite.
4. (Optional) Pro mode: phone Settings -> Developer options -> USB debugging ON, connect PC, run
   `adb shell pm grant com.tasklite.app android.permission.DUMP`, then Processes tab -> *Check again*.
   (Android 11+ can also do this wirelessly: Developer options -> Wireless debugging -> `adb pair`, `adb connect`.)

## Project layout
```
app/src/main/java/com/tasklite/app/
  MainActivity.java   UI + tabs + actions
  Sampler.java        background data collection (CPU, RAM, battery, storage, network)
  Pro.java            dumpsys-based process list (Pro mode)
  Apps.java           usage stats, app sizes, end task, clean RAM
  AppIndex.java       package -> name lookup
  ProcFs.java         fast /proc and /sys readers
  GraphView.java, CoreBarsView.java, BarView.java, RowView.java, Chips.java, Ui.java, Models.java
```
