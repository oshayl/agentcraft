package dev.agentcraft.hq;

import dev.agentcraft.layout.Anchors;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A parkour course through the grounds, for the person waiting on the team.
 *
 * <p>Watching six agents think is not a spectator sport, and the land around the HQ was scenery
 * nobody could do anything with. This lays a run through it: out of the lobby, up over the
 * wooded hills, round the building at roof height and back down to where it started.
 *
 * <p>The jumps are the whole thing, so they are generated to be jumpable rather than drawn and
 * hoped over. Minecraft's limits are four blocks flat at a sprint and three when the landing is
 * a block higher, so the course walks a curve and drops a platform whenever it is three blocks
 * on from the last one, and never climbs more than one at a time. Nothing here needs a sprint
 * and nothing needs a ladder.
 *
 * <p>Checkpoints are lit pads every eighth platform, which is also how you read your progress
 * from the ground. Everything is placed from a hash of the coordinates, so the course is the
 * same run every build and a rebuild never moves a platform out from under anyone.
 */
final class Parkour {
	/** Horizontal distance between platforms: three is a jump everyone can make. */
	private static final double GAP = 3.0;
	/** How many platforms the run is. */
	private static final int LENGTH = 48;
	/** Highest the course climbs above the meadow. */
	private static final int PEAK = 11;

	private Parkour() {
	}

	/** One platform of the run. */
	private record Step(int x, int y, int z, boolean checkpoint) {
	}

	/**
	 * Builds the course in a ring around ({@code cx}, {@code cz}), between {@code inner} and
	 * {@code outer} blocks out, and writes the start and finish as anchors.
	 *
	 * <p>{@code keepOut} is everything already built, as {@code {x0, z0, x1, z1}}: no platform
	 * lands in it and the ring bulges outward where it would. A ring at a fixed radius was fine
	 * until the stair tower moved and took the lobby with it, and then the course ran through
	 * the room people arrive in - a lime pad against the glass and lanterns on the carpet.
	 *
	 * <p>{@code from} is where the run should begin, in blocks from the centre: the course is
	 * turned so its first platform is out that way, which is how the approach stays a short
	 * straight walk from the door instead of a path round two corners of the building.
	 *
	 * @return the first platform, for the approach to be drawn to, or null when there is no room
	 */
	static int @org.jspecify.annotations.Nullable [] build(Plan p, Anchors.Builder a, int cx, int cz, int ground, int inner, int outer,
		int[] keepOut, double fromAngle) {
		List<Step> steps = route(cx, cz, ground, inner, outer, keepOut, fromAngle);
		if (steps.size() < 4) {
			return null;
		}
		// The wood this runs through is twelve blocks tall, so a course laid into it is a course
		// inside a hedge. Every platform and every gap gets a corridor cut through the leaves
		// first - which also reads, from the ground, as a run cut through the trees.
		for (int i = 0; i < steps.size(); i++) {
			clear(p, steps.get(i), i == 0 ? steps.get(0) : steps.get(i - 1));
		}
		for (int i = 0; i < steps.size(); i++) {
			platform(p, steps.get(i), i, steps.size());
		}
		Step first = steps.get(0);
		Step last = steps.get(steps.size() - 1);
		pad(p, first, DyeColor.LIME);
		pad(p, last, DyeColor.ORANGE);
		a.put("parkour_start", first.x() + 0.5, first.y() + 1, first.z() + 0.5, 0, 0);
		a.put("parkour_finish", last.x() + 0.5, last.y() + 1, last.z() + 0.5, 0, 0);
		a.put(dev.agentcraft.layout.AnchorNames.CAM_PREFIX + "parkour", first.x() + 0.5, first.y() + 9, first.z() + 14.0, 0, 28);
		return new int[] {first.x(), first.z()};
	}

	/** Is this spot inside something already built (keepOut is {x0, z0, x1, z1})? */
	private static boolean built(int[] keepOut, double x, double z) {
		return x >= keepOut[0] && x <= keepOut[2] && z >= keepOut[1] && z <= keepOut[3];
	}

	/**
	 * The run: a curve around the building whose radius wanders and whose height rises to a peak
	 * halfway and comes back down, sampled wherever it is a jump's distance on from the last
	 * platform. The height is clamped to a block a step so every gap stays makeable.
	 */
	private static List<Step> route(int cx, int cz, int ground, int inner, int outer, int[] keepOut, double fromAngle) {
		List<Step> out = new ArrayList<>();
		double lastX = Double.NaN;
		double lastZ = 0;
		int lastY = ground + 1;
		// a little over one full turn, so the finish comes back near the start
		for (double t = 0; t <= 1.08 && out.size() < LENGTH; t += 0.0015) {
			double angle = fromAngle + t * 2 * Math.PI;
			double wander = 0.5 + 0.5 * Math.sin(angle * 3 + 0.7);
			double radius = inner + (outer - inner) * wander;
			double x = cx + Math.cos(angle) * radius;
			double z = cz + Math.sin(angle) * radius;
			// out past anything already built, rather than through it
			for (int push = 0; push < 48 && built(keepOut, x, z); push++) {
				radius += 1;
				x = cx + Math.cos(angle) * radius;
				z = cz + Math.sin(angle) * radius;
			}
			if (built(keepOut, x, z)) {
				continue;
			}
			// A run of identical three-block jumps is a chore. The gap changes with the stretch:
			// two blocks to breathe on, three for the standard sprint jump, and the odd four to
			// make you commit - which is also the limit, flat, with no block to climb.
			double want = switch (out.size() % 6) {
				case 0, 3 -> 2.2;
				case 4 -> 3.6;
				default -> GAP;
			};
			if (!Double.isNaN(lastX) && Math.hypot(x - lastX, z - lastZ) < want) {
				continue;
			}
			double progress = out.size() / (double) LENGTH;
			int target = ground + 1 + (int) Math.round(PEAK * Math.sin(Math.PI * progress));
			int rise = Math.hypot(x - lastX, z - lastZ) > 3.2 ? 0 : 1;
			int y = Double.isNaN(lastX) ? target : Math.max(lastY - 2, Math.min(lastY + rise, target));
			out.add(new Step((int) Math.round(x), y, (int) Math.round(z), out.size() > 0 && out.size() % 8 == 0));
			lastX = x;
			lastZ = z;
			lastY = y;
		}
		return out;
	}

