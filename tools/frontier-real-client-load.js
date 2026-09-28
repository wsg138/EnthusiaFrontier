'use strict';

const fs = require('node:fs');
const path = require('node:path');

function applyMineflayer263TeleportPatch() {
  const mineflayerRoot = path.dirname(require.resolve('mineflayer'));
  const physicsFile = path.join(mineflayerRoot, 'lib', 'plugins', 'physics.js');
  if (!fs.existsSync(physicsFile)) {
    throw new Error(`Mineflayer physics source not found: ${physicsFile}`);
  }

  const source = fs.readFileSync(physicsFile, 'utf8');
  const requiredForkShape = [
    "if (bot.protocolVersion >= 777)",
    "bot._client.write('teleport_confirm', {",
    'x: pos.x',
    'y: pos.y',
    'z: pos.z',
    'yaw: newYaw',
    'pitch: newPitch',
    'const generation = ++forcedMoveGeneration'
  ];
  if (!requiredForkShape.every(token => source.includes(token))) {
    throw new Error('Refusing to patch unexpected Mineflayer 26.3 physics source');
  }

  const oldTail = `    sendPacketPositionAndLook(pos, newYaw, newPitch, bot.entity.onGround)

    shouldUsePhysics = true
    bot.jumpTicks = 0
    lastSentYaw = bot.entity.yaw
    lastSentPitch = bot.entity.pitch

    bot.emit('forcedMove')`;
  const marker = 'FRONTIER_26_3_DEFERRED_TELEPORT_ECHO';
  if (source.includes(marker)) {
    console.log('MINEFLAYER_26_3_TELEPORT_PATCH already-present');
    return;
  }

  const occurrences = source.split(oldTail).length - 1;
  if (occurrences !== 1) {
    throw new Error(`Refusing to patch unexpected Mineflayer movement echo; expected one exact tail, found ${occurrences}`);
  }

  const deferredTail = `    // ${marker}: Frontier validation uses both physics-disabled clients for
    // deterministic server-driven teleport load and a physics-enabled client for a
    // genuine walking boundary check. The real 26.3 teleport_confirm above is enough
    // for the physics-disabled mode; normal physics-enabled clients retain the delayed
    // position echo below.
    if (!bot.physicsEnabled) {
      shouldUsePhysics = false
      bot.jumpTicks = 0
      lastSentYaw = bot.entity.yaw
      lastSentPitch = bot.entity.pitch
      bot.emit('forcedMove')
      return
    }

    const delayedPos = pos.clone()
    const delayedYaw = newYaw
    const delayedPitch = newPitch
    const delayedOnGround = bot.entity.onGround
    setTimeout(() => {
      if (generation !== forcedMoveGeneration || bot._client.ended) return
      sendPacketPositionAndLook(delayedPos, delayedYaw, delayedPitch, delayedOnGround)
      shouldUsePhysics = true
      bot.jumpTicks = 0
      lastSentYaw = bot.entity.yaw
      lastSentPitch = bot.entity.pitch
      bot.emit('forcedMove')
    }, PHYSICS_INTERVAL_MS)`;

  fs.writeFileSync(physicsFile, source.replace(oldTail, deferredTail));
  console.log('MINEFLAYER_26_3_TELEPORT_PATCH applied fork=wp2508/mineflayer@4.42.2');
}

// The pinned @wp2508/mineflayer 4.42.2 fork supplies Minecraft 26.3 protocol
// data and the widened 26.3 teleport-confirm fields. Patch only the exact
// disposable dependency tail used by CI; production server artifacts are never modified.
applyMineflayer263TeleportPatch();
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
  if (value === undefined || value === '') throw new Error(`missing --${key}`);
  return value;
}

function integer(args, key) {
  const value = Number.parseInt(required(args, key), 10);
  if (!Number.isInteger(value)) throw new Error(`--${key} must be an integer`);
  return value;
}

function sleep(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

async function waitForFile(file, timeoutMs, fatalState) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (fatalState.error) throw fatalState.error;
    if (fs.existsSync(file)) return;
    await sleep(50);
  }
  throw new Error(`timed out waiting for ${file}`);
}

function axisPosition(bot, direction) {
  return direction === 'east' || direction === 'west'
    ? bot.entity.position.x
    : bot.entity.position.z;
}

function atStart(bot, startAxis, direction) {
  return Math.abs(axisPosition(bot, direction) - startAxis) <= 12;
}

function reached(position, target, direction) {
  return direction === 'east' || direction === 'south'
    ? position >= target - 1
    : position <= target + 1;
}

function yawFor(direction) {
  switch (direction) {
    case 'south': return 0;
    case 'west': return Math.PI / 2;
    case 'north': return Math.PI;
    case 'east': return -Math.PI / 2;
    default: throw new Error(`unsupported direction ${direction}`);
  }
}

