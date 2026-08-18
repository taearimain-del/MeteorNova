package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import com.example.addon.utils.NovaKeybindUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractTorchBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.ConcretePowderBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import org.lwjgl.glfw.GLFW;

public class TrapMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("range")
            .description("Range to detect enemies.")
            .defaultValue(5.0)
            .build());

    private final Setting<BreakMode> breakMode = sgGeneral.add(new EnumSetting.Builder<BreakMode>()
            .name("break-mode")
            .description("How to break blocks under enemies.")
            .defaultValue(BreakMode.Packet)
            .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Rotate toward the block being broken.")
            .defaultValue(true)
            .build());

    private final Setting<Keybind> resetKey = sgGeneral.add(new KeybindSetting.Builder()
            .name("reset-key")
            .description("Key to toggle SpeedMine (Reset InstaMine).")
            .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_4))
            .build());

    public TrapMiner() {
        super(Addon.CATEGORY, "trap-miner",
                "Breaks buttons, torches, and webs inside enemy hitbox (helps concrete traps).");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("TrapMiner", true);
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("TrapMiner", false);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null)
            return;

        for (Entity entity : mc.world.getPlayers()) {
            if (!(entity instanceof PlayerEntity player))
                continue;
            if (player == mc.player || player.isSpectator() || player.isCreative())
                continue;
            if (mc.player.distanceTo(player) > range.get())
                continue;

            BlockPos blockPos = player.getBlockPos();
            Block block = mc.world.getBlockState(blockPos).getBlock();

            // Already trapped in concrete? Don't break (keep them trapped)
            if (block instanceof ConcretePowderBlock
                    || mc.world.getBlockState(blockPos.up()).getBlock() instanceof ConcretePowderBlock
                    || mc.world.getBlockState(blockPos.down()).getBlock() instanceof ConcretePowderBlock) {
                continue;
            }

            if (isTrapObstacle(block)) {
                if (rotate.get()) {
                    Rotations.rotate(Rotations.getYaw(blockPos.toCenterPos()),
                            Rotations.getPitch(blockPos.toCenterPos()));
                }

                if (breakMode.get() == BreakMode.Normal) {
                    mc.interactionManager.updateBlockBreakingProgress(blockPos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                } else {
                    // Reset SpeedMine if configured
                    NovaKeybindUtils.pressKey(resetKey.get(), 2);

                    mc.interactionManager.attackBlock(blockPos, Direction.UP);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }

                // Break one at a time per tick to avoid spam kicks
                break;
            }
        }
    }

    private boolean isTrapObstacle(Block block) {
        return block instanceof ButtonBlock
                || block instanceof AbstractTorchBlock
                || block == Blocks.COBWEB
                || block == Blocks.TRIPWIRE;
    }

    public enum BreakMode {
        Normal,
        Packet
    }
}
