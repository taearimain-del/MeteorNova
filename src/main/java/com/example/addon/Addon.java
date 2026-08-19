package com.example.addon;

import com.example.addon.commands.CommandExample;
import com.example.addon.modules.*;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class Addon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Nova");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Meteor Nova Addon");

        // Modules
        Modules.get().add(new AutoAccept());
        Modules.get().add(new RespawnHome());
        Modules.get().add(new ConcreteWeb());
        Modules.get().add(new InventoryCleaner());
        Modules.get().add(new MiddleClickFriend());
        Modules.get().add(new AutoPearlStasis());
        Modules.get().add(new AutoPearlThrow());
        Modules.get().add(new AutoConcrete());
        Modules.get().add(new AutoMinePlus());
        Modules.get().add(new AutoTNTplus());
        Modules.get().add(new TrapMiner());
        Modules.get().add(new ConcreteDefense());
        Modules.get().add(new AntiPhase());
        Modules.get().add(new NoRenderPlus());
        Modules.get().add(new BlockESPPlus());

        // New Module (replacing AntiFeetPlace)
        Modules.get().add(new AutoWebFeetPlace());

        // Commands
        Commands.add(new CommandExample());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }
}