	/** Cuts the leaves and branches out of a platform and the gap you jumped from. */
	private static void clear(Plan p, Step s, Step from) {
		int steps = Math.max(1, (int) Math.round(Math.hypot(s.x() - from.x(), s.z() - from.z())));
		for (int i = 0; i <= steps; i++) {
			double f = i / (double) steps;
			int x = (int) Math.round(from.x() + (s.x() - from.x()) * f);
			int z = (int) Math.round(from.z() + (s.z() - from.z()) * f);
			int y = (int) Math.round(from.y() + (s.y() - from.y()) * f);
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					for (int dy = 0; dy <= 4; dy++) {
						if (dx * dx + dz * dz > 5) {
							continue;
						}
						BlockState there = p.get(x + dx, y + dy, z + dz);
						// only what grew there: the ground, the building and the course stay
						if (there.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock
							|| there.is(net.minecraft.tags.BlockTags.LOGS)
							|| there.getBlock() instanceof net.minecraft.world.level.block.BushBlock) {
							p.set(x + dx, y + dy, z + dz, Blocks.AIR.defaultBlockState());
						}
					}
				}
			}
		}
	}

	/** A platform, the air over it, and a lantern every few so the run reads at night. */
	private static void platform(Plan p, Step s, int i, int total) {
		BlockState top = s.checkpoint() ? Blocks.SEA_LANTERN.defaultBlockState() : surface(i);
		p.set(s.x(), s.y(), s.z(), top);
		// a jump needs somewhere to land AND room to arrive: three clear above, always
		for (int h = 1; h <= 3; h++) {
			p.set(s.x(), s.y() + h, s.z(), Blocks.AIR.defaultBlockState());
		}
		if (s.checkpoint()) {
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				p.set(s.x() + d[0], s.y(), s.z() + d[1], St.slab(Blocks.CUT_COPPER_SLAB.waxed().unaffected(), true));
				for (int h = 1; h <= 3; h++) {
					p.set(s.x() + d[0], s.y() + h, s.z() + d[1], Blocks.AIR.defaultBlockState());
				}
			}
		} else if (i % 4 == 2) {
			// the lantern goes under the platform, so it lights the gap without blocking the jump
			p.set(s.x(), s.y() - 1, s.z(), St.lantern(Blocks.LANTERN, true));
		}
		// the last stretch is a handrail of fences, for the look of it from below
		if (i > total - 6 && i % 2 == 0) {
			p.set(s.x(), s.y() - 2, s.z(), Blocks.DARK_OAK_FENCE.defaultBlockState());
		}
	}

	/** The run changes material as it climbs, so you can see how far you have got. */
	private static BlockState surface(int i) {
		return switch ((i / 8) % 4) {
			case 0 -> Blocks.POLISHED_ANDESITE.defaultBlockState();
			case 1 -> Blocks.SMOOTH_SANDSTONE.defaultBlockState();
			case 2 -> dev.agentcraft.block.ModBlocks.WALNUT_PANEL.defaultBlockState();
			default -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
		};
	}

	/** Start and finish: a coloured pad with a light under it and a post you can see from afar. */
	private static void pad(Plan p, Step s, DyeColor colour) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				p.set(s.x() + dx, s.y(), s.z() + dz, St.concrete(colour));
				for (int h = 1; h <= 3; h++) {
					p.set(s.x() + dx, s.y() + h, s.z() + dz, Blocks.AIR.defaultBlockState());
				}
			}
		}
		p.set(s.x(), s.y() - 1, s.z(), Blocks.SEA_LANTERN.defaultBlockState());
		for (int[] d : new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
			for (int h = 1; h <= 2; h++) {
				p.set(s.x() + d[0], s.y() + h, s.z() + d[1], Blocks.DARK_OAK_FENCE.defaultBlockState());
			}
			p.set(s.x() + d[0], s.y() + 3, s.z() + d[1], St.lantern(Blocks.LANTERN, false));
		}
	}

	/** A lit path on the grass from the building to the start, so the course is findable. */
	static void approach(Plan p, int fromX, int fromZ, int toX, int toZ, int ground) {
		int steps = (int) Math.round(Math.hypot(toX - fromX, toZ - fromZ));
		for (int i = 0; i <= steps; i++) {
			double f = steps == 0 ? 0 : i / (double) steps;
			int x = (int) Math.round(fromX + (toX - fromX) * f);
			int z = (int) Math.round(fromZ + (toZ - fromZ) * f);
			int y = p.top(x, z);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (Math.abs(dx) + Math.abs(dz) > 1) {
						continue;
					}
					int ty = p.top(x + dx, z + dz);
					p.set(x + dx, ty, z + dz, St.slab(Blocks.SMOOTH_SANDSTONE_SLAB, true));
					p.set(x + dx, ty + 1, z + dz, Blocks.AIR.defaultBlockState());
				}
			}
			if (i % 7 == 3) {
				p.set(x + 2, y + 1, z, Blocks.DARK_OAK_FENCE.defaultBlockState());
				p.set(x + 2, y + 2, z, St.lantern(Blocks.LANTERN, false));
			}
		}
	}
}
