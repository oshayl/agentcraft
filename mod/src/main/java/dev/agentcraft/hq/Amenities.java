package dev.agentcraft.hq;

import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The parts of an HQ that are not work: where you arrive, and where you sit when you are not at
 * a desk.
 *
 * <p>A team that only has desks looks like a server room. The lounge on the work floor covers
 * the short break; these cover the rest - a lobby at the foot of the stair, so arriving at the
 * building means walking into something rather than appearing upstairs, and the pool the build
 * already had, which was sitting twenty blocks underground in the dark with nothing around it.
 */
final class Amenities {
	private static final BlockState WHITE = St.concrete(DyeColor.WHITE);
	private static final BlockState GLASS = Blocks.GLASS_PANE.defaultBlockState();
	private static final BlockState FLOOR = dev.agentcraft.block.ModBlocks.OAK_PARQUET.defaultBlockState();

	private Amenities() {
	}

	/**
	 * A glass lobby at the foot of the stair tower: somewhere to wait, with seats facing each
	 * other across a low table, plants, and the way up in front of you.
	 */
	static int lobby(Plan p, Anchors.Builder a, int cx, int cz, int ground, int towerRadius, int westLimit) {
		// Level with the house, not with the lawn. The building stands on a plinth two blocks
		// up, and a lobby laid on the grass meant you stepped down out of the house and up
		// again into the stair, with nothing joining the two but the stair's own doorway.
		int floor = ground + 1;
		int y = floor + 1;
		// Not a ring. A square lobby round a seven-wide tower left two columns of floor on each
		// side - a corridor you could not put a chair in without blocking it, which is exactly
		// what it looked like with two agents standing in it. It cannot grow west, because the
		// house is there, so it grows the other three ways and the room is the east half.
		int x0 = Math.max(westLimit, cx - towerRadius - 6);
		int x1 = cx + towerRadius + 6;
		int z0 = cz - towerRadius - 5;
		int z1 = cz + towerRadius + 5;
		int top = y + 4;
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				// the tower keeps its own walls; the lobby wraps round it
				if (Math.abs(x - cx) <= towerRadius && Math.abs(z - cz) <= towerRadius) {
					continue;
				}
				boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
				boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
				p.set(x, floor, z, FLOOR);
				p.set(x, ground, z, WHITE); // the plinth it stands on, so it is not floating
				boolean post = wall && ((x - x0) % 5 == 0 || (z - z0) % 5 == 0);
				for (int h = y; h < top; h++) {
					p.set(x, h, z, wall ? (corner || post ? WHITE : GLASS) : Blocks.AIR.defaultBlockState());
				}
				// light in the ceiling. It had one lantern on its table, so the room you arrive
				// in measured zero block light over most of its floor at night.
				boolean lit = !wall && (x - x0) % 3 == 1 && (z - z0) % 3 == 1;
				p.set(x, top, z, lit ? dev.agentcraft.block.ModBlocks.GLOW_PANEL.defaultBlockState() : WHITE);
			}
		}

		// The lounge: a rug, a low table and four chairs round it, in the wide bay east of the
		// tower. Chairs on the four sides rather than side by side, because an agent steps into
		// a seat from the cell next to it - two chairs touching means standing on one to sit in
		// the other, and the slot contract wants 1.6 between any two places at a station.
		int lx = cx + towerRadius + 3;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean border = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				p.set(lx + dx, y, cz + dz, St.carpet(border ? DyeColor.BROWN : DyeColor.LIGHT_GRAY));
			}
		}
		p.set(lx, y, cz, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(lx, y + 1, cz, St.lantern(Blocks.LANTERN, false));
		seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, 3), lx - 2, y, cz, Direction.EAST);
		seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, 4), lx + 2, y, cz, Direction.WEST);
		seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, 5), lx, y, cz - 2, Direction.SOUTH);
		seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, 6), lx, y, cz + 2, Direction.NORTH);

		// a desk facing the door, in the bay north of the tower
		int dz0 = cz - towerRadius - 2;
		for (int dx = -1; dx <= 1; dx++) {
			p.set(cx + dx, y, dz0, St.slab(Blocks.DARK_OAK_SLAB, true));
			p.set(cx + dx, y + 1, dz0, dx == 0
				? dev.agentcraft.block.ModBlocks.MONITOR.defaultBlockState().setValue(dev.agentcraft.block.FacingEntityBlock.FACING, Direction.SOUTH)
				: St.lantern(Blocks.LANTERN, false));
		}

		// something green in the corners, which is the only floor nobody has to walk over
		for (int x : new int[] {x0 + 1, x1 - 1}) {
			for (int z : new int[] {z0 + 1, z1 - 1}) {
				if (p.isAir(x, y, z) && !p.isAir(x, y - 1, z)) {
					p.set(x, y, z, St.of(((x + z) & 1) == 0 ? Blocks.POTTED_AZALEA : Blocks.POTTED_FERN));
				}
			}
		}

		// the way in, three wide, with steps up from the lawn onto the plinth
		for (int dx = -1; dx <= 1; dx++) {
			p.set(cx + dx, floor, z1, FLOOR);
			for (int h = 0; h <= 2; h++) {
				p.set(cx + dx, y + h, z1, Blocks.AIR.defaultBlockState());
			}
			p.set(cx + dx, floor, z1 + 1, St.stairs(Blocks.SMOOTH_SANDSTONE_STAIRS, Direction.NORTH, false));
			p.set(cx + dx, ground, z1 + 1, WHITE);
			for (int h = 1; h <= 2; h++) {
				p.set(cx + dx, floor + h, z1 + 1, Blocks.AIR.defaultBlockState());
			}
		}
		a.put(AnchorNames.ENTRANCE, cx + 0.5, y, z1 - 1.5, 180, 0);
		return x0;
	}

	/**
	 * A walkway from the lobby straight into the building beside it, at the lobby's own level.
	 *
	 * <p>The stair's landing doorway was the only thing joining the two, and it is three blocks
	 * wide in the middle of one side. This is the front hall: you come in, and the house is in
	 * front of you.
	 *
	 * @return true when it found floor to join
	 */
	static boolean hall(Plan p, int fromX, int z, int y, int reach) {
		int to = Integer.MIN_VALUE;
		for (int x = fromX - 1; x >= fromX - reach; x--) {
			boolean here = standable(p, x, y, z) || standable(p, x, y, z - 1) || standable(p, x, y, z + 1);
			boolean beyond = standable(p, x - 1, y, z) || standable(p, x - 1, y, z - 1) || standable(p, x - 1, y, z + 1);
			if (here && beyond) {
				to = x;
				break;
			}
		}
		if (to == Integer.MIN_VALUE) {
			return false;
		}
		for (int x = fromX; x >= to; x--) {
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
			p.set(x, y + 3, z, x % 3 == 0 ? dev.agentcraft.block.ModBlocks.GLOW_PANEL.defaultBlockState() : WHITE);
		}
		return true;
	}

	private static boolean standable(Plan p, int x, int y, int z) {
		return !p.get(x, y - 1, z).isAir() && p.get(x, y, z).isAir() && p.get(x, y + 1, z).isAir();
	}

	/** A seat facing {@code look}: a stair seats you the way it is NOT facing, so it points back. */
	private static void seat(Plan p, Anchors.Builder a, String anchor, int x, int y, int z, Direction look) {
		p.set(x, y, z, St.stairs(Blocks.SPRUCE_STAIRS, look.getOpposite(), false));
		a.put(anchor, x + 0.5, y, z + 0.5, look.toYRot(), 0);
	}

	/**
	 * The pool the build came with, made somewhere to be: a deck around the water, light in the
	 * ceiling, loungers facing it. Does nothing when the build has no pool.
	 *
	 * @return true when a pool was found and fitted out
	 */
	static int poolside(Plan p, Anchors.Builder a, int minX, int minZ, int maxX, int maxZ, int below) {
		// The level with the most water under the building, not the first water found: a modern
		// house has a basin or a trough near the surface, and decking one of those leaves sea
		// lanterns hanging over the lawn.
		int bestY = -1;
		int bestN = 0;
		for (int y = below; y > below - 40; y--) {
			int n = 0;
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					// the surface of a body of water: water here, none directly above
					if (!p.get(x, y, z).getFluidState().isEmpty() && p.get(x, y + 1, z).getFluidState().isEmpty()) {
						n++;
					}
				}
			}
			if (n > bestN) {
				bestN = n;
				bestY = y;
			}
		}
		if (bestN < 20 || bestY < 0) {
			return -1;
		}
		int x0 = Integer.MAX_VALUE;
		int z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int z1 = Integer.MIN_VALUE;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				if (!p.get(x, bestY, z).getFluidState().isEmpty()) {
					x0 = Math.min(x0, x);
					z0 = Math.min(z0, z);
					x1 = Math.max(x1, x);
					z1 = Math.max(z1, z);
				}
			}
		}
		// The build already laid this deck. Earlier this rebuilt it at the water line and punched
		// a hole under every lounger, because the deck sits a block above the water, not level
		// with it. So nothing is rebuilt: the loungers go on the floor that is already there.
		int n = 5;
		int placed = 0;
		int deck = -1;
		for (int x = x0; x <= x1 && n <= 8; x += 3) {
			int z = z0 - 2;
			int y = standing(p, x, z, bestY);
			if (y < 0) {
				continue;
			}
			deck = y;
			// A stair seats you looking the way it is NOT facing, so a lounger north of the water
			// faces north and the sitter looks south, over the pool. Both of these were the wrong
			// way round and the team sat with their backs to it.
			// a seat you cannot step into is furniture: skip it unless a side is clear floor
			if (standing(p, x - 1, z, bestY) != y && standing(p, x + 1, z, bestY) != y) {
				continue;
			}
			p.set(x, y, z, St.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
			a.put(AnchorNames.slot(AnchorNames.LOUNGE, n++), x + 0.5, y, z + 0.5, 0, 0);
			placed++;
		}
		if (placed == 0) {
			return -1;
		}
		// light in the ceiling, only where there is a ceiling to put it in
		for (int x = x0; x <= x1; x += 2) {
			for (int z : new int[] {z0 - 1, z1 + 1}) {
				for (int h = 3; h <= 6; h++) {
					if (!p.get(x, bestY + h, z).isAir()) {
						p.set(x, bestY + h, z, Blocks.SEA_LANTERN.defaultBlockState());
						break;
					}
				}
			}
		}
		return deck;
	}

	/**
	 * A corridor from the stair tower west into the building at this level, so the pool is on
	 * the stair rather than behind twenty blocks of ground.
	 */
	static void passage(Plan p, int fromX, int z, int toX, int y) {
		for (int x = Math.min(fromX, toX) - 1; x <= Math.max(fromX, toX); x++) {
			for (int dz = -1; dz <= 1; dz++) {
				p.set(x, y - 1, z + dz, St.slab(Blocks.SMOOTH_SANDSTONE_SLAB, true));
				for (int h = 0; h <= 2; h++) {
					p.set(x, y + h, z + dz, Blocks.AIR.defaultBlockState());
				}
				boolean side = Math.abs(dz) == 1;
				if (side) {
					p.set(x, y + 3, z + dz, WHITE);
					for (int h = 0; h <= 2; h++) {
						p.set(x, y + h, z + dz * 2, WHITE);
					}
				}
			}
			p.set(x, y + 3, z, (x % 5 == 0) ? Blocks.SEA_LANTERN.defaultBlockState() : WHITE);
		}
	}

	/** The y you would stand at on this column near the water, or -1 when there is nowhere. */
	private static int standing(Plan p, int x, int z, int near) {
		for (int y = near + 2; y >= near - 2; y--) {
			if (!p.get(x, y - 1, z).isAir() && p.get(x, y, z).isAir() && p.get(x, y + 1, z).isAir()
				&& p.get(x, y - 1, z).getFluidState().isEmpty()) {
				return y;
			}
		}
		return -1;
	}

}
