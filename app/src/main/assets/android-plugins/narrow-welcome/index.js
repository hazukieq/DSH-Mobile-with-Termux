/**
 * [dsh-android] 窄屏「开始使用」欢迎页适配 —— 宿主端（v1.3.0）。
 *
 * 与 narrow-settings 同为纯 UI 客户端插件：宿主侧只需一条可 import 的 Loader
 * entry，引擎的 dsh-client-modules 才会把 exports["./client"] 注入页面。
 *
 * @module dsh-android-narrow-welcome
 */

/** Cordis 插件名。 */
const name = "android-narrow-welcome";

/** 宿主侧不依赖任何服务。 */
const inject = [];

/** 宿主端插件体：空实现（纯 UI 插件）。 */
function apply() {}

export { apply, inject, name };
