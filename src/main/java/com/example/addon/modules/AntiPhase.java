package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AntiPhase extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgItems = settings.createGroup("Items");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("Range to detect enemies.")
            .defaultValue(4.5).min(0.0).max(5.0).sliderRange(0.0, 5.0)
            .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
            .name("delay")
            .description("Delay between placements in milliseconds. Each placement costs several packets.")
            .defaultValue(100).min(0).max(1000).sliderMax(1000)
            .build());

    private final Setting<Integer> bpt = sgGeneral.add(new IntSetting.Builder()
            .name("blocks-per-tick")
            .description("How many blocks to place per tick.")
            .defaultValue(2).min(1).max(10).sliderRange(1, 10)
            .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Rotate toward the block being placed.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> raytrace = sgGeneral.add(new BoolSetting.Builder()
            .name("raytrace")
            .description("Only target enemies you have line of sight to.")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> onlyInHole = sgGeneral.add(new BoolSetting.Builder()
            .name("only-in-hole")
            .description("Only place when the enemy is surrounded on all four sides.")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
            .name("swap-back")
            .description("Return to your original hotbar slot after placing.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> buttons = sgItems.add(new BoolSetting.Builder()
            .name("buttons")
            .description("Use any button.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> scaffolding = sgItems.add(new BoolSetting.Builder()
            .name("scaffolding")
            .description("Use scaffolding.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> webs = sgItems.add(new BoolSetting.Builder()
            .name("webs")
            .description("Use cobwebs.")
            .defaultValue(false)
            .build());

    private long lastPlaceAt = 0L;

    public AntiPhase() {
        super(Addon.CATEGORY, "anti-phase", "Blocks enemies from phasing by filling their feet.");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("AntiPhase", true);
        lastPlaceAt = 0L;
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("AntiPhase", false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null)
            return;

        long now = System.currentTimeMillis();
        if (now - lastPlaceAt < delay.get())
            return;

        FindItemResult item = InvUtils.findInHotbar(this::isPlaceable);
        if (!item.found())
            return;

        List<PlayerEntity> targets = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator())
                continue;
            if (Friends.get().isFriend(player))
                continue;
            if (mc.player.distanceTo(player) > range.get())
                continue;
            if (raytrace.get() && !mc.player.canSee(player))
                continue;
            targets.add(player);
        }

        targets.sort(Comparator.comparingDouble(mc.player::distanceTo));

        int placed = 0;
        for (PlayerEntity player : targets) {
            if (placed >= bpt.get())
                break;

            BlockPos pos = player.getBlockPos();

            if (onlyInHole.get() && !isSurrounded(pos))
                continue;
            // Only that the block is replaceable: BlockUtils.canPlace asks
            // whether obsidian would fit, which an enemy standing in the way
            // always denies, and place() rechecks with the real block anyway.
            if (!mc.world.getBlockState(pos).isReplaceable())
                continue;

            // No entity check: the whole point is to place where an enemy
            // stands, and only collision-less blocks can go there at all.
            if (BlockUtils.place(pos, item, rotate.get(), 50, true, false, swapBack.get())) {
                placed++;
                lastPlaceAt = now;
            }
        }
    }

    private boolean isPlaceable(ItemStack stack) {
        if (stack.isEmpty())
            return false;
        if (buttons.get() && stack.isIn(ItemTags.BUTTONS))
            return true;
        if (scaffolding.get() && stack.isOf(Items.SCAFFOLDING))
            return true;
        return webs.get() && stack.isOf(Items.COBWEB);
    }

    /** True when all four horizontal neighbours are blast-resistant surround blocks. */
    private boolean isSurrounded(BlockPos pos) {
        for (Direction dir : Direction.Type.HORIZONTAL) {
            Block block = mc.world.getBlockState(pos.offset(dir)).getBlock();
            if (block != Blocks.OBSIDIAN && block != Blocks.BEDROCK && block != Blocks.ENDER_CHEST)
                return false;
        }
        return true;
    }
}
