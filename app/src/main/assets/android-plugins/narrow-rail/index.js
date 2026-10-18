/**
 * [dsh-android] 窄屏侧边栏收纳 —— 宿主端（v1.3.0）。
 *
 * 收起状态不再显示整条图标栏，只保留左上角一个悬浮图标；点它才展开侧边栏。
 * 纯 UI 客户端插件，宿主侧只需要一条可 import 的 Loader entry。
 *
 * @module dsh-android-narrow-rail
 */

/** Cordis 插件名。 */
const name = "android-narrow-rail";

/** 宿主侧不依赖任何服务。 */
const inject = [];

/** 宿主端插件体：空实现（纯 UI 插件）。 */
function apply() {}

export { apply, inject, name };
