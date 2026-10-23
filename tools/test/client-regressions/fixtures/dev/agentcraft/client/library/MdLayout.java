package dev.agentcraft.client.library;
import java.util.*;
import java.util.function.Function;
import net.minecraft.client.gui.*;
public final class MdLayout {
 public static final int LINE_H = 10; public int height = 0;
 public static MdLayout build(Font f, List<Markdown.Block> blocks, int w, Set<String> ids) { return new MdLayout(); }
 public void draw(GuiGraphicsExtractor g, Font f, int x, int y, int base, int h, Function<String, String> family) {}
}
