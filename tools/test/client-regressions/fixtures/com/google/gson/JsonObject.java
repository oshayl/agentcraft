package com.google.gson;
public class JsonObject extends JsonElement {
 public void addProperty(String k, String v) {} public void addProperty(String k, Number v) {}
 public void addProperty(String k, Boolean v) {} public void add(String k, JsonElement v) {}
 public boolean has(String k) { return false; } public JsonArray getAsJsonArray(String k) { return new JsonArray(); }
}
