package com.minecraftcombatai.novemberajax;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The practice server: a temporary singleplayer world that holds the five {@link Arenas}, made
 * for players who have no world of their own to practise in. It is this client's own integrated
 * server, like any singleplayer world, so the singleplayer-only rule ({@link Gate}) holds.
 *
 * It never piles up in the player's world list:
 *   - every practice world is a folder named "november-ajax-practice-<timestamp>" with a marker file in it,
 *     and its name goes into config/november-ajax.properties BEFORE the world is created;
 *   - leaving the world deletes it as soon as its server has shut down;
 *   - if the game crashed or was killed, the next start finds the leftovers (marker or ledger)
 *     and deletes them before the title screen is used;
 *   - only folders with our prefix AND (our marker OR our ledger entry) are ever touched, and never
 *     the world that is running.
 */
final class PracticeWorld {
    private PracticeWorld() {}

    static final String PREFIX = "november-ajax-practice-", MARKER = "november_ajax_practice.marker",
            MARKER_HEAD = "november_ajax practice world: safe to delete, it is recreated on demand";

    // client thread
    private static Arenas pendingCreate;               // leave the current world, then create
    private static boolean swept;
    private static int deleteTries;
    private static long nextDeleteMs;

    // shared
    private static volatile String pendingDelete;      // a practice world whose server stopped
    static volatile Arenas arriving = Arenas.NEON_GRID; // where the next player to join is sent
    static volatile boolean active;                    // the running integrated server is a practice world
    static volatile Arenas current;                    // the arena the host is in (for the panel)
    static volatile String status = "";