function positions(bots) {
  return bots.filter(bot => bot.entity).map(bot => ({
    username: bot.username,
    x: bot.entity.position.x,
    y: bot.entity.position.y,
    z: bot.entity.position.z,
  }));
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
  const movementMode = args.get('movement-mode') || 'server-teleport-with-real-network-clients';
  const walking = movementMode === 'client-walk';
  if (!walking && movementMode !== 'server-teleport-with-real-network-clients') {
    throw new Error('--movement-mode must be client-walk or server-teleport-with-real-network-clients');
  }
  if (walking && count !== 1) throw new Error('client-walk validation currently requires --count 1');
  if (count < 1 || count > 40) throw new Error('--count must be within 1..40');
  if (!['east', 'west', 'south', 'north'].includes(direction)) throw new Error('--direction must be east, west, south, or north');
  if (prefix.length + 2 > 16) throw new Error('bot usernames would exceed Minecraft 16-character limit');

  fs.mkdirSync(markerDir, { recursive: true });
  const connectedFile = path.join(markerDir, 'connected.json');
  const positionedFile = path.join(markerDir, 'positioned.json');
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
      version: '26.3',
      auth: 'offline',
      physicsEnabled: walking,
    });

    bot.physicsEnabled = walking;

    bots.push(bot);
    spawned.push(new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${username} did not spawn within 45 seconds`)), 45_000);
      bot.once('spawn', () => {
        bot.physicsEnabled = walking;
        clearTimeout(timer);
        resolve();
      });
      bot.once('error', error => { clearTimeout(timer); reject(new Error(`${username} client error before spawn: ${error.message}`)); });
      bot.once('kicked', reason => { clearTimeout(timer); reject(new Error(`${username} was kicked before spawn: ${JSON.stringify(reason)}`)); });
    }));
    bot.on('error', error => {
      if (!finishing && !fatalState.error) fatalState.error = new Error(`${username} client error: ${error.message}`);
    });
    bot.on('kicked', reason => {
      if (!finishing && !fatalState.error) fatalState.error = new Error(`${username} was kicked: ${JSON.stringify(reason)}`);
    });
    bot.on('end', reason => {
      if (!finishing && !fatalState.error) fatalState.error = new Error(`${username} disconnected early: ${String(reason)}`);
    });
  }

  try {
    await Promise.all(spawned);
    fs.writeFileSync(connectedFile, JSON.stringify({ count, usernames: bots.map(bot => bot.username), movement_mode: movementMode }, null, 2));
    console.log(`FRONTIER_REAL_CLIENT_CONNECTED count=${count} movement=${movementMode}`);

    const positionedDeadline = Date.now() + 60_000;
    while (!bots.every(bot => atStart(bot, startAxis, direction))) {
      if (fatalState.error) throw fatalState.error;
      if (Date.now() >= positionedDeadline) {
        const observed = bots.map(bot => `${bot.username}=${axisPosition(bot, direction).toFixed(2)}`).join(',');
        throw new Error(`clients did not reach setup axis ${startAxis}; positions=${observed}`);
      }
      await sleep(50);
    }
    fs.writeFileSync(positionedFile, JSON.stringify({ count, start_axis: startAxis, positions: positions(bots) }, null, 2));
    console.log(`FRONTIER_REAL_CLIENT_POSITIONED count=${count}`);

    await waitForFile(goFile, 60_000, fatalState);
    if (walking) {
      await bots[0].look(yawFor(direction), 0, true);
      bots[0].setControlState('forward', true);
      bots[0].setControlState('sprint', true);
      console.log(`FRONTIER_REAL_CLIENT_WALKING direction=${direction}`);
    }

    const started = process.hrtime.bigint();
    const deadline = Date.now() + timeoutMs;
    let nextProgressLog = Date.now();
    while (true) {
      if (fatalState.error) throw fatalState.error;
      if (bots.every(bot => reached(axisPosition(bot, direction), targetAxis, direction))) break;
      if (Date.now() >= deadline) {
        const observed = bots.map(bot => `${bot.username}=${axisPosition(bot, direction).toFixed(2)}`).join(',');
        throw new Error(`${walking ? 'client walk' : 'server-driven boundary teleport'} timed out; positions=${observed}`);
      }
      if (walking && Date.now() >= nextProgressLog) {
        console.log(`FRONTIER_REAL_CLIENT_WALK_PROGRESS axis=${axisPosition(bots[0], direction).toFixed(2)} target=${targetAxis}`);
        nextProgressLog = Date.now() + 5_000;
      }
      await sleep(100);
    }

    const elapsedMs = Number(process.hrtime.bigint() - started) / 1_000_000;
    const result = {
      status: 'passed', count, direction, elapsed_ms: elapsedMs,
      start_axis: startAxis, target_axis: targetAxis, positions: positions(bots),
      movement_mode: movementMode,
    };
    fs.writeFileSync(resultFile, JSON.stringify(result, null, 2));
    console.log(`FRONTIER_REAL_CLIENT_REACHED count=${count} movement=${movementMode} elapsed_ms=${elapsedMs.toFixed(1)}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    fs.writeFileSync(resultFile, JSON.stringify({ status: 'failed', count, direction, movement_mode: movementMode, error: message, positions: positions(bots) }, null, 2));
    console.error(`FRONTIER_REAL_CLIENT_FAILED count=${count} movement=${movementMode} reason=${message}`);
    process.exitCode = 1;
  } finally {
    finishing = true;
    for (const bot of bots) {
      try { if (typeof bot.clearControlStates === 'function') bot.clearControlStates(); } catch (_) { /* best effort */ }
      try { bot.quit('Frontier test complete'); } catch (_) { /* best effort */ }
    }
    await sleep(500);
  }
}

main().catch(error => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exitCode = 1;
});
