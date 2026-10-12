package dev.agentcraft.hq;

import dev.agentcraft.block.FacingEntityBlock;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turning a floor of someone's build into a place a team can work.
 *
 * <p>A house downloaded off the internet is a house: three storeys of bedrooms, bathrooms and
 * partitions, with no room anywhere for six desks. So one floor - the one with the most standing
 * room - is opened up: everything inside the shell is cleared, and the shell itself, its glass,
 * the floor, the ceiling and the stairs are left exactly as the builder made them. The result
 * still looks like the house from outside and from every other floor, and reads as a studio on
 * the inside.
 *
 * <p>Then it is fitted out as an office rather than a bedroom: parquet underfoot, a run of light
 * in the ceiling, a walnut table in the middle with chairs, plants in the corners, and the
 * stations lined up around the inside of the walls in the order a team uses them - desks along
 * the walls, terminals and the merge station next, the library opposite, the task wall and the
 * podium where the room is widest. Beds and the rest of the bedroom go, building-wide: an HQ
 * with someone else's bed in it reads as a squat.
 *
 * <p>Anything placed by hand with {@code /agentcraft here} wins over all of it, so this is a
 * starting point and not a decision.
 */
final class SchematicFitOut {
	/** Headroom cleared above the floor. */
	private static final int HEIGHT = 4;

	private SchematicFitOut() {
	}

	/** A cell on the inside face of a wall: where a station goes, and which way it faces. */
	record Spot(int x, int y, int z, Direction facing) {
	}

