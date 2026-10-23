# Client logic regressions

Run `python3 tools/test/client-regressions.py` from the repository (Java 25 required).

This compiles the actual `GoalScreen`, `FileNotes`, `LibraryScreen`, and protocol sources.
The fixtures stand in for Minecraft rendering/input, the network transport, and unrelated UI
helpers. The tests drive real screen input, examine generated hit targets against the recorded
scissor viewport, deliver asynchronous library replies, and observe clipboard writes.

No game or Foreman process is started. This does not verify Minecraft API compatibility or visual
appearance; run the normal Java compilation too, and use in-game QA for appearance/input integration.
