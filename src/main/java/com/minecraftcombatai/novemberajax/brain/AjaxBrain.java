package com.minecraftcombatai.novemberajax.brain;

import java.io.IOException;
import java.io.InputStream;
import java.util.SplittableRandom;

/**
 * One fighter's AJAX: its inputs, its two-second window, the network and the action decode. One per body (the integrated
 * server and the client each keep their own), so nothing is shared between threads.
 *
 * <p>The turn is RAW: the yaw and pitch sizes the network picks are the turn made, nothing smoothed, delayed or jittered.
 */
public final class AjaxBrain {
    public static final String WEIGHTS = "/assets/november_ajax/model/ajax-open-0001.ajx";
    private static final float[] YAW = {-80f, -45f, -25f, -14f, -8f, -5f, -3f, -1.8f, -1f, -0.5f, 0f, 0.5f, 1f, 1.8f, 3f, 5f, 8f, 14f, 25f, 45f, 80f};
    private static final float[] PITCH = {-25f, -10f, -4f, -1.5f, -0.5f, 0f, 0.5f, 1.5f, 4f, 10f, 25f};
    // key bits, the same as the mod's Driver.Action
    public static final int FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8, JUMP = 16, SPRINT = 32, SNEAK = 64, ATTACK = 128;

    /** keys + raw turn in degrees */
    public record Decision(int keys, float yawDelta, float pitchDelta) {}

    private final AjaxNet net;
    private final AjaxInputs inputs = new AjaxInputs();
    private final float[][] window = new float[AjaxNet.WINDOW][AjaxInputs.SIZE];
    private final SplittableRandom rng;
    public long decisions, nanos;


    public AjaxBrain(long seed) throws IOException {
        try (InputStream in = AjaxBrain.class.getResourceAsStream(WEIGHTS)) {
            if (in == null) throw new IOException("the model file is missing from the jar: " + WEIGHTS);
            net = new AjaxNet(in);
        }
        rng = new SplittableRandom(seed);
    }

    public String buildTag() {
        return net.buildTag;
    }

    /** A new fight: no memory of the last one. */
    public void reset() {
        inputs.reset();
        for (float[] w : window) java.util.Arrays.fill(w, 0f);
    }

    public Decision decide(AjaxInputs.Frame f) {
        long t0 = System.nanoTime();
        float[] oldest = window[0];
        System.arraycopy(window, 1, window, 0, AjaxNet.WINDOW - 1);
        window[AjaxNet.WINDOW - 1] = oldest;
        inputs.step(f, oldest);
        int[] a = net.decode(net.trunk(window), rng, null, null);
        int keys = 0;
        if (a[0] == 2) keys |= FORWARD;
        if (a[0] == 0) keys |= BACK;
        if (a[1] == 0) keys |= LEFT;
        if (a[1] == 2) keys |= RIGHT;
        if (a[2] == 1) keys |= JUMP;
        if (a[3] == 1) keys |= SPRINT;
        if (a[4] == 1) keys |= SNEAK;
        if (a[7] == 1) keys |= ATTACK;
        decisions++;
        nanos += System.nanoTime() - t0;
        return new Decision(keys, YAW[a[5]], PITCH[a[6]]);
    }
}

