/**
 * [dsh-android] profile 插件启动健康探针。
 *
 * 为什么必须在 Cordis 内观察：Loader 的 import 失败会被 Cordis 隔离，失败 entry
 * 可能没有 fiber，但进程仍能提供 HTTP，因此 Android 侧只检查端口无法判断工作区
 * host service 是否真的可用。探针等待 Loader 树收敛后检查服务注册表，再写一个短期
 * JSON marker，供 EngineSupervisor 在把 WebUI 暴露给用户前读取。
 *
 * marker 只用于诊断；探针不修改 profile、不停用包，有限且可回滚的恢复由 Android
 * 监督器执行。
 */
import fs from "node:fs";
import path from "node:path";

const name = "android-plugin-health";
const inject = ["loader"];
const REQUIRED_SERVICES = [
  "typert",
  "typertGateway",
  "directoryPicker",
  "directoryPickerController",
  "workspaceRegistry",
  "workspaceController",
  "webServer",
  "loader",
];
// `applyEntryPatches` 将 `name` 作为匹配守卫，而不是可赋值字段。
// 用户 patch 一旦改写关键行的 name，后续 Android overlay 无法仅按 id 恢复它。
// 将该行报告为 degraded，监督器才能隔离提供冲突 patch 的 bundle，避免继续提供
// 一个名义上存在、实际并非内置实现的 host service。
const CRITICAL_ROWS = {
  "typert": "@deepseek-ai/dsh-typert-registry",
  "typert-loader": "@deepseek-ai/dsh-typert-loader",
  "typert-gateway": "@deepseek-ai/dsh-api-gateway",
  "directory-picker": "@deepseek-ai/dsh-host-directory-picker-auto",
  "workspace": "@deepseek-ai/dsh-workspace",
  "workspace-controller": "@deepseek-ai/dsh-api-workspace-controller",
  "webserver": "@deepseek-ai/dsh-host-webserver",
  "storage-domain": "@deepseek-ai/dsh-storage-domain",
  "session-persistence-jsonl": "@deepseek-ai/dsh-session-persistence-jsonl",
};
// 被禁用或缺失的关键行没有 Loader entry 可供归因。补充合成行失败，
// 这样 PluginRecovery 仍能按 id 匹配禁用了对应内置行的第三方 patch。
const SERVICE_ROWS = {
  typert: [["typert", CRITICAL_ROWS.typert]],
  typertGateway: [["typert-gateway", CRITICAL_ROWS["typert-gateway"]], ["typert", CRITICAL_ROWS.typert]],
  directoryPicker: [["directory-picker", CRITICAL_ROWS["directory-picker"]]],
  directoryPickerController: [["workspace-controller", "@deepseek-ai/dsh-api-workspace-controller"]],
  workspaceRegistry: [
    ["workspace", CRITICAL_ROWS["workspace"]],
    ["storage-domain", CRITICAL_ROWS["storage-domain"]],
    ["session-persistence-jsonl", CRITICAL_ROWS["session-persistence-jsonl"]],
  ],
  workspaceController: [["workspace-controller", CRITICAL_ROWS["workspace-controller"]]],
  webServer: [["webserver", CRITICAL_ROWS.webserver]],
};
const MAX_WAIT_MS = 15000;
const POLL_MS = 250;

function markerPath() {
  const home = process.env.DSH_HOME || "";
  return home ? path.join(home, ".android-plugin-health.json") : "";
}

function safeGet(ctx, service) {
  try {
    return ctx.get(service);
  } catch {
    return void 0;
  }
}

/**
 * v1.6.3：Cordis context 被 dispose 后，`ctx.loader` 的 getter 会抛
 * "cannot get required service \"loader\" in inactive context"。
 * 旧代码 `safeGet(ctx,"loader") || ctx.loader` 只保护了前半段，后半段在
 * inactive context 上直接抛未捕获异常 → 引擎在重启风暴中崩溃（issue 现场）。
 * 这里两条路径都夹在 try/catch 里，并显式判断 context 是否已 disposed。
 */
function loaderOf(ctx) {
  const direct = safeGet(ctx, "loader");
  if (direct !== void 0) return direct;
  try {
    if (ctx.scope && ctx.scope.disposed) return void 0;
  } catch {}
  try {
    return ctx.loader;
  } catch {
    return void 0;
  }
}

