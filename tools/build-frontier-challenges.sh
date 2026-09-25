#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/server/plugins"
WORK="${RUNNER_TEMP:-/tmp}/frontier-challenges-build"
SOURCE_SHA="${GITHUB_SHA:-local}"
rm -rf "$WORK"
mkdir -p "$WORK" "$OUT" "$OUT/EnthusiaTempChallenges" "$OUT/EnthusiaAdvancements/trees" "$OUT/EnthusiaTags"

normalize_jar() {
  local jar_path="$1"
  python3 - "$jar_path" <<'PY'
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo
import os
import sys

path = Path(sys.argv[1])
tmp = path.with_suffix(path.suffix + '.normalized')
fixed_time = (1980, 1, 1, 0, 0, 0)

with ZipFile(path, 'r') as source, ZipFile(tmp, 'w', allowZip64=True) as target:
    # Sort by path so source-tool insertion order cannot affect the deployable bytes.
    # Duplicate names retain their original relative order through the header offset tie-breaker.
    entries = sorted(source.infolist(), key=lambda info: (info.filename, info.header_offset))
    for info in entries:
        data = source.read(info)
        normalized = ZipInfo(info.filename, fixed_time)
        normalized.create_system = info.create_system
        normalized.create_version = info.create_version
        normalized.extract_version = info.extract_version
        normalized.external_attr = info.external_attr
        normalized.internal_attr = info.internal_attr
        normalized.comment = info.comment
        # Strip timestamp/platform extra fields. ZIP64 metadata is regenerated if needed.
        normalized.extra = b''
        if info.is_dir():
            normalized.compress_type = ZIP_STORED
            target.writestr(normalized, b'')
        else:
            normalized.compress_type = ZIP_DEFLATED
            target.writestr(normalized, data, compress_type=ZIP_DEFLATED, compresslevel=9)

os.replace(tmp, path)
PY
}

echo '== EnthusiaTempChallenges: tests + package =='
# verify already runs package/shade; adding a second explicit package goal re-shades
# sqlite-jdbc into the just-shaded artifact and creates duplicate class entries.
mvn -B --no-transfer-progress -f "$ROOT/server-src/EnthusiaTempChallenges/pom.xml" clean verify
cp "$ROOT/server-src/EnthusiaTempChallenges/target/EnthusiaTempChallenges-0.2.0-frontier.1.jar" "$OUT/EnthusiaTempChallenges-0.2.0-frontier.1.jar"
cp "$ROOT/server-src/EnthusiaTempChallenges/src/main/resources/config.yml" "$OUT/EnthusiaTempChallenges/config.yml"

echo '== EnthusiaAdvancements: pinned Badgers source =='
git clone --quiet https://github.com/BadgersMC/EnthusiaAdvancements.git "$WORK/advancements"
git -C "$WORK/advancements" checkout --quiet 42e901473234f5b69c07d5416565d80addbb197d
python3 - "$WORK/advancements" <<'PY'
from pathlib import Path
import sys
root=Path(sys.argv[1])
build=root/'build.gradle.kts'
text=build.read_text()
text=text.replace('com.frengor:ultimateadvancementapi:2.8.0','com.frengor:ultimateadvancementapi:2.8.1')
text += r'''

sourceSets {
    main {
        kotlin {
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/CommendListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/CurrencyListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/DiaryListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/GuildCollectiveListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/GuildPlaytimeListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/KothListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/LeaderboardRankListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/PlaytimeListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/ShopEventListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/listeners/ShopTransactionListener.kt")
            exclude("io/github/badgersmc/advancements/infrastructure/plugins/LumaGuildsHook.kt")
        }
    }
}
'''
build.write_text(text)
cmd=root/'src/main/kotlin/io/github/badgersmc/advancements/commands/AdvancementCommand.kt'
text=cmd.read_text()
needle='    @Subcommand("list")\n'
revoke=r'''    @Subcommand("revoke")
    @Permission("advancements.admin")
    fun revoke(
        @Context sender: CommandSender,
        @Arg("player") playerName: String,
        @Arg("tree") tree: String,
        @Arg("key") key: String
    ) {
        val player = Bukkit.getPlayer(playerName)
        if (player == null) {
            sender.sendMessage(Component.text("Player '$playerName' not found.", NamedTextColor.RED))
            return
        }
        val advancement = adapter.getAdvancement(tree, key)
        if (advancement == null) {
            sender.sendMessage(Component.text("Advancement '$tree:$key' not found.", NamedTextColor.RED))
            return
        }
        advancement.revoke(player)
        sender.sendMessage(Component.text("Revoked '$tree:$key' from ${player.name}.", NamedTextColor.GREEN))
    }

'''
if needle not in text:
    raise SystemExit('Could not patch AdvancementCommand revoke subcommand')
