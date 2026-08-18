package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.particle.ParticleType;
import java.util.List;

public class NoRenderPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Range (Placeholder for now)
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
            .name("entity-range")
            .description("Entities outside this range will not be rendered (Visual only, WIP).")
            .defaultValue(64)
            .min(1)
            .sliderMax(128)
            .build());

    // Particles
    private final Setting<List<ParticleType<?>>> particles = sgGeneral.add(new ParticleTypeListSetting.Builder()
            .name("particles")
            .description("Particles to not render.")
            .build());

    // Block Entities
    private final Setting<List<Block>> blockEntities = sgGeneral.add(new BlockListSetting.Builder()
            .name("block-entities")
            .description("Block entities to not render.")
            .filter(block -> block instanceof BlockEntityProvider)
            .build());

    private final Setting<Boolean> noHopper = sgGeneral.add(new BoolSetting.Builder()
            .name("no-hopper")
            .description("Disables rendering of hoppers.")
            .defaultValue(true)
            .build());

    public NoRenderPlus() {
        super(Addon.CATEGORY, "no-render-plus", "NoRender with range limit and selective blocking.");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("NoRenderPlus", true);
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("NoRenderPlus", false);
    }

    @EventHandler
    private void onAddParticle(meteordevelopment.meteorclient.events.world.ParticleEvent event) {
        if (particles.get().contains(event.particle.getType()))
            event.cancel();
    }

    @EventHandler
    private void onRenderBlockEntity(meteordevelopment.meteorclient.events.render.RenderBlockEntityEvent event) {
        if (noHopper.get() && event.blockEntityState.type == net.minecraft.block.entity.BlockEntityType.HOPPER) {
            event.cancel();
            return;
        }
        if (blockEntities.get().contains(event.blockEntityState.blockState.getBlock()))
            event.cancel();
    }
}