    // server thread
    private static MinecraftServer server;
    private static final Set<UUID> arrived = new HashSet<>();

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(PracticeWorld::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            if (s == server) {
                pendingDelete = folderOf(s);
                server = null;
                active = false;
                current = null;
                arrived.clear();
            }
        });
    }

    // ---------------------------------------------------------------- client ----

    /** Go to an arena: teleport if already in the practice world, otherwise leave and create a fresh one. */
    static void enter(Minecraft mc, Arenas a) {
        IntegratedServer s = mc.getSingleplayerServer();
        if (active && s != null && mc.player != null) {
            UUID who = mc.player.getUUID();
            s.execute(() -> {
                ServerPlayer p = s.getPlayerList().getPlayer(who);
                if (p == null) return;
                Practice.clear();
                a.send(s, p);
                Practice.kit(p);
                current = a;
            });
            return;
        }
        arriving = a;
        if (mc.level != null) {                         // leave the current world first
            pendingCreate = a;
            mc.disconnectFromWorld(Component.literal("Off to the AJAX practice arenas"));
            return;
        }
        create(mc, a);
    }

    private static void create(Minecraft mc, Arenas a) {
        sweep(mc);
        AjaxMod m = AjaxMod.get();
        String id = PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        m.config.practiceWorlds.add(id);                // in the ledger before anything is on disk
        m.config.save();
        arriving = a;
        status = "Building the arenas...";
        LevelSettings settings = new LevelSettings("AJAX Practice Arenas (temporary)", GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.EASY, false, false), true, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(id, settings, new WorldOptions(0x5EED_A12EL, false, false), registries -> {
            var biomes = registries.lookupOrThrow(Registries.BIOME);
            FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()),
                    biomes.getOrThrow(Biomes.THE_VOID), List.of());
            flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.AIR));     // a void world: the arenas float
            flat.updateLayers();
            return WorldPresets.createFlatWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
        }, mc.screen);
    }

    /** Every client tick: finish a pending leave-then-create, delete a stopped practice world. */
    static void clientTick(Minecraft mc) {
        if (mc.level != null || mc.getSingleplayerServer() != null) return;
        if (pendingDelete != null && System.currentTimeMillis() >= nextDeleteMs) {
            String id = pendingDelete;
            if (delete(mc, id)) {
                pendingDelete = null;
                deleteTries = 0;
            } else if (++deleteTries >= 10) {               // still locked: the next start's sweep gets it
                AjaxMod.LOG.warn("practice world {} could not be deleted yet; it will be cleaned up at the next start", id);
                pendingDelete = null;
                deleteTries = 0;
            } else nextDeleteMs = System.currentTimeMillis() + 1000L * deleteTries;
        }
        if (pendingCreate != null && pendingDelete == null) {
            Arenas a = pendingCreate;
            pendingCreate = null;
            create(mc, a);
        }
    }

    /** Once per game start (title screen), and before every new practice world: delete leftovers. */
    static void sweepOnce(Minecraft mc) {
        if (swept) return;
        swept = true;
        int n = sweep(mc);
        if (n > 0) AjaxMod.get().say("Cleaned up " + n + " practice world" + (n == 1 ? "" : "s") + " left from an earlier session.");
    }

    static int sweep(Minecraft mc) {
        AjaxMod m = AjaxMod.get();
        LevelStorageSource source = mc.getLevelSource();
        String running = mc.getSingleplayerServer() != null ? folderOf(mc.getSingleplayerServer()) : null;
        List<String> found = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(source.getBaseDir())) {
            dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(n -> n.startsWith(PREFIX)).forEach(found::add);
        } catch (IOException e) {
            return 0;
        }
        int deleted = 0;
        for (String id : found) {
            if (id.equals(running)) continue;
            boolean ours = m.config.practiceWorlds.contains(id) || isMarked(source.getBaseDir().resolve(id));
            if (ours && delete(mc, id)) deleted++;
        }
        // forget ledger entries whose folder is already gone
        if (m.config.practiceWorlds.removeIf(id -> !id.equals(running) && !Files.isDirectory(source.getBaseDir().resolve(id))))
            m.config.save();
        return deleted;
    }

    private static boolean isMarked(Path dir) {
        try {
            Path f = dir.resolve(MARKER);
            return Files.isRegularFile(f) && Files.readString(f, StandardCharsets.UTF_8).startsWith(MARKER_HEAD);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean delete(Minecraft mc, String id) {
        if (!id.startsWith(PREFIX) || id.contains("/") || id.contains("\\") || id.contains("..")) return false;
        LevelStorageSource source = mc.getLevelSource();
        if (!source.levelExists(id)) {
            forget(id);
            return true;
        }
        try (LevelStorageSource.LevelStorageAccess access = source.createAccess(id)) {
            access.deleteLevel();
        } catch (Exception e) {
            AjaxMod.LOG.info("practice world {} not deleted yet: {}", id, e.toString());
            return false;
        }
        forget(id);
        AjaxMod.LOG.info("deleted practice world {}", id);
        return true;
    }

    private static void forget(String id) {
        AjaxMod m = AjaxMod.get();
        if (m.config.practiceWorlds.remove(id)) m.config.save();
    }

    private static String folderOf(MinecraftServer s) {
        Path p = s.getWorldPath(LevelResource.LEVEL_DATA_FILE).toAbsolutePath().normalize().getParent();
        return p == null || p.getFileName() == null ? "" : p.getFileName().toString();
    }

    // ---------------------------------------------------------------- server ----

    private static void serverStarted(MinecraftServer s) {
        String id = folderOf(s);
        if (!id.startsWith(PREFIX)) return;
        Path dir = s.getWorldPath(LevelResource.LEVEL_DATA_FILE).toAbsolutePath().normalize().getParent();
        boolean fresh = !Files.exists(dir.resolve(MARKER));
        try {
            Files.writeString(dir.resolve(MARKER), MARKER_HEAD + "\ncreated " + LocalDateTime.now() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            AjaxMod.LOG.warn("could not mark practice world {}: {}", id, e.toString());
        }
        server = s;
        active = true;
        arrived.clear();
        for (String c : RULES) Arenas.cmd(s, c);
        if (fresh) Arenas.buildAll(s);
        double[] a = Arenas.NEON_GRID.padA();
        Arenas.cmd(s, String.format(Locale.ROOT, "setworldspawn %d %d %d", (int) Math.floor(a[0]), (int) a[1], (int) Math.floor(a[2])));
        status = "";
    }

    /** Server tick: send players who just joined into the chosen arena with the kit. */
    static void serverTick(MinecraftServer s) {
        if (s != server) return;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (p instanceof PracticeBot || arrived.contains(p.getUUID())) continue;
            if (p.tickCount < 5) continue;                // let the client finish loading in
            arrived.add(p.getUUID());
            Arenas a = arriving;
            a.send(s, p);
            Practice.kit(p);
            current = a;
        }
    }

    /** No mobs, no time or weather drift, nothing that grows, spreads, melts or piles up. */
    private static final String[] RULES = {
            "gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false",
            "gamerule spawn_monsters false", "gamerule spawn_phantoms false", "gamerule spawn_patrols false",
            "gamerule spawn_wandering_traders false", "gamerule spawn_wardens false", "gamerule random_tick_speed 0",
            "gamerule max_snow_accumulation_height 0", "gamerule fire_spread_radius_around_player 0", "gamerule mob_griefing false",
            "gamerule keep_inventory true", "gamerule immediate_respawn true", "gamerule respawn_radius 0",
            "gamerule send_command_feedback false", "gamerule show_advancement_messages false", "gamerule command_block_output false",
            "gamerule log_admin_commands false", "gamerule fall_damage false", "gamerule pvp true",
    };
}

