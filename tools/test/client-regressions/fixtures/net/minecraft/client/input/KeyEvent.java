package net.minecraft.client.input;
public record KeyEvent(int input) { public boolean isEscape() { return input == 256; } public boolean hasShiftDown() { return false; } }
