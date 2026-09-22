package com.rawal.pocketdeck;

/** Android event values: resumed=1, paused=2, screen off=16, shutdown=26. */
final class PlayTimeline {
    private final String target, launcher;
    private final long start, end;
    private long active = -1;
    long duration;
    PlayTimeline(String target, String launcher, long start, long end) {
        this.target = target; this.launcher = launcher; this.start = start; this.end = end;
    }
    boolean event(int type, String pkg, long timestamp) {
        long at = Math.max(start, Math.min(end, timestamp));
        boolean game = target.equals(pkg), resume = type == 1;
        if (active >= 0 && ((type == 2 && game) || type == 16 || type == 26 || (resume && !game))) {
            duration += Math.max(0, at - active); active = -1;
        }
        if (resume && launcher.equals(pkg) && at > start) return false;
        if (resume && game && active < 0) active = at;
        return true;
    }
}
