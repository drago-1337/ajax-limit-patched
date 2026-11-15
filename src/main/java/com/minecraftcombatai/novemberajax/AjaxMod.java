package com.minecraftcombatai.novemberajax;

import com.minecraftcombatai.novemberajax.brain.AjaxBrain;
import com.minecraftcombatai.novemberajax.brain.AjaxInputs;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * November AJAX: an open-weight LT4 sword AI that runs on your own computer, inside this jar.
 *
 *   R  AJAX plays for you (from your eyes) against this mod's practice bots
 *   O  the AJAX panel (also the AJAX button on the pause menu and title screen)
 *
 * Singleplayer only ({@link Gate}). His aim is raw: the turn the network picks is the turn made.
 */
public final class AjaxMod implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("november-ajax");
    static final double RANGE = 32, MOB_RANGE = 16;
    private static AjaxMod instance;

    enum State { IDLE, RUNNING, ERROR }

    Config config;
    KeyMapping playKey, menuKey, hudKey;
    boolean hudVisible;
    State state = State.IDLE;
    String detail = "";

    private AjaxBrain brain;
    private final AjaxInputs.Frame frame = new AjaxInputs.Frame();
    private final Driver driver = new Driver();
    LivingEntity target;
    private LocalPlayer lastPlayer;
    private Driver.Action pending;
    private int lastTickCount = -1;
    private long fightStartMs;
    private double inferMsEma = -1;
    private boolean cameraLocked, hinted;
    String notice = "";
    private long noticeUntil;

    static AjaxMod get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        config = Config.load();
        hudVisible = config.hud;
        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("november_ajax", "main"));
        playKey = bind("key.november_ajax.play", GLFW.GLFW_KEY_R, cat);
        menuKey = bind("key.november_ajax.menu", GLFW.GLFW_KEY_O, cat);
        hudKey = bind("key.november_ajax.hud", GLFW.GLFW_KEY_UNKNOWN, cat);
        restoreKeys();
        ClientTickEvents.START_CLIENT_TICK.register(this::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> Driver.sweep(mc));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("november_ajax", "hud"), Hud::draw);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("november_ajax", "watermark"), Watermark::draw);
        Practice.register();
        PracticeWorld.register();
        ClientTickEvents.END_CLIENT_TICK.register(PracticeWorld::clientTick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof TitleScreen) PracticeWorld.sweepOnce(client);
            if (screen instanceof PauseScreen ps && ps.showsPauseMenu() || screen instanceof TitleScreen)
                Screens.getWidgets(screen).add(new EntryButton(6, 6));
        });
        LOG.info("November AJAX ready (open weights, build {})", AjaxBrain.WEIGHTS);
    }

    private void restoreKeys() {
        for (KeyMapping k : new KeyMapping[]{playKey, menuKey, hudKey}) {
            String saved = config.keys.get(k.getName());
            if (saved != null && !saved.isEmpty()) {
                try {
                    k.setKey(InputConstants.getKey(saved));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        KeyMapping.resetMapping();
    }

    void saveKeys() {
        for (KeyMapping k : new KeyMapping[]{playKey, menuKey, hudKey}) config.keys.put(k.getName(), k.saveString());
        config.save();
    }

    private static KeyMapping bind(String name, int key, KeyMapping.Category cat) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(name, InputConstants.Type.KEYSYM, key, cat));
    }

    static String keyName(KeyMapping k) {
        return k.isUnbound() ? "unbound" : k.getTranslatedKeyMessage().getString();
    }

    void setHud(boolean on) {
        hudVisible = on;
        config.hud = on;
        config.save();
    }

    long noticeUntil() {
        return noticeUntil;
    }

    double inferMs() {
        return Math.max(0, inferMsEma);
    }

    boolean running() {
        return state == State.RUNNING;
    }

    long fightStartMs() {
        return fightStartMs;
    }

    String buildTag() {
        try {
            if (brain == null) brain = new AjaxBrain(System.nanoTime());
            return brain.buildTag();
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ actions ----

    void toggle(Minecraft mc) {
        if (running()) {
            stop(mc, "");
            return;
        }
        if (mc.player == null) {
            say("Open a world first.");
            return;
        }
        if (!Gate.allowed(mc)) {
            say(Gate.refusal(mc));
            return;
        }
        try {
            if (brain == null) brain = new AjaxBrain(System.nanoTime());
        } catch (Exception e) {
            state = State.ERROR;
            detail = "the bundled model did not load (" + e.getMessage() + ")";
            LOG.error("model load failed: {}", e.toString());
            return;
        }
        state = State.RUNNING;
        detail = "";
        target = null;
        pending = null;
        lastTickCount = -1;
        lastPlayer = mc.player;
        if (Practice.mode == Practice.Mode.NONE) {
            if (Gate.hostsBots(mc))
                say("AJAX fights this mod's practice bots: open the panel (" + keyName(menuKey) + ") and pick one.");
            else
                say("AJAX fights what is here: monsters, and this mod's bots if that server runs the mod too. He is for testing in authorized environments only.");
        }
    }

    void stop(Minecraft mc, String why) {
        if (state == State.RUNNING) state = State.IDLE;
        detail = why;
        pending = null;
        target = null;
        driver.release(mc);
        unlockCamera(mc);
        CameraSmooth.off();
    }

    private void lockCamera(Minecraft mc) {
        if (mc.screen == null && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
            cameraLocked = true;
        }
    }

    private void unlockCamera(Minecraft mc) {
        if (!cameraLocked) return;
        cameraLocked = false;
        if (mc.screen == null && mc.isWindowActive()) mc.mouseHandler.grabMouse();
    }

    void say(String text) {
        notice = text;
        noticeUntil = System.currentTimeMillis() + 5000;
    }

    // ------------------------------------------------------------------ the tick ---

    /** Dev builds only (-Pdirector): the trailer camera. Release jars do not contain it. */
    private final java.util.function.Consumer<Minecraft> director = loadDev();

    @SuppressWarnings("unchecked")
    private static java.util.function.Consumer<Minecraft> loadDev() {
        if (!Boolean.getBoolean("november_ajax.director")) return null;
        try {
            var c = Class.forName("com.minecraftcombatai.novemberajax.Director").getDeclaredConstructor();
            c.setAccessible(true);
            return (java.util.function.Consumer<Minecraft>) c.newInstance();
        } catch (ReflectiveOperationException e) {
            LOG.error("director missing: {}", e.toString());
            return null;
        }
    }

    private void startTick(Minecraft mc) {
        if (director != null) director.accept(mc);
        while (menuKey.consumeClick()) if (mc.screen == null) mc.setScreen(new AjaxScreen(null));
        while (hudKey.consumeClick()) setHud(!hudVisible);
        while (playKey.consumeClick()) toggle(mc);
        if (mc.player == null) hinted = false;
        else if (!hinted && mc.player.tickCount > 20 && director == null) {
            hinted = true;
            String hello = switch (Gate.where(mc)) {
                case SINGLEPLAYER -> "§3[AJAX]§r November AJAX is ready. Press §eEsc§r and click §3AJAX§r (top left), or press "
                        + keyName(menuKey) + ".";
                case LAN -> "§3[AJAX]§r November AJAX is ready in this LAN world. He only fights his practice bots and monsters, "
                        + "never a player. Press " + keyName(menuKey) + ".";
                case REMOTE -> "§3[AJAX]§r November AJAX is ready. On a server he plays for you against monsters; the practice bots "
                        + "need a world on this computer. Press " + keyName(menuKey) + ".";
            };
            mc.gui.getChat().addClientSystemMessage(Component.literal(PracticeWorld.active
                    ? "§3[AJAX]§r Welcome to the practice arenas. Press " + keyName(menuKey) + " to spar AJAX, let him play for you, or watch him fight MIRROR. This world is deleted when you leave."
                    : hello));
        }
        if (!running()) {
            CameraSmooth.off();
            return;
        }
        if (mc.player == null || mc.level == null) {
            stop(mc, "you left the world");
            cameraLocked = false;
            return;
        }
        if (!Gate.allowed(mc)) {
            stop(mc, Gate.refusal(mc));
            return;
        }
        if (director == null) lockCamera(mc);
        Driver.Action a = pending;
        pending = null;
        boolean free = mc.screen == null && mc.player.isAlive() && target != null && target.isAlive();
        float yaw0 = mc.player.getYRot(), pitch0 = mc.player.getXRot();
        if (a != null && free) driver.apply(mc, a);
        else if (!free) driver.release(mc);
        if (config.smoothCamera) CameraSmooth.tick(mc.player, yaw0, pitch0);   // render only: the game's rotation is AJAX's raw turn
        else CameraSmooth.off();
    }

    private void endTick(Minecraft mc) {
        if (!running() || mc.player == null || mc.level == null) return;
        LocalPlayer me = mc.player;
        if (me.tickCount == lastTickCount) return;
        lastTickCount = me.tickCount;
        LivingEntity t = pickTarget(mc, me);
        if (t == null) {
            if (target != null) driver.release(mc);
            target = null;
            return;
        }
        if (t != target || me != lastPlayer) {      // a new fight: no memory of the last one
            target = t;
            lastPlayer = me;
            brain.reset();
            fightStartMs = System.currentTimeMillis();
        }
        Frames.fill(frame, me, me.isCrouching(), t);
        long t0 = System.nanoTime();
        AjaxBrain.Decision d = brain.decide(frame);
        pending = new Driver.Action(d.keys(), d.yawDelta(), d.pitchDelta());
        double ms = (System.nanoTime() - t0) / 1e6;
        inferMsEma = inferMsEma < 0 ? ms : inferMsEma + 0.05 * (ms - inferMsEma);
    }

    /**
     * Only this mod's practice bots (and monsters, if switched on). Never another player: this is the
     * rule that keeps AJAX a practice partner in a LAN world and on a server too, and it holds
     * everywhere, in every kind of world.
     */
    private LivingEntity pickTarget(Minecraft mc, LocalPlayer me) {
        if (target != null && target.isAlive() && !target.isRemoved() && target.level() == me.level()
                && target.distanceToSqr(me) < RANGE * RANGE
                && (target instanceof AbstractClientPlayer p ? true : config.mobs)) return target;
        LivingEntity best = null;
        double bd = RANGE * RANGE;
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p == me || !p.isAlive() || p.isSpectator()) continue;
            double d = p.distanceToSqr(me);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best == null && config.mobs) {
            bd = MOB_RANGE * MOB_RANGE;
            for (Monster m : mc.level.getEntitiesOfClass(Monster.class, me.getBoundingBox().inflate(MOB_RANGE))) {
                if (!m.isAlive()) continue;
                double d = m.distanceToSqr(me);
                if (d < bd) {
                    bd = d;
                    best = m;
                }
            }
        }
        return best;
    }

    static String describe(LivingEntity e) {
        if (e instanceof AbstractClientPlayer p) return p.getGameProfile().name();
        return e.getName().getString();
    }
}

