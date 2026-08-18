package com.example.addon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class NovaChatUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public static void sendToggleMsg(String moduleName, boolean on) {
        if (mc.inGameHud == null || mc.inGameHud.getChatHud() == null)
            return;

        MutableText text = Text.literal("[Nova] ").formatted(Formatting.LIGHT_PURPLE);
        if (on) {
            text.append(Text.literal("[+] ").formatted(Formatting.GREEN));
        } else {
            text.append(Text.literal("[-] ").formatted(Formatting.RED));
        }
        text.append(Text.literal(moduleName).formatted(Formatting.WHITE));

        mc.inGameHud.getChatHud().addMessage(text);
    }

    public static void sendInfoMsg(String moduleName, String message) {
        if (mc.inGameHud == null || mc.inGameHud.getChatHud() == null)
            return;

        MutableText text = Text.literal("[Nova] ").formatted(Formatting.LIGHT_PURPLE);
        text.append(Text.literal(moduleName + " ").formatted(Formatting.WHITE));
        text.append(Text.literal(message));

        mc.inGameHud.getChatHud().addMessage(text);
    }
}
