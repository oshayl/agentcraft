package dev.agentcraft.hq;

import dev.agentcraft.block.FacingEntityBlock;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The work floor, as a glass storey added on top of an imported build.
 *
 * <p>A house downloaded off the internet has rooms the size of rooms: eighteen by thirteen, cut
 * into bedrooms. Six desk bays alone want that much wall. So rather than cramming a studio into
 * someone's bedroom, the team gets a floor of its own - wider than the house, so it cantilevers,
 * in the house's own white concrete and glass - and the house underneath keeps being a house:
 * lobby, lounges, the pool.
 *
 * <p>Everything the team needs is on this one floor, which is also what makes it work: agents
 * walk the layout with a grid pathfinder that climbs a block at a time, so a floor they can cross
 * on the level is a floor they can always reach. A staircase down into the house is for the
 * player.
 */
final class Penthouse {
	/** How far the floor oversails the house on each side. */
	private static final int OVERSAIL = 2;
	/** Floor to ceiling. */
	static final int HEIGHT = 6;

	private static final BlockState WHITE = St.concrete(DyeColor.WHITE);
	private static final BlockState GRAY = St.concrete(DyeColor.GRAY);
	private static final BlockState GLASS = Blocks.GLASS_PANE.defaultBlockState();
	private static final BlockState FLOOR = ModBlocks.OAK_PARQUET.defaultBlockState();
	private static final BlockState WALNUT = ModBlocks.WALNUT_PANEL.defaultBlockState();
	private static final BlockState TRIM = ModBlocks.WALNUT_TRIM.defaultBlockState();
	private static final BlockState LANTERN = St.lantern(Blocks.LANTERN, false);
	/** waxed and unaffected, so the plinth does not go green round the goal */
	private static final net.minecraft.world.level.block.Block COPPER = Blocks.CUT_COPPER_SLAB.waxed().unaffected();

	/** The room the team works in: floor level and the walkable rectangle inside the glass. */
	record Room(int y, int x0, int z0, int x1, int z1) {
		int width() {
			return x1 - x0 + 1;
		}

		int depth() {
			return z1 - z0 + 1;
		}
	}

	private Penthouse() {
	}

