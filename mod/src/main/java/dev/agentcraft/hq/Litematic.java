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
	/**
	 * Blocks that do something to whoever touches them, and what they become instead.
	 *
	 * <p>Builders use end portals as a flat black surface, which is a lovely dark floor right up
	 * until someone walks across it and is standing in the End. The same goes for a nether portal
	 * as a purple feature wall. They are swapped for the nearest thing that just sits there: an
	 * imported build should never move anyone to another dimension.
	 */
	private static final java.util.Map<String, String> SUBSTITUTES = java.util.Map.of(
		"minecraft:end_portal", "minecraft:black_concrete",
		"minecraft:end_gateway", "minecraft:black_concrete",
		"minecraft:nether_portal", "minecraft:purple_stained_glass");

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
	/** Blocks swapped for something inert on the way in (see SUBSTITUTES). */
	public final List<String> replaced;
	public final int blockEntities;
	public final int entities;

	private Litematic(String name, String author, int sizeX, int sizeY, int sizeZ, BlockState[] cells, List<String> unknown, List<String> replaced,
		int blockEntities, int entities) {
		this.name = name;
		this.author = author;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cells = cells;
		this.unknown = List.copyOf(unknown);
		this.replaced = List.copyOf(replaced);
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

	/**
	 * The build's own footprint and roof above its ground line: the columns and the top the
	 * house occupies, ignoring the terrain it was captured with. {@code {x0, z0, x1, z1, topY}}
	 * in schematic coordinates, or null when there is nothing above ground.
	 */
	public int @org.jspecify.annotations.Nullable [] building(int groundY) {
		int x0 = Integer.MAX_VALUE;
		int z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int z1 = Integer.MIN_VALUE;
		int top = -1;
		for (int y = groundY + 1; y < sizeY; y++) {
			for (int x = 0; x < sizeX; x++) {
				for (int z = 0; z < sizeZ; z++) {
					BlockState s = at(x, y, z);
					// plants and loose ground cover are not the building
					if (s.isAir() || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.getBlock() instanceof net.minecraft.world.level.block.BushBlock) {
						continue;
					}
					x0 = Math.min(x0, x);
					z0 = Math.min(z0, z);
					x1 = Math.max(x1, x);
					z1 = Math.max(z1, z);
					top = Math.max(top, y);
				}
			}
		}
		return top < 0 ? null : new int[] {x0, z0, x1, z1, top};
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

	/**
	 * The storeys of the build: the heights you could walk around on, above its own ground line.
	 *
	 * <p>The stair needs these so it can arrive level at every one of them. It used to find them
	 * by looking at a single column beside the tower and asking whether you could stand there,
	 * which worked until the builder put a plant pot on that spot (the landing came out a block
	 * high) and a pane of glass on the one above it (that floor got no landing at all, and the
	 * top of the house was reachable only by jumping out of a window).
	 *
	 * <p>Counting the whole floor instead: a storey is a y where a lot of cells are standable and
	 * roofed, and which is a local peak - the floor above a staircase opening has a few standable
	 * cells too, and those are not storeys.
	 *
	 * @return schematic y values, lowest first
	 */
	public int[] floors(int groundY) {
		int[] count = new int[sizeY];
		int best = 0;
		for (int y = Math.max(1, groundY + 1); y < sizeY - 2; y++) {
			int n = 0;
			for (int x = 0; x < sizeX; x++) {
				for (int z = 0; z < sizeZ; z++) {
					if (at(x, y - 1, z).isAir() || !at(x, y, z).isAir() || !at(x, y + 1, z).isAir()) {
						continue;
					}
					boolean roofed = false;
					for (int h = 2; h <= 8 && !roofed; h++) {
						roofed = !at(x, y + h, z).isAir();
					}
					if (roofed) {
						n++;
					}
				}
			}
			count[y] = n;
			best = Math.max(best, n);
		}
		// a quarter of the biggest floor: enough to be a storey, not a mezzanine or a stair head
		int floor = Math.max(8, best / 4);
		java.util.List<Integer> out = new java.util.ArrayList<>();
		for (int y = 1; y < sizeY - 1; y++) {
			if (count[y] < floor || count[y] < count[y - 1] || count[y] < count[y + 1]) {
				continue;
			}
			// one storey per flight: two peaks a block apart are one floor seen twice
			if (!out.isEmpty() && y - out.get(out.size() - 1) < 3) {
				if (count[y] > count[out.get(out.size() - 1)]) {
					out.set(out.size() - 1, y);
				}
				continue;
			}
			out.add(y);
		}
		int[] ys = new int[out.size()];
		for (int i = 0; i < ys.length; i++) {
			ys[i] = out.get(i);
		}
		return ys;
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
		List<String> replaced = new ArrayList<>();
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

			List<BlockState> palette = palette(r, unknown, replaced);
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
		return new Litematic(name, author, sx, sy, sz, grid, unknown, replaced, bes, ents);
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
	private static List<BlockState> palette(CompoundTag region, List<String> unknown, List<String> replaced) {
		List<BlockState> out = new ArrayList<>();
		for (Tag t : list(region, "BlockStatePalette")) {
			if (!(t instanceof CompoundTag c)) {
				out.add(null);
				continue;
			}
			String id = c.getStringOr("Name", "minecraft:air");
			String safe = SUBSTITUTES.get(id);
			CompoundTag vanilla = new CompoundTag();
			vanilla.putString("id", safe != null ? safe : id);
			if (safe != null) {
				// a substituted block keeps none of the original's properties
				out.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, vanilla));
				if (!replaced.contains(id)) {
					replaced.add(id);
				}
				continue;
			}
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
