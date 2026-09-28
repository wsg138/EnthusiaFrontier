# Dimension-specific borders. /worldborder set uses full width.
# User-facing coordinate extents are Overworld +/-5000, Nether +/-2500,
# and End +/-500 so the temporary server remains on the main End island.
execute in minecraft:overworld run worldborder center 0 0
execute in minecraft:overworld run worldborder set 10000
execute in minecraft:overworld run worldborder warning distance 32
execute in minecraft:overworld run worldborder damage buffer 0
execute in minecraft:overworld run worldborder damage amount 1

execute in minecraft:the_nether run worldborder center 0 0
execute in minecraft:the_nether run worldborder set 5000
execute in minecraft:the_nether run worldborder warning distance 32
execute in minecraft:the_nether run worldborder damage buffer 0
execute in minecraft:the_nether run worldborder damage amount 1

execute in minecraft:the_end run worldborder center 0 0
execute in minecraft:the_end run worldborder set 1000
execute in minecraft:the_end run worldborder warning distance 32
execute in minecraft:the_end run worldborder damage buffer 0
execute in minecraft:the_end run worldborder damage amount 1

# Elytra acquisition is still enforced separately by enthusia_test:tick as a
# defense-in-depth rule, even though outer End islands are outside this border.