cmd.write_text(text.replace(needle,revoke+needle,1))
PY
chmod +x "$WORK/advancements/gradlew"
( cd "$WORK/advancements" && ./gradlew clean shadowJar --no-daemon )
ADV_JAR="$(find "$WORK/advancements/build/libs" -maxdepth 1 -type f -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' -printf '%s %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
test -n "$ADV_JAR"
cp "$ADV_JAR" "$OUT/EnthusiaAdvancements-1.0.0-frontier.jar"

echo '== UltimateAdvancementAPI 2.8.1 =='
curl --fail --location --retry 3 --silent --show-error \
  'https://nexus.frengor.com/repository/public/com/frengor/ultimateadvancementapi/2.8.1/ultimateadvancementapi-2.8.1.jar' \
  -o "$OUT/UltimateAdvancementAPI-2.8.1.jar"

echo '== EnthusiaTags: pinned Java-21/Paper-1.21.11 source =='
git clone --quiet https://github.com/wsg138/EnthusiaTags.git "$WORK/tags"
git -C "$WORK/tags" checkout --quiet 261efb9144216ae86a4a2c8ed406a7f44dcae851
( cd "$WORK/tags" && bash tools/bootstrap_loreitems_release.sh && mvn -B --no-transfer-progress -DskipTests clean package )
TAG_JAR="$(find "$WORK/tags/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*' -printf '%s %p\n' | sort -nr | head -1 | cut -d' ' -f2-)"
test -n "$TAG_JAR"
cp "$TAG_JAR" "$OUT/EnthusiaTags.jar"
cp "$WORK/tags/src/main/resources/config.yml" "$OUT/EnthusiaTags/config.yml"

