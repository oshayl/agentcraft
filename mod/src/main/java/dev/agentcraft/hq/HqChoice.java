package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Which HQ this world uses, remembered between sessions.
 *
 * <p>Without this the default is whatever the code says, so a world that had been moved into a
 * new HQ went back to the old one on the next restart - the agents with it, standing at desks in
 * a building nobody was looking at any more. The choice belongs to the world, not to the build.
 */
final class HqChoice {
	private static final String FILE = "agentcraft-hq.txt";

	private HqChoice() {
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
	}

	/** The builder id this world last chose, or null when it has never been told. */
	static @Nullable String read(MinecraftServer server) {
		Path f = file(server);
		if (!Files.isRegularFile(f)) {
			return null;
		}
		try {
			String id = Files.readString(f, StandardCharsets.UTF_8).strip();
			return id.isEmpty() ? null : id;
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("could not read {}", f, e);
			return null;
		}
	}

	static void write(MinecraftServer server, String id) {
		try {
			Files.writeString(file(server), id + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("could not write {}", file(server), e);
		}
	}

	/** Applies the world's choice to the registry, if it has one and the builder still exists. */
	static void apply(MinecraftServer server) {
		String id = read(server);
		if (id == null) {
			return;
		}
		if (HqBuilders.get(id) == null) {
			AgentCraft.LOGGER.warn("this world's HQ '{}' is not registered; staying on '{}'", id, HqBuilders.defaultId());
			return;
		}
		HqBuilders.setDefault(id);
		AgentCraft.LOGGER.info("this world's HQ is '{}'", id);
	}
}
