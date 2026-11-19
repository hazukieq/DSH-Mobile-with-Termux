import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const name = 'android-discipline';
export const inject = [];
const KIND = 'android-discipline';
export const REMINDER_INTERVAL = 6;
/** trigger 模式的兜底间隔：长时间连续 tool call 仍会补一次，避免完全失去提醒。 */
export const TRIGGER_SAFETY_INTERVAL = 20;
export const MODES = ['every', 'trigger', 'off'];

/**
 * 短版增量提醒（issue #skill-reminder-injection 提案 3）：完整纪律正文已在
 * AGENTS.md 的系统提示里，注入只保留最容易被忽略、且事后代价最高的三条。
 */
export const SHORT_REMINDER = [
  'DSH Mobile 执行检查（增量版）：',
  '- 回执 ok/dispatched 只代表派发，不证明目标达成；交付前核对一次目标状态。',
  '- 同一路线失败两次就换线；用户说停/别测就立即停。',
  '- 禁止按预览图比例猜坐标；用新鲜 scene 的 bounds/tapPoint。',
].join('\n');

let modeCache = { at: -1, mode: 'trigger' };

/** 默认 trigger：首轮/用户插话、失败回执与长 tool 链兜底时各注入一次。 */
function readMode() {
  const home = process.env.DSH_HOME;
  if (!home) return 'trigger';
  try {
    const cfg = path.join(home, '.dsh-discipline.json');
    const { mtimeMs } = fs.statSync(cfg);
    if (modeCache.at === mtimeMs) return modeCache.mode;
    const parsed = JSON.parse(fs.readFileSync(cfg, 'utf8'));
    const mode = MODES.includes(parsed?.mode) ? parsed.mode : 'trigger';
    modeCache = { at: mtimeMs, mode };
    return mode;
  } catch {
    return 'trigger';
  }
}

/** 失败回执的粗判定：只扫 tool-result 的文本内容，不做结构化解析。 */
const FAILURE_RE = /"(ok|verified|changed)"\s*:\s*false|staleFullScreenNode|"exit"\s*:\s*[1-9]\d*/;

function messageText(message) {
  if (!message || !Array.isArray(message.content)) return '';
  return message.content
    .map(block => (block && block.type === 'text' && typeof block.text === 'string' ? block.text : ''))
    .join('\n');
}

function looksLikeFailure(message) {
  return message?.role === 'user' && FAILURE_RE.test(messageText(message));
}

/**
 * 只给已准入的 step 追加 context；绝不唤醒、steer 或延长已停止的回合。
 *
 * @param {object} ctx
 * @param {Function} createUserMessage
 * @param {string} reminder 完整提醒（every 模式）
 * @param {{mode?: string, shortReminder?: string}} [options]
 */
export function installReminders(ctx, createUserMessage, reminder, options = {}) {
  const last = new WeakMap();
  ctx.on('agent/pre-step', async ({ agent, turn, step, signal }, next) => {
    const decision = await next();
    if (signal?.aborted || decision?.kind !== 'enter' ||
        !Array.isArray(decision.messages) || !Number.isInteger(step) ||
        step < 1 || (step === 1 && decision.messages.length === 0)) return decision;

    // 模式在插件加载时从 DSH_HOME 读取（改配置后重启引擎生效）；测试可显式注入。
    const mode = options.mode ?? 'every';
    const previous = last.get(agent);
    const userMessageId = decision.messages.findLast(m => m.source?.kind === 'user')?.id
      ?? previous?.userMessageId;
    const freshUserInput = Boolean(userMessageId && userMessageId !== previous?.userMessageId);

    // 同一 step 重试：保持幂等，不重复追加
    if (decision.messages.some(m => m.source?.kind === KIND)) {
      last.set(agent, { turn, step, userMessageId });
      return decision;
    }
    if (mode === 'off') return decision;

    let reason;
    let text = reminder;
    if (mode === 'trigger') {
      text = options.shortReminder || SHORT_REMINDER;
      const failed = decision.messages.some(looksLikeFailure);
      if (freshUserInput) reason = 'user';
      else if (failed) reason = 'failure';
      else if (!previous) reason = 'start';
      else if (previous.turn === turn && step - previous.step >= TRIGGER_SAFETY_INTERVAL) reason = 'safety';
      else return decision;
    } else {
      // every：保留旧行为（turn 首步 + 每 REMINDER_INTERVAL 步）
      if (previous?.turn === turn && !freshUserInput && step - previous.step < REMINDER_INTERVAL) return decision;
      reason = freshUserInput ? 'user' : previous?.turn === turn ? 'interval' : 'turn-start';
    }

    const message = createUserMessage({
      content: [{ type: 'text', text: `<system-reminder>\n${text}\n</system-reminder>` }],
      source: { kind: KIND, form: 'instructions' },
    });
    last.set(agent, { turn, step, userMessageId });
    // 可回归的注入日志：哪一轮、什么原因、注入了多少字符（issue 验收项 4）
    console.info('[android-discipline] reminder injected', JSON.stringify({
      mode, reason, turn, step, chars: text.length,
    }));
    return { ...decision, messages: [...decision.messages, message] };
  });
}

export async function apply(ctx) {
  const here = path.dirname(fileURLToPath(import.meta.url));
  try {
    const entry = path.resolve(here, '../lib/node_modules/@deepseek-ai/dsh-llm/lib/index.js');
    const { createUserMessage } = await import(pathToFileURL(entry).href);
    if (typeof createUserMessage !== 'function') throw Error('createUserMessage unavailable');
    const reminder = fs.readFileSync(path.join(here, 'discipline-reminder.md'), 'utf8').trim();
    if (!reminder) throw Error('empty discipline reminder');
    installReminders(ctx, createUserMessage, reminder, {
      mode: readMode(),
      shortReminder: SHORT_REMINDER,
    });
    console.info('[android-discipline] pre-step reminders registered', JSON.stringify({ mode: readMode() }));
  } catch (error) {
    // The bundled skills remain preloaded via AGENTS.md if an engine API changes.
    console.warn('[android-discipline] periodic reminders unavailable:', error.message);
  }
}
