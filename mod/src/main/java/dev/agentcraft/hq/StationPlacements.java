package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.AnchorNames;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Where the stations go in a schematic HQ: one line per station in the world folder, written by
 * {@code /agentcraft here <station>} and read by {@link SchematicHqBuilder} on every build.
 *
 * <pre>
 *   terminal|63|71|-4|north|
 *   desk_kit|70|71|-9|south|
 *   library|61|71|0|east|shared
 * </pre>
 *
 * <p>A flat text file rather than NBT because this is a thing people edit by hand when they want
 * to nudge a terminal one block left, and because it has to survive being read by a human asking
 * "where did it put my desk".
 */
public final class StationPlacements {
	private static final String FILE_PREFIX = "agentcraft-stations-";

	/**
	 * One placed station. {@code x,y,z} is the block; the agent stands one block in front of it,
	 * facing it, which is what the anchor records.
	 */
	public record Placement(String station, int x, int y, int z, Direction facing, String binding) {
		/** The anchor name this writes: the station's own name. */
		public String anchor() {
			return station;
		}

		public double standX() {
			return x + 0.5 + facing.getStepX() * 1.2;
		}

		public double standZ() {
			return z + 0.5 + facing.getStepZ() * 1.2;
		}

		/** Looking back at the block: the opposite of the way the block faces. */
		public float standYaw() {
			return facing.getOpposite().toYRot();
		}

		String line() {
			return String.join("|", station, String.valueOf(x), String.valueOf(y), String.valueOf(z), facing.getSerializedName(), binding);
		}
	}

	private StationPlacements() {
	}

	private static Path file(MinecraftServer server, String builderId) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE_PREFIX + builderId + ".txt");
	}

	public static List<Placement> load(MinecraftServer server, String builderId) {
		Path f = file(server, builderId);
		List<Placement> out = new ArrayList<>();
		if (!Files.isRegularFile(f)) {
			return out;
		}
		try {
			for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				String line = raw.strip();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}
				String[] parts = line.split("\\|", -1);
				if (parts.length < 5) {
					continue;
				}
				Direction d = Direction.byName(parts[4].toLowerCase(Locale.ROOT));
				if (d == null || !d.getAxis().isHorizontal()) {
					d = Direction.NORTH;
				}
				out.add(new Placement(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), d,
					parts.length > 5 ? parts[5] : ""));
			}
		} catch (IOException | NumberFormatException e) {
			AgentCraft.LOGGER.warn("could not read {}", f, e);
		}
		return out;
	}

	/** Adds or replaces one station and saves. Returns the list as it now stands. */
	public static List<Placement> put(MinecraftServer server, String builderId, Placement p) {
		List<Placement> all = load(server, builderId);
		all.removeIf(x -> x.station().equals(p.station()));
		all.add(p);
		save(server, builderId, all);
		return all;
	}

	/** Removes one station (or every one when {@code station} is null). */
	public static List<Placement> remove(MinecraftServer server, String builderId, @org.jspecify.annotations.Nullable String station) {
		List<Placement> all = load(server, builderId);
		if (station == null) {
			all.clear();
		} else {
			all.removeIf(x -> x.station().equals(station));
		}
		save(server, builderId, all);
		return all;
	}

	private static void save(MinecraftServer server, String builderId, List<Placement> all) {
		Path f = file(server, builderId);
		try {
			List<String> lines = new ArrayList<>();
			lines.add("# AgentCraft stations for HQ '" + builderId + "': station|x|y|z|facing|binding");
			lines.add("# written by /agentcraft here <station>; edit by hand and rebuild with /agentcraft hq " + builderId);
			for (Placement p : all) {
				lines.add(p.line());
			}
			Files.write(f, lines, StandardCharsets.UTF_8);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("could not write {}", f, e);
		}
	}

	/** The station names {@code /agentcraft here} accepts (desk_/monitor_ take an agent id). */
	public static List<String> names() {
		return List.of(AnchorNames.TERMINAL, AnchorNames.LIBRARY, AnchorNames.TESTBENCH, AnchorNames.MERGESTATION, AnchorNames.MEETING,
			AnchorNames.LOUNGE, AnchorNames.USER, AnchorNames.TASK_WALL, AnchorNames.DECISION_PODIUM, AnchorNames.GOAL_ATRIUM,
			AnchorNames.ENTRANCE, AnchorNames.SPAWN);
	}
}
