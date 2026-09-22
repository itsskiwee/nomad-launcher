package com.rawal.pocketdeck;

public class PlayTimelineTest {
    static PlayTimeline timeline() { return new PlayTimeline("game", "nomad", 100, 10000); }
    static void equal(long actual, long expected) {
        if (actual != expected) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        PlayTimeline t = timeline();
        t.event(1, "game", 200); t.event(2, "game", 1200);
        t.event(1, "other", 1200); t.event(1, "game", 5000); t.event(2, "game", 6000);
        equal(t.duration, 2000); // Background time excluded.
        t = timeline(); t.event(1, "game", 200); t.event(16, "android", 1200);
        t.event(2, "game", 1300); t.event(1, "game", 8000); t.event(26, "android", 9000);
        equal(t.duration, 2000); // Screen off and shutdown close active intervals.
        t = timeline(); t.event(1, "game", 200); t.event(1, "game", 300);
        if (t.event(1, "nomad", 1200)) throw new AssertionError("Session must end at home");
        equal(t.duration, 1000); // Duplicate resumes do not double count.
        t = timeline(); t.event(1, "other", 200); t.event(2, "game", 400);
        equal(t.duration, 0); // Failed launch does not create playtime.
        t = timeline(); t.event(1, "game", 200); equal(t.duration, 0); // Missing end isn't guessed.
        System.out.println("PASS: foreground intervals, app switching, screen off, shutdown, duplicate events and failed launches");
    }
}