	/**
	 * Clears the work floor and returns the cells along the inside of its walls, in order around
	 * the room. {@code floorY} is the level the agents stand on.
	 */
	static List<Spot> open(Plan p, int floorY, int minX, int minZ, int maxX, int maxZ) {
		Set<Long> plate = new LinkedHashSet<>();
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (!p.isAir(x, floorY - 1, z)) {
					plate.add(key(x, z));
				}
			}
		}
		// Inside = a column whose four neighbours are all floor: the outermost ring of the plate
		// is where the walls stand, and it is left alone.
		List<long[]> inside = new ArrayList<>();
		for (int x = minX + 1; x <= maxX - 1; x++) {
			for (int z = minZ + 1; z <= maxZ - 1; z++) {
				if (plate.contains(key(x, z)) && plate.contains(key(x - 1, z)) && plate.contains(key(x + 1, z))
					&& plate.contains(key(x, z - 1)) && plate.contains(key(x, z + 1))) {
					inside.add(new long[] {x, z});
				}
			}
		}
		for (long[] c : inside) {
			int x = (int) c[0];
			int z = (int) c[1];
			if (keepsStairs(p, x, floorY, z)) {
				continue;
			}
			// up to this column's own ceiling, not a fixed four: these storeys are five and
			// eight blocks tall, so clearing four left the tops of the partitions floating
			int ceil = ceilingAbove(p, x, floorY, z);
			int top = ceil < 0 ? floorY + HEIGHT : ceil;
			for (int y = floorY; y < top; y++) {
				p.set(x, y, z, Blocks.AIR.defaultBlockState());
			}
		}

		// the ring just inside the walls, walked clockwise so stations come out in a sensible order
		List<Spot> ring = new ArrayList<>();
		int[] bounds = extent(inside);
		if (bounds == null) {
			return ring;
		}
		int x0 = bounds[0];
		int z0 = bounds[1];
		int x1 = bounds[2];
		int z1 = bounds[3];
		for (int x = x0; x <= x1; x++) {
			add(ring, p, inside, x, floorY, z0, Direction.NORTH);
		}
		for (int z = z0 + 1; z <= z1; z++) {
			add(ring, p, inside, x1, floorY, z, Direction.EAST);
		}
		for (int x = x1 - 1; x >= x0; x--) {
			add(ring, p, inside, x, floorY, z1, Direction.SOUTH);
		}
		for (int z = z1 - 1; z > z0; z--) {
			add(ring, p, inside, x0, floorY, z, Direction.WEST);
		}
		return ring;
	}

	/**
	 * The first solid block above the floor on this column, or -1 when the sky is up there.
	 *
	 * <p>Worth measuring rather than assuming. The light grid used to go in at a fixed four
	 * blocks up and only when something solid sat directly on it, and on this house that is true
	 * on no storey at all - the middle one is five blocks to its ceiling and the top one is
	 * eight - so both opened floors came out with not one light in them.
	 */
	private static int ceilingAbove(Plan p, int x, int floorY, int z) {
		for (int y = floorY + 3; y <= floorY + 10; y++) {
			if (!p.isAir(x, y, z)) {
				return y;
			}
		}
		return -1;
	}

	/** Stairs or a ladder on this column: the way between floors stays open. */
	private static boolean keepsStairs(Plan p, int x, int floorY, int z) {
		for (int y = floorY - 1; y < floorY + HEIGHT; y++) {
			BlockState s = p.get(x, y, z);
			if (s.getBlock() instanceof net.minecraft.world.level.block.StairBlock || s.is(Blocks.LADDER)) {
				return true;
			}
		}
		return false;
	}

	private static void add(List<Spot> ring, Plan p, List<long[]> inside, int x, int y, int z, Direction facing) {
		if (!has(inside, x, z) || !p.isAir(x, y, z) || !p.isAir(x, y + 1, z)) {
			return;
		}
		ring.add(new Spot(x, y, z, facing));
	}

	private static boolean has(List<long[]> inside, int x, int z) {
		for (long[] c : inside) {
			if (c[0] == x && c[1] == z) {
				return true;
			}
		}
		return false;
	}

	private static int[] extent(List<long[]> inside) {
		if (inside.isEmpty()) {
			return null;
		}
		int x0 = Integer.MAX_VALUE;
		int z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int z1 = Integer.MIN_VALUE;
		for (long[] c : inside) {
			x0 = Math.min(x0, (int) c[0]);
			z0 = Math.min(z0, (int) c[1]);
			x1 = Math.max(x1, (int) c[0]);
			z1 = Math.max(z1, (int) c[1]);
		}
		return new int[] {x0, z0, x1, z1};
	}

	/** Parquet, light, a table and plants: the difference between a cleared room and an office. */
	static void office(Plan p, int floorY, List<Spot> ring) {
		if (ring.isEmpty()) {
			return;
		}
		int x0 = Integer.MAX_VALUE;
		int z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int z1 = Integer.MIN_VALUE;
		for (Spot s : ring) {
			x0 = Math.min(x0, s.x());
			z0 = Math.min(z0, s.z());
			x1 = Math.max(x1, s.x());
			z1 = Math.max(z1, s.z());
		}
		int ceiling = floorY + HEIGHT - 1;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				if (!p.isAir(x, floorY, z) || !p.isAir(x, floorY + 1, z)) {
					continue; // a wall, the stairs, something the builder put there
				}
				if (!p.isAir(x, floorY - 1, z)) {
					p.set(x, floorY - 1, z, ModBlocks.OAK_PARQUET.defaultBlockState());
				}
				// a quiet grid of light rather than lamps dotted about
				boolean lit = (x - x0) % 4 == 2 && (z - z0) % 4 == 2;
				if (lit && !p.isAir(x, ceiling + 1, z)) {
					p.set(x, ceiling, z, ModBlocks.GLOW_PANEL.defaultBlockState());
				}
			}
		}
		// a meeting table in the middle of the room, with a rug under it
		int cx = (x0 + x1) / 2;
		int cz = (z0 + z1) / 2;
		for (int x = cx - 1; x <= cx + 1; x++) {
			for (int z = cz - 1; z <= cz; z++) {
				if (p.isAir(x, floorY, z) && p.isAir(x, floorY + 1, z)) {
					p.set(x, floorY, z, St.slab(Blocks.DARK_OAK_SLAB, true));
				}
			}
		}
		for (int[] c : new int[][] {{cx - 2, cz - 1}, {cx + 2, cz - 1}, {cx - 2, cz}, {cx + 2, cz}}) {
			if (p.isAir(c[0], floorY, c[1])) {
				p.set(c[0], floorY, c[1], St.stairs(Blocks.DARK_OAK_STAIRS, c[0] < cx ? Direction.EAST : Direction.WEST, false));
			}
		}
		// something green in each corner
		for (int[] c : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
			if (p.isAir(c[0], floorY, c[1]) && !p.isAir(c[0], floorY - 1, c[1])) {
				p.set(c[0], floorY, c[1], St.of(((c[0] + c[1]) & 1) == 0 ? Blocks.POTTED_FERN : Blocks.POTTED_FLOWERING_AZALEA));
			}
		}
	}

	/**
	 * One storey of the house, opened up and fitted out as a working floor.
	 *
	 * <p>The build came as a house: bedrooms, a bathroom, partitions, and on the floor the stair
	 * now arrives at, a wall two blocks behind the door. A team cannot work in that and an agent
	 * cannot cross it - the pathfinder walks a grid, so a floor you can cross on the level is a
	 * floor every desk on it can be reached from, and a floor cut into rooms is a floor where
	 * half the anchors are unreachable from the other half.
	 *
	 * <p>So the storey is cleared inside its own shell - the glass, the outer walls, the floor,
	 * the ceiling and the house's own stairs are all left exactly as the builder made them, so
	 * from outside and from the street nothing has changed - and laid out as an office: a dark
	 * tile floor with cable runs in it, a grid of light overhead, and a spine straight through
	 * the middle from the door, with everything arranged either side of it rather than in it.
	 *
	 * @param doorZ where the stair arrives on the east side; the spine runs west from there
	 * @return the walkable depth of the spine, or 0 when this storey has no room to open
	 */
	static int openFloor(Plan p, Anchors.Builder a, int floorY, int doorZ, int minX, int minZ, int maxX, int maxZ, List<String> seats) {
		List<Spot> ring = open(p, floorY, minX, minZ, maxX, maxZ);
		if (ring.isEmpty()) {
			return 0;
		}
		int x0 = Integer.MAX_VALUE;
		int z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int z1 = Integer.MIN_VALUE;
		for (Spot s : ring) {
			x0 = Math.min(x0, s.x());
			z0 = Math.min(z0, s.z());
			x1 = Math.max(x1, s.x());
			z1 = Math.max(z1, s.z());
		}
		int spineZ = Math.max(z0 + 1, Math.min(z1 - 1, doorZ));
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				if (p.isAir(x, floorY - 1, z)) {
					continue; // a stairwell opening, or outside the plate: no floor to lay
				}
				if (!p.isAir(x, floorY, z) || !p.isAir(x, floorY + 1, z)) {
					continue; // a wall, the house's stairs, something the builder put there
				}
				// the spine reads as a floor you are meant to walk down, not as leftover space
				boolean spine = Math.abs(z - spineZ) <= 1;
				p.set(x, floorY - 1, z, spine ? ModBlocks.TERRACOTTA_TILE.defaultBlockState() : ModBlocks.OAK_PARQUET.defaultBlockState());
				if (spine && z == spineZ && (x - x0) % 3 == 0) {
					p.set(x, floorY - 1, z, ModBlocks.WALNUT_TRIM.defaultBlockState());
				}
				boolean lit = (x - x0) % 4 == 2 && (z - z0) % 4 == 2;
				if (lit || (spine && z == spineZ && (x - x0) % 5 == 2)) {
					int ceil = ceilingAbove(p, x, floorY, z);
					if (ceil > floorY + 2) {
						p.set(x, ceil - 1, z, ModBlocks.GLOW_PANEL.defaultBlockState());
					}
				}
			}
		}
		deskRow(p, a, floorY, x0, z0, x1, z1, spineZ, seats);
		racks(p, floorY, x0, z0, x1, z1, spineZ);
		return x1 - x0 + 1;
	}

	/**
	 * One run of desks along the wall furthest from the spine, built the way the work floor
	 * upstairs builds them (see {@link Penthouse#deskBay}).
	 *
	 * <p>They used to go wherever a wall cell happened to be free, every third column, on both
	 * long sides: on a house floor whose plan is nothing like a rectangle that put two desks
	 * round an inside corner facing different ways, with a blank wall behind them. A bay is five
	 * wide in plan - three of desk, one of seat clearance either side - so they are laid at a
	 * pitch of five from a centred start, and a bay goes in only where all of it fits.
	 *
	 * @return how many agents got a desk
	 */
	private static int deskRow(Plan p, Anchors.Builder a, int y, int x0, int z0, int x1, int z1, int spineZ, List<String> seats) {
		if (seats.isEmpty()) {
			return 0;
		}
		// the long wall the spine does not come out of, so the run is never cut in half by it
		boolean north = Math.abs(z0 - spineZ) > Math.abs(z1 - spineZ);
		int zd = north ? z0 : z1;
		Direction toDesk = north ? Direction.NORTH : Direction.SOUTH;
		int pitch = 5;
		int span = x1 - x0 + 1;
		int start = x0 + Math.max(1, (span - pitch * seats.size()) / 2) + 1;
		int placed = 0;
		for (int i = 0; i < seats.size(); i++) {
			int bx = start + i * pitch;
			if (bx - 1 < x0 || bx + 1 > x1 || !bayFits(p, bx, zd, y, toDesk)) {
				continue;
			}
			String id = seats.get(i);
			int[] seat = Penthouse.deskBay(p, bx, zd, y, toDesk, id);
			a.put(AnchorNames.desk(id), seat[0] + 0.5, y, seat[1] + 0.5, toDesk.toYRot(), 0);
			a.put("seat_" + id, seat[0] + 0.5, y, seat[1] + 0.5, toDesk.toYRot(), 0);
			a.put(AnchorNames.monitor(id), bx + 0.5, y + 2.0, zd + 0.5 - toDesk.getStepZ() * 0.25, toDesk.getOpposite().toYRot(), 0);
			if (i + 1 < seats.size()) {
				Penthouse.deskPost(p, bx + pitch / 2, zd, y, toDesk.getOpposite());
			}
			placed++;
		}
		return placed;
	}

	/** Three cells of wall to build the desk into, and a clear floor cell in front to sit on. */
	private static boolean bayFits(Plan p, int bx, int bz, int y, Direction toDesk) {
		Direction along = toDesk.getClockWise();
		for (int i = -1; i <= 1; i++) {
			int x = bx + along.getStepX() * i;
			int z = bz + along.getStepZ() * i;
			if (p.isAir(x, y - 1, z) || !p.isAir(x, y, z) || !p.isAir(x, y + 1, z)) {
				return false;
			}
		}
		int sx = bx - toDesk.getStepX();
		int sz = bz - toDesk.getStepZ();
		return !p.isAir(sx, y - 1, sz) && p.isAir(sx, y, sz) && p.isAir(sx, y + 1, sz);
	}

	/**
	 * A rack bay at the blind end of the floor: the wall furthest from the door, where there is
	 * no glass and nothing else wants to be. Terminals and lamps in a run, which is what makes a
	 * floor read as a machine room rather than a cleared bedroom.
	 */
	private static void racks(Plan p, int y, int x0, int z0, int x1, int z1, int spineZ) {
		int xw = x0;
		for (int z = z0 + 1; z <= z1 - 1; z++) {
			if (Math.abs(z - spineZ) <= 1 || !p.isAir(xw, y, z) || !p.isAir(xw, y + 1, z) || p.isAir(xw, y - 1, z)) {
				continue;
			}
			p.set(xw, y, z, ModBlocks.WALNUT_PANEL.defaultBlockState());
			p.set(xw, y + 1, z, (z - z0) % 2 == 0
				? ModBlocks.STATUS_LAMP.defaultBlockState().setValue(dev.agentcraft.block.StatusLampBlock.STATUS, dev.agentcraft.block.LampStatus.IDLE)
				: ModBlocks.MONITOR.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.EAST));
			p.set(xw, y + 2, z, ModBlocks.WALNUT_PANEL.defaultBlockState());
		}
	}

	/** Beds, bedside rugs and the rest of the bedroom, out of the whole building. */
	static void stripBedrooms(Plan p, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					BlockState s = p.get(x, y, z);
					if (s.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
						p.set(x, y, z, Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
	}

	/**
	 * Furnishes the opened floor: a station on the wall behind each chosen spot, the agent's
	 * anchor on the spot itself. Returns the anchors it wrote, so the caller knows which of the
	 * required names are still to fill in.
	 */
	static Set<String> furnish(Plan p, Anchors.Builder a, List<Spot> ring, List<String> agents) {
		Set<String> written = new LinkedHashSet<>();
		if (ring.isEmpty()) {
			return written;
		}
		// every other cell, so two agents never stand shoulder to shoulder
		List<Spot> spaced = new ArrayList<>();
		for (int i = 0; i < ring.size(); i += 2) {
			spaced.add(ring.get(i));
		}
		int i = 0;
		for (String agent : agents) {
			Spot s = spaced.get(i++ % spaced.size());
			wall(p, s, ModBlocks.MONITOR.defaultBlockState().setValue(FacingEntityBlock.FACING, s.facing().getOpposite()));
			put(a, written, AnchorNames.MONITOR_PREFIX + agent, behind(s), s.y() + 1, behindZ(s), s.facing());
			put(a, written, AnchorNames.DESK_PREFIX + agent, s.x() + 0.5, s.y(), s.z() + 0.5, s.facing());
		}
		for (String station : List.of(AnchorNames.TERMINAL, AnchorNames.slot(AnchorNames.TERMINAL, 2), AnchorNames.MERGESTATION,
			AnchorNames.slot(AnchorNames.MERGESTATION, 2), AnchorNames.LIBRARY, AnchorNames.slot(AnchorNames.LIBRARY, 2),
			AnchorNames.TESTBENCH, AnchorNames.slot(AnchorNames.TESTBENCH, 2), AnchorNames.TASK_WALL, AnchorNames.DECISION_PODIUM,
			AnchorNames.USER, AnchorNames.MEETING, AnchorNames.slot(AnchorNames.MEETING, 2), AnchorNames.LOUNGE,
			AnchorNames.slot(AnchorNames.LOUNGE, 2), AnchorNames.GOAL_ATRIUM)) {
			if (i >= spaced.size() * 2) {
				break;
			}
			Spot s = spaced.get(i++ % spaced.size());
			BlockState block = blockFor(station, s.facing().getOpposite());
			if (block != null) {
				wall(p, s, block);
				if (station.startsWith(AnchorNames.LIBRARY)) {
					p.bind(s.x() + s.facing().getStepX(), s.y(), s.z() + s.facing().getStepZ(), "shared");
				}
			}
			put(a, written, station, s.x() + 0.5, s.y(), s.z() + 0.5, s.facing());
		}
		return written;
	}

	/** The wall cell behind a spot (the one the agent faces). */
	private static void wall(Plan p, Spot s, BlockState state) {
		int wx = s.x() + s.facing().getStepX();
		int wz = s.z() + s.facing().getStepZ();
		// never over a window or a door: a station replaces wall, not the builder's glass
		BlockState there = p.get(wx, s.y(), wz);
		if (there.isAir() || there.is(Blocks.GLASS) || there.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
			|| there.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
			return;
		}
		p.set(wx, s.y(), wz, state);
	}

	private static @org.jspecify.annotations.Nullable BlockState blockFor(String station, Direction facing) {
		String base = station.endsWith("_2") ? station.substring(0, station.length() - 2) : station;
		return switch (base) {
			case AnchorNames.TERMINAL -> ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, facing);
			case AnchorNames.LIBRARY -> ModBlocks.MEMORY_ARCHIVE.defaultBlockState().setValue(FacingEntityBlock.FACING, facing);
			case AnchorNames.MERGESTATION -> ModBlocks.MERGE_STATION.defaultBlockState().setValue(FacingEntityBlock.FACING, facing);
			case AnchorNames.TASK_WALL -> ModBlocks.TASK_BOARD.defaultBlockState().setValue(FacingEntityBlock.FACING, facing);
			case AnchorNames.DECISION_PODIUM -> ModBlocks.DECISION_PODIUM.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
			default -> null;
		};
	}

	private static void put(Anchors.Builder a, Set<String> written, String name, double x, double y, double z, Direction facing) {
		a.put(name, x, y, z, facing.toYRot(), 0);
		written.add(name);
	}

	private static double behind(Spot s) {
		return s.x() + 0.5 + s.facing().getStepX();
	}

	private static double behindZ(Spot s) {
		return s.z() + 0.5 + s.facing().getStepZ();
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}
}
