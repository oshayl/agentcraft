package dev.agentcraft.hq;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A Litematica schematic ({@code .litematic}) read into block states, so a build someone made in
 * their own world can become an HQ (see {@link SchematicHqBuilder}).
 *
 * <p>The format is gzipped NBT: {@code Regions.<name>} holds a {@code BlockStatePalette} (block
 * name + properties, both as strings, so no numeric ids to translate), a {@code BlockStates} long
 * array and the region's size. The indices are packed with as many bits as the palette needs and
 * <em>straddle</em> the longs - unlike vanilla's chunk format, an entry can start in one long and
 * end in the next - which is the one thing worth getting right here.
 *
 * <p>Only block states are read. Block entities (what is in a chest, what a sign says) and
 * entities (item frames, armour stands) are listed but not applied: entities would be duplicated
 * by every rebuild, and an HQ build has to be idempotent.
 *
 * <p>A block the running game does not know is dropped and counted in {@link #unknown}, rather
 * than failing the whole import: schematics are usually made in a different version.
 */
public final class Litematic {
	/** Refuse anything bigger than this; a schematic is a building, not a world download. */
	private static final long MAX_CELLS = 8_000_000L;
	private static final long MAX_NBT_BYTES = 256L * 1024 * 1024;

	public final String name;
	public final String author;
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	/** Block states indexed {@code (y * sizeZ + z) * sizeX + x}; air where nothing was set. */
	private final BlockState[] cells;
	/** Block names the game does not have, with how many cells wanted them. */
	public final List<String> unknown;
	public final int blockEntities;
	public final int entities;

	private Litematic(String name, String author, int sizeX, int sizeY, int sizeZ, BlockState[] cells, List<String> unknown, int blockEntities,
		int entities) {
		this.name = name;
		this.author = author;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cells = cells;
		this.unknown = List.copyOf(unknown);
		this.blockEntities = blockEntities;
		this.entities = entities;
	}

	public BlockState at(int x, int y, int z) {
		if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
			return Blocks.AIR.defaultBlockState();
		}
		BlockState s = cells[(y * sizeZ + z) * sizeX + x];
		return s == null ? Blocks.AIR.defaultBlockState() : s;
	}

	/** The y with the most grass blocks: where this build's own ground line sits (-1 = none). */
	public int grassLine() {
		int best = -1;
		int bestN = 0;
		for (int y = 0; y < sizeY; y++) {
			int n = 0;
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					if (at(x, y, z).is(Blocks.GRASS_BLOCK)) {
						n++;
					}
				}
			}
			if (n > bestN) {
				bestN = n;
				best = y;
			}
		}
		return bestN >= sizeX * sizeZ / 20 ? best : -1;
	}

	/** The lowest y holding anything at all (0 when the schematic starts solid). */
	public int lowestSolid() {
		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					if (!at(x, y, z).isAir()) {
						return y;
					}
				}
			}
		}
		return 0;
	}

	public static Litematic read(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException("no such schematic: " + file);
		}
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES));
		CompoundTag meta = compound(root, "Metadata");
		String name = meta.getStringOr("Name", file.getFileName().toString());
		String author = meta.getStringOr("Author", "unknown");
		CompoundTag regions = compound(root, "Regions");
		if (regions.isEmpty()) {
			throw new IOException("no regions in " + file.getFileName());
		}

		// The box that holds every region, so several regions land in one grid.
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (String key : regions.keySet()) {
			CompoundTag r = compound(regions, key);
			int[] pos = xyz(compound(r, "Position"));
			int[] size = xyz(compound(r, "Size"));
			for (int i = 0; i < 3; i++) {
				// a negative size means the region runs the other way from its position
				int a = pos[i];
				int b = pos[i] + size[i] + (size[i] < 0 ? 1 : -1);
				int lo = Math.min(a, b);
				int hi = Math.max(a, b);
				if (i == 0) {
					minX = Math.min(minX, lo);
					maxX = Math.max(maxX, hi);
				} else if (i == 1) {
					minY = Math.min(minY, lo);
					maxY = Math.max(maxY, hi);
				} else {
					minZ = Math.min(minZ, lo);
					maxZ = Math.max(maxZ, hi);
				}
			}
		}
		int sx = maxX - minX + 1;
		int sy = maxY - minY + 1;
		int sz = maxZ - minZ + 1;
		long cells = (long) sx * sy * sz;
		if (sx <= 0 || sy <= 0 || sz <= 0 || cells > MAX_CELLS) {
			throw new IOException("schematic is " + sx + "x" + sy + "x" + sz + ", which is too big to build");
		}

		BlockState[] grid = new BlockState[(int) cells];
		List<String> unknown = new ArrayList<>();
		int bes = 0;
		int ents = 0;
		for (String key : regions.keySet()) {
			CompoundTag r = compound(regions, key);
			int[] pos = xyz(compound(r, "Position"));
			int[] size = xyz(compound(r, "Size"));
			int rx = Math.abs(size[0]);
			int ry = Math.abs(size[1]);
			int rz = Math.abs(size[2]);
			int ox = Math.min(pos[0], pos[0] + size[0] + (size[0] < 0 ? 1 : -1)) - minX;
			int oy = Math.min(pos[1], pos[1] + size[1] + (size[1] < 0 ? 1 : -1)) - minY;
			int oz = Math.min(pos[2], pos[2] + size[2] + (size[2] < 0 ? 1 : -1)) - minZ;

			List<BlockState> palette = palette(r, unknown);
			long[] packed = r.getLongArray("BlockStates").orElse(new long[0]);
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
			long mask = (1L << bits) - 1L;
			long volume = (long) rx * ry * rz;
			for (int i = 0; i < volume; i++) {
				long start = (long) i * bits;
				int word = (int) (start >> 6);
				int off = (int) (start & 63);
				if (word >= packed.length) {
					break;
				}
				long value = packed[word] >>> off;
				// the entry straddles two longs when it does not fit in what is left of this one
				if (off + bits > 64 && word + 1 < packed.length) {
					value |= packed[word + 1] << (64 - off);
				}
				int pi = (int) (value & mask);
				if (pi <= 0 || pi >= palette.size()) {
					continue; // 0 is air in every litematic palette
				}
				BlockState state = palette.get(pi);
				if (state == null) {
					continue;
				}
				int x = i % rx;
				int y = i / (rx * rz);
				int z = (i / rx) % rz;
				grid[((y + oy) * sz + (z + oz)) * sx + (x + ox)] = state;
			}
			bes += list(r, "TileEntities").size();
			ents += list(r, "Entities").size();
		}
		return new Litematic(name, author, sx, sy, sz, grid, unknown, bes, ents);
	}

	/**
	 * The palette, one {@code {Name, Properties}} compound per entry.
	 *
	 * <p>Those are Litematica's key names, and they were vanilla's too until Minecraft renamed its
	 * own block-state keys to {@code id} and {@code properties}. So the keys are renamed here and
	 * the game's own reader does the rest, properties included - rather than this file growing a
	 * second, worse copy of property parsing. (Reading a schematic with the keys as they come back
	 * silently resolves every block to air, which is how this was found: a house of 57,000 blocks
	 * arrived with 3,500 of them and "minecraft:dirt" reported as a block the game does not have.)
	 */
	private static List<BlockState> palette(CompoundTag region, List<String> unknown) {
		List<BlockState> out = new ArrayList<>();
		for (Tag t : list(region, "BlockStatePalette")) {
			if (!(t instanceof CompoundTag c)) {
				out.add(null);
				continue;
			}
			String id = c.getStringOr("Name", "minecraft:air");
			CompoundTag vanilla = new CompoundTag();
			vanilla.putString("id", id);
			CompoundTag props = c.getCompoundOrEmpty("Properties");
			if (!props.isEmpty()) {
				vanilla.put("properties", props);
			}
			BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, vanilla);
			if (state.isAir() && !id.endsWith(":air") && !id.equals("air")) {
				if (!unknown.contains(id)) {
					unknown.add(id);
				}
				out.add(null);
				continue;
			}
			out.add(state);
		}
		return out;
	}

	private static CompoundTag compound(CompoundTag parent, String key) {
		return parent.getCompoundOrEmpty(key);
	}

	private static ListTag list(CompoundTag parent, String key) {
		return parent.getListOrEmpty(key);
	}

	private static int[] xyz(CompoundTag t) {
		return new int[] {t.getIntOr("x", 0), t.getIntOr("y", 0), t.getIntOr("z", 0)};
	}
}
