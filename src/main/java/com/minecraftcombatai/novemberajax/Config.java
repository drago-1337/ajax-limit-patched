package com.minecraftcombatai.novemberajax;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** config/november-ajax.properties: preferences, key bindings and the practice-world ledger. Nothing secret. */
final class Config {
    boolean mobs = true;          // fight hostile mobs when no player is near
    boolean hud = true;
    boolean smoothCamera = true;  // while AJAX plays, draw the view gliding between its turns (CameraSmooth)
    /** May he also play in a LAN world and on a remote server? */
    boolean multiplayer = true;
    /** May he fight real players? He always may fight this mod's own practice bots. */
    boolean fightPlayers = true;
    /** The mod's key bindings by name ("key.keyboard.r"), kept with the other settings. */
    final java.util.Map<String, String> keys = new java.util.LinkedHashMap<>();
    /**
     * Practice worlds this mod created and has not deleted yet (folder names). Written before a
     * world is created, cleared once it is deleted, so a crash or a killed game is cleaned up at
     * the next start ({@link PracticeWorld#sweep}).
     */
    final java.util.Set<String> practiceWorlds = new java.util.LinkedHashSet<>();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("november-ajax.properties");
    }

    static Config load() {
        Config c = new Config();
        Path p = path();
        if (!Files.exists(p)) return c;
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (IOException e) {
            return c;
        }
        c.mobs = Boolean.parseBoolean(props.getProperty("mobs", "true"));
        c.hud = Boolean.parseBoolean(props.getProperty("hud", "true"));
        c.smoothCamera = Boolean.parseBoolean(props.getProperty("smooth_camera", "true"));
        c.multiplayer = Boolean.parseBoolean(props.getProperty("multiplayer", "true"));
        c.fightPlayers = Boolean.parseBoolean(props.getProperty("fight_players", "true"));
        for (String w : props.getProperty("practice_worlds", "").split(","))
            if (!w.isBlank()) c.practiceWorlds.add(w.trim());
        for (String name : props.stringPropertyNames())
            if (name.startsWith("key.")) c.keys.put(name.substring(4), props.getProperty(name).trim());
        return c;
    }

    void save() {
        Properties props = new Properties();
        props.setProperty("mobs", Boolean.toString(mobs));
        props.setProperty("hud", Boolean.toString(hud));
        props.setProperty("smooth_camera", Boolean.toString(smoothCamera));
        props.setProperty("multiplayer", Boolean.toString(multiplayer));
        props.setProperty("fight_players", Boolean.toString(fightPlayers));
        props.setProperty("practice_worlds", String.join(",", practiceWorlds));
        keys.forEach((action, key) -> props.setProperty("key." + action, key));
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                props.store(w, "November AJAX");
            }
        } catch (IOException e) {
            AjaxMod.LOG.warn("could not save {}: {}", p, e.toString());
        }
    }
}

