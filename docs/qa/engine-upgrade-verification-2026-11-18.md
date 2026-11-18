# 引擎升级报告核验与修复（2026-11-18）

核验对象：用户提供的 `dsh-engine-upgrade-issue.md`，以及本仓库 `dev` 工作区。
附件中的建议作为待验证线索；没有据此修改已发布的 v3.0 Release 或手机现有引擎。

| 报告项 | 本次核验 | 仓库处理 |
| --- | --- | --- |
| feed 指向旧分叉 | 源码确认；v3.0 APK 溯源格式没有热更新资产映射 | 改用本仓库滚动 feed 约定；支持环境变量和参数；结构化解析，过滤 APK 清单；强制校验 ABI / SHA-256 / 请求版本 |
| Bash 子脚本直接执行 | 构建入口存在直接执行和 `-x` 守卫 | 显式 Bash 调用；依赖门禁缺失时失败，不能因缺执行位而跳过 |
| `/tmp` 写入 | closure 构建硬编码；Android 需可写临时目录 | closure / runtime / sharp 临时目录遵循 TMPDIR；手机文档设置 `$PREFIX/tmp` |
| npm / pnpm / 手机文档不匹配 | npm shim 无条件引用不存在的 corepack；源码闭包说明错误 | shim 查找实际后端并支持独立 CLI；补全 pack → file consumer → Android 补丁流程 |
| rc.4 缺包 | 报告列出的名称展开后为 25 个，本次逐个请求 registry 均 HTTP 200 | 不沿用“固定缺 26 个”的结论；统一用本地 pack consumer，避免构建取决于发布同步时序 |

pnpm 11.28.5 的实际 npm tarball 使用 `bin/pnpm.mjs`，并提供 `pmOnFail`。
该版本 changelog 明确移除了 `managePackageManagerVersions` 等旧设置；
实际执行 `--pm-on-fail=ignore config get pmOnFail` 返回 `ignore`。
`--config.registry=<URL> config get registry` 与 `PNPM_CONFIG_REGISTRY` 均核对生效。
元数据来源：[pnpm 11.28.5](https://registry.npmjs.org/pnpm/11.28.5)、
[npm 11.6.2](https://registry.npmjs.org/npm/11.6.2)。缺包核验请求形式为
`https://registry.npmjs.org/@deepseek-ai%2f<包名>/0.2.2-rc.4`，例如
[dsh-host-webserver rc.4](https://registry.npmjs.org/@deepseek-ai%2fdsh-host-webserver/0.2.2-rc.4)。
这些是本次时点的 registry 状态，不证明历史下载没有失败，也不证明完整发布依赖闭包均可用。

同时修复的相关缺陷：

- `howto` 的 awk 标记不匹配，原先输出为空。
- `pull` 忽略指定版本、直接递归执行 Android shebang、自拷贝同一路径，且子步骤失败后仍可能报成功。
- 新暂存包缺入口时，错误地借用旧暂存树的入口通过校验。
- 自包含骨架的 `bin/rg` 是相对路径 wrapper；复制进平台包后路径失效。注入时改取 `lib/dsh-engine/rg-bin`。
- 默认 `acceptance.sh --device` 曾启动 Root / Shizuku 测试链；改为 Android CLI 装机以及应用 UID 下的 Normal 模式验收。

## 验证范围与复现命令

```sh
python3 -m unittest discover -s scripts/tests -p test_engine_upgrade.py -v
bash scripts/acceptance.sh
python3 scripts/test-engine-upgrade-device.py --device <serial>
```

- 15 项离线集成回归通过。覆盖真实 tar 暂存、校验失败、候选 feed、版本不匹配、
  缺执行权限的 Bash 门禁、TMPDIR 清理、feed 生成、原生 rg 来源选择；
  还用实际 npm 离线安装本地 tarball 并验证 dsh → cordis → cosmokit 的解析链。
- 30 项 JVM 单测通过，其中新增 7 项 CLI shim 测试；Release APK 编译与 APK 内容断言通过。
- 真机：当前连接的 Redmi M2012K11AC（`5f08bc20`），Normal 模式，应用 UID `10278`，
  Node `v24.22.1`。通过 AdbDebugProvider 在应用环境执行，不调用 su、Root 或 Shizuku。
  使用独立 TMPDIR / PREFIX、极小测试闭包与模拟下载；运行真实依赖审计脚本，
  验证 check / pull / stage、错误清单、校验失败和 closure 打包。测试目录与传输文件自动清理。
- 真机既有引擎为 `0.2.2-rc.1+sharp1`，测试未对它执行 stage / apply / rollback，未安装新 APK。

本次没有在 vivo 上重跑完整 rc.4 构建或实际热切换，也没有完成 rc.4 `read_image` 工具端到端验收。
隔离测试闭包不是完整 dsh 引擎；普通模式应用环境通过不能替代上述功能验收。
生成更新清单不等于发布在线 feed，本次未上传引擎 tar 或改动 Release 资产。
