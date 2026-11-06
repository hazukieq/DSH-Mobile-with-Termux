# flicker —— 真机悬浮窗闪烁 / 输入可达性取证工具链

配套文档：`docs/qa/flicker-forensics.md`（判据与实测事实都在那里，先读它）。

## 一条命令

```bash
cd scripts/probes/flicker
./capture.sh 15            # 15s：logcat + layers + frames + window + input，自动出 report.md
./capture.sh 15 --light    # 只跑 layers+frames（最轻，装机复验时用）
./capture.sh 15 --record   # 额外 screenrecord，给视觉真值
```

产物在 `/tmp/flicker-evidence/run-<时间戳>/`：四路 CSV + `logcat.txt` + `report.md`。

## 单独用

```bash
./layers.sh 10   # 层对象存在性（最高频 17~23Hz）→ (a)/(b)
./frames.sh 10   # 完整 SurfaceFlinger（12Hz）→ (b)/(c)/(d)
./window.sh 10   # dumpsys window windows（14Hz）→ View 可见性 / frame
./input.sh  10   # dumpsys input → 可达性（touchableRegion / NOT_TOUCHABLE / 点上窗口栈）
./logcat.sh dump 10
./record.sh 10 "959,425,1058,524"
```

## 不需要设备也能验证解析器

```bash
./selftest.sh    # 37 个断言，跑 testdata/ 里的合成 fixture
```

## 目录

```
lib.sh                 公共层（adb/root/push/discover/采样封装）
layers.sh frames.sh window.sh input.sh logcat.sh record.sh capture.sh
analyze.py             四路 CSV + logcat + 逐帧统计 → report.md
selftest.sh            离线自测
device/sampler.sh      设备侧采样循环（root 跑，采集+解析同管道，只回 CSV）
device/parse_*.awk     5 个流式解析器（libc/toybox awk 兼容，无 gawk 扩展）
testdata/              合成 fixture（无设备信息）
```

## 铁律

- 只读：不注入输入、不装 APK、不 force-stop、不改设置。`logcat.sh clear` 会动系统状态，需显式 `--yes`。
- 采样必须 root；`dumpsys SurfaceFlinger` 非 root 会裁掉 Output Layer / buffer 句柄 / reuse 计数。
- 目标 layer id 每次装机都会变，脚本每次都重新 `discover`，不要写死。
