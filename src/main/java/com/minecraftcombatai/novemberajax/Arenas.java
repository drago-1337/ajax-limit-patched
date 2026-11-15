package com.minecraftcombatai.novemberajax;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;

import java.util.Locale;
import java.util.SplittableRandom;

/**
 * The five practice arenas, built block by block into the temporary practice world
 * ({@link PracticeWorld}). Each is a floating island in a void world: a flat, round fighting floor
 * (radius 20, flat: AJAX fights on flat ground) inside a low rim with invisible walls above it, so
 * nobody falls off, and a themed island around it. Server thread only.
 *
 *   NEON GRID         black glass floor with glowing grid lines, pylons, a halo of light, at midnight
 *   SAKURA DOJO       cherry-wood floor, blossom trees, a torii gate and lanterns, at sunset
 *   MAGMA COLOSSEUM   blackstone floor in a ring of lava, stands, fire pillars, crimson spores
 *   FROST PEAK        snow floor with a compass rose, ice spikes, spruce trees, falling snow
 *   SKY SANCTUM       quartz and gold temple floor, columns, four coloured beacon beams, clouds
 */
enum Arenas {
    NEON_GRID("NEON GRID", "Midnight. Glowing grid, neon pylons, a halo of light overhead.", 0xFF3DF5FF, 0xFFFF4DD8,
            "minecraft:the_end", 18000, false, "aqua"),
    SAKURA_DOJO("SAKURA DOJO", "Sunset. Cherry-wood floor under blossom trees and a torii gate.", 0xFFFF86C8, 0xFFFFD0E8,
            "minecraft:cherry_grove", 12600, false, "light_purple"),
    MAGMA_COLOSSEUM("MAGMA COLOSSEUM", "Blackstone ring in a moat of lava. Fire pillars, crimson spores.", 0xFFFF6A2A, 0xFFFFD43A,
            "minecraft:crimson_forest", 13600, false, "gold"),
    FROST_PEAK("FROST PEAK", "Snowfall. A compass-rose floor ringed by ice spikes and spruce.", 0xFF9FE6FF, 0xFFFFFFFF,
            "minecraft:snowy_plains", 1000, true, "white"),
    SKY_SANCTUM("SKY SANCTUM", "Dawn above the clouds. Quartz and gold, four beacon beams.", 0xFFFFE27A, 0xFF7DFFB8,
            "minecraft:the_void", 23300, false, "yellow");

    static final int FLOOR = 64, R = 20, SPACING = 400, PAD = 6;
    final String title, tagline, biome, textColour;
    final int colour, accent, time;
    final boolean snow;

    Arenas(String title, String tagline, int colour, int accent, String biome, int time, boolean snow, String textColour) {
        this.title = title;
        this.tagline = tagline;
        this.colour = colour;
        this.accent = accent;
        this.biome = biome;
        this.time = time;
        this.snow = snow;
        this.textColour = textColour;
    }

    int ox() {
        return ordinal() * SPACING;
    }

    int oz() {
        return 0;
    }

    private int pad() {
        return PAD;
    }

    /** Where you stand at the start of a round (facing north, towards the bot). */
    double[] padA() {
        return new double[]{ox() + 0.5, FLOOR + 1, oz() + pad() + 0.5, 180};
    }

    /** Where the practice bot starts (facing south, towards you). */
    double[] padB() {
        return new double[]{ox() + 0.5, FLOOR + 1, oz() - pad() + 0.5, 0};
    }

    /** The arenas the practice world builds. */
    static Arenas[] shown() {
        return values();
    }

    /** The arena whose island the position is on, or null. */
    static Arenas at(double x, double z) {
        for (Arenas a : values()) {
            double dx = x - a.ox(), dz = z - a.oz();
            if (dx * dx + dz * dz < 60 * 60) return a;
        }
        return null;
    }

    // ------------------------------------------------------------------ build ----

