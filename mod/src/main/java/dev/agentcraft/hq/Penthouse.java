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
		for (int y = bottom - 1; y <= top + 1; y++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					boolean wall = Math.abs(dx) == r || Math.abs(dz) == r;
					boolean corner = Math.abs(dx) == r && Math.abs(dz) == r;
					if (!wall) {
						p.set(cx + dx, y, cz + dz, Blocks.AIR.defaultBlockState());
					} else if (y == bottom - 1 || y == top + 1) {
						p.set(cx + dx, y, cz + dz, WHITE);
					} else if (corner || (y - bottom) % 5 == 0) {
						p.set(cx + dx, y, cz + dz, WHITE);
					} else {
						p.set(cx + dx, y, cz + dz, GLASS);
					}
				}
			}
		}
		int[][] ring = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
		Direction[] facing = {Direction.EAST, Direction.SOUTH, Direction.SOUTH, Direction.WEST, Direction.WEST, Direction.NORTH,
			Direction.NORTH, Direction.EAST};
		int steps = top - bottom;
		for (int i = 0; i <= steps; i++) {
			int[] c = ring[i % ring.length];
			p.set(cx + c[0], bottom + i, cz + c[1], St.stairs(Blocks.DARK_OAK_STAIRS, facing[i % facing.length], false));
		}
		for (int y = bottom - 1; y <= top; y++) {
			p.set(cx, y, cz, TRIM);
		}
		// a way in at the bottom and out at the top
		for (int h = 0; h <= 1; h++) {
			p.set(cx - r, bottom + h, cz, Blocks.AIR.defaultBlockState());
			p.set(cx - r, top + h, cz, Blocks.AIR.defaultBlockState());
			p.set(cx - r, top + h, cz - 1, Blocks.AIR.defaultBlockState());
		}
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
			p.set(x, y, cz - 1, St.stairs(Blocks.BIRCH_STAIRS, Direction.NORTH, false));
			a.put(AnchorNames.slot(AnchorNames.MEETING, n++), x + 0.5, y, cz - 0.5, 0, 0);
			p.set(x, y, cz + 1, St.stairs(Blocks.BIRCH_STAIRS, Direction.SOUTH, false));
			a.put(AnchorNames.slot(AnchorNames.MEETING, n++), x + 0.5, y, cz + 1.5, 180, 0);
		}
		// the head of the table: the lead's seat, facing down it
		p.set(cx + half + 1, y, cz, St.stairs(Blocks.DARK_OAK_STAIRS, Direction.EAST, false));
		a.put("seat_lead", cx + half + 1.5, y, cz + 0.5, 270, 0);
		a.put(AnchorNames.MEETING, cx + half + 1.5, y, cz + 0.5, 270, 0);
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
		p.set(x - 1, y, z - 1, St.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
		p.set(x + 1, y, z - 1, St.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
		p.set(x - 1, y, z + 1, St.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
		p.set(x + 1, y, z + 1, St.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
		a.put(AnchorNames.LOUNGE, x - 1 + 0.5, y, z - 0.5, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.LOUNGE, 2), x + 1 + 0.5, y, z - 0.5, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.LOUNGE, 3), x - 1 + 0.5, y, z + 1.5, 0, 0);
		a.put(AnchorNames.slot(AnchorNames.LOUNGE, 4), x + 1 + 0.5, y, z + 1.5, 0, 0);
	}
}
