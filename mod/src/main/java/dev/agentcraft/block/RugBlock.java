package dev.agentcraft.block;

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
	 * A tile on its own is a fragment of a picture, so a rug placed by hand shows its first
	 * slice; the command is what lays a whole one.
	 */
	@Override
	protected BlockState updateShape(BlockState state, net.minecraft.world.level.LevelReader level, net.minecraft.world.level.ScheduledTickAccess ticks,
		BlockPos pos, Direction direction, BlockPos neighbour, BlockState neighbourState, net.minecraft.util.RandomSource random) {
		return super.updateShape(state, level, ticks, pos, direction, neighbour, neighbourState, random);
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
