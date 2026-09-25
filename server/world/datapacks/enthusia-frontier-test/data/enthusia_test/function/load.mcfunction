# Dimension-specific borders. /worldborder set uses full width.
# User-facing coordinate extents are Overworld +/-5000 and Nether/End +/-2500.
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
execute in minecraft:the_end run worldborder set 5000
execute in minecraft:the_end run worldborder warning distance 32
execute in minecraft:the_end run worldborder damage buffer 0
execute in minecraft:the_end run worldborder damage amount 1

# Elytra acquisition is enforced separately by enthusia_test:tick so the larger End border
# cannot re-enable natural End Ship Elytras.
