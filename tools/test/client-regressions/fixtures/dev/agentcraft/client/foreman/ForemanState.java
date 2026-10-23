package dev.agentcraft.client.foreman;
import java.util.*;
public final class ForemanState {
 public final Map<String, Protocol.Goal> goals = new LinkedHashMap<>();
 public final Map<String, Protocol.Task> tasks = new LinkedHashMap<>();
 public final List<Protocol.LibrarySource> sources = new ArrayList<>();
 public Map<String, Protocol.Goal> goals() { return goals; } public Map<String, Protocol.Task> tasks() { return tasks; }
 public Protocol.Repo repo(String id) { return null; } public Protocol.Task task(String id) { return tasks.get(id); }
 public boolean hasData() { return true; } public boolean isStale() { return false; } public long revision() { return 1; }
 public Protocol.ForemanStatus status() { return new Protocol.ForemanStatus(null, null, null, null, null, null, null, null, null, null, sources); }
}
