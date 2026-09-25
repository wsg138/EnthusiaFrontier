# Dimension-specific borders. /worldborder set uses full width.
execute in minecraft:overworld run worldborder center 0 0
execute in minecraft:overworld run worldborder set 5000
execute in minecraft:overworld run worldborder warning distance 32
execute in minecraft:overworld run worldborder damage buffer 0
execute in minecraft:overworld run worldborder damage amount 1

execute in minecraft:the_nether run worldborder center 0 0
execute in minecraft:the_nether run worldborder set 2500
execute in minecraft:the_nether run worldborder warning distance 32
execute in minecraft:the_nether run worldborder damage buffer 0
execute in minecraft:the_nether run worldborder damage amount 1

# Main End island only; normal outer-island/End City Elytra access is outside the border.
execute in minecraft:the_end run worldborder center 0 0
execute in minecraft:the_end run worldborder set 1000
execute in minecraft:the_end run worldborder warning distance 32
execute in minecraft:the_end run worldborder damage buffer 0
execute in minecraft:the_end run worldborder damage amount 1
