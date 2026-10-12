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
	static void lobby(Plan p, Anchors.Builder a, int cx, int cz, int ground, int towerRadius) {
		// Level with the house, not with the lawn. The building stands on a plinth two blocks
		// up, and a lobby laid on the grass meant you stepped down out of the house and up
		// again into the stair, with nothing joining the two but the stair's own doorway - a
		// gap you could see the garden through from inside the room you arrive in.
		int floor = ground + 1;
		int y = floor + 1;
		int r = towerRadius + 3;
		int top = y + 4;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				// the tower keeps its own walls; the lobby wraps round it
				if (Math.abs(dx) <= towerRadius && Math.abs(dz) <= towerRadius) {
					continue;
				}
				int x = cx + dx;
				int z = cz + dz;
				boolean wall = Math.abs(dx) == r || Math.abs(dz) == r;
				p.set(x, floor, z, FLOOR);
				p.set(x, ground, z, WHITE); // the plinth it stands on, so it is not floating
				for (int h = y; h < top; h++) {
					p.set(x, h, z, wall ? (Math.abs(dx) == r && Math.abs(dz) == r ? WHITE : GLASS) : Blocks.AIR.defaultBlockState());
				}
				// light in the lobby ceiling. It had one lantern on its table, so the room you
				// arrive in measured zero block light over most of its floor at night.
				boolean lit = !wall && Math.abs(dx) % 3 == 1 && Math.abs(dz) % 3 == 1;
				p.set(x, top, z, lit ? dev.agentcraft.block.ModBlocks.GLOW_PANEL.defaultBlockState() : WHITE);
			}
		}
		// The lobby is a ring two blocks deep around the tower, not a room with a tower in it:
		// the tower is seven across and the lobby thirteen, so there are two columns of floor on
		// each side and nothing wider than that fits. It used to be furnished as though it were
		// a room - a three-wide seating group starting four blocks off the tower - which put its
		// far side exactly on the wall: a chair replaced the glass and the anchor for whoever
		// sat in it was inside the wall. So it is laid out as what it is, a corridor you arrive
		// in, with everything against the glass and the walking line left clear.
		int inX0 = cx - r + 1;
		int inX1 = cx + r - 1;
		int inZ1 = cz + r - 1;
		// benches either side of the door, backs to the glass, looking in at the stair
		int n = 3;
		for (int dx : new int[] {-3, -2, 2, 3}) {
			seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, n++), cx + dx, y, inZ1, Direction.NORTH);
		}
		for (int dx : new int[] {-1, 1}) {
			p.set(cx + dx, y, inZ1, St.of(dx < 0 ? Blocks.POTTED_FERN : Blocks.POTTED_FLOWERING_AZALEA));
		}
		// a desk down the east side, facing the way you come in
		for (int dz = -1; dz <= 1; dz++) {
			p.set(inX1, y, cz + dz, St.slab(Blocks.DARK_OAK_SLAB, true));
			p.set(inX1, y + 1, cz + dz, dz == 0
				? dev.agentcraft.block.ModBlocks.MONITOR.defaultBlockState().setValue(dev.agentcraft.block.FacingEntityBlock.FACING, Direction.WEST)
				: St.lantern(Blocks.LANTERN, false));
		}
		// and something green in the corners, which is the only place nobody has to walk
		for (int x : new int[] {inX0, inX1}) {
			for (int z : new int[] {cz - r + 1, inZ1}) {
				if (p.isAir(x, y, z) && !p.isAir(x, y - 1, z)) {
					p.set(x, y, z, St.of(Blocks.POTTED_AZALEA));
				}
			}
		}
		// the way in, three wide, and where someone waiting is told to stand
		for (int dx = -1; dx <= 1; dx++) {
			p.set(cx + dx, ground, cz + r, FLOOR);
			for (int h = 0; h <= 2; h++) {
				p.set(cx + dx, y + h, cz + r, Blocks.AIR.defaultBlockState());
			}
		}
		// steps up from the lawn: the lobby stands on the plinth with the rest of the building
		for (int dx = -1; dx <= 1; dx++) {
			p.set(cx + dx, floor, cz + r + 1, St.stairs(Blocks.SMOOTH_SANDSTONE_STAIRS, Direction.NORTH, false));
			p.set(cx + dx, ground, cz + r + 1, WHITE);
			for (int h = 1; h <= 2; h++) {
				p.set(cx + dx, floor + h, cz + r + 1, Blocks.AIR.defaultBlockState());
			}
		}
		a.put(AnchorNames.ENTRANCE, cx + 0.5, y, cz + r - 1.5, 180, 0);
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
