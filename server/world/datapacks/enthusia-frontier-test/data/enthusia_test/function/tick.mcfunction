# Frontier Test policy: Elytras are not obtainable on this temporary server.
# Strip the natural End Ship item-frame Elytra before it can be taken.
execute in minecraft:the_end as @e[type=minecraft:item_frame] if items entity @s contents minecraft:elytra run item replace entity @s contents with minecraft:air
execute in minecraft:the_end as @e[type=minecraft:glow_item_frame] if items entity @s contents minecraft:elytra run item replace entity @s contents with minecraft:air

# Defense in depth for admin/plugin drops or other acquisition paths.
kill @e[type=minecraft:item,nbt={Item:{id:"minecraft:elytra"}}]
execute as @a[nbt={Inventory:[{id:"minecraft:elytra"}]}] run tellraw @s {"text":"Elytras are disabled on Enthusia Frontier Test.","color":"red"}
clear @a minecraft:elytra
