import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const name = 'android-discipline';
export const inject = [];
const KIND = 'android-discipline';
export const REMINDER_INTERVAL = 6;

/** Add context only to an admitted step; never wake, steer, or extend a stopped turn. */
export function installReminders(ctx, createUserMessage, reminder) {
  const last = new WeakMap();
  ctx.on('agent/pre-step', async ({ agent, turn, step, signal }, next) => {
    const decision = await next();
    if (signal?.aborted || decision?.kind !== 'enter' ||
        !Array.isArray(decision.messages) || !Number.isInteger(step) ||
        step < 1 || (step === 1 && decision.messages.length === 0)) return decision;
    const previous = last.get(agent);
    const userMessageId = decision.messages.findLast(m => m.source?.kind === 'user')?.id
      ?? previous?.userMessageId;
    const freshUserInput = userMessageId && userMessageId !== previous?.userMessageId;
    if (decision.messages.some(m => m.source?.kind === KIND)) {
      last.set(agent, { turn, step, userMessageId });
      return decision;
    }
    if (previous?.turn === turn && !freshUserInput && step - previous.step < REMINDER_INTERVAL) return decision;
    const message = createUserMessage({
      content: [{ type: 'text', text: `<system-reminder>\n${reminder}\n</system-reminder>` }],
      source: { kind: KIND, form: 'instructions' },
    });
    last.set(agent, { turn, step, userMessageId });
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
    installReminders(ctx, createUserMessage, reminder);
    console.info('[android-discipline] pre-step reminders registered');
  } catch (error) {
    // The bundled skills remain preloaded via AGENTS.md if an engine API changes.
    console.warn('[android-discipline] periodic reminders unavailable:', error.message);
  }
}
