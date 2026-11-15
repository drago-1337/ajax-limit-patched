package com.minecraftcombatai.novemberajax.brain;

/**
 * AJAX's 62 inputs from one tick of raw game state, built exactly the way they were when he learned to fight. Only raw facts go in (positions, look, health, hurt, swing, armour, ping), the same things the
 * game shows any player.
 */
public final class AjaxInputs {
    public static final int SIZE = 62;
    static final double ATTACK_DELAY_SWORD = 12.5;

    /** One tick of what this fighter knows. "s" = AJAX, "o" = the opponent. */
    public static final class Frame {
        public double sx, sy, sz, ox, oy, oz;
        public float syaw, spitch, oyaw, opitch;
        public boolean sground, ssprint, ssneak, sswing, oground, osprint, osneak, oswing;
        public float shurt, shp = 20, sabs, scharge = 1, ohurt, ohp = 20;
        public boolean shpKnown = true, schargeKnown = true, ohpKnown = true, sSword = true, oSword = true;
        public float sarmor = 20, sweapon = 7, oarmor = 20, pingS = 20, pingO = 20;
        public boolean pingKnown = true;

        void copyFrom(Frame f) {
            sx = f.sx; sy = f.sy; sz = f.sz; ox = f.ox; oy = f.oy; oz = f.oz;
            syaw = f.syaw; spitch = f.spitch; oyaw = f.oyaw; opitch = f.opitch;
            sground = f.sground; ssprint = f.ssprint; ssneak = f.ssneak; sswing = f.sswing;
            oground = f.oground; osprint = f.osprint; osneak = f.osneak; oswing = f.oswing;
            shurt = f.shurt; shp = f.shp; sabs = f.sabs; scharge = f.scharge; ohurt = f.ohurt; ohp = f.ohp;
            shpKnown = f.shpKnown; schargeKnown = f.schargeKnown; ohpKnown = f.ohpKnown; sSword = f.sSword; oSword = f.oSword;
            sarmor = f.sarmor; sweapon = f.sweapon; oarmor = f.oarmor; pingS = f.pingS; pingO = f.pingO; pingKnown = f.pingKnown;
        }
    }

    private final Frame p = new Frame();
    private boolean have;
    private int sinceGot = 999, sinceLanded = 999, sinceOsw = 999, sinceSsw = 999, runS, runO, ticks;

    public void reset() {
        have = false;
        sinceGot = sinceLanded = sinceOsw = sinceSsw = 999;
        runS = runO = ticks = 0;
    }

