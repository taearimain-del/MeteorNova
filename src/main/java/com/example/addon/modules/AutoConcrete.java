package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractTorchBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.component.DataComponentTypes;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.util.math.Box;
import net.minecraft.block.ConcretePowderBlock;
import net.minecraft.block.BlockState;
import java.util.Set;
import java.util.UUID;

public class AutoConcrete extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range").defaultValue(4).min(0).sliderMax(6).build());

    private final Setting<Integer> concreteCount = sgGeneral.add(new IntSetting.Builder()
            .name("concrete-count").description("How many falling blocks to drop at once.")
            .defaultValue(1).min(1).max(3).sliderMax(3).build());

    private final Setting<Integer> pillarDelay = sgGeneral.add(new IntSetting.Builder()
            .name("pillar-delay").defaultValue(30).min(0).sliderMax(100).build());

    private final Setting<Integer> concreteDelay = sgGeneral.add(new IntSetting.Builder()
            .name("drop-delay").defaultValue(50).min(0).sliderMax(100).build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate").defaultValue(true).build());

    private final Setting<Boolean> detectCrystals = sgGeneral.add(new BoolSetting.Builder()
            .name("detect-crystals").defaultValue(true).build());

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
            .name("air-place").defaultValue(false).build());

    private final Setting<Boolean> placeSupport = sgGeneral.add(new BoolSetting.Builder()
            .name("place-support").defaultValue(true).build());

    private final Setting<Boolean> disableOnUse = sgGeneral.add(new BoolSetting.Builder()
            .name("disable-on-use").description("Turns off the module after placing falling blocks.")
            .defaultValue(false).build());

    private final Setting<Boolean> onlyInHole = sgGeneral.add(new BoolSetting.Builder()
            .name("only-in-hole")
            .description("Only place blocks if the target has blocks on all 4 sides.")
            .defaultValue(false)
            .build());

    private final Setting<Boolean> smartMode = sgGeneral.add(new BoolSetting.Builder()
            .name("smart-mode")
            .description("Don't place if the target already has obstacles (Web/Button/etc) at feet.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> pauseWhileEating = sgGeneral.add(new BoolSetting.Builder()
            .name("pause-while-eating")
            .description("Temporarily pauses AutoConcrete while you're eating food.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> fishTrapDelay = sgGeneral.add(new IntSetting.Builder()
            .name("fish-trap-delay")
            .description("Delay (ticks) before placing trap after powder to let it fall.")
            .defaultValue(15)
            .min(0)
            .build());

    private final Setting<Boolean> smartPlace = sgGeneral.add(new BoolSetting.Builder()
            .name("smart-place")
            .description("Prevents placing traps if concrete powder is missing.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> fishTrap = sgGeneral.add(new BoolSetting.Builder()
            .name("fish-trap")
            .description("Places a block at Y+4 to force swim mode (Fish Trap).")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> fishTrapAirPlace = sgGeneral.add(new BoolSetting.Builder()
            .name("fish-trap-air-place")
            .description("Allows placing the fish trap block in the air (use correct server rotation settings).")
            .defaultValue(true)
            .build());

    private PlayerEntity target;
    private BlockPos basePos;
    private BlockPos[] concretePositions;
    private BlockPos lastTargetPos;
    private Direction placedDirection;
    private int currentPillarHeight;
    private int cooldown = 0;
    private boolean wasEating = false;

    private final Set<UUID> placedTargets = new HashSet<>();
    // Temporary set to track positions placed in this cycle to avoid spam due to
    // falling entity delay
    private final Set<BlockPos> temporaryPlacedPositions = new HashSet<>();
    private final Map<BlockPos, Integer> trapQueue = new HashMap<>();

    public AutoConcrete() {
        super(Addon.CATEGORY, "auto-concrete", "Drops falling blocks above enemies' heads.");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("AutoConcrete", true);
        reset();
        placedTargets.clear();
        temporaryPlacedPositions.clear();
        trapQueue.clear();
        wasEating = false;
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("AutoConcrete", false);
    }

    private void reset() {
        basePos = null;
        placedDirection = null;
        lastTargetPos = null;
        currentPillarHeight = 1 + 2 * concreteCount.get();
        concretePositions = new BlockPos[concreteCount.get()];
        cooldown = 0;
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null)
            return;

        // 1. Process Queue (Always runs first)
        if (!trapQueue.isEmpty()) {
            FindItemResult queueObsidian = InvUtils
                    .findInHotbar(item -> Block.getBlockFromItem(item.getItem()) == Blocks.OBSIDIAN);
            Iterator<Map.Entry<BlockPos, Integer>> it = trapQueue.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Integer> entry = it.next();
                if (entry.getValue() <= 0) {
                    boolean proceed = true;
                    if (smartPlace.get()) {
                        BlockPos below = entry.getKey().down();
                        BlockState bs = mc.world.getBlockState(below);
                        boolean isPowder = bs.getBlock() instanceof ConcretePowderBlock;
                        boolean isFallingEntity = false;

                        if (!isPowder) {
                            List<Entity> entities = mc.world.getOtherEntities(null, new Box(below).expand(0, 10, 0));
                            for (Entity e : entities) {
                                if (e instanceof FallingBlockEntity) {
                                    isFallingEntity = true;
                                    break;
                                }
                            }
                        }
                        if (!isPowder && !isFallingEntity)
                            proceed = false;
                    }

                    if (proceed) {
                        if (queueObsidian.found()) {
                            placeTrap(entry.getKey(), queueObsidian);
                        }
                    }
                    it.remove();
                } else {
                    entry.setValue(entry.getValue() - 1);
                }
            }
        }

        if (concretePositions == null || concretePositions.length != concreteCount.get()) {
            concretePositions = new BlockPos[concreteCount.get()];
        }

        if (cooldown > 0) {
            cooldown--;
            return;
        }

        if (pauseWhileEating.get() && mc.player != null) {
            boolean eatingNow = mc.player.isUsingItem() && isFood(mc.player.getActiveItem());
            if (eatingNow) {
                wasEating = true;
                return;
            } else if (wasEating) {
                wasEating = false;
            }
        }

        if (TargetUtils.isBadTarget(target, range.get())) {
            target = TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestHealth);
            if (TargetUtils.isBadTarget(target, range.get()))
                return;
            // New target found
        }

        // If we already placed on this target, skip
        if (placedTargets.contains(target.getUuid())) {
            // Optional: check if they moved away from the trap?
            // For now, strict "once per target" as requested.
            return;
        }

        BlockPos targetPos = target.getBlockPos();

        if (onlyInHole.get() && !isInHole(targetPos))
            return;

        if (smartMode.get() && isObstacle(mc.world.getBlockState(targetPos).getBlock()))
            return;

        if (lastTargetPos != null && !lastTargetPos.equals(targetPos)) {
            // reset(); // Don't reset everything, just positions logic
            basePos = null;
            placedDirection = null;
            temporaryPlacedPositions.clear(); // Clear temp positions for new target location
        }
        lastTargetPos = targetPos;

        FindItemResult obsidian = InvUtils
                .findInHotbar(stack -> Block.getBlockFromItem(stack.getItem()) == Blocks.OBSIDIAN);
        FindItemResult fallingBlock = InvUtils.findInHotbar(stack -> {
            Block block = Block.getBlockFromItem(stack.getItem());
            return block == Blocks.SAND
                    || block == Blocks.RED_SAND
                    || block == Blocks.GRAVEL
                    || block == Blocks.SUSPICIOUS_SAND
                    || block == Blocks.SUSPICIOUS_GRAVEL
                    || block instanceof ConcretePowderBlock;
        });

        if (!fallingBlock.found() || (!obsidian.found() && !airPlace.get()))
            return;

        boolean crystalPresent = detectCrystals.get() && isCrystalOnSurround(target);
        currentPillarHeight = 1 + 2 * concreteCount.get();
        if (crystalPresent)
            currentPillarHeight++;

        if (airPlace.get()) {
            for (int i = 0; i < concreteCount.get(); i++) {
                concretePositions[i] = targetPos.up(2 + concreteCount.get() + i);
            }
        } else if (placeSupport.get()) {
            if (placedDirection == null || basePos == null) {
                for (Direction dir : Direction.Type.HORIZONTAL) {
                    BlockPos side = targetPos.offset(dir);
                    if (!mc.world.getBlockState(side).isAir()) {
                        boolean clear = true;
                        for (int i = 0; i < currentPillarHeight; i++) {
                            if (!mc.world.getBlockState(side.up(i + 1)).isReplaceable()) {
                                clear = false;
                                break;
                            }
                        }
                        if (clear) {
                            placedDirection = dir;
                            basePos = side.up();
                            break;
                        }
                    }
                }
            }

            if (basePos == null || placedDirection == null)
                return;

            boolean allPlaced = true;
            for (int i = 0; i < currentPillarHeight; i++) {
                BlockPos pos = basePos.up(i);
                if (!mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
                    BlockUtils.place(pos, obsidian, rotate.get(), 0);
                    cooldown = pillarDelay.get();
                    allPlaced = false;
                    break;
                }
            }

            if (!allPlaced)
                return;

            for (int i = 0; i < concreteCount.get(); i++) {
                concretePositions[i] = targetPos.up(2 + concreteCount.get() + i);
            }
        }

        for (BlockPos pos : concretePositions) {
            if (pos != null && mc.world.getBlockState(pos).isReplaceable() && !temporaryPlacedPositions.contains(pos)) {
                if (BlockUtils.place(pos, fallingBlock, rotate.get(), 0)) {
                    temporaryPlacedPositions.add(pos);
                }
            }
        }

        // Fish Trap Logic
        if (fishTrap.get() && obsidian.found()) {

            BlockPos fishTrapPos = null;

            // 2. Trigger Queue (Delayed Mode)
            if (!temporaryPlacedPositions.isEmpty()) {
                // Trap = targetPos + count + 1
                fishTrapPos = targetPos.up(concreteCount.get() + 1);

                if (fishTrapPos != null && !trapQueue.containsKey(fishTrapPos)) {
                    // Only add if not already placed/queued
                    Block s = mc.world.getBlockState(fishTrapPos).getBlock();
                    if (s != Blocks.OBSIDIAN && s != Blocks.BEDROCK) {
                        trapQueue.put(fishTrapPos, fishTrapDelay.get());
                    }
                }
            }

            // 3. Scan for existing powder (Backup/Passive Mode)
            // If trap is already queued, skip scan for this pos
            if (fishTrapPos == null || !trapQueue.containsKey(fishTrapPos)) {
                BlockPos baseScan = targetPos;
                boolean foundPowder = false;
                for (int i = 0; i < 5; i++) {
                    if (mc.world.getBlockState(baseScan)
                            .getBlock() instanceof net.minecraft.block.ConcretePowderBlock) {
                        foundPowder = true;
                        while (mc.world.getBlockState(baseScan.down())
                                .getBlock() instanceof net.minecraft.block.ConcretePowderBlock) {
                            baseScan = baseScan.down();
                        }
                        break;
                    }
                    baseScan = baseScan.down();
                }

                if (foundPowder) {
                    BlockPos topPowder = baseScan;
                    while (mc.world.getBlockState(topPowder.up())
                            .getBlock() instanceof net.minecraft.block.ConcretePowderBlock) {
                        topPowder = topPowder.up();
                    }
                    BlockPos scanTrapPos = topPowder.up(2);

                    // If not queued, place immediately (it's existing powder)
                    if (!trapQueue.containsKey(scanTrapPos)) {
                        placeTrap(scanTrapPos, obsidian);
                    }
                }
            }
        }

        // Check if everything is placed to mark as done
        boolean allDone = true;
        // Check concrete (Source blocks must be empty/air implies they fell?)
        // Actually, if we want to confirm they fell, source blocks being AIR is good.
        for (BlockPos pos : concretePositions) {
            if (pos != null && mc.world.getBlockState(pos).isReplaceable() && !temporaryPlacedPositions.contains(pos)) {
                allDone = false;
                break;
            }
        }

        // Check fish trap if enabled
        if (fishTrap.get() && allDone) {
            // Same scan logic for allDone check
            BlockPos baseScan = targetPos;
            boolean foundPowder = false;
            for (int i = 0; i < 5; i++) {
                if (mc.world.getBlockState(baseScan).getBlock() instanceof net.minecraft.block.ConcretePowderBlock) {
                    foundPowder = true;
                    while (mc.world.getBlockState(baseScan.down())
                            .getBlock() instanceof net.minecraft.block.ConcretePowderBlock) {
                        baseScan = baseScan.down();
                    }
                    break;
                }
                baseScan = baseScan.down();
            }

            if (foundPowder) {
                // Find top-most powder block
                BlockPos topPowder = baseScan;
                while (mc.world.getBlockState(topPowder.up())
                        .getBlock() instanceof net.minecraft.block.ConcretePowderBlock) {
                    topPowder = topPowder.up();
                }
                BlockPos fishTrapPos = topPowder.up(2);

                if (mc.world.getBlockState(fishTrapPos).isReplaceable()
                        && !temporaryPlacedPositions.contains(fishTrapPos)) {
                    allDone = false;
                }
            } else {
                // If powder not found yet, we are not done (trap not placed)
                // But if we just placed it, it might still be an entity.
                // Rely on queue check below.
                if (temporaryPlacedPositions.isEmpty()) {
                    // If we haven't placed anything this tick, and no powder found, maybe we are
                    // done?
                    // Keep existing logic: if powder not found, assume done unless queue is active.
                }
            }
        }

        if (!trapQueue.isEmpty())
            allDone = false;

        if (allDone) {
            placedTargets.add(target.getUuid());
        }

        cooldown = concreteDelay.get();

        if (disableOnUse.get())
            toggle();
    }

    private boolean isCrystalOnSurround(PlayerEntity target) {
        BlockPos pos = target.getBlockPos();

        // One spatial query covering all four surround boxes, rather than a
        // full entity sweep per direction.
        Box search = new Box(
                pos.getX() - 1, pos.getY() + 0.5, pos.getZ() - 1,
                pos.getX() + 2, pos.getY() + 3.0, pos.getZ() + 2);
        List<EndCrystalEntity> crystals = mc.world.getEntitiesByClass(EndCrystalEntity.class, search, e -> true);
        if (crystals.isEmpty())
            return false;

        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos surround = pos.offset(dir);
            for (EndCrystalEntity crystal : crystals) {
                if (crystal.getBoundingBox().intersects(
                        surround.toCenterPos().add(-0.5, 0, -0.5),
                        surround.toCenterPos().add(0.5, 2.5, 0.5))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInHole(BlockPos pos) {
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (mc.world.getBlockState(pos.offset(dir)).isAir())
                return false;
        }
        return true;
    }

    private boolean isObstacle(Block block) {
        return block instanceof ButtonBlock || block instanceof AbstractTorchBlock
                || block == Blocks.COBWEB || block == Blocks.TRIPWIRE
                || block == Blocks.OBSIDIAN || block == Blocks.BEDROCK;
    }

    private void placeTrap(BlockPos pos, FindItemResult item) {
        if (pos == null)
            return;

        Block stateBlock = mc.world.getBlockState(pos).getBlock();
        if (stateBlock == Blocks.OBSIDIAN || stateBlock == Blocks.BEDROCK || temporaryPlacedPositions.contains(pos)) {
            return;
        }
        if (!mc.world.getBlockState(pos).isReplaceable()) {
            return;
        }

        boolean placed = false;
        if (fishTrapAirPlace.get()) {
            if (BlockUtils.place(pos, item, rotate.get(), 50, true, false, true))
                placed = true;
        } else {
            boolean canPlaceNormal = BlockUtils.canPlace(pos);
            if (placeSupport.get() && !canPlaceNormal) {
                for (Direction d : Direction.Type.HORIZONTAL) {
                    BlockPos adj = pos.offset(d);
                    if (mc.world.getBlockState(adj).isReplaceable()
                            && !temporaryPlacedPositions.contains(adj)
                            && BlockUtils.canPlace(adj)) {
                        BlockUtils.place(adj, item, rotate.get(), 50, true, false, true);
                        temporaryPlacedPositions.add(adj);
                        break;
                    }
                }
            }
            if (BlockUtils.canPlace(pos)) {
                if (BlockUtils.place(pos, item, rotate.get(), 50, true, false, true))
                    placed = true;
            }
        }
        if (placed) {
            temporaryPlacedPositions.add(pos);
        }
    }

    private boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.get(DataComponentTypes.FOOD) != null;
    }

    @Override
    public String getInfoString() {
        return target != null ? (airPlace.get() ? "AirPlace - " : "Pillar - ") + EntityUtils.getName(target) : null;
    }
}
