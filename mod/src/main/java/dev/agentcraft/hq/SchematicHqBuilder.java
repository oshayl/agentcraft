package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.Cast;
import dev.agentcraft.block.FacingEntityBlock;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * An HQ built from a schematic someone made in their own world: the blocks come from a
 * {@code .litematic} in {@code ~/.agentcraft/schematics}, the stations are put where the player
 * stands ({@code /agentcraft here <station>}, see {@link StationPlacements}).
 *
 * <p>Why the stations are not placed automatically: in a hand-built house there is a right place
 * for the terminal and a wrong one, and no scan of the geometry knows which is which. So this
 * builder stamps the build, works out where the floor is, and parks the required anchors in the
 * middle of the largest room as a starting point. Walking the house and naming each spot takes a
 * couple of minutes and gets a result a heuristic never would.
 *
 * <p>Idempotent like every builder: the schematic is a plan applied as a diff, and the placements
 * are read from a file in the world folder, so a rebuild puts everything back exactly as it was.
 * Block entities and entities in the schematic (chest contents, sign text, item frames) are not
 * applied - see {@link Litematic}.
 */
public final class SchematicHqBuilder implements HqBuilder {
	/** The world y the schematic's own ground line is placed at (the meadow around it). */
	private static final int GROUND = 64;
	/** Schematic HQs are built east of the studio, so trying one does not flatten the other. */
	private static final int SITE_X = 60;
	private static final int SITE_Z = -20;
	/** Land around the build: room for the hills to rise and come back down (SchematicLandscape). */
	private static final int MARGIN = 38;
	/** A ceiling this close above means a cell is indoors rather than on a roof. */
	private static final int CEILING = 8;

	private final String id;
	private final Path file;
	private @Nullable Litematic cached;

