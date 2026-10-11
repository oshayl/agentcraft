package dev.agentcraft.hq;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The ground a schematic HQ stands in, so an imported build is not a house on a billiard table.
 *
 * <p>A schematic brings its own footprint and usually its own flat ground. This keeps that part
 * exactly as it is and shapes everything around it: the land lifts into low wooded hills as it
 * leaves the build, crests a dozen blocks out, and comes back down to the meadow before the edge
 * of the site, so the horizon closes without a step where the build stops.
 *
 * <p>Deterministic, like every builder: every "random" choice comes from a hash of the
 * coordinates, so the same schematic lands in the same landscape every time.
 */
final class SchematicLandscape {
	/** How far the land stays flat around the build before the ground starts to lift. */
	private static final int APRON = 3;
	/**
	 * The hills reach their full height this far out from the build, and come back down over this
	 * much before the site edge. Both are generous on purpose: blocky terrain climbs a block at a
	 * time, so a short rise is a staircase of one-block terraces whose faces read as brown contour
	 * lines from any distance. Spread the same height over twice the ground and it rolls.
	 */
	private static final int RISE = 17;
	private static final int FALL = 13;

	private SchematicLandscape() {
	}

	/** The rectangle the build occupies (world coordinates), which stays level. */
	record Flat(int x0, int z0, int x1, int z1) {
		double distance(int x, int z) {
			double dx = Math.max(0, Math.max(x0 - x, x - x1));
			double dz = Math.max(0, Math.max(z0 - z, z - z1));
			return Math.sqrt(dx * dx + dz * dz);
		}
	}

	/**
	 * Hills, then trees and undergrowth on them. Runs before the schematic is stamped, so the
	 * build always wins over the landscape where the two meet.
	 */
	/** A ring the trees leave alone, so something can be built in it and still be seen. */
	record Glade(int cx, int cz, int inner, int outer) {
		boolean holds(int x, int z) {
			double d = Math.hypot(x - cx, z - cz);
			return d >= inner && d <= outer;
		}
	}

	static void shape(Plan p, int ground, Flat flat) {
		shape(p, ground, flat, null);
	}

	static void shape(Plan p, int ground, Flat flat, @org.jspecify.annotations.Nullable Glade glade) {
		int nx = p.maxX - p.minX + 1;
		int nz = p.maxZ - p.minZ + 1;
		int[] hs = new int[nx * nz];
		for (int x = p.minX; x <= p.maxX; x++) {
			for (int z = p.minZ; z <= p.maxZ; z++) {
				hs[(x - p.minX) * nz + (z - p.minZ)] = height(p, flat, x, z);
			}
		}
		smoothSteps(hs, nx, nz);
		for (int x = p.minX; x <= p.maxX; x++) {
			for (int z = p.minZ; z <= p.maxZ; z++) {
				int h = hs[(x - p.minX) * nz + (z - p.minZ)];
				if (h <= 0) {
					continue;
				}
				for (int y = ground; y < ground + h; y++) {
					p.set(x, y, z, Blocks.DIRT.defaultBlockState());
				}
				p.set(x, ground + h, z, Blocks.GRASS_BLOCK.defaultBlockState());
				p.setTop(x, z, ground + h);
			}
		}
		plant(p, ground, flat, glade);
	}

	/**
	 * Height above the meadow: nothing on the build's apron, hills out in the ring, nothing at the
	 * edge of the site. The long wavelength term is what stops it reading as a ring - without it
	 * every direction crests at the same height and the result is a crater rim.
	 */
	private static int height(Plan p, Flat flat, int x, int z) {
		double swell = 1.0 + 0.55 * Math.sin(x * 0.035 + 1.7) + 0.45 * Math.cos(z * 0.041 - 0.6) + 0.30 * Math.sin((x - z) * 0.022);
		double crest = (4.2 + 1.7 * Math.sin(x * 0.09 + z * 0.06) + 1.2 * Math.cos(z * 0.12 - x * 0.07) + 0.7 * Math.sin((x + z) * 0.21))
			* Math.max(0.15, swell);
		double out = HqLandscape.smooth(APRON, RISE, flat.distance(x, z));
		int edge = Math.min(Math.min(x - p.minX, p.maxX - x), Math.min(z - p.minZ, p.maxZ - z));
		return (int) Math.round(crest * out * HqLandscape.smooth(0, FALL, edge));
	}

