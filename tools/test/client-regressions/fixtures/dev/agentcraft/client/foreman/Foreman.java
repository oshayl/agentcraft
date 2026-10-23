package dev.agentcraft.client.foreman;
import java.util.*;
import java.util.concurrent.*;
public final class Foreman {
 public record Request(String source, String note, String query, CompletableFuture<Protocol.Library> future) {}
 public record Action(String action, String goal) {}
 public static final List<Request> requests = new ArrayList<>();
 public static final List<Action> actions = new ArrayList<>();
 public static final ForemanState STATE = new ForemanState();
 public static boolean online = true;
 public static ForemanState state() { return STATE; }
 public static boolean connected() { return online; }
 public static CompletableFuture<Protocol.Library> requestLibrary(String source, String note, String query, Integer limit) {
  var f = new CompletableFuture<Protocol.Library>(); requests.add(new Request(source, note, query, f)); return f;
 }
 public static CompletableFuture<Protocol.Ack> goalAction(String action, String goal) {
  actions.add(new Action(action, goal)); return CompletableFuture.completedFuture(new Protocol.Ack("ack", true, null, null));
 }
}