python3 - "$OUT/EnthusiaTags/config.yml" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); text=p.read_text()
marker='tags:\n'
if marker not in text: raise SystemExit('EnthusiaTags config has no top-level tags mapping')
block='''  # FRONTIER-FIRST-TAGS-BEGIN
  frontier_first_diamonds: { display-name: "<bold><#5FD3FF>Diamond Pioneer", tag-text: "<bold><#5FD3FF>Diamond Pioneer", icon: "DIAMOND", description: ["&7First to obtain Diamonds on Frontier Test."] }
  frontier_first_nether: { display-name: "<bold><#FF6B6B>Nether Pioneer", tag-text: "<bold><#FF6B6B>Nether Pioneer", icon: "NETHERRACK", description: ["&7First to enter the Nether on Frontier Test."] }
  frontier_first_fortress: { display-name: "<bold><#B54832>Fortress Founder", tag-text: "<bold><#B54832>Fortress Founder", icon: "NETHER_BRICKS", description: ["&7First Nether Fortress discovery on Frontier Test."] }
  frontier_first_ancient_debris: { display-name: "<bold><#8A5A44>Ancient Miner", tag-text: "<bold><#8A5A44>Ancient Miner", icon: "ANCIENT_DEBRIS", description: ["&7First Ancient Debris on Frontier Test."] }
  frontier_first_netherite_ingot: { display-name: "<bold><#8B8B95>Netherite Pioneer", tag-text: "<bold><#8B8B95>Netherite Pioneer", icon: "NETHERITE_INGOT", description: ["&7First Netherite Ingot on Frontier Test."] }
  frontier_first_netherite_armor: { display-name: "<bold><#6F6F7A>Netherite Vanguard", tag-text: "<bold><#6F6F7A>Netherite Vanguard", icon: "NETHERITE_CHESTPLATE", description: ["&7First full Netherite armor set on Frontier Test."] }
  frontier_first_stronghold: { display-name: "<bold><#C690FF>Stronghold Scout", tag-text: "<bold><#C690FF>Stronghold Scout", icon: "ENDER_EYE", description: ["&7First Stronghold discovery on Frontier Test."] }
  frontier_first_mace: { display-name: "<bold><#D0D0D0>Heavy Hitter", tag-text: "<bold><#D0D0D0>Heavy Hitter", icon: "MACE", description: ["&7First legitimate Mace on Frontier Test."] }
  frontier_first_end: { display-name: "<bold><#8B7CFF>End Pioneer", tag-text: "<bold><#8B7CFF>End Pioneer", icon: "END_STONE", description: ["&7First to enter The End on Frontier Test."] }
  frontier_first_dragon: { display-name: "<bold><#E04BFF>Dragonbreaker", tag-text: "<bold><#E04BFF>Dragonbreaker", icon: "DRAGON_HEAD", description: ["&7First credited Ender Dragon final blow on Frontier Test."] }
  frontier_first_elytra: { display-name: "<bold><#7EE7FF>First Flight", tag-text: "<bold><#7EE7FF>First Flight", icon: "ELYTRA", description: ["&cLOCKED: Elytras are unobtainable on Frontier Test."] }
  frontier_first_wither: { display-name: "<bold><#8B8B8B>Witherbreaker", tag-text: "<bold><#8B8B8B>Witherbreaker", icon: "WITHER_SKELETON_SKULL", description: ["&7First credited Wither final blow on Frontier Test."] }
  frontier_first_beacon: { display-name: "<bold><#F7F29A>Beacon Pioneer", tag-text: "<bold><#F7F29A>Beacon Pioneer", icon: "BEACON", description: ["&7First Beacon activation on Frontier Test."] }
  frontier_first_iron: { display-name: "<bold><#D9D9D9>Iron Pioneer", tag-text: "<bold><#D9D9D9>Iron Pioneer", icon: "IRON_INGOT", description: ["&7First Iron Ingot on Frontier Test."] }
  frontier_first_obsidian: { display-name: "<bold><#664A8A>Obsidian Pioneer", tag-text: "<bold><#664A8A>Obsidian Pioneer", icon: "OBSIDIAN", description: ["&7First Obsidian on Frontier Test."] }
  frontier_first_blaze_rod: { display-name: "<bold><#FFB347>Blaze Pioneer", tag-text: "<bold><#FFB347>Blaze Pioneer", icon: "BLAZE_ROD", description: ["&7First Blaze Rod on Frontier Test."] }
  frontier_first_heavy_core: { display-name: "<bold><#4FC3F7>Heavy Core Pioneer", tag-text: "<bold><#4FC3F7>Heavy Core Pioneer", icon: "HEAVY_CORE", description: ["&7First Heavy Core on Frontier Test."] }
  frontier_first_dragon_egg: { display-name: "<bold><#B36CFF>Egg Holder", tag-text: "<bold><#B36CFF>Egg Holder", icon: "DRAGON_EGG", description: ["&7First Dragon Egg on Frontier Test."] }
  frontier_first_totem: { display-name: "<bold><#FFD166>Undying Pioneer", tag-text: "<bold><#FFD166>Undying Pioneer", icon: "TOTEM_OF_UNDYING", description: ["&7First Totem of Undying on Frontier Test."] }
  frontier_first_god_apple: { display-name: "<bold><#FFD700>Golden Legend", tag-text: "<bold><#FFD700>Golden Legend", icon: "ENCHANTED_GOLDEN_APPLE", description: ["&7First Enchanted Golden Apple on Frontier Test."] }
  frontier_first_full_beacon: { display-name: "<bold><#FFF3A3>Beaconator", tag-text: "<bold><#FFF3A3>Beaconator", icon: "BEACON", description: ["&7First fully powered Beacon on Frontier Test."] }
  # FRONTIER-FIRST-TAGS-END
'''
p.write_text(text.replace(marker, marker+block, 1))
PY

echo '== Normalize built JARs for byte-reproducible deployment =='
normalize_jar "$OUT/EnthusiaTempChallenges-0.2.0-frontier.1.jar"
normalize_jar "$OUT/EnthusiaAdvancements-1.0.0-frontier.jar"
normalize_jar "$OUT/EnthusiaTags.jar"

echo '== Hash deployable artifacts =='
cd "$ROOT"
sha256sum \
  server/plugins/EnthusiaTempChallenges-0.2.0-frontier.1.jar \
  server/plugins/EnthusiaAdvancements-1.0.0-frontier.jar \
  server/plugins/UltimateAdvancementAPI-2.8.1.jar \
  server/plugins/EnthusiaTags.jar > server/PLUGIN-SHA256SUMS.txt
cat server/PLUGIN-SHA256SUMS.txt
