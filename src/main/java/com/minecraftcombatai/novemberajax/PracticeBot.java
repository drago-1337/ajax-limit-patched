package com.minecraftcombatai.novemberajax;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.Optional;

/**
 * A practice opponent that lives only inside the player's own world (the integrated server of a singleplayer game or of a LAN
 * world this computer hosts). It is a real {@link ServerPlayer}, so damage, crits, sprint knockback, armour, invulnerability
 * frames and hunger are vanilla's own.
 *
 * Each tick it does what a vanilla 26.1.2 client does for its own player, in order: apply a pending knockback packet, count down
 * missTime, turn, click (vanilla pick rules; AJAX was trained with no charge gate, so a click is a click), turn the keys into
 * movement input the way LocalPlayer.aiStep / applyInput do, then run vanilla player physics.
 */
final class PracticeBot extends ServerPlayer {
    /** Supplies the bot's action each tick; null = stand still (the practice dummy). */
    interface Pilot {
        Driver.Action decide(PracticeBot bot);

        /**
         * The only entity this bot may hit: its opponent. In a LAN world other people can be
         * standing around, and a bot must never land a hit on one of them, so a click that would
         * hit anything else is dropped.
         */
        default boolean canHit(Entity e) {
            return false;
        }
    }

    final BotConnection network;
    private Pilot pilot;
    private Vec3 pendingMotion;

    // LocalPlayer state
    private int sprintTriggerTime, missTime;
    private boolean prevShift, prevForwardImpulse, crouching;
    private float inputXxa, inputZza;
    private boolean inputJump;
    boolean lastAttackRequested;
    long clicks, whiffs;

    /**
     * A real client sees other players late: the server sends their moves every other tick and the client eases them in over
     * three more. AJAX learned to fight against that late view, so the practice
     * bots see and swing at other players as they were {@link #LAG} ticks ago, like the player's own client does. Without it a
     * bot reads exact server positions and lands every edge-of-reach hit a client could not.
     */
    static final int LAG = 3;

    record Seen(double x, double y, double z, float yaw, float pitch, boolean ground, boolean sprint, boolean sneak, boolean swing,
                int hurt, float health, float armor, boolean sword, AABB box) {}

    private final java.util.Map<Integer, java.util.ArrayDeque<Seen>> seen = new java.util.HashMap<>();

