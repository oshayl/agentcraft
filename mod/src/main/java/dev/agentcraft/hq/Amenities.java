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
				p.set(x, top, z, WHITE);
			}
		}
		// seating: two benches facing each other over a table, east of the tower
		int sx = cx + towerRadius + 2;
		for (int dz = -2; dz <= 2; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				p.set(sx + dx, y - 1, cz + dz, St.carpet(Math.abs(dz) == 2 ? DyeColor.BROWN : DyeColor.LIGHT_GRAY));
			}
		}
		p.set(sx, y - 1, cz, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(sx, y, cz, St.lantern(Blocks.LANTERN, false));
		p.set(sx, y - 1, cz - 2, St.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
		p.set(sx, y - 1, cz + 2, St.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, false));
		a.put(AnchorNames.slot(AnchorNames.LOUNGE, 3), sx + 0.5, y - 1, cz - 1.5, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.LOUNGE, 4), sx + 0.5, y - 1, cz + 2.5, 0, 0);
		p.set(sx + 1, y - 1, cz - 2, St.of(Blocks.POTTED_FERN));
		p.set(sx + 1, y - 1, cz + 2, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		// the way in, and where someone waiting is told to stand
		for (int h = 0; h <= 1; h++) {
			p.set(cx, y - 1 + h, cz + r, Blocks.AIR.defaultBlockState());
		}
		a.put(AnchorNames.ENTRANCE, cx + 0.5, y - 1, cz + r - 1.5, 180, 0);
	}

	/**
	 * The pool the build came with, made somewhere to be: a deck around the water, light in the
	 * ceiling, loungers facing it. Does nothing when the build has no pool.
	 *
	 * @return true when a pool was found and fitted out
	 */
	static boolean poolside(Plan p, Anchors.Builder a, int minX, int minZ, int maxX, int maxZ, int below) {
		// The level with the most water under the building, not the first water found: a modern
		// house has a basin or a trough near the surface, and decking those leaves sea lanterns
		// hanging over the lawn.
		int bestY = -1;
		int bestN = 0;
		for (int y = below; y > below - 40; y--) {
			int n = 0;
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					// the surface of a body of water: water here, none directly above
					boolean water = !p.get(x, y, z).getFluidState().isEmpty();
					boolean deeper = !p.get(x, y + 1, z).getFluidState().isEmpty();
					if (water && !deeper) {
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
			return false;
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
		int deck = bestY;
		for (int x = x0 - 2; x <= x1 + 2; x++) {
			for (int z = z0 - 2; z <= z1 + 2; z++) {
				boolean water = x >= x0 && x <= x1 && z >= z0 && z <= z1 && !p.get(x, deck, z).getFluidState().isEmpty();
				if (water || p.get(x, deck, z).isAir()) {
					continue;
				}
				p.set(x, deck, z, St.slab(Blocks.SMOOTH_SANDSTONE_SLAB, true));
				for (int h = 1; h <= 2; h++) {
					if (!p.get(x, deck + h, z).isAir()) {
						break;
					}
					p.set(x, deck + h, z, Blocks.AIR.defaultBlockState());
				}
			}
		}
		// loungers along one side, facing the water, a lantern between them
		int lz = z0 - 2;
		int n = 5;
		for (int x = x0; x <= x1 && n <= 8; x += 3) {
			if (p.get(x, deck - 1, z0 - 2).isAir()) {
				continue;
			}
			p.set(x, deck, lz, St.stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, false));
			a.put(AnchorNames.slot(AnchorNames.LOUNGE, n++), x + 0.5, deck, lz + 0.5, 180, 0);
			if (x + 1 <= x1) {
				p.set(x + 1, deck, lz, St.lantern(Blocks.LANTERN, false));
			}
		}
		// light in the ceiling, only where there is a ceiling to put it in
		for (int x = x0; x <= x1; x += 2) {
			for (int z : new int[] {z0 - 1, z1 + 1}) {
				for (int h = 3; h <= 6; h++) {
					if (!p.get(x, deck + h, z).isAir()) {
						p.set(x, deck + h, z, Blocks.SEA_LANTERN.defaultBlockState());
						break;
					}
				}
			}
		}
		return true;
	}
}
