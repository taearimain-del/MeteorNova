package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BlockESPPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Block Group 1
    private final Setting<List<Block>> blocks1 = sgGeneral.add(new BlockListSetting.Builder()
            .name("blocks-1")
            .description("Blocks to render (Red).")
            .build());

    private final Setting<SettingColor> color1 = sgGeneral.add(new ColorSetting.Builder()
            .name("color-1")
            .defaultValue(new SettingColor(255, 0, 0, 100))
            .build());

    // Block Group 2
    private final Setting<List<Block>> blocks2 = sgGeneral.add(new BlockListSetting.Builder()
            .name("blocks-2")
            .description("Blocks to render (Blue).")
            .build());

    private final Setting<SettingColor> color2 = sgGeneral.add(new ColorSetting.Builder()
            .name("color-2")
            .defaultValue(new SettingColor(0, 0, 255, 100))
            .build());

    // Entity Group 1
    private final Setting<Set<EntityType<?>>> entities1 = sgGeneral.add(new EntityTypeListSetting.Builder()
            .name("entities-1")
            .description("Entities to render (Group 1).")
            .build());

    private final Setting<SettingColor> entColor1 = sgGeneral.add(new ColorSetting.Builder()
            .name("ent-color-1")
            .defaultValue(new SettingColor(0, 255, 0, 100))
            .build());

    // Settings
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
            .name("block-scan-range")
            .description("Scan range for blocks (heavy).")
            .defaultValue(8)
            .sliderMax(16)
            .build());

    // Scanning the cube every frame is tens of thousands of block lookups a
    // frame; scan on a tick instead and let rendering just walk the results.
    private static final int SCAN_INTERVAL = 10;
    private int scanTicks = 0;
    private final List<BlockPos> found1 = new ArrayList<>();
    private final List<BlockPos> found2 = new ArrayList<>();
    private final List<Entity> foundEntities = new ArrayList<>();

    public BlockESPPlus() {
        super(Addon.CATEGORY, "block-esp-plus", "ESP for Blocks and Entities with custom colors.");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("BlockESPPlus", true);
        clearFound();
        scanTicks = 0;
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("BlockESPPlus", false);
        clearFound();
    }

    private void clearFound() {
        found1.clear();
        found2.clear();
        foundEntities.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            clearFound();
            return;
        }

        if (--scanTicks > 0)
            return;
        scanTicks = SCAN_INTERVAL;

        clearFound();

        int r = range.get();
        BlockPos center = mc.player.getBlockPos();

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = center.add(x, y, z);
                    Block block = mc.world.getBlockState(pos).getBlock();

                    if (blocks1.get().contains(block)) {
                        found1.add(pos);
                    } else if (blocks2.get().contains(block)) {
                        found2.add(pos);
                    }
                }
            }
        }

        for (Entity entity : mc.world.getEntities()) {
            if (entities1.get().contains(entity.getType()))
                foundEntities.add(entity);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null)
            return;

        for (BlockPos pos : found1)
            event.renderer.box(pos, color1.get(), color1.get(), ShapeMode.Lines, 0);

        for (BlockPos pos : found2)
            event.renderer.box(pos, color2.get(), color2.get(), ShapeMode.Lines, 0);

        // Boxes come from the entity so they follow it between scans.
        for (Entity entity : foundEntities) {
            if (!entity.isRemoved())
                event.renderer.box(entity.getBoundingBox(), entColor1.get(), entColor1.get(), ShapeMode.Lines, 0);
        }
    }
}
