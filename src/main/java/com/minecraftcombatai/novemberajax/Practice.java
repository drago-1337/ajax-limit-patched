package com.minecraftcombatai.novemberajax;

import com.minecraftcombatai.novemberajax.brain.AjaxBrain;
import com.minecraftcombatai.novemberajax.brain.AjaxInputs;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Practice inside the player's own world. Modes:
 *
 *   DUMMY  a practice dummy that only turns to face you (for "AJAX plays for you")
 *   SPAR   you fight AJAX
 *   RIVAL  AJAX plays for you, from your eyes, against MIRROR (a second AJAX in rival colours)
 *   WATCH  AJAX vs MIRROR in a practice arena while you watch from above (spectator)
 *
 * Runs only on this client's integrated server, and the bots only ever fight the player who asked or each other.
 * Every method that touches the world runs on the server thread; the client reads the volatile fields.
 */
public final class Practice {
    enum Mode { NONE, DUMMY, SPAR, RIVAL, WATCH }

    static volatile Mode mode = Mode.NONE;
    static volatile UUID botId;
    static volatile int botKOs, youKOs, duelA, duelB, duelDraws, round;
    static volatile float botHealth;
    static volatile String error = "";
    static final int DUEL_ROUND_TICKS = 2400;

    static final String AJAX_NAME = "AJAX", MIRROR_NAME = "MIRROR", DUMMY_NAME = "Practice Dummy";
    static final UUID AJAX_ID = id(AJAX_NAME), MIRROR_ID = id(MIRROR_NAME), DUMMY_ID = id(DUMMY_NAME),
            WATCH_A = id(AJAX_NAME + "#watch"), WATCH_B = id(MIRROR_NAME + "#watch");

    // server thread
    private static PracticeBot bot, bot2;
    private static UUID owner;
    private static Vec3 home;
    private static float homeYaw;
    private static int downTicks, roundTicks, duelClock;
    private static boolean ownerWasDown;
    private static Arenas arena;
    private static GameType ownerMode;
    private static AjaxPilot pilotA, pilotB;

