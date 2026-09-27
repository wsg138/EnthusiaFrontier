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
  const oldLine = "      bot._client.write('teleport_confirm', { teleportId })";
  const upstreamPatchedBlock = `      bot._client.write('teleport_confirm', {
        teleportId,
        x: pos.x,
        y: pos.y,
        z: pos.z,
        yRot: yaw,
        xRot: pitch
      })`;

  // @wp2508/mineflayer 4.42.2 now carries its own 26.3 implementation. It
  // gates the widened acknowledgement on protocol 777 and names the resolved
  // rotation values yaw/pitch. Treat that exact shape as already fixed rather
  // than trying to rewrite a dependency that no longer matches the old source.
  const forkAlreadyFixed = [
    "if (bot.protocolVersion >= 777)",
    "bot._client.write('teleport_confirm', {",
    'x: pos.x',
    'y: pos.y',
    'z: pos.z',
    'yaw: newYaw',
    'pitch: newPitch'
  ].every(token => source.includes(token));

  if (forkAlreadyFixed || source.includes(upstreamPatchedBlock)) {
    console.log('MINEFLAYER_26_3_TELEPORT_PATCH already-present');
    return;
  }

  const occurrences = source.split(oldLine).length - 1;
  if (occurrences !== 1) {
    throw new Error(`Refusing to patch unexpected Mineflayer physics source; expected one legacy teleport_confirm write, found ${occurrences}`);
  }

  fs.writeFileSync(physicsFile, source.replace(oldLine, upstreamPatchedBlock));
  console.log('MINEFLAYER_26_3_TELEPORT_PATCH applied upstream=PrismarineJS/mineflayer@741ddeb9b707000b6103bd23361c34fc39f82c68');
}

// The pinned @wp2508/mineflayer 4.42.2 fork supplies Minecraft 26.3 protocol
// data. Some published revisions include the 26.3 teleport acknowledgement fix
// directly; older compatible revisions need the exact upstream 741ddeb payload.
// Verify one of those known-good shapes before Mineflayer loads and fail closed
// for anything unexpected. This never modifies a production server artifact.
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
      physicsEnabled: false,
    });

    // These clients only observe server-driven Frontier teleports. Keep local
    // physics disabled so the test measures server-side generation admission,
    // not autonomous bot motion.
    bot.physicsEnabled = false;

    bots.push(bot);
    spawned.push(new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${username} did not spawn within 45 seconds`)), 45_000);
      bot.once('spawn', () => {
        bot.physicsEnabled = false;
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
    fs.writeFileSync(connectedFile, JSON.stringify({ count, usernames: bots.map(bot => bot.username) }, null, 2));
    console.log(`FRONTIER_REAL_CLIENT_CONNECTED count=${count}`);

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
    const started = process.hrtime.bigint();
    const deadline = Date.now() + timeoutMs;
    while (true) {
      if (fatalState.error) throw fatalState.error;
      if (bots.every(bot => reached(axisPosition(bot, direction), targetAxis, direction))) break;
      if (Date.now() >= deadline) {
        const observed = bots.map(bot => `${bot.username}=${axisPosition(bot, direction).toFixed(2)}`).join(',');
        throw new Error(`server-driven boundary teleport timed out; positions=${observed}`);
      }
      await sleep(100);
    }

    const elapsedMs = Number(process.hrtime.bigint() - started) / 1_000_000;
    const result = {
      status: 'passed', count, direction, elapsed_ms: elapsedMs,
      start_axis: startAxis, target_axis: targetAxis, positions: positions(bots),
      movement_mode: 'server-teleport-with-real-network-clients',
    };
    fs.writeFileSync(resultFile, JSON.stringify(result, null, 2));
    console.log(`FRONTIER_REAL_CLIENT_REACHED count=${count} elapsed_ms=${elapsedMs.toFixed(1)}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    fs.writeFileSync(resultFile, JSON.stringify({ status: 'failed', count, direction, error: message, positions: positions(bots) }, null, 2));
    console.error(`FRONTIER_REAL_CLIENT_FAILED count=${count} reason=${message}`);
    process.exitCode = 1;
  } finally {
    finishing = true;
    for (const bot of bots) {
      try { bot.quit('Frontier test complete'); } catch (_) { /* best effort */ }
    }
    await sleep(500);
  }
}

main().catch(error => {
  console.error(error instanceof Error ? error.stack : String(error));
  process.exitCode = 1;
});