	/**
	 * Builds the shell on top of {@code houseTop} over the house's footprint and returns the room
	 * inside it. Nothing of the house below is touched except the one layer it stands on.
	 */
	static Room build(Plan p, int houseTop, int hx0, int hz0, int hx1, int hz1) {
		int x0 = hx0 - OVERSAIL;
		int z0 = hz0 - OVERSAIL;
		int x1 = hx1 + OVERSAIL;
		int z1 = hz1 + OVERSAIL;
		int slab = houseTop + 1;
		int floor = slab + 1;
		int ceil = floor + HEIGHT - 1;

		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
				p.set(x, slab, z, edge ? WHITE : FLOOR);
				for (int y = floor; y <= ceil; y++) {
					p.set(x, y, z, Blocks.AIR.defaultBlockState());
				}
				// a flat roof with a gray band round the lip, like the house's own
				p.set(x, ceil + 1, z, edge ? GRAY : WHITE);
			}
		}
		// the glass: posts at the corners and every five blocks, panes between them
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				if (x != x0 && x != x1 && z != z0 && z != z1) {
					continue;
				}
				boolean post = x == x0 || x == x1 ? (z - z0) % 5 == 0 || z == z1 : (x - x0) % 5 == 0 || x == x1;
				for (int y = floor; y <= ceil; y++) {
					p.set(x, y, z, post ? WHITE : y == ceil ? WHITE : GLASS);
				}
			}
		}
		// ceiling light, a quiet grid rather than lamps dotted about
		for (int x = x0 + 2; x < x1; x += 4) {
			for (int z = z0 + 2; z < z1; z += 4) {
				p.set(x, ceil, z, ModBlocks.GLOW_PANEL.defaultBlockState());
			}
		}
		return new Room(floor, x0 + 1, z0 + 1, x1 - 1, z1 - 1);
	}

	/** Wall radius of the stair tower: a seven by seven tower round a five by five well. */
	static final int TOWER_R = 3;
	/** How far the floor oversails, for whoever is placing the tower clear of it. */
	static int oversail() {
		return OVERSAIL;
	}

	/**
	 * The ring of treads, anticlockwise from the north cell. Orthogonally adjacent in sequence,
	 * which is the part that matters: the pathfinder will not cut a corner past the newel, so a
	 * spiral that only worked diagonally would be a stair no agent could use.
	 */
	private static final int[][] RING = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
	private static final Direction[] FACING = {Direction.EAST, Direction.SOUTH, Direction.SOUTH, Direction.WEST, Direction.WEST, Direction.NORTH,
		Direction.NORTH, Direction.EAST};
	/**
	 * The second cell of each tread, out towards the wall: everything on the outer ring that is
	 * orthogonally beside its tread. This is what makes the stair two wide - you can pass someone
	 * on it, and it reads as a staircase rather than a ladder with corners. The four corners of
	 * the well are beside no tread at all, so they stay solid and become the tower's posts.
	 */
	private static final int[][][] OUTER = {
		{{0, -2}}, {{1, -2}, {2, -1}}, {{2, 0}}, {{2, 1}, {1, 2}}, {{0, 2}}, {{-1, 2}, {-2, 1}}, {{-2, 0}}, {{-2, -1}, {-1, -2}},
	};
	/** The ring cell the landings and the doorways are on (west). */
	private static final int WEST = 6;

	/**
	 * A spiral stair in a glass tower beside the building, from {@code bottom} to {@code top},
	 * arriving level at every height in {@code stops}.
	 *
	 * <p>The build came with ladders, and an agent cannot climb one: the pathfinder walks a grid
	 * and steps at most a block at a time. A spiral solves that - a five by five well, a newel in
	 * the middle, and eight treads a turn each two cells wide, rising a block per tread.
	 *
	 * <p>It stands in its own tower rather than threading the house, because the floors above the
	 * ground are set back and a stair cut through them hangs in the air halfway up. It also
	 * stands clear of the penthouse above: a tower overlapping that floor carved its own shaft
	 * out of the work floor's east side, taking a merge station with it.
	 *
	 * <p>{@code stops} are the heights that get a landing and a doorway. Anything closer than
	 * four blocks to the last landing is dropped: a flight shorter than that puts two treads in
	 * one column within a block of each other, and then neither can be stood on.
	 */
	static java.util.List<Integer> spiral(Plan p, int cx, int cz, int bottom, int top, int[] stops) {
		shaft(p, cx, cz, TOWER_R, bottom, top);

		java.util.List<Integer> floors = new java.util.ArrayList<>();
		floors.add(bottom);
		for (int y : stops) {
			if (y > bottom + 3 && y < top - 3 && y - floors.get(floors.size() - 1) >= 4) {
				floors.add(y);
			}
		}
		if (top - floors.get(floors.size() - 1) < 4) {
			floors.remove(floors.size() - 1);
		}
		floors.add(top);

		int i = WEST;
		landing(p, cx, cz, TOWER_R, floors.get(0));
		for (int f = 1; f < floors.size(); f++) {
			int from = floors.get(f - 1);
			int climb = floors.get(f) - from;
			// A whole number of turns per flight, so every landing comes back round to the west
			// cell. The rise is spread over the turn rather than taken a block at a time: an
			// eight-cell turn that climbs less than eight has to go flat somewhere, and doing
			// that all in one place is what made the stair meet itself and dead-end.
			int cells = Math.max(1, (climb + 7) / 8) * 8;
			for (int k = 0; k < cells - 1; k++) {
				i = (i + 1) % 8;
				tread(p, cx, cz, i, from + (k + 1) * climb / cells - 1);
			}
			landing(p, cx, cz, TOWER_R, floors.get(f));
			i = WEST;
		}
		for (int yy = bottom - 2; yy <= top + 1; yy++) {
			p.set(cx, yy, cz, TRIM);
		}
		return floors;
	}

	/**
	 * Walks the finished stair the way an agent would and says where it breaks, or null when it
	 * goes all the way up and every landing is on the way.
	 *
	 * <p>Three rebuilds in a row left a stair that looked right and dead-ended a few steps in, so
	 * the build checks its own work. The walk is the pathfinder's own rule and nothing softer: a
	 * cell you can stand in, a neighbour on one of the four sides, at most one block up or down.
	 * No diagonals, because a diagonal past the newel is a corner the pathfinder will not cut.
	 */
	static @Nullable String check(Plan p, int cx, int cz, int bottom, int top, java.util.List<Integer> landings) {
		java.util.Set<Long> stand = new java.util.HashSet<>();
		for (int dx = -TOWER_R + 1; dx <= TOWER_R - 1; dx++) {
			for (int dz = -TOWER_R + 1; dz <= TOWER_R - 1; dz++) {
				for (int y = bottom; y <= top + 1; y++) {
					int x = cx + dx;
					int z = cz + dz;
					if (!p.get(x, y - 1, z).isAir() && p.get(x, y, z).isAir() && p.get(x, y + 1, z).isAir()) {
						stand.add(cell(dx, dz, y));
					}
				}
			}
		}
		java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (long k : stand) {
			if ((int) (k & 0xffff) - 2048 == bottom) {
				queue.add(k);
				seen.add(k);
			}
		}
		if (queue.isEmpty()) {
			return "the stair has no bottom step at y=" + bottom;
		}
		int highest = Integer.MIN_VALUE;
		java.util.Set<Integer> reached = new java.util.HashSet<>();
		while (!queue.isEmpty()) {
			long k = queue.poll();
			int dx = (int) (k >> 40) - 8;
			int dz = (int) ((k >> 24) & 0xffff) - 8;
			int y = (int) (k & 0xffff) - 2048;
			highest = Math.max(highest, y);
			if (dx == -1 && dz == 0) {
				reached.add(y); // the landing cell at this height, which is where a doorway is
			}
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				for (int dy = -1; dy <= 1; dy++) {
					long next = cell(dx + d[0], dz + d[1], y + dy);
					if (stand.contains(next) && seen.add(next)) {
						queue.add(next);
					}
				}
			}
		}
		if (highest < top) {
			return "the stair stops at y=" + highest + " and the top is y=" + top;
		}
		for (int y : landings) {
			if (!reached.contains(y)) {
				return "the stair passes y=" + y + " without a landing you can walk onto";
			}
		}
		return null;
	}

	/** (dx, dz, y) as one key; the offsets keep every part positive without a sign to mask off. */
	private static long cell(int dx, int dz, int y) {
		return ((long) (dx + 8) << 40) | ((long) (dz + 8) << 24) | (y + 2048);
	}

	/** The glass well the stair winds up, open inside, walled outside, solid at the corners. */
	private static void shaft(Plan p, int cx, int cz, int r, int bottom, int top) {
		for (int y = bottom - 2; y <= top + 2; y++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					boolean wall = Math.abs(dx) == r || Math.abs(dz) == r;
					boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
					// the four cells of the well no tread reaches: posts, so the tower has corners
					boolean post = Math.abs(dx) == r - 1 && Math.abs(dz) == r - 1;
					if (!wall) {
						BlockState fill = y == bottom - 2 || y == top + 2 ? WHITE : post ? TRIM : Blocks.AIR.defaultBlockState();
						p.set(cx + dx, y, cz + dz, fill);
					} else if (y == bottom - 2 || y == top + 2 || corner || (y - bottom) % 5 == 0) {
						p.set(cx + dx, y, cz + dz, WHITE);
					} else {
						p.set(cx + dx, y, cz + dz, GLASS);
					}
				}
			}
		}
	}

	private static void tread(Plan p, int cx, int cz, int i, int y) {
		Direction f = FACING[i];
		p.set(cx + RING[i][0], y, cz + RING[i][1], St.stairs(Blocks.DARK_OAK_STAIRS, f, false));
		for (int[] o : OUTER[i]) {
			p.set(cx + o[0], y, cz + o[1], St.stairs(Blocks.DARK_OAK_STAIRS, f, false));
		}
	}

	/**
	 * A floor-level landing: somewhere to arrive, and a doorway three wide beside it.
	 *
	 * <p>Only the west side is touched, and only outwards, and that is the point. The ring comes
	 * back to each column every eight treads, so a landing that also levelled its neighbours
	 * round the ring deleted the treads those columns hold on the turn above, and the stair ended
	 * in a dead end a few steps up. Twice. What it may take is the outer half of the two treads
	 * either side: those are the widening, not the climb, so the stair simply goes single file
	 * for one step as you step off it.
	 */
	private static void landing(Plan p, int cx, int cz, int r, int y) {
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -2; dx <= -1; dx++) {
				if (dx == -1 && dz != 0) {
					continue; // the treads either side of the landing: the climb, left alone
				}
				p.set(cx + dx, y - 1, cz + dz, TRIM);
				for (int h = 0; h <= 2; h++) {
					p.set(cx + dx, y + h, cz + dz, Blocks.AIR.defaultBlockState());
				}
			}
			// through the wall: three wide and three high, with a sill you step out onto
			p.set(cx - r, y - 1, cz + dz, TRIM);
			for (int h = 0; h <= 2; h++) {
				p.set(cx - r, y + h, cz + dz, Blocks.AIR.defaultBlockState());
			}
		}
		p.set(cx - 2, y + 3, cz, ModBlocks.GLOW_PANEL.defaultBlockState());
	}

	/**
	 * The glazed link from a landing into the building: floor, headroom and a roof, driven west
	 * from the tower until it reaches ground the floor already has.
	 *
	 * <p>How far that is differs storey by storey - this facade is glass on one floor, a terrace
	 * on another and a wall on the third - so it is measured rather than assumed, and the link
	 * stops at the first cell of real floor it finds instead of punching on through the house.
	 *
	 * @return true when it reached the building
	 */
	static boolean link(Plan p, int fromX, int z, int y, int reach) {
		// Floor to stand on, not a clear cell: the link clears what is at head and foot height as
		// it goes, so asking for an empty doorway here stopped at neither of the two storeys
		// whose facade has a planter or a pane of glass on that line, and bulldozed eight blocks
		// into the house looking for one.
		int to = Integer.MIN_VALUE;
		for (int x = fromX - 1; x >= fromX - reach; x--) {
			if (!p.get(x, y - 1, z).isAir()) {
				to = x;
				break;
			}
		}
		// nothing to join: carve the whole reach anyway rather than leave a doorway onto a drop
		boolean found = to != Integer.MIN_VALUE;
		int end = found ? to : fromX - reach;
		for (int x = fromX; x >= end; x--) {
			for (int dz = -1; dz <= 1; dz++) {
				p.set(x, y - 1, z + dz, FLOOR);
				for (int h = 0; h <= 2; h++) {
					p.set(x, y + h, z + dz, Blocks.AIR.defaultBlockState());
				}
			}
			for (int dz = -2; dz <= 2; dz += 4) {
				for (int h = -1; h <= 3; h++) {
					p.set(x, y + h, z + dz, Math.abs(h) == 3 || h == -1 ? WHITE : GLASS);
				}
			}
			p.set(x, y + 3, z, x % 4 == 0 ? ModBlocks.GLOW_PANEL.defaultBlockState() : WHITE);
		}
		return found;
	}

	/**
	 * The fit-out: desks along the long wall, the task wall facing them, the library and the
	 * terminals on the short walls, and a table in the middle for whoever is running the day.
	 */
	static void fitOut(Plan p, Anchors.Builder a, Room r, List<String> agents, int doorZ) {
		int y = r.y();
		desks(p, a, r, agents);
		taskWall(p, a, r);
		library(p, a, r);
		workbench(p, a, r, doorZ);
		table(p, a, r);
		lounge(p, a, r);

		atrium(p, a, r);
		a.put(AnchorNames.SPAWN, r.x1() - 1.5, y, r.z0() + 1.5, 0, 0);
		a.put(AnchorNames.ENTRANCE, r.x1() - 1.5, y, r.z0() + 2.5, 0, 0);
		a.put(AnchorNames.CAM_PREFIX + "overview", (r.x0() + r.x1()) / 2.0, y + 4, r.z1() + 14.0, 0, 22);
		a.put(AnchorNames.CAM_PREFIX + "desks", (r.x0() + r.x1()) / 2.0, y + 2, r.z0() + 7.0, 180, 8);
	}

	/**
	 * A seat, placed so the sitter looks at {@code look}.
	 *
	 * <p>Every seat in here is a stair block, and a stair seats you facing the way it is NOT
	 * facing - so the block and the anchor point opposite ways. Writing that out by hand once per
	 * chair got it wrong at the lead's place, in the lounge, in the lobby and by the pool, with
	 * the whole team sitting with their backs to the thing they were meant to be looking at. One
	 * helper, and it cannot happen again.
	 */
	private static void seat(Plan p, Anchors.Builder a, String anchor, int x, int y, int z, Direction look, net.minecraft.world.level.block.Block wood) {
		p.set(x, y, z, St.stairs(wood, look.getOpposite(), false));
		a.put(anchor, x + 0.5, y, z + 0.5, look.toYRot(), 0);
	}

	/** Desk bays against the north glass: desk, two monitors bound to the agent, lamp, chair. */
	private static void desks(Plan p, Anchors.Builder a, Room r, List<String> agents) {
		int y = r.y();
		int zd = r.z0();
		BlockState desk = St.slab(Blocks.DARK_OAK_SLAB, true);
		int span = r.width();
		int bays = agents.size();
		int pitch = Math.max(3, span / Math.max(1, bays));
		int start = r.x0() + Math.max(0, (span - pitch * bays) / 2) + pitch / 2;
		for (int i = 0; i < bays; i++) {
			String id = agents.get(i);
			int bx = Math.min(r.x1() - 1, start + i * pitch);
			for (int x = bx - 1; x <= bx + 1; x++) {
				p.set(x, y, zd, desk);
				for (int h = 1; h <= 2; h++) {
					p.set(x, y + h, zd, ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, Direction.SOUTH)
						.setValue(MonitorBlock.LIT, true));
					p.bind(x, y + h, zd, id);
				}
			}
			p.set(bx, y + 3, zd, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
			p.bind(bx, y + 3, zd, "agent:" + id);
			p.set(bx - 1, y + 3, zd, WALNUT);
			p.set(bx + 1, y + 3, zd, WALNUT);
			// the chair is the desk spot: the agent sits looking at its monitors
			p.set(bx, y, zd + 1, St.stairs(Blocks.DARK_OAK_STAIRS, Direction.SOUTH, false));
			a.put(AnchorNames.DESK_PREFIX + id, bx + 0.5, y, zd + 1.5, 180, 0);
			a.put("seat_" + id, bx + 0.5, y, zd + 1.5, 180, 0);
			a.put(AnchorNames.MONITOR_PREFIX + id, bx + 0.5, y + 2.0, zd + 0.25, 0, 0);
			if (i + 1 < bays) {
				int gx = bx + pitch / 2;
				p.set(gx, y, zd, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
				p.set(gx, y + 1, zd, LANTERN);
			}
		}
	}

	/** The board the team reads: seven wide, four high, on the south wall facing the desks. */
	private static void taskWall(Plan p, Anchors.Builder a, Room r) {
		int y = r.y();
		int zb = r.z1();
		int cx = (r.x0() + r.x1()) / 2;
		int half = Math.min(3, (r.width() - 2) / 2);
		BlockState board = ModBlocks.TASK_BOARD.defaultBlockState().setValue(PanelBlock.FACING, Direction.NORTH);
		for (int x = cx - half; x <= cx + half; x++) {
			for (int h = 1; h <= 4; h++) {
				p.set(x, y + h, zb, board);
			}
			p.set(x, y, zb, TRIM);
			p.set(x, y + 5, zb, WALNUT);
		}
		a.put(AnchorNames.TASK_WALL, cx + 0.5, y + 2.5, zb - 0.25, 0, 0);
		// the podium in front of it, and the place the user stands to answer
		p.set(cx, y, zb - 2, ModBlocks.DECISION_PODIUM.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH));
		a.put(AnchorNames.DECISION_PODIUM, cx + 0.5, y, zb - 2 + 0.5, 0, 0);
		a.put(AnchorNames.USER, cx + 0.5, y, zb - 3.5, 0, 0);
		p.set(cx - 2, y, zb - 2, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		p.set(cx + 2, y, zb - 2, St.of(Blocks.POTTED_FERN));
	}

	/** Shelves on the west wall: the team's shared notes, its artifacts, and the user's own folder. */
	private static void library(Plan p, Anchors.Builder a, Room r) {
		int y = r.y();
		int xw = r.x0();
		int z0 = r.z0() + 2;
		int z1 = Math.min(r.z1() - 2, z0 + 7);
		String[] bays = {"shared", "artifacts", "vault"};
		int n = 1;
		for (int z = z0; z <= z1; z++) {
			String bay = bays[Math.min(bays.length - 1, (z - z0) / Math.max(1, (z1 - z0 + 1) / bays.length))];
			for (int h = 0; h <= 2; h++) {
				p.set(xw, y + h, z, ModBlocks.MEMORY_ARCHIVE.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.EAST));
				p.bind(xw, y + h, z, bay);
			}
			p.set(xw, y + 3, z, St.slab(Blocks.DARK_OAK_SLAB, true));
			if ((z - z0) % 3 == 1) {
				a.put(AnchorNames.slot(AnchorNames.LIBRARY, n++), xw + 1.5, y, z + 0.5, 90, 0);
			}
		}
		p.set(xw, y, z1 + 1, ModBlocks.MEMORY_CATALOG.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST));
		p.set(xw + 1, y, z0 - 1, St.of(Blocks.POTTED_AZALEA));
	}

	/**
	 * Terminals and the merge station on the east wall, with a bench of lamps for the test runs.
	 *
	 * <p>{@code doorZ} is where the stair arrives on this wall. Nothing is placed within a block
	 * of it: the way in gets cut through this wall after the fit-out, and a station standing in
	 * the doorway is simply deleted - which is how the second merge station disappeared, leaving
	 * an anchor an agent walked to and nothing to work at when it got there.
	 */
	private static void workbench(Plan p, Anchors.Builder a, Room r, int doorZ) {
		int y = r.y();
		int xe = r.x1();
		int z = r.z0() + 2;
		int[] n = {1, 1, 1};
		// three benches in order up the wall, stepping over the doorway rather than into it
		for (int stage = 0; stage < 3 && z <= r.z1(); stage++) {
			for (int i = 0; i < 2 && z <= r.z1(); i++) {
				while (Math.abs(z - doorZ) <= 1 && z <= r.z1()) {
					z++;
				}
				if (z > r.z1()) {
					break;
				}
				switch (stage) {
					case 0 -> {
						p.set(xe, y, z, TRIM);
						p.set(xe, y + 1, z, ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.WEST));
						a.put(AnchorNames.slot(AnchorNames.TERMINAL, n[0]++), xe - 1.5, y, z + 0.5, 270, 0);
					}
					case 1 -> {
						p.set(xe, y, z, ModBlocks.MERGE_STATION.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.WEST));
						a.put(AnchorNames.slot(AnchorNames.MERGESTATION, n[1]++), xe - 1.5, y, z + 0.5, 270, 0);
					}
					default -> {
						p.set(xe, y, z, TRIM);
						p.set(xe, y + 1, z, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
						a.put(AnchorNames.slot(AnchorNames.TESTBENCH, n[2]++), xe - 1.5, y, z + 0.5, 270, 0);
					}
				}
				z += 2;
			}
			z += 1;
		}
	}

	/** The long table in the middle: where the lead sits, and where the team meets. */
	private static void table(Plan p, Anchors.Builder a, Room r) {
		int y = r.y();
		int cz = (r.z0() + r.z1()) / 2;
		int cx = (r.x0() + r.x1()) / 2;
		int half = Math.min(3, (r.width() - 6) / 2);
		for (int x = cx - half - 2; x <= cx + half + 2; x++) {
			for (int z = cz - 2; z <= cz + 2; z++) {
				boolean border = x == cx - half - 2 || x == cx + half + 2 || z == cz - 2 || z == cz + 2;
				p.set(x, y, z, St.carpet(border ? DyeColor.BROWN : DyeColor.LIGHT_GRAY));
			}
		}
		for (int x = cx - half; x <= cx + half; x++) {
			p.set(x, y, cz, St.slab(Blocks.DARK_OAK_SLAB, true));
		}
		p.set(cx, y + 1, cz, LANTERN);
		p.set(cx - half, y + 1, cz, St.of(Blocks.POTTED_FERN));
		int n = 1;
		for (int x = cx - half + 1; x <= cx + half - 1; x += 2) {
			seat(p, a, AnchorNames.slot(AnchorNames.MEETING, n++), x, y, cz - 1, Direction.SOUTH, Blocks.BIRCH_STAIRS);
			seat(p, a, AnchorNames.slot(AnchorNames.MEETING, n++), x, y, cz + 1, Direction.NORTH, Blocks.BIRCH_STAIRS);
		}
		// the head of the table: the lead sits at the east end and looks west, down the table
		seat(p, a, "seat_lead", cx + half + 1, y, cz, Direction.WEST, Blocks.DARK_OAK_STAIRS);
		a.put(AnchorNames.MEETING, cx + half + 1.5, y, cz + 0.5, Direction.WEST.toYRot(), 0);
	}

	/**
	 * The goal atrium: a lamp on a copper plinth whose renderer draws the hologram above it - the
	 * goal, its progress ring, the task and agent counts. Without this the floor has every
	 * station and no way to see what the team is actually on.
	 *
	 * <p>The lamps beside it are the other two the Foreman drives: anything waiting on the user,
	 * and a merge waiting to be answered.
	 */
	private static void atrium(Plan p, Anchors.Builder a, Room r) {
		int y = r.y();
		int cx = (r.x0() + r.x1()) / 2;
		int cz = r.z0() + 3; // the desk end, clear of the podium and the spot the user stands on
		p.set(cx, y, cz, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
		p.bind(cx, y, cz, "goal:atrium");
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					p.set(cx + dx, y, cz + dz, St.slab(COPPER, false));
				}
			}
		}
		a.put(AnchorNames.GOAL_ATRIUM, cx + 0.5, y, cz + 2.5, 0, 0);
		// the two lamps that mean "you are needed", set into the wall over the task board
		int zb = r.z1();
		p.set(cx - 2, y + 5, zb, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.OFF));
		p.bind(cx - 2, y + 5, zb, "decisions");
		p.set(cx + 2, y + 5, zb, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.OFF));
		p.bind(cx + 2, y + 5, zb, "merge");
	}

	/** The beacon on the roof: the whole place at a glance, from outside and at night. */
	static void beacon(Plan p, Room r, int roofY) {
		int cx = (r.x0() + r.x1()) / 2;
		int cz = (r.z0() + r.z1()) / 2;
		p.set(cx, roofY, cz, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
		p.bind(cx, roofY, cz, "beacon");
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx != 0 || dz != 0) {
					p.set(cx + dx, roofY, cz + dz, St.slab(COPPER, false));
				}
			}
		}
	}

	/** Armchairs round a low table in the corner, for the agents with nothing on. */
	private static void lounge(Plan p, Anchors.Builder a, Room r) {
		int y = r.y();
		int x = r.x0() + 3;
		int z = r.z1() - 3;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean border = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				p.set(x + dx, y, z + dz, St.carpet(border ? DyeColor.BROWN : DyeColor.WHITE));
			}
		}
		p.set(x, y, z, St.slab(Blocks.DARK_OAK_SLAB, true));
		seat(p, a, AnchorNames.LOUNGE, x - 1, y, z, Direction.EAST, Blocks.SPRUCE_STAIRS);
		seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, 2), x + 1, y, z, Direction.WEST, Blocks.SPRUCE_STAIRS);
		seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, 3), x, y, z - 1, Direction.SOUTH, Blocks.SPRUCE_STAIRS);
		seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, 4), x, y, z + 1, Direction.NORTH, Blocks.SPRUCE_STAIRS);
	}
}
