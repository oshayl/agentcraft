package dev.agentcraft.hq;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqWorld;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * HQ feature (common side): {@code /agentcraft hq [builder] [force]} builds the HQ and publishes its
 * anchors. The studio builder keeps cells the player changed since its last build; {@code force}
 * resets them too.
 */
public final class HqFeature {
	private HqFeature() {
	}

	/** The report of the last build (for QA: {@code dev.state.hq.lastBuild}). */
	private static volatile @Nullable String lastReport;

	public static @Nullable String lastReport() {
		return lastReport;
	}

	public static void init() {
		HqBuilders.register(new TestRoomBuilder());
		HqBuilders.register(new StudioHqBuilder());
		// one builder per .litematic in ~/.agentcraft/schematics: drop a build in, /agentcraft hq <name>
		SchematicHqBuilder.registerAll();
		HqBuilders.setDefault(StudioHqBuilder.ID);
		// A fresh HQ world (no saved layout yet) gets the default HQ built before the player joins, so
		// the first launch walks straight into it. AGENTCRAFT_HQ_AUTOBUILD=0 turns this off.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (!HqWorld.isHq(server) || !Anchors.current().isEmpty() || !autoBuild()) {
				return;
			}
			HqBuilder builder = HqBuilders.get(HqBuilders.defaultId());
			if (builder != null) {
				try {
					buildAndPublish(server.overworld(), builder, HqBuilder.Options.DEFAULT);
					AgentCraft.LOGGER.info("Fresh HQ world: built the default HQ '{}'", builder.id());
				} catch (RuntimeException e) {
					AgentCraft.LOGGER.error("Auto-building the HQ failed (run /agentcraft hq)", e);
				}
			}
		});
		registerHere();
		AgentCraftCommands.sub(root -> root.then(Commands.literal("hq")
			.executes(ctx -> build(ctx, HqBuilders.defaultId(), false))
			.then(Commands.literal("force").executes(ctx -> build(ctx, HqBuilders.defaultId(), true)))
			.then(Commands.argument("builder", StringArgumentType.word())
				.suggests((ctx, b) -> {
					HqBuilders.ids().forEach(b::suggest);
					return b.buildFuture();
				})
				.executes(ctx -> build(ctx, StringArgumentType.getString(ctx, "builder"), false))
				.then(Commands.literal("force").executes(ctx -> build(ctx, StringArgumentType.getString(ctx, "builder"), true))))));
	}

	/**
	 * {@code /agentcraft here <station> [binding]}: puts a station where the player stands, facing
	 * the way they came from, and remembers it for this HQ ({@link StationPlacements}). This is how
	 * a schematic HQ gets its terminal and shelves without anyone writing coordinates in code.
	 */
	private static void registerHere() {
		AgentCraftCommands.sub(root -> root.then(Commands.literal("here")
			.then(Commands.literal("clear").executes(ctx -> {
				StationPlacements.remove(ctx.getSource().getServer(), HqBuilders.defaultId(), null);
				ctx.getSource().sendSuccess(() -> Component.literal("Cleared every placed station; rebuild with /agentcraft hq"), true);
				return 1;
			}))
			.then(Commands.argument("station", StringArgumentType.word())
				.suggests((ctx, b) -> {
					StationPlacements.names().forEach(b::suggest);
					for (String a : dev.agentcraft.Cast.ids()) {
						b.suggest(dev.agentcraft.layout.AnchorNames.DESK_PREFIX + a);
						b.suggest(dev.agentcraft.layout.AnchorNames.MONITOR_PREFIX + a);
					}
					return b.buildFuture();
				})
				.executes(ctx -> here(ctx, ""))
				.then(Commands.argument("binding", StringArgumentType.word()).executes(ctx -> here(ctx, StringArgumentType.getString(ctx, "binding")))))));
	}

	private static int here(CommandContext<CommandSourceStack> ctx, String binding) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		var player = ctx.getSource().getPlayerOrException();
		String station = StringArgumentType.getString(ctx, "station");
		String builderId = HqBuilders.defaultId();
		// the block goes where the player stands, facing them: they are looking at it already
		Direction facing = player.getDirection().getOpposite();
		BlockPos pos = player.blockPosition();
		StationPlacements.Placement p = new StationPlacements.Placement(station, pos.getX(), pos.getY(), pos.getZ(), facing, binding);
		StationPlacements.put(ctx.getSource().getServer(), builderId, p);
		ctx.getSource().sendSuccess(() -> Component.literal("Placed " + station + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
			+ " facing " + facing.getSerializedName() + " for HQ '" + builderId + "'. Run /agentcraft hq " + builderId + " to build it."), true);
		return 1;
	}

	private static boolean autoBuild() {
		String v = System.getProperty("agentcraft.hq.autobuild");
		if (v == null) {
			v = System.getenv("AGENTCRAFT_HQ_AUTOBUILD");
		}
		return v == null || !(v.trim().equals("0") || v.trim().equalsIgnoreCase("false") || v.trim().equalsIgnoreCase("off"));
	}

	/**
	 * Building rewrites a large area of terrain and moves the world spawn, so it only runs in the
	 * AgentCraft HQ world unless AGENTCRAFT_HQ_ANYWORLD=1 (-Dagentcraft.hq.anyworld=1) opts in, and
	 * never in a Hardcore world.
	 */
	private static @Nullable String refusal(CommandSourceStack source) {
		var server = source.getServer();
		if (server.getWorldData().isHardcore()) {
			return "/agentcraft hq never builds in a Hardcore world (it rewrites terrain and moves the spawn)";
		}
		if (!HqWorld.isHq(server) && !anyWorld()) {
			return "/agentcraft hq only builds in the \"" + HqWorld.LEVEL_NAME + "\" world; to build into this world on purpose,"
				+ " back it up and start the game with -Dagentcraft.hq.anyworld=1";
		}
		return null;
	}

	private static boolean anyWorld() {
		String v = System.getProperty("agentcraft.hq.anyworld");
		if (v == null) {
			v = System.getenv("AGENTCRAFT_HQ_ANYWORLD");
		}
		return v != null && (v.trim().equals("1") || v.trim().equalsIgnoreCase("true") || v.trim().equalsIgnoreCase("on"));
	}

	private static int build(CommandContext<CommandSourceStack> ctx, String id, boolean force) {
		String refused = refusal(ctx.getSource());
		if (refused != null) {
			ctx.getSource().sendFailure(Component.literal(refused));
			return 0;
		}
		HqBuilder builder = HqBuilders.get(id);
		if (builder == null) {
			ctx.getSource().sendFailure(Component.literal("Unknown HQ builder '" + id + "' (known: " + HqBuilders.ids() + ")"));
			return 0;
		}
		Anchors.Layout layout;
		try {
			layout = buildAndPublish(ctx.getSource().getLevel(), builder, new HqBuilder.Options(force));
		} catch (RuntimeException e) {
			AgentCraft.LOGGER.error("HQ builder '{}' failed", id, e);
			ctx.getSource().sendFailure(Component.literal("HQ builder '" + id + "' failed: " + e));
			return 0;
		}
		String report = lastReport;
		ctx.getSource().sendSuccess(() -> Component.literal("Built HQ '" + id + "': " + layout.anchors().size() + " anchors"
			+ (report == null ? "" : ". " + report)), true);
		return layout.anchors().size();
	}

	/** Runs {@code builder} (server thread), publishes its layout and moves the world spawn to its spawn anchor. */
	public static Anchors.Layout buildAndPublish(ServerLevel level, HqBuilder builder) {
		return buildAndPublish(level, builder, HqBuilder.Options.DEFAULT);
	}

	public static Anchors.Layout buildAndPublish(ServerLevel level, HqBuilder builder, HqBuilder.Options options) {
		long t0 = System.nanoTime();
		// another builder rewrites the same ground without a record: the studio's memory of its last
		// build no longer describes the world
		PlanStore.invalidateUnless(level.getServer(), builder.id());
		Anchors.Builder anchors = Anchors.builder(builder.id());
		lastReport = builder.build(level, anchors, options);
		Anchors.Layout layout = anchors.build();
		Anchors.publish(level.getServer(), layout);
		Anchor spawn = layout.get(AnchorNames.SPAWN);
		if (spawn != null) {
			level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
				String.format(Locale.ROOT, "setworldspawn %d %d %d %.1f 0", (int) Math.floor(spawn.x()), (int) Math.floor(spawn.y()),
					(int) Math.floor(spawn.z()), spawn.yaw()));
		}
		AgentCraft.LOGGER.info("HQ '{}' built in {} ms{}", builder.id(), (System.nanoTime() - t0) / 1_000_000,
			lastReport == null ? "" : ": " + lastReport);
		return layout;
	}
}
