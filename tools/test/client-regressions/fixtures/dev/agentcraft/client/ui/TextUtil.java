package dev.agentcraft.client.ui;
import java.util.*;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
public final class TextUtil {
 public static String ellipsize(Font f, String s, int w) { return s; }
 public static List<FormattedCharSequence> wrap(Font f, String s, int w) {
  var out = new ArrayList<FormattedCharSequence>();
  int chars = Math.max(1, w / 6);
  for (int i = 0; i < s.length(); i += chars) out.add(new FormattedCharSequence(s.substring(i, Math.min(s.length(), i + chars))));
  return out;
 }
}
