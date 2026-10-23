package dev.agentcraft.client.library;
import java.util.*;
import dev.agentcraft.client.foreman.Protocol.MemoryEntry;
public final class MemoryIndex {
 public static final String ALL = "", ARTIFACTS = "artifacts";
 public static List<MemoryEntry> entries(String scope) { return FileNotes.isSource(scope) ? FileNotes.entries(scope) : List.of(); }
 public static List<String> scopes() { return List.of(); } public static boolean isPlan(MemoryEntry e) { return false; }
 public static boolean isUnread(MemoryEntry e) { return false; } public static boolean isArtifact(MemoryEntry e) { return false; }
 public static int count(String scope) { return 0; } public static long newest(String scope) { return 0; }
 public static String scopeLabel(String s) { return s; } public static String itemCount(MemoryEntry e) { return "0"; }
 public static void markSeen(MemoryEntry e) {}
}
