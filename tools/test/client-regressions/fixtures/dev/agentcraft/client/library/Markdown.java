package dev.agentcraft.client.library;
import java.util.*;
public final class Markdown {
 public enum Type { H1, PARA, BULLET, NUMBER, TASK, QUOTE }
 public static class Block { public Type type = Type.PARA; public String text = ""; }
 public static List<Block> parse(String text) { return List.of(); } public static String plain(String text) { return text; }
}
