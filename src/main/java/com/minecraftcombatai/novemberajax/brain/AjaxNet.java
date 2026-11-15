package com.minecraftcombatai.novemberajax.brain;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * November AJAX's network, in plain Java: the public build (ajax-open-*.ajx, the TorchScript release file's own tensors).
 *
 * <p>40 ticks (2 seconds) of AJAX's 62 inputs go in. 26 of those ticks (every tick for the last 12, then every second tick)
 * pass one shared 24-unit encoder; the encodings, this tick's inputs and the LT4 tier row feed two dense layers; a chain of
 * ten heads then picks aim first (yaw, pitch), then the click, then the feet, each head seeing the choices before it.
 *
 * <p>Every weight is a 12-bit integer times a per-row scale, and every layer output is rounded to 1/4096, exactly like the
 * release file, so this port plays the same as the file it was read from.
 */
public final class AjaxNet {
    public static final int OBS = 62, WINDOW = 40, BRANCHES = 10;
    public static final int[] SIZES = {3, 3, 2, 2, 2, 21, 11, 2, 2, 9};          // branch order
    public static final int[] ORDER = {5, 6, 7, 0, 1, 2, 3, 4, 8, 9};             // AJAX's head chain: aim, click, feet
    private static final int[] AGES;
    private static final float STEP = 0.000244140625f;
    private static final int CONTRACT_MAGIC = 0x41A7A4;

    static {
        int[] a = new int[26];
        int n = 0;
        for (int i = 0; i < 12; i++) a[n++] = i;
        for (int i = 13; i < WINDOW; i += 2) a[n++] = i;
        AGES = a;
    }

    /** y = round((W x + b) / step) * step, W[out][in] decoded from q * s. */
    private static final class Lin {
        final float[] w, b;
        final int in, out;

        Lin(Map<String, Tensor> t, String name) {
            Tensor q = t.get(name + ".q"), s = t.get(name + ".s");
            out = q.dims[0];
            in = q.dims[1];
            w = new float[out * in];
            for (int o = 0; o < out; o++) {
                float sc = s.f[o];
                for (int i = 0; i < in; i++) w[o * in + i] = (float) q.s[o * in + i] * sc;
            }
            b = t.containsKey(name + ".b") ? t.get(name + ".b").f : new float[out];
        }

        void apply(float[] x, float[] y) {
            for (int o = 0; o < out; o++) {
                double acc = 0;
                int r = o * in;
                for (int i = 0; i < in; i++) acc += (double) w[r + i] * x[i];
                y[o] = rnd((float) acc + b[o]);
            }
        }
    }

    private final Lin enc, l1, l2, msg0;
    private final Lin[] heads = new Lin[BRANCHES], updates = new Lin[BRANCHES];
    private final float[][] embeds = new float[BRANCHES][];
    private final float[] tierRow, n1w, n1b, n2w, n2b;
    private final int embDim, msgDim, frameDim, h1, h2;
    public final String buildTag;

    // scratch
    private final float[] encIn, encOut, l1In, hA, hB, feat, msg, headIn, upIn, upOut, logits;

    public AjaxNet(InputStream in) throws IOException {
        Map<String, Tensor> t = read(in);
        long[] c = t.get("ajax_contract").l;
        if (c[0] != CONTRACT_MAGIC || c[1] != 1 || c[2] != WINDOW || c[3] != OBS)
            throw new IOException("not a November AJAX v1 weight file");
        enc = new Lin(t, "enc");
        l1 = new Lin(t, "l1");
        l2 = new Lin(t, "l2");
        msg0 = new Lin(t, "msg0");
        for (int j = 0; j < BRANCHES; j++) {
            heads[j] = new Lin(t, "heads." + j);
            updates[j] = new Lin(t, "updates." + j);
            Tensor q = t.get("embeds." + j + ".q"), s = t.get("embeds." + j + ".s");
            float[] e = new float[q.s.length];
            int d = q.dims[1];
            for (int r = 0; r < q.dims[0]; r++)
                for (int k = 0; k < d; k++) e[r * d + k] = (float) q.s[r * d + k] * s.f[r];
            embeds[j] = e;
            if (heads[j].out != SIZES[ORDER[j]]) throw new IOException("head " + j + " size " + heads[j].out);
        }
        tierRow = t.get("tier_row").f;
        n1w = t.get("n1.w").f; n1b = t.get("n1.b").f;
        n2w = t.get("n2.w").f; n2b = t.get("n2.b").f;
        embDim = t.get("embeds.0.q").dims[1];
        msgDim = msg0.out;
        frameDim = enc.out;
        h1 = l1.out;
        h2 = l2.out;
        if (enc.in != OBS + 1 || l1.in != AGES.length * frameDim + OBS) throw new IOException("unexpected layer sizes");
        byte[] tag = t.get("build_tag").u;
        StringBuilder sb = new StringBuilder();
        for (byte b : tag) sb.append(String.format("%02x", b & 0xff));
        buildTag = sb.toString();
        encIn = new float[OBS + 1];
        encOut = new float[frameDim];
        l1In = new float[l1.in];
        hA = new float[h1];
        hB = new float[h2];
        feat = new float[h2];
        msg = new float[msgDim];
        headIn = new float[h2 + msgDim];
        upIn = new float[msgDim + embDim];
        upOut = new float[msgDim];
        logits = new float[32];
    }

