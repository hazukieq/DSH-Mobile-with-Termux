/**
 * [dsh-android] android-fetch-auto —— 官方 DeepSeek Fetch API 优先、本地 HTTP(S) 抓取兜底，
 * 并给本地这一跳换上「fake-IP 友好」的地址解析。
 *
 * ## 为什么需要它（2026-10-20 移动端移植）
 * 两件事必须一起解决：
 *   1. **fake-IP 拦截**：web-fetch-http 的 SSRF 守卫要求解析出的地址全部是公网单播；
 *      旁路由 / Clash / Surge 的 fake-IP 模式（198.18.0.0/15，RFC 2544 保留段）会让
 *      **所有域名**都解析到保留段 → 整批拒绝，报 `WEB_BLOCKED_URL`。桌面端靠
 *      `allowedNonPublicRanges` 修复（0.2.1-rc.1 之后的上游 master），而移动端引擎
 *      锁的是 npm 0.2.1-rc.2 —— 没有这个配置口。
 *   2. **官方 Fetch API 抓不到部分站点**：目标站反爬 / Captcha / 重防火墙时，官方接口
 *      直接回 404 `failed to fetch URL`（不计费），而本地抓取经旁路由反而能读到。
 *
 * 本插件注册**一个**组合 provider（id = android-fetch-auto）：
 *   · 默认先试官方 DeepSeek Fetch API（有凭据时），任何非取消的失败 → 回落到本地；
 *   · `preferLocal: true`（或 `DSH_ANDROID_FETCH_PREFER_LOCAL=1`）反过来：本地优先、官方兜底；
 *   · 本地这一跳用自定义 resolver：公网地址照旧钉死，允许配置的 fake-IP 段
 *     （默认 198.18.0.0/15，可用 `allowedNonPublicRanges` / `DSH_ANDROID_FETCH_RANGES` 改），
 *     loopback / RFC1918 / link-local / CGNAT 仍然拒绝；
 *   · 两条都失败时把两边的失败原因都带上，便于排查。
 *
 * ## 为什么不用裸包名 import
 * 插件位于 `<engine>/android-plugins/`，**不在 node 的模块解析链上**（engine/ 下
 * 没有 node_modules），裸包名解析必然失败（同 fast-mode.mjs 的踩坑记录）。这里按
 * **绝对路径**从 `<engine>/lib/node_modules/...` 动态 import。
 *
 * ## 引擎热更新
 * 只依赖引擎里已有的包（dsh-web / dsh-web-fetch-http / dsh-web-deepseek-api /
 * dsh-launch-environment / ipaddr.js）与它们的公开导出，不修改任何 vendor 文件；
 * 引擎层被 dsh-update 换掉后，本插件（由 App 资产在启动时重新部署）仍然可用，只要
 * 上述接口不变（`HttpFetchProvider(limits, resolveAddresses)` 的注入点是上游设计）。
 *
 * @module dsh-android-fetch-auto
 */

import fs from "node:fs";
import path from "node:path";
import dns from "node:dns/promises";
import { fileURLToPath, pathToFileURL } from "node:url";

export const name = "android-fetch-auto";

/** 只需要 web 接缝来注册 provider；其余服务在 fetch 时按需读取。 */
export const inject = ["web"];

/** 与官方 deepseek-account 会话路由一致的 provider 名。 */
const ACCOUNT_PROVIDER = "deepseek-account";

/** provider id（overlay 里 web.fetchProvider 指向它）。 */
const PROVIDER_ID = "android-fetch-auto";

/** 默认放行的非公网段：Clash / Mihomo / Surge 的 fake-IP 池。 */
const DEFAULT_ALLOWED_RANGES = "198.18.0.0/15";

/** 与 @deepseek-ai/dsh-web-fetch-http 0.2.1-rc.2 的 Config 默认值保持一致。 */
const DEFAULT_LIMITS = {
  maxResponseBytes: 5e6,
  maxBodyChars: 1e5,
  timeoutMs: 30e3,
  maxRedirects: 5,
  userAgent: "deepseek-harness/0.0.1 (+https://github.com/deepseek-ai)",
};

/** 插件日志前缀（引擎日志里可 grep）。 */
const TAG = "[android-fetch-auto]";

/** 引擎把插件 stdout/stderr 收进 engine.log；context 上没有统一的 logger。 */
function logInfo(message) {
  console.info(`${TAG} ${message}`);
}

/** 警告走 stderr，便于 grep。 */
function logWarn(message) {
  console.warn(`${TAG} ${message}`);
}

/** 本文件所在目录 = <engine>/android-plugins，engine 根是上一级。 */
const HERE = path.dirname(fileURLToPath(import.meta.url));
const ENGINE_ROOT = path.resolve(HERE, "..");

/**
 * 按绝对路径加载引擎里的包（裸包名不可用，见文件头）。
 * @param {string} pkg - `@deepseek-ai/dsh-web` 或 `ipaddr.js` 这类 node_modules 下的包名。
 * @param {string} [entry] - 包内入口文件，默认 `lib/index.js`。
 * @returns {Promise<Record<string, unknown>>} 模块命名空间。
 */
