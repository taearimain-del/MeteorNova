package com.example.addon.modules;

import com.example.addon.Addon;
import com.example.addon.utils.NovaChatUtils;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import net.minecraft.text.Text;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoAccept extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Mode {
        Friends,
        Whitelist,
        Blacklist,
        All
    }

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
            .name("mode")
            .description("Which players to accept requests from.")
            .defaultValue(Mode.Friends)
            .build());

    private final Setting<List<String>> whitelist = sgGeneral.add(new StringListSetting.Builder()
            .name("whitelist")
            .description("List of players to accept.")
            .visible(() -> mode.get() == Mode.Whitelist)
            .build());

    private final Setting<List<String>> blacklist = sgGeneral.add(new StringListSetting.Builder()
            .name("blacklist")
            .description("List of players to ignore.")
            .visible(() -> mode.get() == Mode.Blacklist)
            .build());

    private final Setting<String> acceptCommand = sgGeneral.add(new StringSetting.Builder()
            .name("accept-command")
            .description("Command to accept with. The requester's name is appended.")
            .defaultValue("/tpy")
            .build());

    // The requester's name has to lead the line - optionally behind bracketed
    // server prefixes - so that a player simply saying "<someone> Steve has
    // requested to teleport" in chat cannot trigger an accept.
    private static final Pattern LEADING_NAME =
            Pattern.compile("^(?:\\[[^\\]]*\\]\\s*)*([A-Za-z0-9_]{3,16})\\b");

    private static final String[] REQUEST_PHRASES = {
            "has requested to teleport",
            "to teleport to you",
            "has invited you to join them"
    };

    public AutoAccept() {
        super(Addon.CATEGORY, "auto-accept", "Automatically accepts teleport requests.");
    }

    @Override
    public void onActivate() {
        NovaChatUtils.sendToggleMsg("AutoAccept", true);
    }

    @Override
    public void onDeactivate() {
        NovaChatUtils.sendToggleMsg("AutoAccept", false);
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        Text message = event.getMessage();
        String text = message.getString();

        String playerName = extractPlayerName(text);
        if (playerName == null || !shouldAccept(playerName))
            return;

        String command = acceptCommand.get().trim();
        if (command.isEmpty())
            return;

        info("Accepting teleport request from " + playerName);
        ChatUtils.sendPlayerMsg(command + " " + playerName);
    }

    private boolean shouldAccept(String playerName) {
        switch (mode.get()) {
            case Friends:
                for (meteordevelopment.meteorclient.systems.friends.Friend friend : Friends.get()) {
                    if (friend.name.equalsIgnoreCase(playerName))
                        return true;
                }
                return false;
            case Whitelist:
                for (String name : whitelist.get()) {
                    if (name.equalsIgnoreCase(playerName))
                        return true;
                }
                return false;
            case Blacklist:
                for (String name : blacklist.get()) {
                    if (name.equalsIgnoreCase(playerName))
                        return false;
                }
                return true;
            case All:
                return true;
            default:
                return false;
        }
    }

    /** Returns the requester's name, or null if this is not a request line. */
    private String extractPlayerName(String text) {
        Matcher matcher = LEADING_NAME.matcher(text);
        if (!matcher.find())
            return null;

        // The phrase has to come after the name, not before it.
        String rest = text.substring(matcher.end());
        for (String phrase : REQUEST_PHRASES) {
            if (rest.contains(phrase))
                return matcher.group(1);
        }
        return null;
    }
}
