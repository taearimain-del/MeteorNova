package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import com.example.addon.utils.NovaKeybindUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.ConcretePowderBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;

public class ConcreteWeb extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("Range to detect target.")
            .defaultValue(5.0)
            .build());

    private final Setting<Keybind> webAuraKey = sgGeneral.add(new KeybindSetting.Builder()
            .name("web-aura-key")
            .description("Key to toggle external Web Aura (Mio Client).")
            .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_B))
            .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
            .name("debug")
            .description("Send chat messages when toggling.")
            .defaultValue(true)
            .build());

    private boolean isAuraActive = false;
    private int toggleTimer = 0;
    private int activeTimer = 0;

    public ConcreteWeb() {
        super(Addon.CATEGORY, "concrete-web", "Toggles external Web Aura when target is lifted by concrete.");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("ConcreteWeb", true);
        isAuraActive = false;
        toggleTimer = 0;
        activeTimer = 0;
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("ConcreteWeb", false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null)
            return;

        if (toggleTimer > 0)
            toggleTimer--;
        if (isAuraActive)
            activeTimer++;

        PlayerEntity target = findNearestTarget();

        // Safety: Turn off if no target found while active
        if (target == null) {
            if (isAuraActive && toggleTimer == 0) {
                toggleWebAura(false);
            }
            return;
        }

        boolean onPowder = isConcretePowder(target.getBlockPos().down()) || isConcretePowder(target.getBlockPos());
        boolean inWeb = isInWeb(target);

        if (!isAuraActive) {
            // Activate if target is lifted by powder
            if (onPowder && toggleTimer == 0) {
                toggleWebAura(true);
            }
        } else {
            // Deactivate if target is NOT in web (escaped)
            // Wait at least 10 ticks after activation to allow web to spawn
            if (activeTimer > 10 && !inWeb && toggleTimer == 0) {
                toggleWebAura(false);
            }
        }
    }

    private void toggleWebAura(boolean state) {
        isAuraActive = state;
        toggleTimer = 10; // Prevent spam toggling
        if (state)
            activeTimer = 0;

        pressKey();

        if (debug.get()) {
            NovaChatUtils.sendInfoMsg("ConcreteWeb", state ? "Toggled Web Aura >> ON" : "Toggled Web Aura >> OFF");
        }
    }

    private void pressKey() {
        NovaKeybindUtils.pressKey(webAuraKey.get(), 1);
    }

    private PlayerEntity findNearestTarget() {
        PlayerEntity best = null;
        double bestDist = range.get() * range.get();

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || Friends.get().isFriend(p))
                continue;
            double dist = mc.player.squaredDistanceTo(p);
            if (dist <= bestDist) {
                best = p;
                bestDist = dist;
            }
        }
        return best;
    }

    private boolean isConcretePowder(BlockPos pos) {
        return mc.world.getBlockState(pos).getBlock() instanceof ConcretePowderBlock;
    }

    private boolean isInWeb(PlayerEntity player) {
        Box box = player.getBoundingBox();
        BlockPos min = BlockPos.ofFloored(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ);

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() == Blocks.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