async function loadEnginePackage(pkg, entry = "lib/index.js") {
  const candidates = [
    path.join(ENGINE_ROOT, "lib/node_modules", pkg, entry),
    path.join(ENGINE_ROOT, "node_modules", pkg, entry),
  ];
  for (const candidate of candidates) {
    if (!fs.existsSync(candidate)) continue;
    return await import(pathToFileURL(candidate).href);
  }
  throw new Error(`engine package not found: ${pkg}/${entry}`);
}

/**
 * 拆分逗号 / 空白分隔的 CIDR 列表，空值回落到默认 fake-IP 段。
 * @param {string | undefined} value
 * @returns {string[]} CIDR 字符串数组。
 */
function parseRanges(value) {
  const raw = (value ?? DEFAULT_ALLOWED_RANGES).trim();
  return raw === "" ? [] : raw.split(/[,\s]+/u).filter((item) => item !== "");
}

/**
 * 判断一个地址是否允许：公网单播，或落在显式放行的 CIDR 里。
 * @param {import("ipaddr.js")} ipaddr
 * @param {string} address
 * @param {Array<[import("ipaddr.js").IPv4 | import("ipaddr.js").IPv6, number]>} cidrs
 * @returns {boolean} true = 允许连接。
 */
function isAllowedAddress(ipaddr, address, cidrs) {
  let parsed;
  try {
    parsed = ipaddr.parse(address);
  } catch {
    return false;
  }
  if (parsed.range() === "unicast") return true;
  const candidate = parsed.kind() === "ipv6" && parsed.isIPv4MappedAddress() === true
    ? parsed.toIPv4Address()
    : parsed;
  return cidrs.some((cidr) => {
    try {
      return candidate.kind() === cidr[0].kind() && candidate.match(cidr);
    } catch {
      return false;
    }
  });
}

/**
 * 构造本地抓取用的 resolver：整组校验 + 显式 fake-IP 放行，语义与上游一致，
 * 只是多了一个「这些非公网段是运营商/旁路由的 fake-IP」的信任声明。
 * @param {object} deps - 引擎模块与放行表。
 * @returns {(hostname: string, signal?: AbortSignal) => Promise<Array<{address: string, family: number}>>}
 */
function createResolver({ ipaddr, cidrs, WebError }) {
  return async (hostname) => {
    const entries = await dns.lookup(hostname, { all: true, order: "verbatim" });
    if (entries.length === 0) {
      throw new WebError(`hostname "${hostname}" resolved to no addresses`, "WEB_PROVIDER_ERROR");
    }
    const addresses = [];
    for (const entry of entries) {
      if (!isAllowedAddress(ipaddr, entry.address, cidrs)) {
        const range = (() => {
          try {
            return ipaddr.parse(entry.address).range();
          } catch {
            return "unknown";
          }
        })();
        throw new WebError(
          `URL hostname "${hostname}" resolves to ${entry.address}, which is in the ${range} range; `
          + "only the configured fake-IP ranges are allowed (set DSH_ANDROID_FETCH_RANGES to change)",
          "WEB_BLOCKED_URL",
        );
      }
      addresses.push({ address: entry.address, family: entry.family });
    }
    return addresses;
  };
}

/**
 * 复刻官方插件的鉴权来源（见 @deepseek-ai/dsh-web-deepseek-api 的 deepSeekWebAuth）：
 * 账号路由会话发账号令牌，否则用凭据服务 / 启动环境里的 API key。
 * 返回 undefined 时表示拿不到任何凭据（只走本地抓取）。
 *
 * @param {import("@deepseek-ai/cordis").Context} ctx
 * @param {string} apiKeyEnv - 凭据引用名。
 * @param {(ctx: unknown) => { get(name: string): { value: string } | undefined } | undefined} launchEnvironmentOf
 * @returns {{ resolveAccountToken: Function, resolveApiKey: Function, apiKeyEnv: string }}
 */
function createAuth(ctx, apiKeyEnv, launchEnvironmentOf) {
  return {
    apiKeyEnv,
    resolveAccountToken: async (endpoint) => {
      const initiator = ctx.get("agents")?.currentInitiator?.();
      if (initiator?.session?.requestContext?.()?.provider !== ACCOUNT_PROVIDER) return undefined;
      return await ctx.get("deepseekAccount")?.resolveToken?.(endpoint);
    },
    resolveApiKey: async () => {
      try {
        const credentials = ctx.get("credentials");
        if (credentials !== undefined) return (await credentials.resolve(apiKeyEnv))?.value;
      } catch (error) {
        logWarn(`credential service failed: ${String(error)}`);
      }
      try {
        const ambient = launchEnvironmentOf(ctx)?.get(apiKeyEnv);
        return ambient !== undefined && ambient.value.length > 0 ? ambient.value : undefined;
      } catch {
        return undefined;
      }
    },
  };
}