    private Practice() {}

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(("november_ajax:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** True for this mod's practice bots (the client dresses them). */
    public static boolean isBot(UUID id) {
        return AJAX_ID.equals(id) || MIRROR_ID.equals(id) || DUMMY_ID.equals(id) || WATCH_A.equals(id) || WATCH_B.equals(id);
    }

    /** Who the client draws in AJAX/MIRROR/dummy colours (and without the armour they wear). */
    public static boolean dressed(UUID id) {
        return isBot(id);
    }

    private static final Map<String, PlayerSkin> SKINS = new HashMap<>();

    /** The bundled skins, drawn locally (no Mojang signature): AJAX, MIRROR and the dummy. */
    public static PlayerSkin botSkin(UUID id) {
        String which = AJAX_ID.equals(id) || WATCH_A.equals(id) ? "ajax" : MIRROR_ID.equals(id) || WATCH_B.equals(id) ? "mirror" : "dummy";
        return SKINS.computeIfAbsent(which, w -> PlayerSkin.insecure(new ClientAsset.ResourceTexture(
                Identifier.fromNamespaceAndPath("november_ajax", "entity/" + w)), null, null, PlayerModelType.WIDE));
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Practice::tick);
        ServerTickEvents.END_SERVER_TICK.register(PracticeWorld::serverTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> clear());
    }

    /** From the client: start a mode (or NONE to end practice) for the local player. */
    static void request(Minecraft mc, Mode m) {
        IntegratedServer server = Gate.host(mc);
        if (server == null) {
            // No world on this computer: the bots are server players, so they cannot be spawned on
            // someone else's server. AJAX can still play for the player there, against monsters.
            error = m == Mode.NONE ? ""
                    : "The practice bots need a world hosted by this computer (singleplayer, or a LAN world you open).";
            return;
        }
        if (mc.player == null || !Gate.allowed(mc)) {
            error = Gate.refusal(mc);
            return;
        }
        UUID who = mc.player.getUUID();
        server.execute(() -> {
            clear();
            if (m != Mode.NONE) start(server, who, m);
        });
    }

    /** From the client: the trained kit for the local player too. */
    static void kitForMe(Minecraft mc) {
        IntegratedServer server = Gate.host(mc);
        if (server == null || mc.player == null) return;
        UUID who = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(who);
            if (p != null) kit(p);
        });
    }

    private static void start(MinecraftServer server, UUID who, Mode m) {
        ServerPlayer me = server.getPlayerList().getPlayer(who);
        if (me == null) return;
        owner = who;
        Vec3 ahead = Vec3.directionFromRotation(0, me.getYRot()).scale(5.0);
        double x = me.getX() + ahead.x, z = me.getZ() + ahead.z;
        ServerLevel level = me.level();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        home = new Vec3(x, y, z);
        homeYaw = me.getYRot() + 180f;
        arena = PracticeWorld.active ? Arenas.at(me.getX(), me.getZ()) : null;
        if (m == Mode.WATCH && arena == null) {
            error = "WATCH runs in the practice arenas: open ARENAS and pick one first.";
            owner = null;
            return;
        }
        if (arena != null) {
            double[] b = arena.padB();
            home = new Vec3(b[0], b[1], b[2]);
            homeYaw = (float) b[3];
            if (m != Mode.WATCH) toPad(server, me);
        }
        botKOs = youKOs = duelA = duelB = duelDraws = 0;
        round = 1;
        roundTicks = duelClock = 0;
        ownerWasDown = false;
        error = "";
        try {
            if (m == Mode.SPAR || m == Mode.RIVAL) pilotA = new AjaxPilot(owner, 0x5EED);
            if (m == Mode.WATCH) {
                pilotA = new AjaxPilot(WATCH_B, 0xA1);
                pilotB = new AjaxPilot(WATCH_A, 0xB2);
            }
        } catch (Exception e) {
            error = "the bundled model did not load: " + e.getMessage();
            AjaxMod.LOG.error("model failed: {}", e.toString());
            owner = null;
            return;
        }
        mode = m;
        if (m == Mode.WATCH) {
            ownerMode = me.gameMode();
            me.setGameMode(GameType.SPECTATOR);
            watch(server, me);
        } else {
            kit(me);
        }
        spawn(level);
    }

    private static void spawn(ServerLevel level) {
        downTicks = 0;
        duelClock = 0;
        if (mode == Mode.WATCH) {
            bot = PracticeBot.spawn(level, new GameProfile(WATCH_A, AJAX_NAME), home.x, home.y, home.z, homeYaw);
            double[] a = arena.padA();
            bot2 = PracticeBot.spawn(level, new GameProfile(WATCH_B, MIRROR_NAME), a[0], a[1], a[2], (float) a[3]);
            kit(bot);
            kit(bot2);
            pilotA.reset();
            pilotB.reset();
            bot.setPilot(pilotA);
            bot2.setPilot(pilotB);
            botId = bot.getUUID();
            return;
        }
        UUID id = mode == Mode.SPAR ? AJAX_ID : mode == Mode.RIVAL ? MIRROR_ID : DUMMY_ID;
        String name = mode == Mode.SPAR ? AJAX_NAME : mode == Mode.RIVAL ? MIRROR_NAME : DUMMY_NAME;
        bot = PracticeBot.spawn(level, new GameProfile(id, name), home.x, home.y, home.z, homeYaw);
        botId = id;
        kit(bot);
        if (pilotA != null) {
            pilotA.reset();
            bot.setPilot(pilotA);
        } else {
            bot.setPilot(new DummyPilot(owner));
        }
    }

    /** WATCH: the watcher floats beside the arena, looking at both pads. */
    private static void watch(MinecraftServer server, ServerPlayer me) {
        Arenas.cmd(server, String.format(Locale.ROOT, "tp %s %.2f %d %.2f 90 32", me.getUUID(), arena.ox() + 16.5, Arenas.FLOOR + 11,
                arena.oz() + 0.5));
    }

    static String probe() {
        if (bot == null) return "no bot";
        return String.format("gm=%s hp=%.1f pos=(%.2f, %.2f, %.2f)", bot.gameMode(), bot.getHealth(), bot.getX(), bot.getY(), bot.getZ());
    }

    /** For the dev director: the two bodies of a WATCH duel (null otherwise). */
    static PracticeBot[] duelBots() {
        return mode == Mode.WATCH && bot != null && bot2 != null ? new PracticeBot[]{bot, bot2} : null;
    }

    static PracticeBot bot() {
        return bot;
    }

    static void clear() {
        if (mode == Mode.WATCH && owner != null && ownerMode != null && bot != null) {
            ServerPlayer me = bot.level().getServer().getPlayerList().getPlayer(owner);
            if (me != null) {
                me.setGameMode(ownerMode);
                if (arena != null) toPad(bot.level().getServer(), me);
            }
        }
        ownerMode = null;
        pilotA = pilotB = null;
        if (bot2 != null) bot2.despawn();
        bot2 = null;
        if (bot != null) bot.despawn();
        bot = null;
        owner = null;
        botId = null;
        arena = null;
        mode = Mode.NONE;
    }

    private static void tick(MinecraftServer server) {
        if (bot == null) return;
        ServerPlayer me = server.getPlayerList().getPlayer(owner);
        if (me == null) {
            clear();
            return;
        }
        if (!bot.isRemoved() && !bot.isDeadOrDying()) bot.forceSurvival();
        if (mode == Mode.WATCH) {
            duelTick(server, me);
            return;
        }
        boolean meDown = me.isDeadOrDying();
        if (meDown && !ownerWasDown) {
            botKOs++;
            if (arena != null) roundTicks = 40;
        }
        ownerWasDown = meDown;
        if (bot.isRemoved() || bot.isDeadOrDying()) {
            if (downTicks++ == 0) youKOs++;
            if (downTicks >= 40) {
                ServerLevel level = bot.level();
                bot.despawn();
                spawn(level);
                if (arena != null) newRound(server, me);
            }
        } else if (roundTicks > 0 && --roundTicks == 0 && !me.isDeadOrDying()) {
            ServerLevel level = bot.level();
            bot.despawn();
            spawn(level);
            newRound(server, me);
        }
        botHealth = bot.getHealth();
    }

    private static void duelTick(MinecraftServer server, ServerPlayer me) {
        if (bot2 == null) return;
        if (!bot2.isRemoved() && !bot2.isDeadOrDying()) bot2.forceSurvival();
        if (me.gameMode() != GameType.SPECTATOR) me.setGameMode(GameType.SPECTATOR);
        boolean aDown = bot.isRemoved() || bot.isDeadOrDying(), bDown = bot2.isRemoved() || bot2.isDeadOrDying();
        boolean timeUp = ++duelClock >= DUEL_ROUND_TICKS;
        if (aDown || bDown || timeUp) {
            if (downTicks++ == 0) {
                if (aDown && !bDown) duelB++;
                else if (bDown && !aDown) duelA++;
                else duelDraws++;
            }
            if (downTicks >= 40) {
                ServerLevel level = bot.level();
                bot.despawn();
                bot2.despawn();
                round++;
                spawn(level);
                Arenas.actionbar(me, "ROUND " + round, ChatFormatting.AQUA);
            }
        }
        botHealth = bot.isRemoved() ? 0 : bot.getHealth();
        youKOs = duelA;
        botKOs = duelB;
    }

    private static void newRound(MinecraftServer server, ServerPlayer me) {
        round++;
        toPad(server, me);
        kit(me);
        Arenas.actionbar(me, "ROUND " + round, ChatFormatting.AQUA);
    }

    private static void toPad(MinecraftServer server, ServerPlayer me) {
        double[] a = arena.padA();
        Arenas.cmd(server, String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f %.0f 0", me.getUUID(), a[0], a[1], a[2], a[3]));
    }

    /** The kit AJAX trained with: a diamond sword (Unbreaking III, no Sharpness), diamond armour with Protection IV + Unbreaking III. */
    static void kit(ServerPlayer p) {
        var reg = p.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Holder<Enchantment> prot = reg.getOrThrow(Enchantments.PROTECTION), unb = reg.getOrThrow(Enchantments.UNBREAKING);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(unb, 3);
        p.getInventory().setItem(0, sword);
        p.getInventory().setSelectedSlot(0);
        armour(p, EquipmentSlot.HEAD, Items.DIAMOND_HELMET, prot, unb);
        armour(p, EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, prot, unb);
        armour(p, EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, prot, unb);
        armour(p, EquipmentSlot.FEET, Items.DIAMOND_BOOTS, prot, unb);
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
    }

    private static void armour(ServerPlayer p, EquipmentSlot slot, Item item, Holder<Enchantment> prot, Holder<Enchantment> unb) {
        ItemStack s = new ItemStack(item);
        s.enchant(prot, 4);
        s.enchant(unb, 3);
        p.setItemSlot(slot, s);
    }

    // ------------------------------------------------------------------ pilots ----

    /** Stands still and turns to face its player, at most 12 degrees a tick. */
    private record DummyPilot(UUID target) implements PracticeBot.Pilot {
        @Override
        public Driver.Action decide(PracticeBot bot) {
            ServerPlayer t = bot.level().getServer().getPlayerList().getPlayer(target);
            if (t == null || t.level() != bot.level()) return null;
            double dx = t.getX() - bot.getX(), dz = t.getZ() - bot.getZ();
            float want = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
            float dy = Mth.clamp(Mth.wrapDegrees(want - bot.getYRot()), -12f, 12f);
            return new Driver.Action(0, dy, Mth.clamp(-bot.getXRot(), -6f, 6f));
        }

        @Override
        public boolean canHit(Entity e) {
            return e.getUUID().equals(target);      // never a bystander in a LAN world
        }
    }

    /** AJAX driving a practice bot: its own state as "self", its target as the opponent. Its own brain (server thread). */
    static final class AjaxPilot implements PracticeBot.Pilot {
        private final UUID target;
        private final AjaxBrain brain;
        private final AjaxInputs.Frame frame = new AjaxInputs.Frame();
        private boolean engaged;

        AjaxPilot(UUID target, long seed) throws Exception {
            this.target = target;
            this.brain = new AjaxBrain(seed ^ System.nanoTime());
        }

        void reset() {
            engaged = false;
        }

        @Override
        public Driver.Action decide(PracticeBot bot) {
            ServerPlayer t = bot.level().getServer().getPlayerList().getPlayer(target);
            if (t == null || !t.isAlive() || t.level() != bot.level() || t.isSpectator() || t.isCreative()
                    || t.distanceToSqr(bot) > 32 * 32) {
                engaged = false;
                return null;
            }
            if (!engaged) {
                brain.reset();
                engaged = true;
            }
            Frames.fill(frame, bot, t);
            AjaxBrain.Decision d = brain.decide(frame);
            return new Driver.Action(d.keys(), d.yawDelta(), d.pitchDelta());
        }

        @Override
        public boolean canHit(Entity e) {
            return e.getUUID().equals(target);      // its opponent, and nobody else
        }
    }
}

