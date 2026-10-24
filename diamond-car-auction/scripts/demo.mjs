import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const origin = process.env.APP_URL ?? 'http://localhost:8080';
const recovery = process.argv.includes('--recovery');
const password = 'DiamondDemo2026!';

async function call(path, { token, method = 'GET', data, key } = {}) {
  const headers = { origin };
  if (token) headers.authorization = 'Bearer ' + token;
  if (data) headers['content-type'] = 'application/json';
  if (key) headers['idempotency-key'] = key;
  const response = await fetch(origin + path, { method, headers, body: data ? JSON.stringify(data) : undefined });
  return { status: response.status, body: await response.json().catch(() => null) };
}

async function checked(path, options) {
  const result = await call(path, options);
  assert.ok(result.status < 300, path + ' returned ' + result.status + ' ' + JSON.stringify(result.body));
  return result.body;
}

async function signIn(name) {
  return (await checked('/api/auth/login', { method: 'POST', data: { email: name + '@diamond.test', password } })).accessToken;
}

function compose(...args) {
  execFileSync('docker', ['compose', ...args], { stdio: 'inherit' });
}

async function until(check, timeout = 30000) {
  const deadline = Date.now() + timeout;
  do {
    const result = await check();
    if (result) return result;
    await delay(750);
  } while (Date.now() < deadline);
  assert.fail('Timed out waiting for the expected state');
}

const [admin, firstBuyer, secondBuyer] = await Promise.all(['admin', 'buyer1', 'buyer2'].map(signIn));
if (recovery) compose('stop', 'orders', 'notifications', 'rabbitmq');

try {
  const auction = await checked('/api/admin/demo/quick-auction', { token: admin, method: 'POST', data: { seconds: 25 } });
  const bid = (token, amount, endpoint = 'bids', key = randomUUID()) => call('/api/auctions/' + auction.id + '/' + endpoint,
    { token, method: 'POST', data: { amount }, key });
  const keys = [randomUUID(), randomUUID()];
  const buyers = [firstBuyer, secondBuyer];
  const started = performance.now();
  const attempts = await Promise.all(buyers.map((token, index) => bid(token, 1000, 'bids', keys[index])));
  const elapsed = Math.round(performance.now() - started);
  assert.deepEqual(attempts.map(attempt => attempt.status).sort(), [200, 409]);
  const winnerIndex = attempts.findIndex(attempt => attempt.status === 200);
  const winningBuyer = buyers[winnerIndex];
  const otherBuyer = buyers[1 - winnerIndex];
  assert.equal(attempts[winnerIndex].body.extended, true);
  assert.deepEqual(await bid(winningBuyer, 1000, 'bids', keys[winnerIndex]), attempts[winnerIndex]);
  assert.equal((await bid(winningBuyer, 1250, 'bids', keys[winnerIndex])).status, 409);
  assert.equal((await bid(winningBuyer, 3000, 'max-bid')).body.price, 1000);
  const challenge = await bid(otherBuyer, 1250);
  assert.equal(challenge.body.price, 1500);
  assert.equal(challenge.body.leading, false);
  console.log('Concurrent requests: one accepted, one rejected in ' + elapsed + ' ms (wall clock).');
  if (recovery) {
    const service = execFileSync('docker', ['compose', 'ps', '--quiet', 'auctions'], { encoding: 'utf8' }).trim();
    compose('stop', 'auctions');
    await delay(Math.max(0, Date.parse(challenge.body.endsAt) - Date.now() + 500));
    execFileSync('docker', ['start', service], { stdio: 'ignore' });
  }
  const final = await until(async () => {
    const state = await call('/api/auctions/' + auction.id + '/state');
    return state.status === 200 && state.body.auction.status === 'SOLD' && state.body;
  }, 60000);
  assert.equal(final.auction.price, 1500);
  assert.equal(final.bids.length, 2);
  assert.equal('reserve' in final.auction, false);
  assert.ok(final.bids.every(item => !('maximum' in item) && !('bidderId' in item)));
  if (recovery) compose('start', 'rabbitmq', 'orders', 'notifications');

  const order = await until(async () => {
    const result = await call('/api/orders', { token: winningBuyer });
    if (result.status !== 200) return null;
    const orders = result.body.filter(item => item.auctionId === auction.id);
    assert.ok(orders.length <= 1);
    return orders[0];
  }, 60000);
  assert.equal(order.amount, 1500);
  assert.equal((await call('/api/orders/' + order.id, { token: otherBuyer })).status, 404);
  const paid = await checked('/api/orders/' + order.id + '/simulate-payment', { token: winningBuyer, method: 'POST' });
  assert.equal(paid.status, 'PAID');
  await until(async () => {
    const notices = await checked('/api/notifications', { token: winningBuyer });
    return notices.some(item => item.link === '/orders/' + order.id && item.title === 'Simulated payment complete');
  }, 45000);
  console.log('Verified extension, proxy price, one order, checkout and notification for ' + auction.id);
} finally {
  if (recovery) compose('start', 'rabbitmq', 'auctions', 'orders', 'notifications');
}
