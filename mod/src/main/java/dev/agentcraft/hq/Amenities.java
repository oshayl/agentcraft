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
		int y = ground + 1;
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
				p.set(x, ground, z, FLOOR);
				for (int h = y; h < top; h++) {
					p.set(x, h, z, wall ? (Math.abs(dx) == r && Math.abs(dz) == r ? WHITE : GLASS) : Blocks.AIR.defaultBlockState());
				}
				// light in the lobby ceiling. It had one lantern on its table, so the room you
				// arrive in measured zero block light over most of its floor at night.
				boolean lit = !wall && Math.abs(dx) % 3 == 1 && Math.abs(dz) % 3 == 1;
				p.set(x, top, z, lit ? dev.agentcraft.block.ModBlocks.GLOW_PANEL.defaultBlockState() : WHITE);
			}
		}
		// Reception, laid out from the radius rather than from numbers that happened to fit.
		// The seating used to start four blocks off the tower and run three wide, which put its
		// far side exactly on the wall ring: a chair replaced the glass, and the anchor for
		// whoever sat in it was inside the wall.
		int inner = r - 1; // the last column that is floor rather than wall
		int sx = cx + towerRadius + 2;
		if (sx + 1 <= inner) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					p.set(sx + dx, y, cz + dz, St.carpet(Math.abs(dz) == 2 ? DyeColor.BROWN : DyeColor.LIGHT_GRAY));
				}
			}
			p.set(sx, y, cz, St.slab(Blocks.DARK_OAK_SLAB, true));
			p.set(sx, y + 1, cz, St.lantern(Blocks.LANTERN, false));
			// benches down the long sides, facing each other across the table
			for (int dx = -1; dx <= 1; dx += 2) {
				seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, dx < 0 ? 3 : 4), sx + dx, y, cz - 2, Direction.SOUTH);
				seat(p, a, AnchorNames.slot(AnchorNames.LOUNGE, dx < 0 ? 5 : 6), sx + dx, y, cz + 2, Direction.NORTH);
			}
			p.set(sx + 1, y, cz - 1, St.of(Blocks.POTTED_FERN));
			p.set(sx + 1, y, cz + 1, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		}
		// a desk facing whoever comes in, on the far side of the tower from the door
		int dx0 = cx - towerRadius - 2;
		if (dx0 - 1 >= cx - inner) {
			for (int dz = -1; dz <= 1; dz++) {
				p.set(dx0, y, cz + dz, St.slab(Blocks.DARK_OAK_SLAB, true));
				p.set(dx0, y + 1, cz + dz, dz == 0 ? dev.agentcraft.block.ModBlocks.MONITOR.defaultBlockState()
					.setValue(dev.agentcraft.block.FacingEntityBlock.FACING, Direction.EAST) : WHITE);
			}
			p.set(dx0 - 1, y, cz, St.stairs(Blocks.DARK_OAK_STAIRS, Direction.WEST, false));
		}
		// something green in the corners the walkway does not use
		for (int sgnX = -1; sgnX <= 1; sgnX += 2) {
			for (int sgnZ = -1; sgnZ <= 1; sgnZ += 2) {
				int px = cx + sgnX * inner;
				int pz = cz + sgnZ * inner;
				if (p.isAir(px, y, pz) && !p.isAir(px, y - 1, pz)) {
					p.set(px, y, pz, St.of((sgnX + sgnZ) == 0 ? Blocks.POTTED_AZALEA : Blocks.POTTED_FERN));
				}
			}
		}
		// the way in, and where someone waiting is told to stand
		for (int dz = -1; dz <= 1; dz++) {
			for (int h = 0; h <= 2; h++) {
				p.set(cx + dz, y + h, cz + r, Blocks.AIR.defaultBlockState());
			}
			p.set(cx + dz, ground, cz + r, FLOOR);
		}
		a.put(AnchorNames.ENTRANCE, cx + 0.5, y, cz + r - 1.5, 180, 0);
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
