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
	/** The band the parkour runs in, and the glade the trees leave for it. */
	private static final int COURSE_INNER = 21;
	private static final int COURSE_OUTER = 28;
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
	public int @Nullable [] site() {
		try {
			Litematic lit = cached != null ? cached : (cached = Litematic.read(file));
			int y0 = GROUND - (lit.grassLine() >= 0 ? lit.grassLine() : lit.lowestSolid());
			return new int[] {SITE_X - MARGIN, Math.min(y0, GROUND - 4), SITE_Z - MARGIN, SITE_X + lit.sizeX - 1 + MARGIN,
				Math.max(y0 + lit.sizeY - 1, GROUND + 12) + Penthouse.HEIGHT + 3, SITE_Z + lit.sizeZ - 1 + MARGIN};
		} catch (IOException | RuntimeException e) {
			return null;
		}
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
			SITE_X + lit.sizeX - 1 + MARGIN, Math.max(y0 + lit.sizeY - 1, GROUND + 12) + Penthouse.HEIGHT + 3, SITE_Z + lit.sizeZ - 1 + MARGIN};
		Plan p = new Plan(site[0], site[1], site[2], site[3], site[4], site[5], GROUND,
			y -> y > GROUND ? Blocks.AIR.defaultBlockState()
				: y == GROUND ? Blocks.GRASS_BLOCK.defaultBlockState()
				: y >= GROUND - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
		// the land first, so the build always wins where the two meet
		int courseX = SITE_X + lit.sizeX / 2;
		int courseZ = SITE_Z + lit.sizeZ / 2;
		SchematicLandscape.shape(p, GROUND, new SchematicLandscape.Flat(SITE_X - 1, SITE_Z - 1, SITE_X + lit.sizeX, SITE_Z + lit.sizeZ),
			// The glade has to cover where the course actually ends up, not where it would be if
			// nothing were in the way: it bulges out past the lobby now, and a platform planted
			// in a wood is a platform inside a hedge.
			new SchematicLandscape.Glade(courseX, courseZ, COURSE_INNER - 6, COURSE_OUTER + 12));
		for (int x = 0; x < lit.sizeX; x++) {
			for (int y = 0; y < lit.sizeY; y++) {
				for (int z = 0; z < lit.sizeZ; z++) {
					p.set(SITE_X + x, y0 + y, SITE_Z + z, lit.at(x, y, z));
				}
			}
		}

		Floor floor = mainFloor(lit, y0);
		// Someone else's house becomes an HQ: its beds go, the team gets a glass floor of its own
		// on top (the rooms in here are bedroom-sized and a studio does not fit in one), and a
		// spiral replaces the ladders so an agent can actually walk between the floors.
		SchematicFitOut.stripBedrooms(p, SITE_X, y0, SITE_Z, SITE_X + lit.sizeX - 1, y0 + lit.sizeY - 1, SITE_Z + lit.sizeZ - 1);
		int[] b = lit.building(groundY);
		// The storeys above the ground, opened up and fitted out as working floors. The ground
		// floor is left alone: it is where you arrive, and it is the one the lobby opens onto.
		// This runs before the stair, so the doorways it cuts land in a cleared room rather than
		// two blocks in front of a bedroom wall.
		int opened = 0;
		if (b != null) {
			int doorZ = (SITE_Z + b[1] + SITE_Z + b[3]) / 2;
			int[] storeys = lit.floors(groundY);
			// a different half of the cast watching from each floor, so no two floors of screens
			// say the same thing
			List<String> cast = new ArrayList<>(Cast.ids());
			for (int i = 1; i < storeys.length; i++) {
				List<String> watching = new ArrayList<>(cast.subList(0, cast.size()));
				java.util.Collections.rotate(watching, -((i - 1) * Math.max(1, cast.size() / 2)));
				if (SchematicFitOut.openFloor(p, y0 + storeys[i], doorZ, SITE_X + b[0], SITE_Z + b[1], SITE_X + b[2], SITE_Z + b[3], watching) > 0) {
					opened++;
				}
			}
		}
		java.util.Set<String> fitted = new java.util.LinkedHashSet<>();
		Penthouse.Room room = null;
		String stairFault = null;
		String stairNote = "";
		int bottomY = GROUND;
		if (b != null) {
			room = Penthouse.build(p, y0 + b[4], SITE_X + b[0], SITE_Z + b[1], SITE_X + b[2], SITE_Z + b[3]);
			Penthouse.fitOut(p, a, room, new ArrayList<>(Cast.ids()), (SITE_Z + b[1] + SITE_Z + b[3]) / 2);
			Penthouse.beacon(p, room, room.y() + Penthouse.HEIGHT);
			// Clear of the penthouse, not flush with it: a tower on the work floor's own footprint
			// carved its shaft out of the east side of that floor and took a merge station with
			// it, and left a wall of the shaft standing in the middle of the room.
			int tx = SITE_X + b[2] + Penthouse.oversail() + 1 + Penthouse.TOWER_R;
			int tz = (SITE_Z + b[1] + SITE_Z + b[3]) / 2;
			// the pool first: the stair has to know how far down it is going
			int poolDeck = Amenities.poolside(p, a, SITE_X, SITE_Z, SITE_X + lit.sizeX - 1, SITE_Z + lit.sizeZ - 1, GROUND - 1);
			int bottom = poolDeck > 0 ? poolDeck : GROUND + 1;
			bottomY = bottom;
			// every storey of the house, measured off the schematic rather than guessed at from
			// one column beside the tower (which missed the top floor entirely)
			int[] storeys = lit.floors(groundY);
			for (int i = 0; i < storeys.length; i++) {
				storeys[i] += y0;
			}
			List<Integer> landings = Penthouse.spiral(p, tx, tz, bottom, room.y(), storeys);
			// a glazed link from each landing into the building, so every floor has a way in
			int bridged = 0;
			for (int y : landings) {
				if (y == bottom) {
					continue; // the lobby wraps the tower; it needs no bridge
				}
				if (Penthouse.link(p, tx - Penthouse.TOWER_R - 1, tz, y, Penthouse.oversail() + 6)) {
					bridged++;
				}
			}
			stairFault = Penthouse.check(p, tx, tz, bottom, room.y(), landings);
			stairNote = landings.size() + " landing(s), " + bridged + " joined to the building, " + opened + " storey(s) opened as working floors";
            if (poolDeck > 0) {
				Amenities.passage(p, tx - Penthouse.TOWER_R - 1, tz, SITE_X + b[2], poolDeck);
			}
			// it may not grow west past the house; the house is there
			int lobbyWest = Amenities.lobby(p, a, tx, tz, GROUND, Penthouse.TOWER_R, SITE_X + b[2]);
			// And the front hall, re-opened after the lobby rather than before it: the lobby
			// wraps the tower and rebuilds its own west wall, which sealed up the doorway the
			// stair's ground landing had just cut through it.
			boolean hall = Amenities.hall(p, lobbyWest, tz, GROUND + 2, Penthouse.oversail() + 8);
			stairNote += hall ? ", front hall open" : ", BUT the front hall found no floor to join";
			// somewhere to be while the team works: a run through the grounds, out past the woods
			int hcx = (SITE_X + b[0] + SITE_X + b[2]) / 2;
			int hcz = (SITE_Z + b[1] + SITE_Z + b[3]) / 2;
			// Everything standing, so the run goes round it and not through it. The lobby is the
			// part that catches people out: it is not on the house's footprint, it is a ring
			// round a tower that sits off the end of it.
			int lobbyR = Penthouse.TOWER_R + 6;
			int[] built = {Math.min(SITE_X + b[0], tx - lobbyR) - 2, Math.min(SITE_Z + b[1], tz - lobbyR) - 2,
				Math.max(SITE_X + b[2], tx + lobbyR) + 3, Math.max(SITE_Z + b[3], tz + lobbyR) + 3};
			// the run starts out the way you leave the lobby, so the walk to it is a straight line
			int doorX = tx;
			int doorZ = tz + Penthouse.TOWER_R + 5;
			int[] start = Parkour.build(p, a, hcx, hcz, GROUND, COURSE_INNER, COURSE_OUTER, built,
				Math.atan2(doorZ - hcz, doorX - hcx));
			if (start != null) {
				Parkour.approach(p, doorX, doorZ + 1, start[0], start[1], GROUND);
			}
			fitted.addAll(a.names());
			floor = new Floor(room.y(), (room.x0() + room.x1()) / 2, (room.z0() + room.z1()) / 2, room.x0(), room.z0(), room.x1(), room.z1());
		}
		List<StationPlacements.Placement> placed = StationPlacements.load(level.getServer(), id);
		for (StationPlacements.Placement s : placed) {
			BlockState block = blockFor(s);
			if (block == null) {
				continue;
			}
			p.set(s.x(), s.y(), s.z(), block);
			// the goal lamp is useless unbound: its renderer looks for this to draw the hologram
			String binding = !s.binding().isEmpty() ? s.binding() : s.station().equals(AnchorNames.GOAL_ATRIUM) ? "goal:atrium" : "";
			if (!binding.isEmpty()) {
				p.bind(s.x(), s.y(), s.z(), binding);
			}
		}
		p.settleGrass();
		BlockState[] previous = PlanStore.load(level.getServer(), id, site, p.size());
		Plan.Stats st = p.apply(level, previous, options.force());
		PlanStore.save(level.getServer(), id, site, p.cells());

		anchors(a, floor, placed, fitted);
		// the whole building, not just the schematic's own footprint: the penthouse oversails it,
		// the stair tower stands beside it and the pool is far below, and agents walk all three
		if (room != null) {
			a.bounds(Math.min(room.x0(), SITE_X) - 6, Math.min(bottomY, GROUND) - 2, Math.min(room.z0(), SITE_Z) - 6,
				Math.max(room.x1(), SITE_X + lit.sizeX - 1) + 6, room.y() + Penthouse.HEIGHT + 2, Math.max(room.z1(), SITE_Z + lit.sizeZ - 1) + 6);
		} else {
			a.bounds(SITE_X, floor.y - 1, SITE_Z, SITE_X + lit.sizeX - 1, floor.y + 16, SITE_Z + lit.sizeZ - 1);
		}

		String swapped = lit.replaced.isEmpty() ? ""
			: ", " + String.join(" and ", lit.replaced) + " replaced with something that does not move you to another dimension";
		String unknown = lit.unknown.isEmpty() ? "" : ", " + lit.unknown.size() + " block(s) this version does not have were skipped ("
			+ String.join(", ", lit.unknown.subList(0, Math.min(3, lit.unknown.size()))) + ")";
		return "\"" + lit.name + "\" by " + lit.author + ": " + lit.sizeX + "x" + lit.sizeY + "x" + lit.sizeZ + ", " + st.changed()
			+ " blocks placed. Main floor y=" + floor.y + ", " + placed.size() + " station(s) placed"
			+ (placed.isEmpty() ? "" : ", " + placed.size() + " placed by hand")
			+ (stairFault == null ? ", the stair walks from bottom to top" : ", BUT " + stairFault)
			+ (stairNote.isEmpty() ? "" : " (" + stairNote + ")") + swapped + unknown;
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
			// the goal hologram's projector: /agentcraft here goal_atrium moves it anywhere
			case AnchorNames.GOAL_ATRIUM -> ModBlocks.STATUS_LAMP.defaultBlockState()
				.setValue(dev.agentcraft.block.StatusLampBlock.STATUS, dev.agentcraft.block.LampStatus.IDLE);
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
