package com.tunic.parallel.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.tunic.parallel.client.screen.SettingsScreen;

/** Adds the settings button to the mod's entry in Mod Menu. Only loaded when Mod Menu is installed. */
public class ParallelModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }
}