    /**
     * Features for one tick. {@code win} holds WINDOW frames of OBS inputs, oldest first, newest last; a frame of all zeros
     * is a tick that has not happened (fight start) and contributes nothing.
     */
    public float[] trunk(float[][] win) {
        int off = 0;
        for (int a : AGES) {
            float[] x = win[WINDOW - 1 - a];
            double sum = 0;
            for (int i = 0; i < OBS; i++) {
                encIn[i] = x[i];
                sum += Math.abs(x[i]);
            }
            float pres = sum > 0 ? 1f : 0f;
            encIn[OBS] = pres;
            enc.apply(encIn, encOut);
            for (int k = 0; k < frameDim; k++) l1In[off + k] = silu(encOut[k]) * pres;
            off += frameDim;
        }
        System.arraycopy(win[WINDOW - 1], 0, l1In, off, OBS);
        l1.apply(l1In, hA);
        for (int k = 0; k < h1; k++) hA[k] += tierRow[k];
        norm(hA, n1w, n1b);
        for (int k = 0; k < h1; k++) hA[k] = silu(hA[k]);
        l2.apply(hA, hB);
        norm(hB, n2w, n2b);
        for (int k = 0; k < h2; k++) feat[k] = silu(hB[k]);
        return feat;
    }

    /**
     * Chain sampling at temperature 1, the way AJAX was trained and gated: returns the ten choices in branch order.
     * If {@code given} is not null its choices are used instead of sampling, and the logits of every head are written to
     * {@code logitsOut} in branch order (for the parity test).
     */
    public int[] decode(float[] f, SplittableRandom rng, int[] given, float[] logitsOut) {
        msg0.apply(f, msg);
        for (int k = 0; k < msgDim; k++) msg[k] = (float) Math.tanh(msg[k]);
        int[] acts = new int[BRANCHES];
        int[] branchOff = new int[BRANCHES];
        for (int b = 1; b < BRANCHES; b++) branchOff[b] = branchOff[b - 1] + SIZES[b - 1];
        for (int j = 0; j < BRANCHES; j++) {
            int b = ORDER[j], n = SIZES[b];
            System.arraycopy(f, 0, headIn, 0, h2);
            System.arraycopy(msg, 0, headIn, h2, msgDim);
            heads[j].apply(headIn, logits);
            int a = given != null ? given[b] : sample(logits, n, rng);
            if (logitsOut != null) System.arraycopy(logits, 0, logitsOut, branchOff[b], n);
            acts[b] = a;
            System.arraycopy(msg, 0, upIn, 0, msgDim);
            System.arraycopy(embeds[j], a * embDim, upIn, msgDim, embDim);
            updates[j].apply(upIn, upOut);
            for (int k = 0; k < msgDim; k++) msg[k] = msg[k] + (float) Math.tanh(upOut[k]);
        }
        return acts;
    }

    private static int sample(float[] lg, int n, SplittableRandom rng) {
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) max = Math.max(max, lg[i]);
        double[] p = new double[n];
        double s = 0;
        for (int i = 0; i < n; i++) s += (p[i] = Math.exp(lg[i] - max));
        double u = rng.nextDouble() * s;
        for (int i = 0; i < n; i++) {
            u -= p[i];
            if (u <= 0) return i;
        }
        return n - 1;
    }

    private static void norm(float[] x, float[] w, float[] b) {
        double mean = 0, var = 0;
        for (float v : x) mean += v;
        mean /= x.length;
        for (float v : x) var += (v - mean) * (v - mean);
        var /= x.length;
        double inv = 1.0 / Math.sqrt(var + 1e-5);
        for (int i = 0; i < x.length; i++) x[i] = rnd((float) ((x[i] - mean) * inv) * w[i] + b[i]);
    }

    private static float rnd(float y) {
        return (float) Math.rint(y / STEP) * STEP;
    }

    private static float silu(float x) {
        return (float) (x / (1.0 + Math.exp(-x)));
    }

    // ------------------------------------------------------------------ .ajx reader

    private static final class Tensor {
        int[] dims;
        float[] f;
        short[] s;
        long[] l;
        byte[] u;
    }

    private static Map<String, Tensor> read(InputStream raw) throws IOException {
        byte[] all = new DataInputStream(raw).readAllBytes();
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        b.get(magic);
        if (!new String(magic, StandardCharsets.US_ASCII).equals("AJX1")) throw new IOException("not an .ajx file");
        int count = b.getInt();
        Map<String, Tensor> out = new HashMap<>();
        for (int i = 0; i < count; i++) {
            byte[] nb = new byte[b.getShort() & 0xffff];
            b.get(nb);
            int dtype = b.get(), nd = b.get();
            Tensor t = new Tensor();
            t.dims = new int[nd];
            int n = 1;
            for (int d = 0; d < nd; d++) n *= (t.dims[d] = b.getInt());
            switch (dtype) {
                case 0 -> { t.f = new float[n]; for (int k = 0; k < n; k++) t.f[k] = b.getFloat(); }
                case 1 -> { t.s = new short[n]; for (int k = 0; k < n; k++) t.s[k] = b.getShort(); }
                case 2 -> { t.l = new long[n]; for (int k = 0; k < n; k++) t.l[k] = b.getLong(); }
                case 3 -> { t.u = new byte[n]; b.get(t.u); }
                default -> throw new IOException("dtype " + dtype);
            }
            out.put(new String(nb, StandardCharsets.UTF_8), t);
        }
        return out;
    }
}