    private void watchOthers() {
        for (var p : level().players()) {
            if (p == this) continue;
            var q = seen.computeIfAbsent(p.getId(), k -> new java.util.ArrayDeque<>());
            q.addLast(new Seen(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), p.onGround(), p.isSprinting(), p.isCrouching(),
                    p.swinging, p.hurtTime, p.getHealth(), p.getArmorValue(), p.getMainHandItem().is(net.minecraft.world.item.Items.DIAMOND_SWORD),
                    p.getBoundingBox()));
            while (q.size() > LAG + 1) q.removeFirst();
        }
    }

    /** How this body's client sees {@code e} now (LAG ticks late), or null before it has been seen. */
    Seen seenOf(Entity e) {
        var q = seen.get(e.getId());
        return q == null || q.isEmpty() ? null : q.peekFirst();
    }

    /** Whether this bot's pilot allows a hit on this entity. */
    private boolean mayHit(Entity e) {
        Pilot p = pilot;
        return p != null && p.canHit(e);
    }

    private PracticeBot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, new ClientInformation("en_us", 2, ChatVisiblity.HIDDEN, false, 0x7F,
                HumanoidArm.RIGHT, false, false, ParticleStatus.MINIMAL));
        this.network = new BotConnection(this::receive);
    }

    /** Create and log in. Server thread only. */
    static PracticeBot spawn(ServerLevel level, GameProfile profile, double x, double y, double z, float yaw) {
        MinecraftServer server = level.getServer();
        PracticeBot bot = new PracticeBot(server, level, profile);
        bot.snapTo(x, y, z, yaw, 0f);
        server.getPlayerList().placeNewPlayer(bot.network, bot, CommonListenerCookie.createInitial(profile, false));
        // A new player is invulnerable until its client reports the world loaded; this one has.
        bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        bot.forceSurvival();
        return bot;
    }

    /**
     * A new player takes the world's default game mode, and in a creative world that is creative:
     * invulnerable, so hits do no damage and no knockback. A sparring partner is always survival.
     */
    void forceSurvival() {
        if (gameMode() != GameType.SURVIVAL) setGameMode(GameType.SURVIVAL);
        if (getAbilities().flying || getAbilities().invulnerable) {
            getAbilities().flying = false;
            getAbilities().invulnerable = false;
            onUpdateAbilities();
        }
    }

    void setPilot(Pilot pilot) {
        this.pilot = pilot;
    }

    Pilot pilot() {
        return pilot;
    }

    int attackTicker() {
        return attackStrengthTicker;
    }

    int missTime() {
        return missTime;
    }

    /** Log out like a disconnect. Server thread only. */
    void despawn() {
        pilot = null;
        if (level().getServer().getPlayerList().getPlayers().contains(this))
            level().getServer().getPlayerList().remove(this);
        network.disconnect(new net.minecraft.network.DisconnectionDetails(net.minecraft.network.chat.Component.literal("practice over")));
    }

    boolean crouchingFlag() {
        return crouching;
    }

    /** The only server packet a client-less body acts on: its own knockback. */
    private void receive(Packet<?> packet) {
        if (packet instanceof ClientboundSetEntityMotionPacket m && m.id() == getId()) pendingMotion = m.movement();
    }


    @Override
    public void tick() {
        super.tick();
        if (isRemoved() || isDeadOrDying() || isSpectator()) return;
        watchOthers();
        clientStep();
    }

    private void clientStep() {
        // The client applies a motion packet when it arrives: the start of its next tick.
        if (pendingMotion != null) {
            setDeltaMovement(pendingMotion);
            pendingMotion = null;
        }
        if (missTime > 0) --missTime;
        Driver.Action a = pilot == null ? null : pilot.decide(this);
        boolean fwd = false, back = false, left = false, right = false, jump = false, sprint = false, sneak = false;
        lastAttackRequested = false;
        if (a != null) {
            look(a.yawDelta(), a.pitchDelta());
            if (a.has(Driver.Action.ATTACK)) {
                lastAttackRequested = true;
                clickAttack();
            }
            // Minecraft.continueAttack(false): the model clicks, it never holds the key, so a whiff's lockout clears at once
            // (vanilla clears it when the attack key is not held).
            missTime = 0;
            fwd = a.has(Driver.Action.FORWARD);
            back = a.has(Driver.Action.BACK);
            left = a.has(Driver.Action.LEFT);
            right = a.has(Driver.Action.RIGHT);
            jump = a.has(Driver.Action.JUMP);
            sprint = a.has(Driver.Action.SPRINT);
            sneak = a.has(Driver.Action.SNEAK);
        }
        movementInput(fwd, back, left, right, jump, sprint, sneak);
        doTick();
        prevShift = sneak;
    }

    /** Entity.turn in degrees; yaw is never wrapped, like a client's. */
    private void look(float yawDelta, float pitchDelta) {
        float yaw = getYRot() + yawDelta;
        float pitch = Mth.clamp(getXRot() + pitchDelta, -90.0f, 90.0f);
        setYRot(yaw);
        setXRot(pitch);
        yRotO += yawDelta;
        xRotO = Mth.clamp(xRotO + pitchDelta, -90.0f, 90.0f);
        setYHeadRot(yaw);
    }

    /** Minecraft.startAttack, then the server's attack for whatever the pick hit. */
    private void clickAttack() {
        if (missTime > 0 || getHealth() <= 0) return;
        ItemStack held = getMainHandItem();
        if (cannotAttackWithItem(held, 0)) return;
        clicks++;
        Entity target = pick();
        if (target != null) {
            if (isWithinAttackRange(held, target.getBoundingBox(), 3.0) && !cannotAttackWithItem(held, 5)) attack(target);
        } else {
            missTime = 10;
            whiffs++;
        }
        resetAttackStrengthTicker();
        swing(InteractionHand.MAIN_HAND);
    }

    private Entity pick() {
        return pick(false);
    }


    /**
     * Minecraft.pick: block-limited ray, nearest box hit, within entity reach (players, as this body's client sees them).
     */
    private Entity pick(boolean unused) {
        Vec3 eye = getEyePosition();
        Vec3 look = calculateViewVector(getXRot(), getYRot());
        double maxRange = Math.max(blockInteractionRange(), entityInteractionRange());
        Vec3 end = eye.add(look.scale(maxRange));
        HitResult block = level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                CollisionContext.of(this)));
        double blockDistSq = block.getLocation().distanceToSqr(eye);
        double nearest = maxRange * maxRange;
        if (block.getType() != HitResult.Type.MISS) {
            nearest = blockDistSq;
            end = eye.add(look.scale(Math.sqrt(blockDistSq)));
        }
        Entity best = null;
        java.util.List<Entity> targets = new java.util.ArrayList<>(level().players());
        for (Entity other : targets) {
            if (other == this || other.isSpectator() || !other.isAlive()) continue;
            if (!mayHit(other)) continue;                 // only the opponent: never a bystander
            Seen v = seenOf(other);
            AABB box = v != null ? v.box() : other.getBoundingBox();      // where this body's client sees it
            Optional<Vec3> clip = box.clip(eye, end);
            if (box.contains(eye)) {
                if (nearest >= 0.0) {
                    best = other;
                    nearest = 0.0;
                }
            } else if (clip.isPresent()) {
                double d = eye.distanceToSqr(clip.get());
                if (d < nearest || nearest == 0.0) {
                    best = other;
                    nearest = d;
                }
            }
        }
        if (best == null || !(nearest < blockDistSq)) return null;
        double range = entityInteractionRange();
        return nearest < range * range ? best : null;
    }

    /** LocalPlayer.aiStep (input half) and applyInput / modifyInput. */
    private void movementInput(boolean fwd, boolean back, boolean left, boolean right,
                               boolean jump, boolean sprintKey, boolean sneak) {
        if (sprintTriggerTime > 0) --sprintTriggerTime;
        boolean wasShift = prevShift, hadForward = prevForwardImpulse;
        crouching = !isSwimming() && !isPassenger()
                && canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING)
                && (wasShift || !canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING));

        Vec2 move = new Vec2((left ? 1f : 0f) - (right ? 1f : 0f), (fwd ? 1f : 0f) - (back ? 1f : 0f)).normalized();
        boolean forwardNow = move.y > 1.0E-5f;
        if (wasShift || back) sprintTriggerTime = 0;
        boolean enoughFood = getFoodData().getFoodLevel() > 6 || getAbilities().mayfly;
        boolean canStart = !isSprinting() && forwardNow && enoughFood && !crouching && !isUsingItem() && !isFallFlying();
        if (canStart) {
            if (!hadForward) {
                if (sprintTriggerTime > 0) setSprinting(true);
                else sprintTriggerTime = 7;
            }
            if (sprintKey) setSprinting(true);
        }
        if (isSprinting() && (!forwardNow || !enoughFood || (horizontalCollision && !minorHorizontalCollision)))
            setSprinting(false);

        Vec2 v = move;
        if (v.lengthSquared() != 0.0f) {
            v = v.scale(0.98f);
            // LocalPlayer.modifyInput: an item in use (drinking, drawing, blocking) slows to 20%.
            if (isUsingItem() && !isPassenger()) v = v.scale(0.2f);
            if (crouching) v = v.scale((float) getAttributeValue(Attributes.SNEAKING_SPEED));
            float length = v.length();
            if (length > 0.0f) {
                Vec2 dir = v.scale(1.0f / length);
                float ax = Math.abs(dir.x), ay = Math.abs(dir.y);
                float tan = ay > ax ? ax / ay : ay / ax;
                v = dir.scale(Math.min(length * Mth.sqrt(1.0f + tan * tan), 1.0f));
            }
        }
        inputXxa = v.x;
        inputZza = v.y;
        inputJump = jump;
        setShiftKeyDown(sneak);
        setLastClientInput(new Input(fwd, back, left, right, jump, sneak, sprintKey));
        prevForwardImpulse = forwardNow;
    }

    /** LocalPlayer.applyInput: the computed input replaces the base class's decay. */
    @Override
    protected void applyInput() {
        this.xxa = inputXxa;
        this.zza = inputZza;
        this.jumping = inputJump;
    }

    /**
     * This body is its own client. Vanilla marks server-side players client-authoritative (their
     * clients report moves), which would skip fall-distance bookkeeping here: no crits ever.
     */
    @Override
    public boolean isClientAuthoritative() {
        return false;
    }

    /** A practice bot never drops its kit. */
    @Override
    protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
    }
}