/**
 * 组合 provider：官方 Fetch API 优先，本地抓取兜底。
 * @param {object} deps
 * @returns {{ id: string, available(): boolean, fetch(request: object, signal?: AbortSignal): Promise<object> }}
 */
function createAutoProvider({ official, local, WebError, preferLocal = false }) {
  /** 官方优先（默认）或本地优先；两条通道都失败时把两边原因一起报出来。 */
  async function attempt(request, signal) {
    let officialError;
    if (!preferLocal && official !== undefined && official.available() === true) {
      try {
        return await official.fetch(request, signal);
      } catch (error) {
        if (signal?.aborted === true) throw error;
        officialError = error;
      }
    }
    try {
      return await local.fetch(request, signal);
    } catch (error) {
      if (signal?.aborted === true) throw error;
      if (preferLocal) {
        // 本地失败后才轮到官方；把本地失败原因附在官方错误后面。
        if (official !== undefined && official.available() === true) {
          try {
            return await official.fetch(request, signal);
          } catch (officialFailure) {
            if (signal?.aborted === true) throw officialFailure;
            throw new WebError(
              `${String(officialFailure?.message ?? officialFailure)}\n\nLocal fetch also failed: `
              + `${String(error?.message ?? error)}`,
              officialFailure?.code ?? "WEB_PROVIDER_ERROR",
              { cause: officialFailure },
            );
          }
        }
        throw error;
      }
      if (officialError === undefined) throw error;
      // 两条通道都失败：把官方失败原因附在本地错误后面，避免只看到一半线索。
      throw new WebError(
        `${String(error?.message ?? error)}\n\nOfficial DeepSeek Fetch API also failed: `
        + `${String(officialError?.message ?? officialError)}`,
        error?.code ?? "WEB_PROVIDER_ERROR",
        { cause: error },
      );
    }
  }
  return {
    id: PROVIDER_ID,
    /** 本地一跳始终可用，所以组合 provider 永远可用。 */
    available() {
      return true;
    },
    fetch(request, signal) {
      return attempt(request, signal);
    },
  };
}

/**
 * 挂载：加载引擎包 → 组装官方 / 本地两个 provider → 注册组合 provider。
 * @param {import("@deepseek-ai/cordis").Context} ctx
 * @param {Record<string, unknown> | undefined} config - 可选配置（overlay insert 的 config）。
 */
export async function apply(ctx, config) {
  const settings = config ?? {};
  const [webFetch, deepseekApi, web, launch] = await Promise.all([
    loadEnginePackage("@deepseek-ai/dsh-web-fetch-http"),
    loadEnginePackage("@deepseek-ai/dsh-web-deepseek-api"),
    loadEnginePackage("@deepseek-ai/dsh-web"),
    loadEnginePackage("@deepseek-ai/dsh-launch-environment"),
  ]);
  const ipaddr = (await loadEnginePackage("ipaddr.js", "lib/ipaddr.js")).default
    ?? (await loadEnginePackage("ipaddr.js", "lib/ipaddr.js"));

  const { WebError } = web;
  const { HttpFetchProvider } = webFetch;
  const {
    DeepSeekFetchApiProvider,
    DEEPSEEK_WEB_API_BASE_URL,
  } = deepseekApi;
  const { launchEnvironmentOf } = launch;

  const ranges = parseRanges(
    (typeof settings.allowedNonPublicRanges === "string" ? settings.allowedNonPublicRanges : undefined)
    ?? process.env.DSH_ANDROID_FETCH_RANGES,
  );
  const cidrs = [];
  for (const range of ranges) {
    try {
      cidrs.push(ipaddr.parseCIDR(range));
    } catch (error) {
      logWarn(`ignoring invalid CIDR ${range}: ${String(error)}`);
    }
  }

  const limits = { ...DEFAULT_LIMITS };
  const resolver = createResolver({ ipaddr, cidrs, WebError });
  const local = new HttpFetchProvider(limits, resolver);

  const baseURL = (typeof settings.baseURL === "string" && settings.baseURL.length > 0)
    ? settings.baseURL
    : (process.env.DSH_DEEPSEEK_WEB_API_BASE_URL ?? DEEPSEEK_WEB_API_BASE_URL);
  const apiKeyEnv = typeof settings.apiKeyEnv === "string" && settings.apiKeyEnv.length > 0
    ? settings.apiKeyEnv
    : "DEEPSEEK_API_KEY";
  const preferLocal = settings.preferLocal === true || process.env.DSH_ANDROID_FETCH_PREFER_LOCAL === "1";
  const auth = createAuth(ctx, apiKeyEnv, launchEnvironmentOf);
  const official = new DeepSeekFetchApiProvider(() => ({ ...auth, baseURL }));

  ctx.web.registerFetchProvider(createAutoProvider({ official, local, WebError, preferLocal }));
  logInfo(
    `registered ${PROVIDER_ID} (order: ${preferLocal ? "local-first" : "official-first"}; `
    + `fake-IP ranges: ${cidrs.length > 0 ? ranges.join(",") : "none"})`,
  );
}
