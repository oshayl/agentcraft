package net.minecraft.client.gui.screens;
import net.minecraft.client.gui.*;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
public class Screen {
 public int width = 640, height = 260;
 protected final Font font = new Font();
 public final Client minecraft = new Client();
 public Screen(Component title) {} public boolean isPauseScreen() { return false; }
 protected void init() {} public void onClose() {}
 public void extractBackground(GuiGraphicsExtractor g, int x, int y, float a) {}
 public void extractTransparentBackground(GuiGraphicsExtractor g) {} public void extractBlurredBackground(GuiGraphicsExtractor g) {}
 public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float a) {}
 public boolean keyPressed(KeyEvent e) { return false; }
 public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) { return false; }
 public boolean mouseReleased(MouseButtonEvent e) { return false; }
 public boolean mouseDragged(MouseButtonEvent e, double x, double y) { return false; }
 public boolean mouseScrolled(double x, double y, double h, double v) { return false; }
 public static class Client {
  public final Keyboard keyboardHandler = new Keyboard(); public Window getWindow() { return new Window(); }
 }
 public static class Keyboard { public String clipboard = "unchanged"; public void setClipboard(String s) { clipboard = s; } }
 public static class Window { public int getGuiScale() { return 1; } }
}
