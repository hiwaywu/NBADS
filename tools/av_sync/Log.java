package android.util;

/** Stand-in for android.util.Log so Relay.java runs on a desktop JVM (av_sync_check.py). */
public final class Log {
    private Log() { }
    public static int i(String t, String m) { System.out.println("I/" + t + ": " + m); return 0; }
    public static int w(String t, String m) { System.out.println("W/" + t + ": " + m); return 0; }
    public static int e(String t, String m) { System.out.println("E/" + t + ": " + m); return 0; }
    public static int d(String t, String m) { return 0; }
}
