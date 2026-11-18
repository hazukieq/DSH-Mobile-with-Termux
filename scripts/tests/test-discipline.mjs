import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { installReminders, REMINDER_INTERVAL } from '../../app/src/main/assets/android-plugins/discipline.mjs';

function fixture() {
  let hook;
  const ctx = { on(event, callback) { assert.equal(event, 'agent/pre-step'); hook = callback; } };
  const create = input => Object.freeze({ ...input, id: randomUUID(), role: 'user' });
  installReminders(ctx, create, 'Follow the current goal; read receipts.');
  const agent = {};
  const user = { id: 'user-task', role: 'user', content: [{ type: 'text', text: 'select the checkbox' }], source: { kind: 'user' } };
  const enter = { kind: 'enter', messages: [user], startsRequestSeries: true };
  return { agent, user, enter, invoke: (payload, decision = enter) => hook({ agent, turn: 1, step: 1, ...payload }, async () => decision) };
}

test('starts with one context reminder and preserves the real user input and decision fields', async () => {
  const f = fixture();
  const result = await f.invoke({});
  assert.equal(result.messages.length, 2);
  assert.equal(result.messages[0], f.user);
  assert.equal(result.startsRequestSeries, true);
  assert.equal(f.enter.messages.length, 1);
  const message = result.messages[1];
  assert.equal(message.source.kind, 'android-discipline');
  assert.equal(message.source.form, 'instructions');
  assert.equal(message.role, 'user');
  assert.ok(message.id);
  assert.match(message.content[0].text, /<system-reminder>/);
});

test('does not repeat until six model steps have elapsed', async () => {
  const f = fixture();
  await f.invoke({});
  for (let step = 2; step <= REMINDER_INTERVAL; step++) assert.equal(await f.invoke({ step }), f.enter);
  assert.equal((await f.invoke({ step: REMINDER_INTERVAL + 1 })).messages.length, 2);
});

test('separate agents and new turns each get their own initial reminder', async () => {
  const f = fixture();
  await f.invoke({});
  assert.equal((await f.invoke({ agent: {} })).messages.length, 2);
  assert.equal((await f.invoke({ turn: 2, step: 1 })).messages.length, 2);
});

test('rejected, cancelled and empty first steps receive no context and do not consume the reminder', async () => {
  const f = fixture();
  const reject = { kind: 'reject' };
  const empty = { kind: 'enter', messages: [] };
  assert.equal(await f.invoke({}, reject), reject);
  assert.equal(await f.invoke({ signal: { aborted: true } }), f.enter);
  assert.equal(await f.invoke({}, empty), empty);
  assert.equal((await f.invoke({})).messages.length, 2);
});

test('same-step retry does not duplicate context or wake/steer an agent', async () => {
  const f = fixture();
  const agent = { inject() { assert.fail('must not inject queued work'); }, steer() { assert.fail('must not steer'); }, followup() { assert.fail('must not wake'); } };
  const first = await f.invoke({ agent });
  const retry = await f.invoke({ agent }, first);
  assert.equal(retry, first);
  assert.equal(retry.messages.filter(m => m.source.kind === 'android-discipline').length, 1);
});

test('a tool-result continuation can be reminded without fabricating a user task', async () => {
  const f = fixture();
  const continuation = { kind: 'enter', messages: [] };
  const result = await f.invoke({ step: 4 }, continuation);
  assert.equal(result.messages.length, 1);
  assert.equal(result.messages[0].source.kind, 'android-discipline');
});

test('fresh user steering is reminded immediately within the interval and remains unchanged', async () => {
  const f = fixture();
  await f.invoke({});
  const steering = { ...f.user, id: 'user-correction', content: [{ type: 'text', text: '不要再测，先读文档' }] };
  const decision = { kind: 'enter', messages: [steering] };
  const result = await f.invoke({ step: 3 }, decision);
  assert.equal(result.messages.length, 2);
  assert.equal(result.messages[0], steering);
  assert.equal(await f.invoke({ step: 4 }, decision), decision);
});