    static void buildAll(MinecraftServer server) {
        ServerLevel level = server.overworld();
        long t0 = System.nanoTime();
        cmd(server, "gamerule max_block_modifications 10000000");
        for (Arenas a : shown()) {
            B b = new B(level, a.ox(), a.oz(), new SplittableRandom(0x5EED + a.ordinal() * 7919L));
            switch (a) {
                case NEON_GRID -> neon(b);
                case SAKURA_DOJO -> sakura(b);
                case MAGMA_COLOSSEUM -> magma(b);
                case FROST_PEAK -> frost(b);
                case SKY_SANCTUM -> sky(b);
            }
            pads(b, a);
            walls(b);
            if (!a.biome.equals("minecraft:the_void")) {
                // fillbiome only touches loaded chunks ("That position is not loaded"): load the arena's first
                for (int cx = (a.ox() - 48) >> 4; cx <= (a.ox() + 48) >> 4; cx++)
                    for (int cz = (a.oz() - 48) >> 4; cz <= (a.oz() + 48) >> 4; cz++) level.getChunk(cx, cz);
                cmd(server, String.format(Locale.ROOT, "fillbiome %d %d %d %d %d %d %s", a.ox() - 48, FLOOR - 24, a.oz() - 48,
                        a.ox() + 48, FLOOR + 40, a.oz() + 48, a.biome));
            }
            // the arena's name, floating over the far side
            cmd(server, String.format(Locale.ROOT, "summon minecraft:text_display %.1f %d %.1f {text:{text:\"%s\",color:\"%s\",bold:true},"
                            + "billboard:\"vertical\",background:0,shadow:1b,brightness:{block:15,sky:15},"
                            + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[7f,7f,7f]}}",
                    a.ox() + 0.5, FLOOR + 13, a.oz() - 27.5, a.title, a.textColour));
            cmd(server, String.format(Locale.ROOT, "summon minecraft:text_display %.1f %d %.1f {text:{text:\"AJAX PRACTICE ARENA\",color:\"gray\"},"
                            + "billboard:\"vertical\",background:0,shadow:1b,brightness:{block:15,sky:15},"
                            + "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[2.5f,2.5f,2.5f]}}",
                    a.ox() + 0.5, FLOOR + 11, a.oz() - 27.5));
        }
        cmd(server, "gamerule max_block_modifications 32768");
        AjaxMod.LOG.info("practice arenas built in {} ms", (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Send a player into this arena: pad A, facing the bot's pad, the arena's sky and weather, full
     * health; you respawn here too. The spawn point and the title go through the API: the spawnpoint
     * and title commands take players by name only (a UUID there fails as "includes entities").
     */
    void send(MinecraftServer server, ServerPlayer p) {
        double[] a = padA();
        cmd(server, String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.0f 0", p.getUUID(), a[0], a[1], a[2], a[3]));
        p.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(p.level().dimension(),
                BlockPos.containing(a[0], a[1], a[2]), (float) a[3], 0f), true), false);
        cmd(server, "time set " + time);
        cmd(server, snow ? "weather rain" : "weather clear");
        ChatFormatting colour = ChatFormatting.getByName(textColour);
        p.connection.send(new ClientboundSetTitlesAnimationPacket(5, 50, 15));
        p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("AJAX PRACTICE ARENA").withStyle(ChatFormatting.GRAY)));
        p.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)
                .withStyle(colour == null ? ChatFormatting.WHITE : colour, ChatFormatting.BOLD)));
    }

    /** A line over the hotbar ("ROUND 2"). */
    static void actionbar(ServerPlayer p, String text, ChatFormatting colour) {
        p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(text).withStyle(colour, ChatFormatting.BOLD)));
    }

    /**
     * Run a command as the server, quietly. Build its text with String.format(Locale.ROOT, ...):
     * with the player's own locale a German, French, Polish... PC writes "400,50", the command does
     * not parse, and every teleport silently fails (everyone stays at the world spawn, NEON GRID).
     * A command that fails is logged, so it shows in the player's latest.log.
     */
    static void cmd(MinecraftServer server, String c) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(new FailureLog(c)), c);
    }

    /** Takes no output except failures, which go to the log. */
    private record FailureLog(String command) implements CommandSource {
        @Override
        public void sendSystemMessage(Component message) {
            AjaxMod.LOG.warn("command failed: {} ({})", command, message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return false;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    // --------------------------------------------------------------- builders ----

    /** Block placement relative to the arena's origin (x, z) and the floor height. */
    private static final class B {
        final ServerLevel level;
        final int ox, oz;
        final SplittableRandom rng;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        B(ServerLevel level, int ox, int oz, SplittableRandom rng) {
            this.level = level;
            this.ox = ox;
            this.oz = oz;
            this.rng = rng;
        }

        void set(int x, int y, int z, BlockState s) {
            level.setBlock(pos.set(ox + x, y, oz + z), s, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        void set(int x, int y, int z, Block b) {
            set(x, y, z, b.defaultBlockState());
        }

        /** Neighbours connect (fences, walls). */
        void setConnected(int x, int y, int z, Block b) {
            level.setBlock(pos.set(ox + x, y, oz + z), b.defaultBlockState(), Block.UPDATE_ALL);
        }

        void column(int x, int z, int y0, int y1, Block b) {
            for (int y = y0; y <= y1; y++) set(x, y, z, b);
        }

        double r(int x, int z) {
            return Math.sqrt(x * x + z * z);
        }

        /** The island under everything: the top at FLOOR out to `radius`, tapering downwards into a rough cone. */
        void island(int radius, Block top, Block under, Block deep) {
            for (int x = -radius; x <= radius; x++)
                for (int z = -radius; z <= radius; z++) {
                    double d = r(x, z);
                    if (d > radius + 0.4) continue;
                    set(x, FLOOR, z, top);
                    int depth = (int) ((radius - d) * 0.75) + 1 + rng.nextInt(3);
                    for (int k = 1; k <= depth; k++) set(x, FLOOR - k, z, k <= 2 ? under : deep);
                }
        }

        BlockState leaves(Block b) {
            return b.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        }

        void blob(int cx, int cy, int cz, double rx, double ry, BlockState s, double holes) {
            for (int x = (int) -rx - 1; x <= rx + 1; x++)
                for (int y = (int) -ry - 1; y <= ry + 1; y++)
                    for (int z = (int) -rx - 1; z <= rx + 1; z++) {
                        double q = (x * x + z * z) / (rx * rx) + (y * y) / (ry * ry);
                        if (q <= 1 && rng.nextDouble() >= holes * q) set(cx + x, cy + y, cz + z, s);
                    }
        }
    }

    /** The start pads: you at +z, the bot at -z. */
    private static void pads(B b, Arenas a) {
        Block mine = switch (a) {
            case NEON_GRID -> Blocks.LIGHT_BLUE_STAINED_GLASS;
            case SAKURA_DOJO -> Blocks.WHITE_GLAZED_TERRACOTTA;
            case MAGMA_COLOSSEUM -> Blocks.GILDED_BLACKSTONE;
            case FROST_PEAK -> Blocks.LIGHT_BLUE_CONCRETE;
            case SKY_SANCTUM -> Blocks.GOLD_BLOCK;
        };
        Block theirs = switch (a) {
            case NEON_GRID -> Blocks.MAGENTA_STAINED_GLASS;
            case SAKURA_DOJO -> Blocks.PINK_GLAZED_TERRACOTTA;
            case MAGMA_COLOSSEUM -> Blocks.SHROOMLIGHT;
            case FROST_PEAK -> Blocks.CYAN_CONCRETE;
            case SKY_SANCTUM -> Blocks.AMETHYST_BLOCK;
        };
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                b.set(dx, FLOOR, PAD + dz, mine);
                b.set(dx, FLOOR, -PAD + dz, theirs);
                if (a == NEON_GRID) {
                    b.set(dx, FLOOR - 1, PAD + dz, Blocks.SEA_LANTERN);
                    b.set(dx, FLOOR - 1, -PAD + dz, Blocks.SEA_LANTERN);
                }
            }
    }

    /** Invisible walls over the rim: four blocks of barrier, so the fight stays on the floor. */
    private static void walls(B b) {
        for (int x = -R - 2; x <= R + 2; x++)
            for (int z = -R - 2; z <= R + 2; z++) {
                double d = b.r(x, z);
                if (d > R + 0.5 && d <= R + 1.6)
                    for (int y = FLOOR + 2; y <= FLOOR + 5; y++) b.set(x, y, z, Blocks.BARRIER);
            }
    }

    private static boolean floor(B b, int x, int z) {
        return b.r(x, z) <= R + 0.5;
    }

    private static boolean rim(B b, int x, int z) {
        double d = b.r(x, z);
        return d > R + 0.5 && d <= R + 1.6;
    }

    // NEON GRID -----------------------------------------------------------------
    private static void neon(B b) {
        b.island(31, Blocks.BLACK_CONCRETE, Blocks.BLACK_CONCRETE, Blocks.GRAY_CONCRETE);
        for (int x = -31; x <= 31; x++)
            for (int z = -31; z <= 31; z++) {
                double d = b.r(x, z);
                if (floor(b, x, z)) {
                    boolean lx = Math.floorMod(x, 5) == 0, lz = Math.floorMod(z, 5) == 0;
                    if (lx || lz) {
                        b.set(x, FLOOR, z, lx && lz ? Blocks.MAGENTA_STAINED_GLASS : Blocks.CYAN_STAINED_GLASS);
                        b.set(x, FLOOR - 1, z, Blocks.SEA_LANTERN);
                    } else b.set(x, FLOOR, z, Blocks.BLACK_CONCRETE);
                    if (!(lx || lz)) b.set(x, FLOOR - 1, z, Blocks.BLACK_CONCRETE);
                } else if (rim(b, x, z)) {
                    b.set(x, FLOOR + 1, z, Blocks.LIGHT_BLUE_STAINED_GLASS);
                    b.set(x, FLOOR, z, Blocks.SEA_LANTERN);
                } else if (d <= 31.4 && d > 29.5) {
                    b.set(x, FLOOR, z, Blocks.MAGENTA_STAINED_GLASS);
                    b.set(x, FLOOR - 1, z, Blocks.SEA_LANTERN);
                }
            }
        // pylons
        for (int k = 0; k < 12; k++) {
            double ang = k * Math.PI * 2 / 12 + Math.PI / 12;
            int x = (int) Math.round(Math.cos(ang) * 26), z = (int) Math.round(Math.sin(ang) * 26);
            int h = 9 + (k % 3) * 3;
            Block glow = k % 2 == 0 ? Blocks.SEA_LANTERN : Blocks.PEARLESCENT_FROGLIGHT;
            for (int y = 1; y <= h; y++) b.set(x, FLOOR + y, z, y % 3 == 0 ? glow : Blocks.BLACK_CONCRETE);
            b.set(x, FLOOR + h + 1, z, Blocks.END_ROD);
        }
        // the halo
        for (int k = 0; k < 160; k++) {
            double ang = k * Math.PI * 2 / 160;
            int x = (int) Math.round(Math.cos(ang) * 24), z = (int) Math.round(Math.sin(ang) * 24);
            b.set(x, FLOOR + 21, z, k % 4 == 0 ? Blocks.PEARLESCENT_FROGLIGHT : Blocks.END_ROD);
        }
        // glowing shards under the island
        for (int k = 0; k < 14; k++) {
            int x = b.rng.nextInt(-20, 21), z = b.rng.nextInt(-20, 21);
            int depth = 5 + b.rng.nextInt(10);
            for (int y = 1; y <= depth; y++) b.set(x, FLOOR - (int) ((31 - b.r(x, z)) * 0.75) - y, z,
                    y == depth ? Blocks.SEA_LANTERN : Blocks.CYAN_STAINED_GLASS);
        }
    }

    // SAKURA DOJO ---------------------------------------------------------------
    private static void sakura(B b) {
        b.island(33, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE);
        for (int x = -33; x <= 33; x++)
            for (int z = -33; z <= 33; z++) {
                double d = b.r(x, z);
                if (floor(b, x, z)) {
                    Block f = Blocks.CHERRY_PLANKS;
                    if (d > R - 1.5) f = Blocks.STRIPPED_CHERRY_WOOD;
                    else if (d <= 3.5) f = d <= 1.2 ? Blocks.WHITE_GLAZED_TERRACOTTA : Blocks.PINK_GLAZED_TERRACOTTA;
                    else if (Math.abs(d - 11) < 0.6) f = Blocks.DARK_OAK_PLANKS;
                    b.set(x, FLOOR, z, f);
                } else if (rim(b, x, z)) {
                    b.set(x, FLOOR, z, Blocks.STRIPPED_CHERRY_WOOD);
                } else if (d <= 33.4 && b.rng.nextInt(4) == 0) {
                    b.set(x, FLOOR + 1, z, Blocks.PINK_PETALS);
                }
            }
        // fence ring with lanterns
        for (int x = -R - 2; x <= R + 2; x++)
            for (int z = -R - 2; z <= R + 2; z++)
                if (rim(b, x, z)) b.setConnected(x, FLOOR + 1, z, Blocks.CHERRY_FENCE);
        for (int k = 0; k < 16; k++) {
            double ang = k * Math.PI * 2 / 16;
            int x = (int) Math.round(Math.cos(ang) * (R + 1)), z = (int) Math.round(Math.sin(ang) * (R + 1));
            b.set(x, FLOOR + 1, z, Blocks.CHERRY_LOG);
            b.set(x, FLOOR + 2, z, Blocks.LANTERN);
        }
        // blossom trees
        for (int k = 0; k < 9; k++) {
            double ang = k * Math.PI * 2 / 9 + 0.2;
            if (Math.abs(Math.sin(ang) + 1) < 0.35) continue;     // keep the torii side open
            int x = (int) Math.round(Math.cos(ang) * 27), z = (int) Math.round(Math.sin(ang) * 27);
            int h = 5 + b.rng.nextInt(3);
            b.column(x, z, FLOOR + 1, FLOOR + h, Blocks.CHERRY_LOG);
            int bx = (int) Math.signum(Math.cos(ang)), bz = (int) Math.signum(Math.sin(ang));
            b.set(x + bx, FLOOR + h - 1, z + bz, Blocks.CHERRY_LOG);
            b.blob(x, FLOOR + h + 1, z, 4.2, 2.6, b.leaves(Blocks.CHERRY_LEAVES), 0.25);
            b.blob(x + bx * 2, FLOOR + h, z + bz * 2, 2.6, 1.8, b.leaves(Blocks.CHERRY_LEAVES), 0.3);
        }
        // torii gate behind the bot's side
        int tz = -27;
        for (int side : new int[]{-5, 5}) {
            b.column(side, tz, FLOOR + 1, FLOOR + 8, Blocks.RED_CONCRETE);
            b.set(side, FLOOR + 1, tz, Blocks.BLACK_CONCRETE);
            b.set(side, FLOOR + 3, tz - 1, Blocks.STONE_BRICKS);
            b.set(side, FLOOR + 4, tz - 1, Blocks.LANTERN);
        }
        for (int x = -7; x <= 7; x++) {
            b.set(x, FLOOR + 9, tz, Blocks.BLACK_CONCRETE);
            if (Math.abs(x) <= 6) b.set(x, FLOOR + 7, tz, Blocks.RED_CONCRETE);
        }
        b.set(-7, FLOOR + 10, tz, Blocks.BLACK_CONCRETE);
        b.set(7, FLOOR + 10, tz, Blocks.BLACK_CONCRETE);
        b.set(0, FLOOR + 8, tz, Blocks.RED_CONCRETE);
    }

    // MAGMA COLOSSEUM -----------------------------------------------------------
    private static void magma(B b) {
        b.island(34, Blocks.POLISHED_BLACKSTONE, Blocks.BLACKSTONE, Blocks.BASALT);
        for (int x = -34; x <= 34; x++)
            for (int z = -34; z <= 34; z++) {
                double d = b.r(x, z);
                if (floor(b, x, z)) {
                    Block f = b.rng.nextInt(7) == 0 ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS : Blocks.POLISHED_BLACKSTONE_BRICKS;
                    if (d <= 2.5) f = Blocks.CHISELED_POLISHED_BLACKSTONE;
                    else if (Math.abs(d - 10) < 0.6 || Math.abs(d - R + 0.3) < 0.6) f = Blocks.GILDED_BLACKSTONE;
                    else if (x == z || x == -z || x == 0 || z == 0) f = Blocks.POLISHED_BLACKSTONE;
                    b.set(x, FLOOR, z, f);
                } else if (rim(b, x, z)) {
                    b.set(x, FLOOR, z, Blocks.MAGMA_BLOCK);
                    b.set(x, FLOOR + 1, z, Blocks.POLISHED_BLACKSTONE_BRICK_WALL);
                } else if (d <= 24.6) {                         // the lava moat, contained on every side
                    b.set(x, FLOOR - 1, z, Blocks.BLACKSTONE);
                    b.set(x, FLOOR, z, Blocks.LAVA);
                } else if (d <= 25.6) {
                    b.set(x, FLOOR, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
                    b.set(x, FLOOR + 1, z, Blocks.POLISHED_BLACKSTONE_BRICKS);
                } else if (d <= 34.4) {                         // the stands, rising outwards
                    int tier = Math.min(4, (int) ((d - 25.6) / 2) + 1);
                    for (int y = 1; y <= tier; y++)
                        b.set(x, FLOOR + y, z, y == tier && ((x + z) & 3) == 0 ? Blocks.SHROOMLIGHT
                                : y == tier ? Blocks.RED_NETHER_BRICKS : Blocks.NETHER_BRICKS);
                }
            }
        // fire pillars
        for (int k = 0; k < 8; k++) {
            double ang = k * Math.PI * 2 / 8 + Math.PI / 8;
            int x = (int) Math.round(Math.cos(ang) * 29), z = (int) Math.round(Math.sin(ang) * 29);
            for (int dx = 0; dx <= 1; dx++)
                for (int dz = 0; dz <= 1; dz++) {
                    for (int y = 1; y <= 13; y++)
                        b.set(x + dx, FLOOR + y, z + dz, y == 7 || y == 8 ? Blocks.CRYING_OBSIDIAN : Blocks.POLISHED_BASALT);
                    b.set(x + dx, FLOOR + 14, z + dz, Blocks.NETHERRACK);
                    b.set(x + dx, FLOOR + 15, z + dz, Blocks.FIRE);
                }
        }
        // magma veins under the island
        for (int k = 0; k < 40; k++) {
            int x = b.rng.nextInt(-28, 29), z = b.rng.nextInt(-28, 29);
            if (b.r(x, z) > 30) continue;
            b.set(x, FLOOR - 3 - b.rng.nextInt(6), z, Blocks.MAGMA_BLOCK);
        }
    }

    // FROST PEAK ----------------------------------------------------------------
    private static void frost(B b) {
        b.island(33, Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.STONE);
        for (int x = -33; x <= 33; x++)
            for (int z = -33; z <= 33; z++) {
                double d = b.r(x, z);
                if (floor(b, x, z)) {
                    Block f = Blocks.SNOW_BLOCK;
                    double ang = Math.atan2(z, x);
                    double ray = Math.abs(Math.sin(ang * 4));           // 8-pointed rose
                    if (d <= 2.5) f = Blocks.LIGHT_BLUE_CONCRETE;
                    else if (ray < 0.06 * (R - d) / 4 && d < R - 2) f = Math.abs(Math.sin(ang * 2)) < 0.1 ? Blocks.LIGHT_BLUE_CONCRETE : Blocks.CYAN_TERRACOTTA;
                    else if (Math.abs(d - 15) < 0.6) f = Blocks.WHITE_CONCRETE;
                    else if (Math.abs(d - R + 0.3) < 0.6) f = Blocks.POLISHED_DIORITE;
                    b.set(x, FLOOR, z, f);
                } else if (rim(b, x, z)) {
                    b.set(x, FLOOR + 1, z, Blocks.POLISHED_DIORITE);
                }
            }
        // ice spikes
        for (int k = 0; k < 12; k++) {
            double ang = k * Math.PI * 2 / 12 + b.rng.nextDouble() * 0.3;
            int cx = (int) Math.round(Math.cos(ang) * (26 + b.rng.nextInt(4))), cz = (int) Math.round(Math.sin(ang) * (26 + b.rng.nextInt(4)));
            int h = 8 + b.rng.nextInt(12);
            double base = 1.8 + b.rng.nextDouble();
            for (int y = 1; y <= h; y++) {
                double rr = base * (1 - (double) y / (h + 1));
                for (int dx = -3; dx <= 3; dx++)
                    for (int dz = -3; dz <= 3; dz++)
                        if (dx * dx + dz * dz <= rr * rr + 0.3)
                            b.set(cx + dx, FLOOR + y, cz + dz, dx == 0 && dz == 0 ? Blocks.BLUE_ICE : Blocks.PACKED_ICE);
            }
            b.set(cx, FLOOR, cz, Blocks.SEA_LANTERN);
        }
        // snowy spruce
        for (int k = 0; k < 6; k++) {
            double ang = k * Math.PI * 2 / 6 + 0.5;
            int x = (int) Math.round(Math.cos(ang) * 30), z = (int) Math.round(Math.sin(ang) * 30);
            b.column(x, z, FLOOR + 1, FLOOR + 6, Blocks.SPRUCE_LOG);
            for (int y = 2; y <= 8; y++) {
                double rr = (8 - y) * 0.45 + (y % 2 == 0 ? 0.6 : 0);
                for (int dx = -3; dx <= 3; dx++)
                    for (int dz = -3; dz <= 3; dz++)
                        if ((dx != 0 || dz != 0 || y > 6) && dx * dx + dz * dz <= rr * rr + 0.5)
                            b.set(x + dx, FLOOR + y, z + dz, y >= 7 || b.rng.nextInt(3) == 0
                                    ? Blocks.SNOW_BLOCK.defaultBlockState() : b.leaves(Blocks.SPRUCE_LEAVES));
            }
        }
    }

    // SKY SANCTUM ---------------------------------------------------------------
    private static void sky(B b) {
        b.island(31, Blocks.QUARTZ_BRICKS, Blocks.SMOOTH_QUARTZ, Blocks.CALCITE);
        for (int x = -31; x <= 31; x++)
            for (int z = -31; z <= 31; z++) {
                double d = b.r(x, z);
                if (floor(b, x, z)) {
                    Block f = Blocks.SMOOTH_QUARTZ;
                    boolean ray = (x == 0 || z == 0 || Math.abs(x) == Math.abs(z)) && d > 3.5 && d < R - 1;
                    if (d <= 3.5) f = Blocks.CHISELED_QUARTZ_BLOCK;
                    else if (ray) f = Blocks.GOLD_BLOCK;
                    else if (Math.abs(d - 12) < 0.6) f = Blocks.AMETHYST_BLOCK;
                    else if (Math.abs(d - R + 0.3) < 0.6) f = Blocks.QUARTZ_BRICKS;
                    b.set(x, FLOOR, z, f);
                } else if (rim(b, x, z)) {
                    b.set(x, FLOOR + 1, z, Blocks.QUARTZ_BRICKS);
                } else if (d > 30.4 && d <= 31.4) {
                    b.set(x, FLOOR, z, Blocks.GOLD_BLOCK);
                }
            }
        // columns
        for (int k = 0; k < 8; k++) {
            double ang = k * Math.PI * 2 / 8;
            int x = (int) Math.round(Math.cos(ang) * 25), z = (int) Math.round(Math.sin(ang) * 25);
            b.column(x, z, FLOOR + 1, FLOOR + 9, Blocks.QUARTZ_PILLAR);
            b.set(x, FLOOR + 10, z, Blocks.CHISELED_QUARTZ_BLOCK);
            b.set(x, FLOOR + 11, z, Blocks.GOLD_BLOCK);
            b.set(x, FLOOR + 12, z, Blocks.END_ROD);
        }
        // four beacons with coloured beams
        Block[] glass = {Blocks.CYAN_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS, Blocks.LIME_STAINED_GLASS};
        for (int k = 0; k < 4; k++) {
            double ang = k * Math.PI / 2 + Math.PI / 4;
            int x = (int) Math.round(Math.cos(ang) * 27), z = (int) Math.round(Math.sin(ang) * 27);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) b.set(x + dx, FLOOR, z + dz, Blocks.IRON_BLOCK);
            b.set(x, FLOOR + 1, z, Blocks.BEACON);
            b.set(x, FLOOR + 2, z, glass[k]);
        }
        // clouds drifting below and around
        for (int k = 0; k < 26; k++) {
            double ang = b.rng.nextDouble() * Math.PI * 2, dist = 30 + b.rng.nextDouble() * 22;
            int x = (int) (Math.cos(ang) * dist), z = (int) (Math.sin(ang) * dist);
            int y = FLOOR - 4 - b.rng.nextInt(16);
            b.blob(x, y, z, 3 + b.rng.nextDouble() * 4, 1.4 + b.rng.nextDouble(), Blocks.WHITE_WOOL.defaultBlockState(), 0.35);
        }
    }
}