	/** No cliffs: at most one block of step between neighbours, so hills show grass and not dirt walls. */
	private static void smoothSteps(int[] hs, int nx, int nz) {
		for (int pass = 0; pass < 12; pass++) {
			boolean changed = false;
			for (int i = 0; i < nx; i++) {
				for (int k = 0; k < nz; k++) {
					int h = hs[i * nz + k];
					int lo = h;
					if (i > 0) {
						lo = Math.min(lo, hs[(i - 1) * nz + k]);
					}
					if (i < nx - 1) {
						lo = Math.min(lo, hs[(i + 1) * nz + k]);
					}
					if (k > 0) {
						lo = Math.min(lo, hs[i * nz + k - 1]);
					}
					if (k < nz - 1) {
						lo = Math.min(lo, hs[i * nz + k + 1]);
					}
					if (h > lo + 1) {
						hs[i * nz + k] = lo + 1;
						changed = true;
					}
				}
			}
			if (!changed) {
				return;
			}
		}
	}

	/** Trees on the slopes, grass and flowers everywhere outside the build. */
	private static void plant(Plan p, int ground, Flat flat, @org.jspecify.annotations.Nullable Glade glade) {
		for (int x = p.minX + 1; x <= p.maxX - 1; x++) {
			for (int z = p.minZ + 1; z <= p.maxZ - 1; z++) {
				double d = flat.distance(x, z);
				if (d < APRON + 1) {
					continue; // the build keeps its own ground clear
				}
				int top = p.top(x, z);
				if (!p.isAir(x, top + 1, z)) {
					continue;
				}
				double r = HqLandscape.rnd(x, z, 9001);
				int h = top - ground;
				// Denser on the hills, thinning towards the build so the windows keep their light
				// and, at the far end, thinning again rather than stopping at a circle of wood.
				int edge = Math.min(Math.min(x - p.minX, p.maxX - x), Math.min(z - p.minZ, p.maxZ - z));
				double stand = HqLandscape.smooth(APRON, RISE + 2, d) * HqLandscape.smooth(0, FALL + 4, edge);
				double clumps = 0.5 + 0.5 * Math.sin(x * 0.07 + Math.cos(z * 0.06) * 2.2);
				double density = 0.008 + 0.085 * stand * (0.45 + 0.55 * clumps) + 0.015 * Math.min(1, h / 4.0);
				// a glade keeps its trees down: something is built in there to be looked at
				if (r < density && spaced(x, z) && (glade == null || !glade.holds(x, z))) {
					HqLandscape.tree(p, x, z, kind(x, z), 9100);
					continue;
				}
				groundCover(p, x, z, top, h > 1);
			}
		}
	}

	/** Trees no closer than three blocks to each other, so the wood has floor rather than a hedge. */
	private static boolean spaced(int x, int z) {
		return (x * 31 + z * 17) % 3 == 0;
	}

	private static HqLandscape.Kind kind(int x, int z) {
		return switch (HqLandscape.hash(x, z, 9200) % 5) {
			case 0, 1 -> HqLandscape.Kind.SPRUCE;
			case 2 -> HqLandscape.Kind.BIRCH;
			case 3 -> HqLandscape.Kind.POPLAR;
			default -> HqLandscape.Kind.OAK;
		};
	}

	private static void groundCover(Plan p, int x, int z, int top, boolean hill) {
		double r = HqLandscape.rnd(x, z, 9300);
		double patch = Math.sin(x * 0.19 + Math.cos(z * 0.15) * 2.0) + Math.cos(z * 0.21 - x * 0.06);
		BlockState s = null;
		if (r < 0.24) {
			s = hill && r < 0.09 ? Blocks.FERN.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState();
		} else if (r < 0.28 && p.isAir(x, top + 2, z)) {
			p.set(x, top + 2, z, St.upper(hill ? Blocks.LARGE_FERN : Blocks.TALL_GRASS));
			s = St.lower(hill ? Blocks.LARGE_FERN : Blocks.TALL_GRASS);
		} else if (r < 0.33 && patch > 0.8) {
			s = St.flowerBed(Blocks.WILDFLOWERS, Direction.from2DDataValue(HqLandscape.hash(x, z, 9400) & 3), 1 + HqLandscape.hash(x, z, 9401) % 4);
		} else if (r < 0.37 && patch < -1.0 && !hill) {
			s = switch (HqLandscape.hash(x, z, 9500) % 5) {
				case 0 -> Blocks.POPPY.defaultBlockState();
				case 1 -> Blocks.CORNFLOWER.defaultBlockState();
				case 2 -> Blocks.DANDELION.defaultBlockState();
				case 3 -> Blocks.OXEYE_DAISY.defaultBlockState();
				default -> Blocks.LILY_OF_THE_VALLEY.defaultBlockState();
			};
		} else if (r < (hill ? 0.52 : 0.385)) {
			// the slopes are stepped by a block at a time, and bare steps read as terracing:
			// undergrowth on them breaks the contour lines up
			s = hill && r > 0.44 ? St.leafLitter(Direction.from2DDataValue(HqLandscape.hash(x, z, 9600) & 3), 1 + HqLandscape.hash(x, z, 9601) % 3)
				: Blocks.BUSH.defaultBlockState();
		}
		if (s != null) {
			p.set(x, top + 1, z, s);
		}
	}
}