	public SchematicHqBuilder(String id, Path file) {
		this.id = id;
		this.file = file;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public String description() {
		return "Schematic " + file.getFileName();
	}

	@Override
	public void build(ServerLevel level, Anchors.Builder anchors) {
		build(level, anchors, Options.DEFAULT);
	}

	@Override
	public @Nullable String build(ServerLevel level, Anchors.Builder a, Options options) {
		Litematic lit;
		try {
			lit = cached != null ? cached : (cached = Litematic.read(file));
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.error("could not read {}", file, e);
			throw new IllegalStateException("could not read " + file.getFileName() + ": " + e.getMessage());
		}

		// The schematic's own ground line sits on the meadow, so the build is not on a plinth.
		int groundY = lit.grassLine() >= 0 ? lit.grassLine() : lit.lowestSolid();
		int y0 = GROUND - groundY;
		int[] site = {SITE_X - MARGIN, Math.min(y0, GROUND - 4), SITE_Z - MARGIN,
			SITE_X + lit.sizeX - 1 + MARGIN, Math.max(y0 + lit.sizeY - 1, GROUND + 12), SITE_Z + lit.sizeZ - 1 + MARGIN};
		Plan p = new Plan(site[0], site[1], site[2], site[3], site[4], site[5], GROUND,
			y -> y > GROUND ? Blocks.AIR.defaultBlockState()
				: y == GROUND ? Blocks.GRASS_BLOCK.defaultBlockState()
				: y >= GROUND - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
		// the land first, so the build always wins where the two meet
		SchematicLandscape.shape(p, GROUND, new SchematicLandscape.Flat(SITE_X - 1, SITE_Z - 1, SITE_X + lit.sizeX, SITE_Z + lit.sizeZ));
		for (int x = 0; x < lit.sizeX; x++) {
			for (int y = 0; y < lit.sizeY; y++) {
				for (int z = 0; z < lit.sizeZ; z++) {
					p.set(SITE_X + x, y0 + y, SITE_Z + z, lit.at(x, y, z));
				}
			}
		}

		Floor floor = mainFloor(lit, y0);
		// someone else's house becomes an office: one floor opened, fitted out, beds gone
		SchematicFitOut.stripBedrooms(p, SITE_X, y0, SITE_Z, SITE_X + lit.sizeX - 1, y0 + lit.sizeY - 1, SITE_Z + lit.sizeZ - 1);
		List<SchematicFitOut.Spot> ring = SchematicFitOut.open(p, floor.y, floor.minX - 2, floor.minZ - 2, floor.maxX + 2, floor.maxZ + 2);
		SchematicFitOut.office(p, floor.y, ring);
		// the stations go in while the plan is still being written, so they apply with everything else
		java.util.Set<String> fitted = SchematicFitOut.furnish(p, a, ring, new ArrayList<>(Cast.ids()));
		List<StationPlacements.Placement> placed = StationPlacements.load(level.getServer(), id);
		for (StationPlacements.Placement s : placed) {
			BlockState block = blockFor(s);
			if (block != null) {
				p.set(s.x(), s.y(), s.z(), block);
				if (!s.binding().isEmpty()) {
					p.bind(s.x(), s.y(), s.z(), s.binding());
				}
			}
		}
		p.settleGrass();
		BlockState[] previous = PlanStore.load(level.getServer(), id, site, p.size());
		Plan.Stats st = p.apply(level, previous, options.force());
		PlanStore.save(level.getServer(), id, site, p.cells());

		anchors(a, floor, placed, fitted);
		a.bounds(SITE_X, floor.y - 1, SITE_Z, SITE_X + lit.sizeX - 1, floor.y + 16, SITE_Z + lit.sizeZ - 1);

		String unknown = lit.unknown.isEmpty() ? "" : ", " + lit.unknown.size() + " block(s) this version does not have were skipped ("
			+ String.join(", ", lit.unknown.subList(0, Math.min(3, lit.unknown.size()))) + ")";
		return "\"" + lit.name + "\" by " + lit.author + ": " + lit.sizeX + "x" + lit.sizeY + "x" + lit.sizeZ + ", " + st.changed()
			+ " blocks placed. Main floor y=" + floor.y + ", " + placed.size() + " station(s) placed"
			+ (placed.isEmpty() ? " - stand where you want one and run /agentcraft here <station>" : "") + unknown;
	}

	/** The station block a placement asks for (null: anchor only, nothing to place). */
	private static @Nullable BlockState blockFor(StationPlacements.Placement s) {
		Direction f = s.facing();
		return switch (s.station()) {
			case AnchorNames.TERMINAL -> ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, f);
			case AnchorNames.LIBRARY -> ModBlocks.MEMORY_ARCHIVE.defaultBlockState().setValue(FacingEntityBlock.FACING, f);
			case AnchorNames.MERGESTATION -> ModBlocks.MERGE_STATION.defaultBlockState().setValue(FacingEntityBlock.FACING, f);
			case AnchorNames.TASK_WALL -> ModBlocks.TASK_BOARD.defaultBlockState().setValue(FacingEntityBlock.FACING, f);
			case AnchorNames.DECISION_PODIUM -> ModBlocks.DECISION_PODIUM.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, f);
			default -> s.station().startsWith(AnchorNames.MONITOR_PREFIX)
				? ModBlocks.MONITOR.defaultBlockState().setValue(FacingEntityBlock.FACING, f)
				: null;
		};
	}

	/** Where the agents stand, for the anchors a placement does not cover. */
	private record Floor(int y, int cx, int cz, int minX, int minZ, int maxX, int maxZ) {
	}

	/**
	 * The biggest room: for every y, the cells you can stand in (solid under, two clear above)
	 * that also have a ceiling, which is what tells a floor from a roof.
	 */
	private static Floor mainFloor(Litematic lit, int y0) {
		int bestY = 0;
		int bestN = 0;
		long sumX = 0;
		long sumZ = 0;
		int minX = 0;
		int minZ = 0;
		int maxX = 0;
		int maxZ = 0;
		for (int y = 1; y < lit.sizeY - 2; y++) {
			int n = 0;
			long sx = 0;
			long sz = 0;
			int lox = Integer.MAX_VALUE;
			int loz = Integer.MAX_VALUE;
			int hix = Integer.MIN_VALUE;
			int hiz = Integer.MIN_VALUE;
			for (int x = 0; x < lit.sizeX; x++) {
				for (int z = 0; z < lit.sizeZ; z++) {
					if (lit.at(x, y - 1, z).isAir() || !lit.at(x, y, z).isAir() || !lit.at(x, y + 1, z).isAir()) {
						continue;
					}
					boolean roofed = false;
					for (int h = 2; h <= CEILING && !roofed; h++) {
						roofed = !lit.at(x, y + h, z).isAir();
					}
					if (!roofed) {
						continue;
					}
					n++;
					sx += x;
					sz += z;
					lox = Math.min(lox, x);
					loz = Math.min(loz, z);
					hix = Math.max(hix, x);
					hiz = Math.max(hiz, z);
				}
			}
			if (n > bestN) {
				bestN = n;
				bestY = y;
				sumX = sx;
				sumZ = sz;
				minX = lox;
				minZ = loz;
				maxX = hix;
				maxZ = hiz;
			}
		}
		if (bestN == 0) {
			return new Floor(GROUND + 1, SITE_X + lit.sizeX / 2, SITE_Z + lit.sizeZ / 2, SITE_X, SITE_Z, SITE_X + lit.sizeX - 1, SITE_Z + lit.sizeZ - 1);
		}
		return new Floor(y0 + bestY, SITE_X + (int) (sumX / bestN), SITE_Z + (int) (sumZ / bestN),
			SITE_X + minX, SITE_Z + minZ, SITE_X + maxX, SITE_Z + maxZ);
	}

