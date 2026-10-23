package dev.agentcraft.client.ui;
public final class Kit {
 public static final String HEADER = "header", KEYCAP = "keycap", TAB_ACTIVE = "tab", TAB_INACTIVE = "tab",
  SCROLL_TRACK = "scroll", SCROLL_THUMB = "scroll", SCROLL_THUMB_HOVER = "scroll", SCROLL_GRIP = "scroll";
 public record Padding(int left, int top, int right, int bottom) {}
 public static Padding padding(String s) { return new Padding(8, 8, 8, 9); }
 public static String button(boolean primary, String state) { return state; } public static String icon(String s) { return s; }
}
