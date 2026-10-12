package dev.agentcraft.block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * One block of a brand rug.
 *
 * <p>A logo in a single sixteen-pixel square is a smudge - the Noira mark reads as a squiggle and
 * the Shrigma wordmark as a smear - so a rug is several blocks, each carrying its own slice of
 * the picture. {@link #TILE} says which slice this block shows, and the blockstate maps that to
 * the texture. Laying one by hand would mean setting the number on every block, so
 * {@code /agentcraft rug} puts the whole thing down at once.
 *
 * <p>It is a carpet underneath: one pixel thick, walk-on, breaks in an instant. The background of
 * every tile is transparent, which is the one place a mark can be - a painting is drawn by a
 * render type that ignores alpha, a block picks its layer from the sprite.
 */
public class RugBlock extends CarpetBlock {
	/** Which slice of the mark: row-major from the north-west corner of the rug. */
	public static final IntegerProperty TILE = IntegerProperty.create("tile", 0, 7);

	private final int width;
	private final int depth;

	public RugBlock(Properties properties, int width, int depth) {
		super(properties);
		this.width = width;
		this.depth = depth;
		registerDefaultState(stateDefinition.any().setValue(TILE, 0));
	}

	/** How many blocks across the whole rug is. */
	public int width() {
		return width;
	}

	/** How many blocks deep. */
	public int depth() {
		return depth;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
		builder.add(TILE);
	}

	/**
	 * A tile on its own is a fragment of a picture, and every block placed by hand used to show
	 * slice zero, so laying a rug out of the creative inventory produced the same smudge repeated
	 * - which is what it looked like on the library floor: a scrap of the mark and nothing else.
	 * A rug placed by hand now assembles itself instead.
	 */
	@Override
	protected BlockState updateShape(BlockState state, net.minecraft.world.level.LevelReader level, net.minecraft.world.level.ScheduledTickAccess ticks,
		BlockPos pos, Direction direction, BlockPos neighbour, BlockState neighbourState, net.minecraft.util.RandomSource random) {
		return super.updateShape(state, level, ticks, pos, direction, neighbour, neighbourState, random);
	}

	/**
	 * Renumber the patch of this rug that {@code pos} belongs to, so the picture assembles as it
	 * is laid out block by block.
	 *
	 * <p>The patch is every same-rug block reachable from {@code pos} through its four sides. Its
	 * north-west corner is the origin, and each block takes the slice its offset from that corner
	 * asks for. A block further out than the rug is wide or deep belongs to a second copy rather
	 * than this one, so its offset wraps: two rugs laid side by side both read correctly.
	 */
	private void reflow(Level level, BlockPos pos) {
		Set<BlockPos> patch = new HashSet<>();
		Deque<BlockPos> queue = new ArrayDeque<>(List.of(pos.immutable()));
		// a hard cap: a floor tiled with rug blocks must not walk the whole floor on every placement
		while (!queue.isEmpty() && patch.size() < 512) {
			BlockPos at = queue.poll();
			if (!patch.add(at)) {
				continue;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos next = at.relative(d);
				if (!patch.contains(next) && level.getBlockState(next).is(this)) {
					queue.add(next.immutable());
				}
			}
		}
		int minX = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		for (BlockPos at : patch) {
			minX = Math.min(minX, at.getX());
			minZ = Math.min(minZ, at.getZ());
		}
		for (BlockPos at : patch) {
			int tile = Math.floorMod(at.getZ() - minZ, depth) * width + Math.floorMod(at.getX() - minX, width);
			BlockState want = level.getBlockState(at).setValue(TILE, tile);
			if (!want.equals(level.getBlockState(at))) {
				// flag 2: tell the client, do not re-run neighbour updates and restart this walk
				level.setBlock(at, want, 2);
			}
		}
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
		super.onPlace(state, level, pos, old, moving);
		if (!level.isClientSide() && !old.is(this)) {
			reflow(level, pos);
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, boolean moving) {
		super.affectNeighborsAfterRemoval(state, level, pos, moving);
		// what is left of the patch is its own rug now, and its corner may have been the one broken
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos next = pos.relative(d);
			if (level.getBlockState(next).is(this)) {
				reflow(level, next);
				return;
			}
		}
	}

	/** Lays the whole rug with its north-west corner at {@code origin}. */
	public void lay(Level level, BlockPos origin) {
		for (int dz = 0; dz < depth; dz++) {
			for (int dx = 0; dx < width; dx++) {
				level.setBlockAndUpdate(origin.offset(dx, 0, dz), defaultBlockState().setValue(TILE, dz * width + dx));
			}
		}
	}
}
