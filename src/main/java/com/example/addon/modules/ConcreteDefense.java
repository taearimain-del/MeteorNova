package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractTorchBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.ConcretePowderBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.function.Predicate;

public class ConcreteDefense extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
            .name("mode")
            .description("When to place the obstruction.")
            .defaultValue(Mode.Strict)
            .build());

    private final Setting<BlockType> blockType = sgGeneral.add(new EnumSetting.Builder<BlockType>()
            .name("block-type")
            .description("Which block to place.")
            .defaultValue(BlockType.Button)
            .build());

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
            .name("smart-range")
            .description("Enemy range to trigger placing.")
            .defaultValue(3)
            .min(1)
            .sliderRange(1, 7)
            .build());

    private final Setting<Boolean> silentSwap = sgGeneral.add(new BoolSetting.Builder()
            .name("silent-inventory-swap")
            .description("Temporarily moves the item to hotbar slot.")
            .defaultValue(true)
            .build());

    private final Setting<Integer> hotbarSlotSetting = sgGeneral.add(new IntSetting.Builder()
            .name("hotbar-slot")
            .description("Which hotbar slot (0-8) to use for swapping.")
            .defaultValue(0)
            .min(0)
            .max(8)
            .sliderMax(8)
            .build());

    private final Setting<Integer> returnDelay = sgGeneral.add(new IntSetting.Builder()
            .name("return-delay")
            .description("Delay before returning item to inventory (in ticks).")
            .defaultValue(40)
            .min(1)
            .sliderMax(200)
            .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
            .name("rotate")
            .description("Rotate toward blocks when placing.")
            .defaultValue(true)
            .build());

    private int returnTimer = -1;
    private int originalSlot = -1;
    private boolean waitingToReturn = false;
    private int cooldown = 0;

    public ConcreteDefense() {
        super(Addon.CATEGORY, "concrete-defense",
                "Places a button or web under yourself to break falling concrete.");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("ConcreteDefense", true);
        returnTimer = -1;
        originalSlot = -1;
        waitingToReturn = false;
        cooldown = 0;
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("ConcreteDefense", false);
        if (waitingToReturn && originalSlot != -1) {
            int hotbarSlot = hotbarSlotSetting.get();
            InvUtils.move().from(hotbarSlot).to(originalSlot);
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null)
            return;

        if (waitingToReturn) {
            if (--returnTimer <= 0) {
                int hotbarSlot = hotbarSlotSetting.get();
                InvUtils.move().from(hotbarSlot).to(originalSlot);
                waitingToReturn = false;
            }
        }

        if (cooldown > 0)
            cooldown--;

        if (TargetUtils.getPlayerTarget(range.get(), SortPriority.LowestDistance) != null) {
            if (mode.get() == Mode.Smart) {
                if (isConcreteAbove())
                    tryPlace();
            } else {
                tryPlace();
            }
        }
    }

    private void tryPlace() {
        BlockPos currentPos = mc.player.getBlockPos();
        // Check if already protected
        Block currentBlock = mc.world.getBlockState(currentPos).getBlock();
        if (isProtectionBlock(currentBlock))
            return;

        // Determine what item to look for
        Predicate<ItemStack> itemPredicate = getItemPredicate();

        FindItemResult itemResult = InvUtils.findInHotbar(itemPredicate);

        originalSlot = -1;
        boolean swapped = false;

        if (!itemResult.found() && silentSwap.get()) {
            FindItemResult invItem = InvUtils.find(itemPredicate);
            if (invItem.found() && invItem.slot() >= 9) {
                int hotbarSlot = hotbarSlotSetting.get();
                originalSlot = invItem.slot();

                InvUtils.move().from(invItem.slot()).to(hotbarSlot);
                swapped = true;

                itemResult = InvUtils.findInHotbar(itemPredicate);
            }
        }

        if (!itemResult.found()) {
            if (cooldown == 0) {
                warning("No required item (Button/Web) in hotbar or inventory.");
                cooldown = 40; // 2 seconds
            }
            return;
        }

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(currentPos.toCenterPos()), Rotations.getPitch(currentPos.toCenterPos()));
        }

        BlockUtils.place(currentPos, itemResult, rotate.get(), 0);

        if (swapped && originalSlot != -1) {
            returnTimer = returnDelay.get();
            waitingToReturn = true;
        }
    }

    private Predicate<ItemStack> getItemPredicate() {
        return stack -> {
            Item item = stack.getItem();
            if (blockType.get() == BlockType.Web) {
                return item == Items.COBWEB;
            } else {
                return Block.getBlockFromItem(item) instanceof ButtonBlock;
            }
        };
    }

    private boolean isProtectionBlock(Block block) {
        if (blockType.get() == BlockType.Web) {
            return block == Blocks.COBWEB;
        }
        // Button or similar non-collision block that breaks falling blocks
        return block instanceof ButtonBlock || block instanceof AbstractTorchBlock || block == Blocks.COBWEB;
    }

    private boolean isConcreteAbove() {
        BlockPos base = mc.player.getBlockPos();

        // Check blocks above (physically placed)
        for (int i = 1; i <= 3; i++) {
            if (mc.world.getBlockState(base.up(i)).getBlock() instanceof ConcretePowderBlock)
                return true;
        }

        // Check falling entities
        Box box = new Box(
                mc.player.getX() - 0.5, mc.player.getY() + 1, mc.player.getZ() - 0.5,
                mc.player.getX() + 0.5, mc.player.getY() + 4, mc.player.getZ() + 0.5);

        for (Entity entity : mc.world.getOtherEntities(null, box)) {
            if (entity instanceof FallingBlockEntity falling) {
                if (falling.getBlockState().getBlock() instanceof ConcretePowderBlock)
                    return true;
            }
        }

        return false;
    }

    public enum Mode {
        Strict,
        Smart
    }

    public enum BlockType {
        Button,
        Web
    }
}
