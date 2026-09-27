'use strict';

const fs = require('node:fs');
const path = require('node:path');
const mineflayer = require('mineflayer');

function parseArgs(argv) {
  const values = new Map();
  for (let index = 2; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key || !key.startsWith('--') || value === undefined) {
      throw new Error('arguments must be --key value pairs');
    }
    values.set(key.slice(2), value);
  }
  return values;
}

function required(args, key) {
  const value = args.get(key);
  if (value === undefined || value === '') {
    throw new Error(`missing --${key}`);
  }
  return value;
}

function integer(args, key) {
  const value = Number.parseInt(required(args, key), 10);
  if (!Number.isInteger(value)) {
    throw new Error(`--${key} must be an integer`);
  }
  return value;
}

function sleep(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

async function waitForFile(file, timeoutMs, fatalState) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (fatalState.error) {
      throw fatalState.error;
    }
    if (fs.existsSync(file)) {
      return;
    }
    await sleep(50);
  }
  throw new Error(`timed out waiting for ${file}`);
}

function axisPosition(bot, direction) {
  return direction === 'east' || direction === 'west'
    ? bot.entity.position.x
    : bot.entity.position.z;
}

function reached(position, target, direction) {
  return direction === 'east' || direction === 'south'
    ? position >= target
    : position <= target;
}

function lookTarget(bot, direction) {
  switch (direction) {
    case 'east':
      return bot.entity.position.offset(1000, 0, 0);
    case 'west':
      return bot.entity.position.offset(-1000, 0, 0);
    case 'south':
      return bot.entity.position.offset(0, 0, 1000);
    case 'north':
      return bot.entity.position.offset(0, 0, -1000);
    default:
      throw new Error(`unsupported direction ${direction}`);
  }
}

async function main() {
  const args = parseArgs(process.argv);
  const host = required(args, 'host');
  const port = integer(args, 'port');
  const count = integer(args, 'count');
  const prefix = required(args, 'prefix');
  const direction = required(args, 'direction');
  const markerDir = path.resolve(required(args, 'marker-dir'));
  const startAxis = integer(args, 'start-axis');
  const targetAxis = integer(args, 'target-axis');
  const timeoutMs = integer(args, 'timeout-ms');
  if (count < 1 || count > 40) {
    throw new Error('--count must be within 1..40');
  }
  if (!['east', 'west', 'south', 'north'].includes(direction)) {
    throw new Error('--direction must be east, west, south, or north');
  }
  if (prefix.length + 2 > 16) {
    throw new Error('bot usernames would exceed Minecraft 16-character limit');
  }

  fs.mkdirSync(markerDir, { recursive: true });
  const connectedFile = path.join(markerDir, 'connected.json');
  const goFile = path.join(markerDir, 'go');
  const resultFile = path.join(markerDir, 'result.json');
  const bots = [];
  const fatalState = { error: null };
  let finishing = false;

  const spawned = [];
  for (let index = 0; index < count; index++) {
    const username = `${prefix}${String(index).padStart(2, '0')}`;
    const bot = mineflayer.createBot({
      host,
      port,
      username,
      version: '1.21.11',
      auth: 'offline',
    });
    bots.push(bot);
    spawned.push(new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${username} did not spawn within 45 seconds`)), 45_000);
      bot.once('spawn', () => {
        clearTimeout(timer);
        resolve();
      });
      bot.once('error', error => {
        clearTimeout(timer);
        reject(new Error(`${username} client error before spawn: ${error.message}`));
      });
      bot.once('kicked', reason => {
        clearTimeout(timer);
        reject(new Error(`${username} was kicked before spawn: ${String(reason)}`));
      });
    }));
    bot.on('error', error => {
      if (!finishing && !fatalState.error) {
        fatalState.error = new Error(`${username} client error: ${error.message}`);
      }
    });
    bot.on('kicked', reason => {
      if (!finishing && !fatalState.error) {
        fatalState.error = new Error(`${username} was kicked: ${String(reason)}`);
      }
    });
    bot.on('end', reason => {
      if (!finishing && !fatalState.error) {
        fatalState.error = new Error(`${username} disconnected early: ${String(reason)}`);
      }
    });
  }

  try {
    await Promise.all(spawned);
    fs.writeFileSync(connectedFile, JSON.stringify({
      count,
      usernames: bots.map(bot => bot.username),
    }, null, 2));
    console.log(`FRONTIER_REAL_CLIENT_CONNECTED count=${count}`);

    await waitForFile(goFile, 60_000, fatalState);
    for (const bot of bots) {
      const observed = axisPosition(bot, direction);
      if (Math.abs(observed - startAxis) > 12) {
        throw new Error(`${bot.username} was not at the expected start axis: ${observed.toFixed(3)} vs ${startAxis}`);
      }
    }

    await Promise.all(bots.map(bot => bot.lookAt(lookTarget(bot, direction), true)));
    const started = process.hrtime.bigint();
    for (const bot of bots) {
      bot.setControlState('sprint', true);
      bot.setControlState('forward', true);
    }

    const deadline = Date.now() + timeoutMs;
    while (true) {
      if (fatalState.error) {
        throw fatalState.error;
      }
      if (bots.every(bot => reached(axisPosition(bot, direction), targetAxis, direction))) {
        break;
      }
      if (Date.now() >= deadline) {
        const positions = bots.map(bot => `${bot.username}=${axisPosition(bot, direction).toFixed(2)}`).join(',');
        throw new Error(`movement timed out; positions=${positions}`);
      }
      await sleep(100);
    }

    const elapsedMs = Number(process.hrtime.bigint() - started) / 1_000_000;
    for (const bot of bots) {
      bot.setControlState('forward', false);
      bot.setControlState('sprint', false);
    }
    const result = {
      status: 'passed',
      count,
      direction,
      elapsed_ms: elapsedMs,
      start_axis: startAxis,
      target_axis: targetAxis,
      positions: bots.map(bot => ({
        username: bot.username,
        x: bot.entity.position.x,
        y: bot.entity.position.y,
        z: bot.entity.position.z,
      })),
    };
    fs.writeFileSync(resultFile, JSON.stringify(result, null, 2));
    console.log(`FRONTIER_REAL_CLIENT_REACHED count=${count} elapsed_ms=${elapsedMs.toFixed(1)}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    fs.writeFileSync(resultFile, JSON.stringify({
      status: 'failed',
      count,
      direction,
      error: message,
      positions: bots
        .filter(bot => bot.entity)
        .map(bot => ({ username: bot.username, x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z })),
    }, null, 2));
    console.error(`FRONTIER_REAL_CLIENT_FAILED count=${count} reason=${message}`);
    process.exitCode = 1;
  } finally {
    finishing = true;
    for (const bot of bots) {
      try {
        bot.clearControlStates();
        bot.quit('Frontier test complete');
      } catch (_) {
        // Best-effort disconnect only; the disposable server is still process-isolated.
      }
    }
    await sleep(500);
  }
}

main().catch(error => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exitCode = 1;
});