    public void step(Frame f, float[] out) {
        int n = 0;
        double yawr = Math.toRadians(f.syaw), pitr = Math.toRadians(f.spitch);
        double svx = 0, svy = 0, svz = 0, ovx = 0, ovy = 0, ovz = 0, dyaw = 0, dpit = 0, odyaw = 0, odpit = 0;
        boolean got = false, landed = false, osw = false, ssw = false;
        if (have) {
            svx = (float) f.sx - (float) p.sx; svy = (float) f.sy - (float) p.sy; svz = (float) f.sz - (float) p.sz;
            ovx = (float) f.ox - (float) p.ox; ovy = (float) f.oy - (float) p.oy; ovz = (float) f.oz - (float) p.oz;
            dyaw = wrapDeg((double) f.syaw - p.syaw); dpit = (double) f.spitch - p.spitch;
            odyaw = wrapDeg((double) f.oyaw - p.oyaw); odpit = (double) f.opitch - p.opitch;
            got = f.shurt > p.shurt;
            landed = f.ohurt > p.ohurt;
            osw = f.oswing && !p.oswing;
            ssw = f.sswing && !p.sswing;
            if (svx * svx + svz * svz > 16.0) svx = svy = svz = 0;
            if (ovx * ovx + ovz * ovz > 16.0) ovx = ovy = ovz = 0;
        }
        sinceGot = got ? 0 : Math.min(sinceGot + 1, 999);
        sinceLanded = landed ? 0 : Math.min(sinceLanded + 1, 999);
        sinceOsw = osw ? 0 : Math.min(sinceOsw + 1, 999);
        sinceSsw = ssw ? 0 : Math.min(sinceSsw + 1, 999);
        if (landed) { runS = Math.min(runS + 1, 20); runO = 0; }
        if (got) { runO = Math.min(runO + 1, 20); runS = 0; }

        // positions in float32, as when he learned, so the geometry matches to the bit
        double fsx = (float) f.sx, fsy = (float) f.sy, fsz = (float) f.sz, fox = (float) f.ox, foy = (float) f.oy, foz = (float) f.oz;
        double dx = fox - fsx, dy = foy - fsy, dz = foz - fsz;
        double dist = Math.hypot(dx, dz);
        double eyeY = fsy + (f.ssneak ? 1.27 : 1.62);
        double bx = Math.max(0.0, Math.abs(dx) - 0.3), bz = Math.max(0.0, Math.abs(dz) - 0.3);
        double by = Math.max(Math.max(foy - eyeY, eyeY - (foy + 1.8)), 0.0);
        double box = Math.sqrt(bx * bx + by * by + bz * bz);
        double yawErr = wrapDeg(Math.toDegrees(Math.atan2(-dx, dz)) - f.syaw);
        double pitchErr = -Math.toDegrees(Math.atan2(foy + 0.9 - eyeY, dist)) - f.spitch;
        double lx = -Math.sin(yawr) * Math.cos(pitr), ly = -Math.sin(pitr), lz = Math.cos(yawr) * Math.cos(pitr);
        boolean onBox = rayHitsBox(fsx, eyeY, fsz, lx, ly, lz, fox - 0.3, foy, foz - 0.3, fox + 0.3, foy + 1.8, foz + 0.3, 3.0);
        double theirErr = wrapDeg(Math.toDegrees(Math.atan2(dx, -dz)) - f.oyaw);
        double ux = dist > 1e-6 ? dx / dist : 0.0, uz = dist > 1e-6 ? dz / dist : 0.0;
        double tx = -uz, tz = ux;
        double sSpeed = Math.hypot(svx, svz);
        double sHead = Math.atan2(svx * (-Math.cos(yawr)) + svz * (-Math.sin(yawr)), svx * (-Math.sin(yawr)) + svz * Math.cos(yawr));
        double schg = f.schargeKnown ? f.scharge : Math.min(1.0, (sinceSsw + 0.5) / ATTACK_DELAY_SWORD);
        double ochg = Math.min(1.0, (sinceOsw + 0.5) / ATTACK_DELAY_SWORD);

        // ---- me (0..15)
        out[n++] = b(f.sground);
        out[n++] = b(f.ssprint);
        out[n++] = b(f.ssneak);
        out[n++] = f.shurt > 0.5f ? 1f : 0f;
        out[n++] = (float) (f.shurt / 10.0);
        out[n++] = (float) (f.shp / 20.0);
        out[n++] = b(f.shpKnown);
        out[n++] = (float) schg;
        out[n++] = schg >= 0.95 ? 1f : 0f;
        out[n++] = (float) c(sinceSsw / 40.0, 0, 1);
        out[n++] = (float) c(sSpeed / 0.4, 0, 2);
        out[n++] = (float) (Math.cos(sHead) * c(sSpeed / 0.4, 0, 2));
        out[n++] = (float) (Math.sin(sHead) * c(sSpeed / 0.4, 0, 2));
        out[n++] = (float) c(svy / 0.4, -2, 2);
        out[n++] = (float) Math.sin(pitr);
        out[n++] = (float) c(f.sabs / 8.0, 0, 2);
        // ---- them, polar (16..47)
        out[n++] = (float) c(dist / 6.0, 0, 3);
        out[n++] = (float) c(box / 3.0, 0, 3);
        out[n++] = box <= 3.0 ? 1f : 0f;
        out[n++] = (float) (Math.log(1.0 + dist) / Math.log(2) / 4.0);
        out[n++] = onBox ? 1f : 0f;
        out[n++] = (float) Math.cos(Math.toRadians(yawErr));
        out[n++] = (float) c(yawErr / 45.0, -4, 4);
        out[n++] = (float) Math.sin(Math.toRadians(yawErr));
        out[n++] = (float) c(pitchErr / 45.0, -2, 2);
        out[n++] = (float) c(dy / 3.0, -2, 2);
        out[n++] = (float) c(-(ovx * ux + ovz * uz) / 0.4, -2, 2);
        out[n++] = (float) c((ovx * tx + ovz * tz) / 0.4, -2, 2);
        out[n++] = (float) c(ovy / 0.4, -2, 2);
        out[n++] = (float) c((svx * ux + svz * uz) / 0.4, -2, 2);
        out[n++] = (float) c((svx * tx + svz * tz) / 0.4, -2, 2);
        out[n++] = (float) Math.cos(Math.toRadians(theirErr));
        out[n++] = (float) Math.sin(Math.toRadians(theirErr));
        out[n++] = (float) c(odyaw / 30.0, -2, 2);
        out[n++] = (float) c(odpit / 30.0, -2, 2);
        out[n++] = (float) (f.opitch / 90.0);
        out[n++] = b(f.oground);
        out[n++] = b(f.osprint);
        out[n++] = b(f.osneak);
        out[n++] = f.ohurt > 0.5f ? 1f : 0f;
        out[n++] = (float) (f.ohurt / 10.0);
        out[n++] = (float) (f.ohp / 20.0);
        out[n++] = b(f.ohpKnown);
        out[n++] = b(f.oswing);
        out[n++] = (float) ochg;
        out[n++] = (float) c(sinceOsw / 40.0, 0, 1);
        out[n++] = (float) (f.oarmor / 20.0);
        out[n++] = b(f.oSword);
        // ---- the exchange (48..53)
        out[n++] = (float) c(sinceLanded / 60.0, 0, 1);
        out[n++] = (float) c(sinceGot / 60.0, 0, 1);
        out[n++] = (float) c(runS / 5.0, 0, 1);
        out[n++] = (float) c(runO / 5.0, 0, 1);
        out[n++] = (float) c((f.shp - f.ohp) / 20.0, -1, 1);
        out[n++] = b(f.sSword);
        // ---- my camera's real motion and the network (54..61)
        out[n++] = (float) c(dyaw / 30.0, -3, 3);
        out[n++] = (float) c(dpit / 30.0, -3, 3);
        out[n++] = (float) (f.sarmor / 20.0);
        out[n++] = (float) (f.sweapon / 10.0);
        out[n++] = (float) c(f.pingS / 250.0, 0, 2);
        out[n++] = (float) c(f.pingO / 250.0, 0, 2);
        out[n++] = b(f.pingKnown);
        out[n++] = (float) c(ticks / 400.0, 0, 1);
        ticks = Math.min(ticks + 1, 100000);
        p.copyFrom(f);
        have = true;
    }

    private static float b(boolean v) {
        return v ? 1f : 0f;
    }

    private static double c(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double wrapDeg(double a) {
        a = (a + 180.0) % 360.0;
        if (a < 0) a += 360.0;
        return a - 180.0;
    }

    static boolean rayHitsBox(double ex, double ey, double ez, double dx, double dy, double dz,
                              double minx, double miny, double minz, double maxx, double maxy, double maxz, double reach) {
        double t0 = 0.0, t1 = reach;
        double[] o = {ex, ey, ez}, d = {dx, dy, dz}, a = {minx, miny, minz}, bb = {maxx, maxy, maxz};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-9) {
                if (o[i] < a[i] || o[i] > bb[i]) return false;
                continue;
            }
            double p = (a[i] - o[i]) / d[i], q = (bb[i] - o[i]) / d[i];
            if (p > q) { double s = p; p = q; q = s; }
            t0 = Math.max(t0, p);
            t1 = Math.min(t1, q);
            if (t0 > t1) return false;
        }
        return true;
    }
}

