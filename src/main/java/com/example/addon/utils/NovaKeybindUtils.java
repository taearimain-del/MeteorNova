package com.example.addon.utils;

import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

/**
 * Feeds synthetic key events to Minecraft's Keyboard so that everything
 * listening to it reacts: Meteor's own KeyboardMixin, and just as importantly
 * mods outside Meteor's module list, such as the external client whose Web
 * Aura ConcreteWeb drives.
 *
 * Keyboard#onKey is private and takes a KeyInput as of 1.21.11 - the addon's
 * access widener opens it back up, and the argument order it wants is
 * (window, action, input).
 */
public class NovaKeybindUtils {
    /** Sends a press/release pair for the bound key, once per press. */
    public static void pressKey(Keybind keybind, int presses) {
        if (keybind == null || !keybind.isSet() || !keybind.isKey())
            return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.keyboard == null || mc.getWindow() == null)
            return;

        long window = mc.getWindow().getHandle();
        KeyInput input = new KeyInput(keybind.getValue(), 0, 0);

        for (int i = 0; i < presses; i++) {
            mc.keyboard.onKey(window, GLFW.GLFW_PRESS, input);
            mc.keyboard.onKey(window, GLFW.GLFW_RELEASE, input);
        }
    }
}
