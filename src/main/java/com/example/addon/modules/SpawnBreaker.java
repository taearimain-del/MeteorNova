package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.AntiHunger;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.component.DataComponentTypes;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public class SpawnBreaker extends Module {
    public enum ServerMode {
        _6b6t, _2b2t
    }

    public enum MoveMode {
        Speed, Efficiency
    }

    private enum State {
        IDLE,
        INITIALIZING,
        TRAVELING,
        HUNTING_CHASE,
        HUNTING_COLLECT,
        GATHERING_MATERIALS,
        BUILDING_SHELTER,
        FINISHED
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSurvival = settings.createGroup("Survival");
    private final SettingGroup sgEscape = settings.createGroup("Escape");

    // General
    private final Setting<ServerMode> serverMode = sgGeneral.add(new EnumSetting.Builder<ServerMode>()
            .name("server-mode").defaultValue(ServerMode._6b6t).build());

    private final Setting<MoveMode> moveMode = sgGeneral.add(new EnumSetting.Builder<MoveMode>()
            .name("move-mode").defaultValue(MoveMode.Speed).build());

    // Survival
    private final Setting<Boolean> autoFood = sgSurvival.add(new BoolSetting.Builder()
            .name("auto-hunt").description("Hunt animals when hungry and no food.").defaultValue(true).build());

    private final Setting<Integer> hungerThreshold = sgSurvival.add(new IntSetting.Builder()
            .name("hunker-threshold").defaultValue(10).min(1).max(18).visible(autoFood::get).build());

    private final Setting<Boolean> evasionAssist = sgSurvival.add(new BoolSetting.Builder()
            .name("evasion-assist").description("Run from players.").defaultValue(true).build());

    private final Setting<Double> enemyRange = sgSurvival.add(new DoubleSetting.Builder()
            .name("enemy-detection-range").defaultValue(64.0).visible(evasionAssist::get).build());

    // Escape
    private final Setting<Integer> escapeDistance = sgEscape.add(new IntSetting.Builder()
            .name("escape-distance").description("Distance from spawn to stop at.").defaultValue(10000).build());

    private final Setting<Boolean> buildShelter = sgEscape.add(new BoolSetting.Builder()
            .name("build-shelter").description("Box yourself in when arrived.").defaultValue(true).build());

    // State
    private State currentState = State.IDLE;
    private int tickCounter = 0;
    private int evadeCooldown = 0;
    private BlockPos shelterCenter = null;
    private boolean originalKillAura = false;
    private boolean originalAutoEat = false;

    private Entity huntingTarget = null;
    private int huntTimeout = 0;

    public SpawnBreaker() {
        super(Addon.CATEGORY, "spawn-breaker", "Advanced spawn escape bot.");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("SpawnBreaker", true);
        currentState = State.IDLE;
        updateServerSettings();
        // Enable AutoEat if strictly needed, but better to check if user wants it
        // monitoring 'originalAutoEat' to revert later is good practice
        Module ae = Modules.get().get(AutoEat.class);
        if (ae != null) {
            originalAutoEat = ae.isActive();
            if (!originalAutoEat)
                ae.toggle();
        }
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("SpawnBreaker", false);
        baritoneCommand("stop");
        if (Modules.get().get(AutoEat.class) != null && !originalAutoEat
                && Modules.get().get(AutoEat.class).isActive()) {
            Modules.get().get(AutoEat.class).toggle();
        }
        if (Modules.get().get(KillAura.class) != null && !originalKillAura
                && Modules.get().get(KillAura.class).isActive()) {
            Modules.get().get(KillAura.class).toggle();
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null)
            return;
        tickCounter++;
        if (evadeCooldown > 0)
            evadeCooldown--;

        if (tickCounter % 60 == 0)
            updateServerSettings();

        // High priority: Evasion
        if (evasionAssist.get() && evadeCooldown == 0 && currentState != State.BUILDING_SHELTER) {
            if (handleEvasion())
                return;
        }

        // State Machine
        switch (currentState) {
            case INITIALIZING:
                handleInitializing();
                break;
            case TRAVELING:
                handleTraveling();
                break;
            case HUNTING_CHASE:
                handleHunting();
                break;
            case HUNTING_COLLECT:
                handleCollectingFood();
                break;
            case GATHERING_MATERIALS:
                handleGathering();
                break;
            case BUILDING_SHELTER:
                handleBuilding();
                break;
            case FINISHED:
                if (isActive()) {
                    NovaChatUtils.sendInfoMsg("SpawnBreaker", "Escape complete. Shutting down.");
                    toggle();
                }
                break;
        }
    }

    @EventHandler
    public void onMessageSend(SendMessageEvent event) {
        String msg = event.message.trim();
        if (msg.equalsIgnoreCase("/sb start_exodus")) {
            startExodus();
            event.cancel();
        }
    }

    public void startExodus() {
        if (!isActive())
            toggle();
        ChatUtils.info("[SpawnBreaker] Starting Exodus sequence...");
        currentState = State.INITIALIZING;
        tickCounter = 0;
    }

    // --- State Handlers ---

    private void handleInitializing() {
        if (tickCounter == 2) {
            baritoneCommand("stop");
            baritoneCommand("cancel");
        }
        if (tickCounter == 10) {
            double currentX = mc.player.getX();
            double currentZ = mc.player.getZ();
            double targetX = currentX >= 0 ? escapeDistance.get() : -escapeDistance.get();
            double targetZ = currentZ >= 0 ? escapeDistance.get() : -escapeDistance.get();

            NovaChatUtils.sendInfoMsg("SpawnBreaker", "Moving to " + (int) targetX + ", " + (int) targetZ);
            baritoneCommand("goto " + (int) targetX + " " + (int) targetZ);
            currentState = State.TRAVELING;
        }
    }

    private void handleTraveling() {
        // 1. Check Hunger
        if (autoFood.get() && mc.player.getHungerManager().getFoodLevel() <= hungerThreshold.get()) {
            if (!hasFood()) {
                Entity animal = findAnimal();
                if (animal != null) {
                    NovaChatUtils.sendInfoMsg("SpawnBreaker",
                            "Hungry! Hunting " + animal.getName().getString() + "...");
                    huntingTarget = animal;
                    currentState = State.HUNTING_CHASE;
                    huntTimeout = 300; // 15s limit
                    baritoneCommand("follow entity " + animal.getUuidAsString());
                    enableKillAura(true);
                    return;
                } else {
                    if (tickCounter % 200 == 0)
                        NovaChatUtils.sendInfoMsg("SpawnBreaker", "Hungry but no food/animals found. Continuing...");
                }
            }
        }

        // 2. Check Arrival
        double distSq = mc.player.getEntityPos().lengthSquared(); // distance from 0,0
        if (distSq >= escapeDistance.get() * escapeDistance.get()) {
            NovaChatUtils.sendInfoMsg("SpawnBreaker", "Arrived at destination!");
            baritoneCommand("stop");
            if (buildShelter.get()) {
                currentState = State.GATHERING_MATERIALS;
            } else {
                currentState = State.FINISHED;
            }
            return;
        }

        // 3. Ensure moving
        // (Baritone handles pathing, we just monitor)
    }

    private void handleHunting() {
        huntTimeout--;
        if (huntTimeout <= 0 || huntingTarget == null || !huntingTarget.isAlive()) {
            enableKillAura(false); // Disable KA
            currentState = State.HUNTING_COLLECT;
            huntTimeout = 100; // time to collect drops
            // Go to position where mob died? Or just look for drops
            baritoneCommand("stop");
            return;
        }
        // Baritone follow is active. KillAura will kill it.
    }

    private void handleCollectingFood() {
        // Look for item drops nearby
        ItemEntity drop = findFoodDrop();
        if (drop != null) {
            baritoneCommand("goto " + drop.getBlockPos().getX() + " " + drop.getBlockPos().getY() + " "
                    + drop.getBlockPos().getZ());
        }

        if (hasFood() || huntTimeout-- <= 0) {
            NovaChatUtils.sendInfoMsg("SpawnBreaker", "Food collected (or timeout). Resuming travel.");
            baritoneCommand("stop");
            currentState = State.INITIALIZING; // Reuse init logic
            tickCounter = 0;
        }
    }

    private void handleGathering() {
        if (getBlockCount() >= 8) {
            NovaChatUtils.sendInfoMsg("SpawnBreaker", "Materials ready. Building shelter...");
            baritoneCommand("stop");
            shelterCenter = mc.player.getBlockPos();
            currentState = State.BUILDING_SHELTER;
            return;
        }

        // Mine some blocks
        // We need solid blocks. Stone, Deepslate, Netherrack, Logs...
        // Simple heuristic: Mine stone
        if (tickCounter % 60 == 0) {
            NovaChatUtils.sendInfoMsg("SpawnBreaker", "Mining building blocks...");
            baritoneCommand("mine stone dirt netherrack deepslate");
        }
    }

    private void handleBuilding() {
        // Simple 1x2 surround logic
        if (shelterCenter == null)
            shelterCenter = mc.player.getBlockPos();

        // Center player
        if (mc.player.getBlockPos().getSquaredDistance(shelterCenter) > 2) {
            // somehow drifted, reset
            shelterCenter = mc.player.getBlockPos();
        }

        BlockPos[] surround = new BlockPos[] {
                shelterCenter.north(), shelterCenter.south(), shelterCenter.east(), shelterCenter.west(),
                shelterCenter.north().up(), shelterCenter.south().up(), shelterCenter.east().up(),
                shelterCenter.west().up(),
                shelterCenter.up(2) // roof
        };

        int placed = 0;
        int slot = findBlockSlot();
        if (slot == -1) {
            currentState = State.GATHERING_MATERIALS; // run out of blocks?
            return;
        }

        for (BlockPos pos : surround) {
            if (BlockUtils.place(pos, InvUtils.findInHotbar(s -> s.getItem() instanceof BlockItem), true, 10)) {
                placed++;
            }
        }

        // If we can't place anymore (surrounded), finish
        // Or confirm all filled
        boolean allFull = true;
        for (BlockPos pos : surround) {
            if (mc.world.getBlockState(pos).isReplaceable())
                allFull = false;
        }

        if (allFull) {
            NovaChatUtils.sendInfoMsg("SpawnBreaker", "Shelter built. Stay safe!");
            currentState = State.FINISHED;
        }
    }

    // --- Helpers ---

    private boolean handleEvasion() {
        PlayerEntity enemy = getNearestEnemy();
        if (enemy != null) {
            double dist = mc.player.distanceTo(enemy);
            if (dist < 15) {
                NovaChatUtils.sendInfoMsg("SpawnBreaker", "Enemy! Evading...");
                baritoneCommand("stop");
                Vec3d enemyPos = enemy.getEntityPos();
                Vec3d myPos = mc.player.getEntityPos();
                Vec3d awayDir = myPos.subtract(enemyPos).normalize();
                Vec3d targetPos = myPos.add(awayDir.multiply(50));

                baritoneCommand("goto " + (int) targetPos.x + " " + (int) myPos.y + " " + (int) targetPos.z);

                evadeCooldown = 100; // 5s
                return true;
            }
        }
        return false;
    }

    private boolean hasFood() {
        return mc.player.getInventory().contains(s -> s.getItem().getComponents().contains(DataComponentTypes.FOOD))
                || mc.player.getOffHandStack().getItem().getComponents().contains(DataComponentTypes.FOOD);
    }

    private ItemEntity findFoodDrop() {
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof ItemEntity ie) {
                if (ie.getStack().getItem().getComponents().contains(DataComponentTypes.FOOD)) {
                    if (mc.player.distanceTo(ie) < 20)
                        return ie;
                }
            }
        }
        return null;
    }

    private Entity findAnimal() {
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof AnimalEntity && mc.player.distanceTo(e) < 40) {
                if (e instanceof PigEntity || e instanceof CowEntity || e instanceof SheepEntity
                        || e instanceof ChickenEntity) {
                    return e;
                }
            }
        }
        return null;
    }

    private int getBlockCount() {
        return InvUtils.find(s -> s.getItem() instanceof BlockItem).count();
    }

    private int findBlockSlot() {
        return InvUtils.findInHotbar(s -> s.getItem() instanceof BlockItem).slot();
    }

    private void enableKillAura(boolean enable) {
        Module ka = Modules.get().get(KillAura.class);
        if (ka != null) {
            if (enable && !ka.isActive()) {
                originalKillAura = false;
                ka.toggle();
            } else if (!enable && ka.isActive() && !originalKillAura) {
                ka.toggle();
            }
        }
    }

    private void updateServerSettings() {
        Module antiHunger = Modules.get().get(AntiHunger.class);
        if (antiHunger != null && serverMode.get() == ServerMode._2b2t && antiHunger.isActive()) {
            antiHunger.toggle();
        }
    }

    private PlayerEntity getNearestEnemy() {
        PlayerEntity nearest = null;
        double minDst = enemyRange.get() * enemyRange.get();
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof PlayerEntity p && p != mc.player && !Friends.get().isFriend(p)) {
                double d = mc.player.squaredDistanceTo(p);
                if (d < minDst) {
                    minDst = d;
                    nearest = p;
                }
            }
        }
        return nearest;
    }

    private void baritoneCommand(String command) {
        ChatUtils.sendPlayerMsg("#" + command);
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        list.add(theme.settings(settings));
        list.add(theme.horizontalSeparator("Actions"));

        WHorizontalList actions = list.add(theme.horizontalList()).widget();
        WButton startBtn = actions.add(theme.button("Start Exodus")).widget();
        startBtn.action = this::startExodus;
        return list;
    }
}
