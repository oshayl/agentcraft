package net.minecraft.client.gui;
import java.util.*;
public class GuiGraphicsExtractor {
 public final List<int[]> scissors = new ArrayList<>();
 public void enableScissor(int x, int y, int right, int bottom) { scissors.add(new int[] {x, y, right, bottom}); }
 public void disableScissor() {} public void fill(int a, int b, int c, int d, int color) {}
 public void fillGradient(int a, int b, int c, int d, int e, int f) {}
 public void text(Font f, Object text, int x, int y, int color, boolean shadow) {}
 public Pose pose() { return new Pose(); }
 public static class Pose { public void pushMatrix() {} public void popMatrix() {} public void translate(float x, float y) {} }
}