	/**
	 * Every anchor the contract asks for: the ones the player has placed where they placed them,
	 * the rest spread around the middle of the main room so nothing is missing and no two agents
	 * stand inside each other.
	 */
	private void anchors(Anchors.Builder a, Floor floor, List<StationPlacements.Placement> placed, java.util.Set<String> fitted) {
		List<String> ids = new ArrayList<>(Cast.ids());
		List<String> wanted = new ArrayList<>();
		for (String agent : ids) {
			wanted.add(AnchorNames.DESK_PREFIX + agent);
			wanted.add(AnchorNames.MONITOR_PREFIX + agent);
		}
		wanted.addAll(List.of(AnchorNames.LIBRARY, AnchorNames.TERMINAL, AnchorNames.TESTBENCH, AnchorNames.MERGESTATION, AnchorNames.MEETING,
			AnchorNames.LOUNGE, AnchorNames.USER, AnchorNames.TASK_WALL, AnchorNames.DECISION_PODIUM, AnchorNames.GOAL_ATRIUM,
			AnchorNames.ENTRANCE, AnchorNames.SPAWN));
		// two slots per shared station, so a second agent has somewhere to stand
		for (String s : List.of(AnchorNames.LIBRARY, AnchorNames.TERMINAL, AnchorNames.TESTBENCH, AnchorNames.MERGESTATION, AnchorNames.MEETING,
			AnchorNames.LOUNGE)) {
			wanted.add(AnchorNames.slot(s, 2));
		}

		// the fit-out already wrote these against the walls; a hand placement wins over them
		wanted.removeAll(fitted);
		for (StationPlacements.Placement s : placed) {
			a.put(s.anchor(), s.standX(), s.y(), s.standZ(), s.standYaw(), 0);
			wanted.remove(s.anchor());
		}
		// a ring around the middle of the room: provisional, and visibly so
		int i = 0;
		for (String name : wanted) {
			double angle = i * 2 * Math.PI / Math.max(1, wanted.size());
			double r = 2.5 + (i % 3);
			double x = floor.cx + 0.5 + Math.cos(angle) * r;
			double z = floor.cz + 0.5 + Math.sin(angle) * r;
			a.put(name, clamp(x, floor.minX + 0.5, floor.maxX + 0.5), floor.y, clamp(z, floor.minZ + 0.5, floor.maxZ + 0.5),
				(float) (Math.toDegrees(angle) + 90), 0);
			i++;
		}
		a.put(AnchorNames.CAM_PREFIX + "overview", floor.cx + 0.5, floor.y + 12, floor.minZ - 18.0, 0, 25);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Registers one builder per {@code .litematic} in {@code ~/.agentcraft/schematics}. */
	public static void registerAll() {
		Path dir = schematicsDir();
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (var files = Files.list(dir)) {
			files.filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic")).sorted().forEach(f -> {
				String name = f.getFileName().toString();
				String id = name.substring(0, name.length() - ".litematic".length()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
				if (!id.isEmpty()) {
					HqBuilders.register(new SchematicHqBuilder(id, f));
					AgentCraft.LOGGER.info("HQ builder '{}' from {}", id, f);
				}
			});
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("could not list {}", dir, e);
		}
	}

	static Path schematicsDir() {
		String home = System.getenv("AGENTCRAFT_HOME");
		Path base = home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".agentcraft");
		return base.resolve("schematics");
	}
}
