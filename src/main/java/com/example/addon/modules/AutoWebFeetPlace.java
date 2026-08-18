package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import com.example.addon.utils.NovaKeybindUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

public class AutoWebFeetPlace extends Module {
    public enum PlaceItem {
        Cobweb, Ladder, Button
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTarget = settings.createGroup("Targeting");
    private final SettingGroup sgPlace = settings.createGroup("Placement");
    private final SettingGroup sgInv = settings.createGroup("Inventory");

    // General
    private final Setting<PlaceItem> itemMode = sgGeneral.add(new EnumSetting.Builder<PlaceItem>()
            .name("place-item").description("What to place into the broken surround slot.")
            .defaultValue(PlaceItem.Cobweb).build());

    private final Setting<Keybind> resetKey = sgGeneral.add(new KeybindSetting.Builder()
            .name("reset-key").description("Key to toggle SpeedMine (Reset InstaMine).")
            .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_4)).build());

    // Targeting
    private final Setting<Double> range = sgTarget.add(new DoubleSetting.Builder()
            .name("range").description("Target acquisition range.")
            .defaultValue(6.5).min(1.0).sliderMin(2.0).sliderMax(12.0).build());

    private final Setting<Boolean> ignoreFriends = sgTarget.add(new BoolSetting.Builder()
            .name("ignore-friends").description("Do not target players on your friends list.")
            .defaultValue(true).build());

    private final Setting<Boolean> ignoreNaked = sgTarget.add(new BoolSetting.Builder()
            .name("ignore-naked").description("Ignore players with no armor equipped.")
            .defaultValue(false).build());

    // Placement
    private final Setting<Double> attemptsPerSecond = sgPlace.add(new DoubleSetting.Builder()
            .name("APS").description("Placement attempts per second (spam rate).")
            .defaultValue(6.0).min(0.5).sliderMin(0.5).sliderMax(20.0).build());

    private final Setting<Boolean> rotate = sgPlace.add(new BoolSetting.Builder()
            .name("rotate").description("Rotate to the block being placed.")
            .defaultValue(true).build());

    private final Setting<Boolean> silentSwap = sgPlace.add(new BoolSetting.Builder()
            .name("silent-swap").description("Silently use item from hotbar without changing your selected slot.")
            .defaultValue(true).build());

    private final Setting<Boolean> requireAir = sgPlace.add(new BoolSetting.Builder()
            .name("only-when-air").description("Only attempt when the surround slot is placeable (air/replaceable).")
            .defaultValue(true).build());

    private final Setting<Boolean> preferClosestSide = sgPlace.add(new BoolSetting.Builder()
            .name("prefer-closest-hole").description("If multiple sides broke, prefer the one closest to you.")
            .defaultValue(true).build());

    private final Setting<Boolean> pauseWhileEating = sgPlace.add(new BoolSetting.Builder()
            .name("pause-while-eating")
            .description("Temporarily pauses while you're eating food.")
            .defaultValue(true)
            .build());

    // Inventory
    private final Setting<Boolean> grabFromInventory = sgInv.add(new BoolSetting.Builder()
            .name("grab-from-inventory")
            .description("If not in hotbar, move a stack from main inventory into the chosen hotbar slot.")
            .defaultValue(true).build());

    private final Setting<Integer> grabHotbarSlot = sgInv.add(new IntSetting.Builder()
            .name("hotbar-slot").description("Hotbar slot (0-8) to receive the item when grabbing from inventory.")
            .defaultValue(8).min(0).max(8).sliderMin(0).sliderMax(8).build());

    private final Setting<Boolean> preSwapOnEnable = sgInv.add(new BoolSetting.Builder()
            .name("pre-swap-on-enable").description("On enable, move the needed item to the configured hotbar slot.")
            .defaultValue(true).build());

    private final Setting<Boolean> stashOldHotbar = sgInv.add(new BoolSetting.Builder()
            .name("stash-old-hotbar")
            .description("If the hotbar slot is occupied, stash its stack in the first empty main slot.")
            .defaultValue(true).build());

    private final Setting<Boolean> returnOnDisable = sgInv.add(new BoolSetting.Builder()
            .name("return-on-disable").description("On disable, return items to their original slots if possible.")
            .defaultValue(true).build());

    // Timing
    private long lastAttemptNs = 0;

    // Surround lock state
    private BlockPos lockedFeetPos = null;
    private final Set<BlockPos> lockedSurround = new HashSet<>();
    private boolean surroundLocked = false;

    // Pre-swap bookkeeping
    private boolean didPreSwap = false;
    private int preSwapSourceMainSlot = -1;
    private int preSwapHotbarSlot = -1;
    private int stashedHotbarToMainSlot = -1;

    public AutoWebFeetPlace() {
        super(Addon.CATEGORY, "auto-web-feet-place",
                "Places cobweb, ladder, or any button after enemies surround/feet-place is broken.");
    }

    @Override
    public void onActivate() {
        lastAttemptNs = 0;
        clearLock();
        performPreSwapIfNeeded();
    }

    @Override
    public void onDeactivate() {
        if (returnOnDisable.get())
            undoPreSwapIfNeeded();
        clearLock();
    }

    private void clearLock() {
        surroundLocked = false;
        lockedFeetPos = null;
        lockedSurround.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null)
            return;

        if (pauseWhileEating.get() && mc.player.isUsingItem() && isFood(mc.player.getActiveItem())) {
            return;
        }

        final PlayerEntity target = findNearestTarget();
        if (target == null) {
            clearLock();
            return;
        }

        double aps = Math.max(0.5, attemptsPerSecond.get());
        long intervalNs = (long) (1_000_000_000L / aps);
        long now = System.nanoTime();
        if (now - lastAttemptNs < intervalNs)
            return;
        lastAttemptNs = now;

        BlockPos feet = target.getBlockPos();
        if (!feet.equals(lockedFeetPos))
            clearLock();

        if (!surroundLocked) {
            BlockPos[] four = surroundPositions(feet);
            if (allSolid(four)) {
                for (BlockPos p : four)
                    lockedSurround.add(p.toImmutable());
                lockedFeetPos = feet.toImmutable();
                surroundLocked = true;
            } else
                return;
        }

        BlockPos broken = pickBrokenSlot();
        if (broken == null)
            return;
        if (requireAir.get() && !BlockUtils.canPlace(broken))
            return;

        FindItemResult fir = switch (itemMode.get()) {
            case Cobweb -> ensureInHotbarSingle(Items.COBWEB);
            case Ladder -> ensureInHotbarSingle(Items.LADDER);
            case Button -> ensureInHotbarByTag(ItemTags.BUTTONS);
        };
        if (fir == null)
            return;

        placeAt(broken, fir);
    }

    private boolean isFood(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.get(DataComponentTypes.FOOD) != null;
    }

    private BlockPos pickBrokenSlot() {
        BlockPos chosen = null;
        double bestDist = Double.MAX_VALUE;

        for (BlockPos p : lockedSurround) {
            if (!BlockUtils.canPlace(p))
                continue;
            if (!preferClosestSide.get())
                return p;

            double d = mc.player.squaredDistanceTo(Vec3d.ofCenter(p));
            if (d < bestDist) {
                bestDist = d;
                chosen = p;
            }
        }
        return chosen;
    }

    private BlockPos[] surroundPositions(BlockPos feet) {
        return new BlockPos[] { feet.north(), feet.south(), feet.east(), feet.west() };
    }

    private boolean allSolid(BlockPos[] positions) {
        for (BlockPos p : positions)
            if (!isSolid(p))
                return false;
        return true;
    }

    private boolean isSolid(BlockPos pos) {
        var s = mc.world.getBlockState(pos);
        if (s.isAir())
            return false;
        return !s.getCollisionShape(mc.world, pos).isEmpty();
    }

    private PlayerEntity findNearestTarget() {
        PlayerEntity best = null;
        double bestDist = Double.MAX_VALUE;

        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player)
                continue;
            if (!p.isAlive())
                continue;
            if (ignoreFriends.get() && Friends.get().isFriend(p))
                continue;
            if (ignoreNaked.get() && isNaked(p))
                continue;

            double d = mc.player.distanceTo(p);
            if (d <= range.get() && d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    private boolean isNaked(PlayerEntity p) {
        return isEmpty(p.getEquippedStack(EquipmentSlot.HEAD))
                && isEmpty(p.getEquippedStack(EquipmentSlot.CHEST))
                && isEmpty(p.getEquippedStack(EquipmentSlot.LEGS))
                && isEmpty(p.getEquippedStack(EquipmentSlot.FEET));
    }

    private boolean isEmpty(ItemStack s) {
        return s == null || s.isEmpty();
    }

    private void placeAt(BlockPos pos, FindItemResult fir) {
        // Reset SpeedMine Logic
        NovaKeybindUtils.pressKey(resetKey.get(), 2);

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), 5,
                    () -> BlockUtils.place(pos, fir, true, 50, true, true, silentSwap.get()));
        } else {
            BlockUtils.place(pos, fir, false, 50, true, true, silentSwap.get());
        }
    }

    private FindItemResult ensureInHotbarSingle(Item item) {
        FindItemResult hb = InvUtils.findInHotbar(item);
        if (hb.found())
            return hb;

        if (grabFromInventory.get()) {
            int src = findMainSlot(item);
            if (src != -1) {
                int slot = clampHotbar(grabHotbarSlot.get());
                InvUtils.move().from(src).toHotbar(slot);
                FindItemResult recheck = InvUtils.findInHotbar(item);
                if (recheck.found())
                    return recheck;
            }
        }
        return null;
    }

    private FindItemResult ensureInHotbarByTag(TagKey<Item> tag) {
        for (int i = 0; i <= 8; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.isIn(tag)) {
                FindItemResult hb = InvUtils.findInHotbar(s.getItem());
                if (hb.found())
                    return hb;
            }
        }

        if (!grabFromInventory.get())
            return null;

        int src = findMainSlotByTag(tag);
        if (src != -1) {
            Item movedItem = mc.player.getInventory().getStack(src).getItem();
            int slot = clampHotbar(grabHotbarSlot.get());
            InvUtils.move().from(src).toHotbar(slot);
            FindItemResult recheck = InvUtils.findInHotbar(movedItem);
            if (recheck.found())
                return recheck;

            for (int i = 0; i <= 8; i++) {
                ItemStack s = mc.player.getInventory().getStack(i);
                if (!s.isEmpty() && s.isIn(tag)) {
                    FindItemResult hb = InvUtils.findInHotbar(s.getItem());
                    if (hb.found())
                        return hb;
                }
            }
        }
        return null;
    }

    private void performPreSwapIfNeeded() {
        if (!preSwapOnEnable.get() || mc.player == null)
            return;

        if (itemMode.get() == PlaceItem.Button) {
            if (hotbarHasAnyByTag(ItemTags.BUTTONS)) {
                didPreSwap = false;
                return;
            }
            int src = findMainSlotByTag(ItemTags.BUTTONS);
            if (src == -1) {
                didPreSwap = false;
                return;
            }
            doPreSwapFrom(src);
            return;
        }

        Item need = (itemMode.get() == PlaceItem.Cobweb) ? Items.COBWEB : Items.LADDER;
        if (InvUtils.findInHotbar(need).found()) {
            didPreSwap = false;
            return;
        }
        int src = findMainSlot(need);
        if (src == -1) {
            didPreSwap = false;
            return;
        }
        doPreSwapFrom(src);
    }

    private void doPreSwapFrom(int srcSlot) {
        int targetHotbar = clampHotbar(grabHotbarSlot.get());
        ItemStack existing = mc.player.getInventory().getStack(targetHotbar);

        stashedHotbarToMainSlot = -1;
        if (!existing.isEmpty() && stashOldHotbar.get()) {
            int emptyMain = firstEmptyMainSlot();
            if (emptyMain != -1) {
                InvUtils.move().from(targetHotbar).to(emptyMain);
                stashedHotbarToMainSlot = emptyMain;
            }
        }

        InvUtils.move().from(srcSlot).toHotbar(targetHotbar);

        didPreSwap = true;
        preSwapSourceMainSlot = srcSlot;
        preSwapHotbarSlot = targetHotbar;
    }

    private void undoPreSwapIfNeeded() {
        if (!didPreSwap || mc.player == null)
            return;

        ItemStack inHotbar = mc.player.getInventory().getStack(preSwapHotbarSlot);
        if (!inHotbar.isEmpty()) {
            int dest = preSwapSourceMainSlot;
            if (dest < 9 || dest > 35 || !mc.player.getInventory().getStack(dest).isEmpty())
                dest = firstEmptyMainSlot();
            if (dest != -1)
                InvUtils.move().from(preSwapHotbarSlot).to(dest);
        }

        if (stashedHotbarToMainSlot != -1) {
            ItemStack stashed = mc.player.getInventory().getStack(stashedHotbarToMainSlot);
            if (!stashed.isEmpty()) {
                InvUtils.move().from(stashedHotbarToMainSlot).to(preSwapHotbarSlot);
            }
        }

        didPreSwap = false;
        preSwapSourceMainSlot = -1;
        preSwapHotbarSlot = -1;
        stashedHotbarToMainSlot = -1;
    }

    private int clampHotbar(int v) {
        return Math.max(0, Math.min(8, v));
    }

    private int firstEmptyMainSlot() {
        for (int i = 9; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty())
                return i;
        }
        return -1;
    }

    private boolean hotbarHasAnyByTag(TagKey<Item> tag) {
        for (int i = 0; i <= 8; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.isIn(tag))
                return true;
        }
        return false;
    }

    private int findMainSlot(Item it) {
        for (int i = 9; i <= 35; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == it)
                return i;
        }
        return -1;
    }

    private int findMainSlotByTag(TagKey<Item> tag) {
        for (int i = 9; i <= 35; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.isIn(tag))
                return i;
        }
        return -1;
    }
}
