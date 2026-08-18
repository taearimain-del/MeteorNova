package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

public class MiddleClickFriend extends Module {

    public MiddleClickFriend() {
        super(Addon.CATEGORY, "middle-click-friend", "Add/Remove friends by middle clicking them.");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("MiddleClickFriend", true);
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("MiddleClickFriend", false);
    }

    @EventHandler
    private void onMouseButton(MouseClickEvent event) {
        if (event.action == KeyAction.Press && event.button() == GLFW.GLFW_MOUSE_BUTTON_MIDDLE
                && mc.currentScreen == null) {
            Entity target = mc.targetedEntity;

            if (target instanceof PlayerEntity player) {
                String name = player.getName().getString();

                if (Friends.get().isFriend(player)) {
                    Friends.get().remove(Friends.get().get(player));
                    ChatUtils.info(Formatting.RED + "Removed " + name + " from friends.");
                } else {
                    Friends.get().add(new Friend(name));
                    ChatUtils.info(Formatting.GREEN + "Added " + name + " to friends.");
                }
            }
        }
    }
}
