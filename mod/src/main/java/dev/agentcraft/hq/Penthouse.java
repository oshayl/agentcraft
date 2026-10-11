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

	/**
	 * A spiral stair in a glass tower against the building, from {@code bottom} up to the
	 * penthouse floor.
	 *
	 * <p>The build came with ladders, and an agent cannot climb one: the pathfinder walks a grid
	 * and steps at most a block at a time. A spiral solves that - a three by three well, the
	 * middle a newel, the eight cells round it a tread each, rising a block per tread. Those
	 * eight are orthogonally adjacent in sequence, which is the part that matters: the
	 * pathfinder will not cut a corner past the newel, so a spiral that only worked diagonally
	 * would be a stair no agent could use.
	 *
	 * <p>It stands in its own tower rather than threading the house, because the floors above
	 * the ground are set back and a stair cut through them hangs in the air halfway up.
	 */
	static void spiral(Plan p, int cx, int cz, int bottom, int top) {
		int r = 2;
		shaft(p, cx, cz, r, bottom, top);

		// Where the stair has to arrive level: every height beside the tower you could stand on -
		// this building's floors - plus the two ends. Floors closer together than four blocks are
		// dropped: a flight shorter than that puts two treads in one column within a block of
		// each other, and then neither can be stood on.
		java.util.List<Integer> floors = new java.util.ArrayList<>();
		floors.add(bottom);
		int wx = cx - r - 1;
		for (int y = bottom + 4; y <= top - 4; y++) {
			if (!p.get(wx, y - 1, cz).isAir() && p.get(wx, y, cz).isAir() && p.get(wx, y + 1, cz).isAir()
				&& y - floors.get(floors.size() - 1) >= 4) {
				floors.add(y);
			}
		}
		if (top - floors.get(floors.size() - 1) < 4) {
			floors.remove(floors.size() - 1);
		}
		floors.add(top);

		int[][] ring = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
		Direction[] facing = {Direction.EAST, Direction.SOUTH, Direction.SOUTH, Direction.WEST, Direction.WEST, Direction.NORTH,
			Direction.NORTH, Direction.EAST};
		int west = 6; // the cell the doorways open onto
		int i = west;
		landing(p, cx, cz, r, floors.get(0));
		for (int f = 1; f < floors.size(); f++) {
			int from = floors.get(f - 1);
			int to = floors.get(f);
			int climb = to - from;
			// A whole number of turns per flight, so every landing comes back round to the west
			// cell. The rise is spread over the turn rather than taken a block at a time: an
			// eight-cell turn that climbs less than eight has to go flat somewhere, and doing
			// that all in one place is what made the stair meet itself and dead-end.
			int turns = Math.max(1, (climb + 7) / 8);
			int cells = turns * 8;
			int y = from;
			for (int k = 0; k < cells; k++) {
				int risen = (k + 1) * climb / cells;
				int next = from + risen;
				i = (i + 1) % 8;
				if (k == cells - 1) {
					break; // the last cell of the flight is the landing itself
				}
				tread(p, cx, cz, ring[i], next - 1, facing[i]);
				y = next;
			}
			landing(p, cx, cz, r, to);
			i = west;
		}
		for (int yy = bottom - 2; yy <= top + 1; yy++) {
			p.set(cx, yy, cz, TRIM);
		}
	}

	/**
	 * Walks the finished stair the way an agent would and says where it breaks, or null when it
	 * goes all the way up. Three rebuilds in a row left a stair that looked right and dead-ended
	 * a few steps in, so the build checks its own work: for every height there has to be one
	 * cell you can stand in, and the next one up has to be beside it.
	 */
	static @Nullable String check(Plan p, int cx, int cz, int bottom, int top) {
		int[][] ring = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
		// every place in the well you could stand, as (ring index, height)
		java.util.Set<Long> stand = new java.util.HashSet<>();
		for (int i = 0; i < ring.length; i++) {
			int x = cx + ring[i][0];
			int z = cz + ring[i][1];
			for (int y = bottom; y <= top + 1; y++) {
				if (!p.get(x, y - 1, z).isAir() && p.get(x, y, z).isAir() && p.get(x, y + 1, z).isAir()) {
					stand.add(((long) i << 32) | (y & 0xffffffffL));
				}
			}
		}
		// walk from the bottom the way an agent would: a neighbouring cell, at most a block up
		java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		int highest = -1;
		for (long k : stand) {
			if ((int) (k & 0xffffffffL) == bottom) {
				queue.add(k);
				seen.add(k);
			}
		}
		if (queue.isEmpty()) {
			return "the stair has no bottom step at y=" + bottom;
		}
		while (!queue.isEmpty()) {
			long k = queue.poll();
			int i = (int) (k >> 32);
			int y = (int) (k & 0xffffffffL);
			highest = Math.max(highest, y);
			for (int d = -1; d <= 1; d += 2) {
				int j = Math.floorMod(i + d, ring.length);
				// the ring's cells are orthogonally adjacent in sequence; the corners are not
				if (Math.abs(ring[j][0] - ring[i][0]) + Math.abs(ring[j][1] - ring[i][1]) != 1) {
					continue;
				}
				for (int dy = -1; dy <= 1; dy++) {
					long next = ((long) j << 32) | ((y + dy) & 0xffffffffL);
					if (stand.contains(next) && seen.add(next)) {
						queue.add(next);
					}
				}
			}
		}
		return highest >= top ? null : "the stair stops at y=" + highest + " and the top is y=" + top;
	}

	/** The glass well the stair winds up, open inside and walled outside. */
	private static void shaft(Plan p, int cx, int cz, int r, int bottom, int top) {
		for (int y = bottom - 2; y <= top + 2; y++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					boolean wall = Math.abs(dx) == r || Math.abs(dz) == r;
					boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
					if (!wall) {
						p.set(cx + dx, y, cz + dz, y == bottom - 2 || y == top + 2 ? WHITE : Blocks.AIR.defaultBlockState());
					} else if (y == bottom - 2 || y == top + 2 || corner || (y - bottom) % 5 == 0) {
						p.set(cx + dx, y, cz + dz, WHITE);
					} else {
						p.set(cx + dx, y, cz + dz, GLASS);
					}
				}
			}
		}
	}

	private static void tread(Plan p, int cx, int cz, int[] c, int y, Direction f) {
		p.set(cx + c[0], y, cz + c[1], St.stairs(Blocks.DARK_OAK_STAIRS, f, false));
	}

	/**
	 * A floor-level landing: one cell, and the doorway beside it.
	 *
	 * <p>Only the west cell is touched, and that is the point. The ring comes back to each
	 * column every eight treads, so a landing that also tidied its neighbours - levelling them,
	 * clearing headroom over them - deleted the treads those columns hold on the turn above, and
	 * the stair ended in a dead end a few steps up. Twice. The landing is the tread the stair
	 * already arrived on, made a full block so it reads as a floor rather than a step.
	 */
	private static void landing(Plan p, int cx, int cz, int r, int y) {
		p.set(cx - 1, y - 1, cz, TRIM);
		for (int h = 0; h <= 2; h++) {
			p.set(cx - 1, y + h, cz, Blocks.AIR.defaultBlockState());
		}
		// through the wall: two high, with a sill you step out onto
		for (int h = 0; h <= 1; h++) {
			p.set(cx - r, y + h, cz, Blocks.AIR.defaultBlockState());
		}
		p.set(cx - r, y - 1, cz, TRIM);
	}

	/**
	 * The fit-out: desks along the long wall, the task wall facing them, the library and the
	 * terminals on the short walls, and a table in the middle for whoever is running the day.
	 */
	static void fitOut(Plan p, Anchors.Builder a, Room r, List<String> agents) {
		int y = r.y();
		desks(p, a, r, agents);
		taskWall(p, a, r);
		library(p, a, r);
		workbench(p, a, r);
		table(p, a, r);
		lounge(p, a, r);

		a.put(AnchorNames.GOAL_ATRIUM, (r.x0() + r.x1()) / 2.0 + 0.5, y, (r.z0() + r.z1()) / 2.0 + 0.5, 180, 0);
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

	/** Terminals and the merge station on the east wall, with a bench of lamps for the test runs. */
	private static void workbench(Plan p, Anchors.Builder a, Room r) {
		int y = r.y();
		int xe = r.x1();
		int z = r.z0() + 2;
		for (int i = 1; i <= 2; i++) {
			p.set(xe, y, z, TRIM);
			p.set(xe, y + 1, z, ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.WEST));
			a.put(AnchorNames.slot(AnchorNames.TERMINAL, i), xe - 1.5, y, z + 0.5, 270, 0);
			z += 2;
		}
		z += 1;
		for (int i = 1; i <= 2; i++) {
			p.set(xe, y, z, ModBlocks.MERGE_STATION.defaultBlockState().setValue(FacingEntityBlock.FACING, Direction.WEST));
			a.put(AnchorNames.slot(AnchorNames.MERGESTATION, i), xe - 1.5, y, z + 0.5, 270, 0);
			z += 2;
		}
		z += 1;
		for (int i = 1; i <= 2; i++) {
			p.set(xe, y, z, TRIM);
			p.set(xe, y + 1, z, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
			a.put(AnchorNames.slot(AnchorNames.TESTBENCH, i), xe - 1.5, y, z + 0.5, 270, 0);
			z += 2;
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
