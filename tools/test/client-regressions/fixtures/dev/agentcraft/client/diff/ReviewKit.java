package dev.agentcraft.client.diff;
import net.minecraft.client.gui.*;
public final class ReviewKit {
 public static boolean blurBehind = false;
 public static int ink() { return 0; } public static int muted() { return 0; }
 public static String ago(long time) { return "now"; } public static int agentInk(String id) { return 0; }
 public static String agentName(String id) { return id; } public static int mix(int a, int b, float t) { return a; }
 public static void bold(GuiGraphicsExtractor g, Font f, String s, int x, int y, int color) {}
 public static void face(GuiGraphicsExtractor g, Font f, String s, int x, int y, int w) {}
 public static int chip(GuiGraphicsExtractor g, Font f, String s, int x, int y, int a, int b) { return 10; }
 public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {}
 public static int hintWidth(Font f, String key, String verb) { return 20; }
 public static void hint(GuiGraphicsExtractor g, Font f, String key, String verb, int x, int y) {}
}