function writeMarker(value) {
  const file = markerPath();
  if (!file) return;
  try {
    const temp = `${file}.tmp-${process.pid}`;
    // Root 模式下引擎由 uid 0 写 marker；0644 让 App uid 仍能读取诊断。
    // marker 不含凭证、会话内容或工作区文件名。
    fs.writeFileSync(temp, JSON.stringify(value) + "\n", { mode: 0o644 });
    // Android 进程的 umask 可能把 0644 收窄为 0600；Root 模式下 supervisor
    // 仍需读取这个无敏感信息 marker，因此显式恢复为其他用户可读。
    try { fs.chmodSync(temp, 0o644); } catch {}
    fs.renameSync(temp, file);
  } catch (error) {
    console.error(`[android-plugin-health] marker write failed: ${error?.message || error}`);
  }
}

function inspect(ctx) {
  const loader = loaderOf(ctx);
  if (loader === void 0) return null;
  const entries = loader && typeof loader.entries === "function" ? [...loader.entries()] : [];
  const failedEntries = entries
    .filter((entry) => {
      if (entry.disabled || entry.options?.group) return false;
      // import 失败没有 fiber；插件 init 抛错则保留 fiber，但 Cordis
      // 将其状态置为 3 并保存 _error。两种情况都表示 entry 不可用。
      return entry.fiber === void 0 || entry.fiber.state === 3 || entry.fiber?._error !== void 0;
    })
    .map((entry) => ({
      id: String(entry.options?.id || entry.id || ""),
      name: String(entry.options?.name || ""),
      error: entry.fiber?._error ? String(entry.fiber._error?.stack || entry.fiber._error?.message || entry.fiber._error) : "",
    }));
  const criticalMismatches = entries
    .filter((entry) => Object.hasOwn(CRITICAL_ROWS, entry.options?.id || ""))
    .filter((entry) => entry.options?.name !== CRITICAL_ROWS[entry.options.id])
    .map((entry) => ({
      id: String(entry.options?.id || ""),
      name: String(entry.options?.name || ""),
      error: `critical row must be ${CRITICAL_ROWS[entry.options.id]}`,
    }));
  failedEntries.push(...criticalMismatches);
  const missingServices = REQUIRED_SERVICES.filter((service) => safeGet(ctx, service) === void 0);
  for (const service of missingServices) {
    const rows = SERVICE_ROWS[service] || [];
    for (const [id, name] of rows) {
      if (failedEntries.some((entry) => entry.id === id)) continue;
      failedEntries.push({
        id,
        name,
        error: `required service ${service} is unavailable`,
      });
    }
  }
  const profile = safeGet(ctx, "profileContext");
  const bundles = Array.isArray(profile?.startedBundles) ? profile.startedBundles.map(String) : [];
  const marker = {
    version: 1,
    bootId: process.env.DSH_ANDROID_BOOT_ID || "",
    pid: process.pid,
    at: Date.now(),
    ok: missingServices.length === 0 && failedEntries.length === 0,
    missingServices,
    failedEntries,
    bundles,
  };
  writeMarker(marker);
  if (!marker.ok) {
    console.error(
      `[android-plugin-health] profile is degraded: missing=${missingServices.join(",") || "none"} ` +
        `failed=${failedEntries.map((entry) => `${entry.id}:${entry.name}`).join(",") || "none"}`,
    );
  }
  return marker;
}

function apply(ctx) {
  const started = Date.now();
  let finished = false;
  let timer;
  const stop = () => {
    finished = true;
    if (timer !== void 0) {
      clearInterval(timer);
      timer = void 0;
    }
  };
  const check = () => {
    if (finished) return;
    try {
      const loader = loaderOf(ctx);
      if (loader === void 0) { stop(); return; }
      const tasks = typeof loader.getTasks === "function" ? loader.getTasks() : [];
      if (tasks.length > 0 && Date.now() - started < MAX_WAIT_MS) return;
      stop();
      runCatching(() => inspect(ctx));
    } catch (error) {
      // 探针永远不能成为引擎崩溃源：任何异常都只记录并停表
      stop();
      console.error(`[android-plugin-health] probe failed: ${error?.message || error}`);
    }
  };
  const runCatching = (fn) => { try { fn(); } catch (error) {
    console.error(`[android-plugin-health] inspect failed: ${error?.message || error}`);
  } };
  timer = setInterval(check, POLL_MS);
  check();
  // 双保险：Cordis 的 disposer 与 effect 清理都停表（disposal 后 timer 不再触发）
  try { ctx.effect(() => stop); } catch {}
  return stop;
}

export { apply, inject, name };
