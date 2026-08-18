package com.example.addon.utils;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for driving other Meteor modules from a keybind setting.
 *
 * This used to be done by feeding synthetic GLFW events to Keyboard#onKey so
 * that Meteor would pick them up and toggle whatever was bound to the key.
 * That method is private and takes a KeyInput as of 1.21.11, so the bound
 * modules are toggled directly instead.
 */
public class NovaKeybindUtils {
    /** Toggles every module bound to the key, once per press. */
    public static void pressBoundModules(Keybind keybind, int presses) {
        if (keybind == null || !keybind.isSet() || !keybind.isKey())
            return;

        // Collect first so toggling can't disturb the iteration.
        List<Module> bound = new ArrayList<>();
        for (Module module : Modules.get().getAll()) {
            if (module.keybind != null && module.keybind.matches(true, keybind.getValue(), 0))
                bound.add(module);
        }

        for (int i = 0; i < presses; i++) {
            for (Module module : bound) module.toggle();
        }
    }
}
