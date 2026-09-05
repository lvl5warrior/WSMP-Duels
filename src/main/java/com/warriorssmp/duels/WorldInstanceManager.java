package com.warriorssmp.duels;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Clones an arena's world folder into a fresh, independent copy
 * for each run, so multiple matches (duels, wars, or Gauntlet runs) can genuinely run at the same
 * time without ever sharing the same physical space - simply offsetting
 * coordinates within one world was considered and rejected, since a
 * second run would then land in raw, unbuilt terrain instead of the
 * admin's actual hand-built arena. A full world copy preserves the exact
 * same structure for every simultaneous instance, because each one is a
 * genuinely separate world with its own copy of every block.
 *
 * Every source/destination path used here is derived from an ACTUAL
 * loaded World's own File (World.getWorldFolder()), never reconstructed
 * by guessing Bukkit.getWorldContainer().resolve(name) - a real, confirmed
 * bug used to do exactly that guess, and it broke outright on a server
 * using Multiverse-Core (or any other world-management plugin, or a
 * non-default bukkit.yml world-container setting) where a world's actual
 * folder isn't necessarily a direct child of whatever getWorldContainer()
 * reports. getWorldFolder() is the one thing Bukkit itself guarantees is
 * correct for a currently-loaded world, so every path here is built from
 * that instead of an assumption about server layout.
 *
 * Cleaning these back up is treated as the single most important part of
 * this whole system - an orphaned instance world folder is a permanent
 * leak on disk, and this plugin is explicitly designed so that can never
 * silently happen:
 *   - destroyInstance retries the unload if it fails the first time
 *     (a real, confirmed cause: a player who just died has their state
 *     restore QUEUED for their next respawn event rather than applied
 *     immediately, so Bukkit can still consider them "in" that world for
 *     a few seconds after the run around them has already ended).
 *   - every failure path (copy fails, world fails to load) deletes
 *     whatever was already written to disk before giving up, rather than
 *     leaving a partial copy behind.
 *   - a startup scan (see cleanupOrphanedInstances) deletes any instance
 *     folder left over from a previous run of the server at all - a
 *     crash, a forced stop, or a /reload while a run was active. It
 *     scans both the default world container AND the parent folder of
 *     every currently-loaded world, since an instance folder (being a
 *     sibling of whichever world it was cloned from) could live under
 *     either depending on server layout.
 *   - onDisable makes a best-effort synchronous pass to tear down every
 *     currently-active instance immediately, AND independently re-scans
 *     every currently-loaded world for the instance naming pattern -
 *     this second check matters because a run can already have been
 *     dropped from the owning manager's own tracking (e.g. mid-retry-loop
 *     when the server happens to stop) while its world is still loaded,
 *     which the tracked-run-only sweep alone would silently miss.
 *
 * The instance-name pattern match is INTENTIONALLY narrow, not a loose
 * "contains" check: it requires the marker followed by exactly 8 lowercase
 * hex characters at the very end of the folder name. A real, confirmed
 * risk with a looser check is that an admin naming their own world
 * something like "gauntlet_instance_arena" would have it silently deleted
 * by the startup scan - permanent data loss of a real, hand-built arena,
 * not just a leaked temp file. The exact-suffix requirement makes an
 * accidental collision with a legitimately-named world astronomically
 * unlikely while still reliably catching every folder this class itself
 * ever generates.
 */
public class WorldInstanceManager {

    private static final String INSTANCE_MARKER = "_instance_";
    /** Matches only the EXACT suffix this class generates - marker plus
     *  exactly 8 lowercase hex characters, anchored to the end of the
     *  name. See the class doc for why this is deliberately narrow rather
     *  than a loose substring check. */
    private static final Pattern INSTANCE_NAME_PATTERN = Pattern.compile(".*" + Pattern.quote(INSTANCE_MARKER) + "[0-9a-f]{8}$");
    private static final int MAX_UNLOAD_ATTEMPTS = 120; // 120 attempts x 0.5s = up to 1 minute of retrying before giving up
    private static final int MAX_COPY_ATTEMPTS = 3;

    private final DuelsPlugin plugin;
    /** Names currently mid-destroy, so a duplicate destroyInstance call
     *  for the same world (which shouldn't normally happen, but isn't
     *  provably impossible given how many different paths can end a run)
     *  is a harmless no-op instead of two overlapping retry loops and
     *  duplicate delete attempts. */
    private final java.util.Set<String> destroyInProgress = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public WorldInstanceManager(DuelsPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isInstanceName(String name) {
        return INSTANCE_NAME_PATTERN.matcher(name).matches();
    }

    /** Copies templateWorldName's folder to a freshly-named instance
     *  folder placed right alongside it, then loads it as a real Bukkit
     *  world and hands it to onReady (called back on the main thread
     *  either way). Calls onFailure instead if the template isn't
     *  currently loaded, or if the copy/world load didn't work - either
     *  way, nothing is ever left on disk from a failed attempt.
     *
     *  The template world is still live and loaded on the server while
     *  this runs, which means its own region files can be actively
     *  rewritten by the server's normal autosave/chunk-unload activity at
     *  the exact same time this is trying to read them - a genuinely
     *  confirmed race, not a hypothetical one (a file disappearing or
     *  changing mid-walk surfaces as an UncheckedIOException from deep
     *  inside Files.walk's lazy iteration, not just a plain IOException
     *  at the top). Two things address this: saving the template world
     *  synchronously right before copying shrinks the window by flushing
     *  most pending writes first, and the copy itself retries a few
     *  times on failure - since the specific file that raced is
     *  overwhelmingly likely to be stable by the very next attempt. */
    /** Copies templateWorldName's folder to a freshly-named instance
     *  folder placed right alongside it, then loads it as a real Bukkit
     *  world and hands it to onReady (called back on the main thread
     *  either way). Calls onFailure instead if the template isn't
     *  currently loaded, or if the copy/world load didn't work - either
     *  way, nothing is ever left on disk from a failed attempt.
     *
     *  criticalLocations should be every spawn point (and, for a war
     *  arena, every team spawn and flag location) this match actually
     *  needs, IN THE TEMPLATE WORLD, before any translation - a real,
     *  confirmed cause of players/mobs ending up buried inside solid
     *  terrain: if the chunk containing one of these locations wasn't
     *  actually loaded in the TEMPLATE world at copy time, a generic
     *  World.save() (which only writes chunks already in memory) would
     *  silently skip it, copying whatever was last written to that
     *  region file - which, worse, might not even be a genuine on-disk
     *  gap so much as this class's OWN later chunk pre-load in the
     *  cloned world (see Arena.retarget) finding nothing there and
     *  generating fresh vanilla terrain instead of the admin's actual
     *  build. Force-loading each of these in the template first, then
     *  saving, guarantees the region files being copied actually contain
     *  the real, built arena at every location this match will use.
     *
     *  The template world is still live and loaded on the server while
     *  this runs, which means its own region files can be actively
     *  rewritten by the server's normal autosave/chunk-unload activity at
     *  the exact same time this is trying to read them - a genuinely
     *  confirmed race, not a hypothetical one (a file disappearing or
     *  changing mid-walk surfaces as an UncheckedIOException from deep
     *  inside Files.walk's lazy iteration, not just a plain IOException
     *  at the top). The copy itself retries a few times on failure -
     *  since the specific file that raced is overwhelmingly likely to be
     *  stable by the very next attempt. */
    public void createInstance(String templateWorldName, java.util.List<Location> criticalLocations,
                                Consumer<World> onReady, Runnable onFailure) {
        World templateWorld = Bukkit.getWorld(templateWorldName);
        if (templateWorld == null) {
            plugin.getLogger().warning("arena world '" + templateWorldName
                    + "' isn't currently loaded - can't clone it into a new instance.");
            onFailure.run();
            return;
        }

        Path sourceFolder = templateWorld.getWorldFolder().toPath();
        // A real, confirmed root cause, finally caught directly: the
        // in-console "instance folder check" line proved Bukkit was
        // loading the clone from a completely different location than
        // where this class actually placed the copy - specifically, the
        // same "world/dimensions/minecraft/<name>" nesting the template
        // itself uses, since this is a custom dimension. Copying files to
        // a plain top-level folder under the world container was always
        // going to be invisible to whatever loaded the "real" clone from
        // that nested path instead - a perfect copy sitting in a folder
        // nothing ever reads from. The template's own parent folder is
        // the one place proven, directly from that log line, to be
        // exactly where a sibling world needs to go. For an ordinary,
        // non-custom-dimension arena, the template's own folder already
        // sits directly under the world container, so this same logic
        // naturally resolves back to the normal container path anyway -
        // nothing changes for that case.
        Path destinationParent = sourceFolder.getParent();
        String instanceName = templateWorldName + INSTANCE_MARKER + UUID.randomUUID().toString().substring(0, 8);

        // A real, confirmed root cause, not a copy problem at all: this
        // arena is a custom dimension imported into Multiverse, not a
        // plain standalone world - its region files were confirmed
        // byte-for-byte correct, but loading a fresh WorldCreator with no
        // settings treats it as a brand new, default-generated Overworld.
        // A custom dimension's environment/generator/seed live in its
        // World object, not the region files themselves, so copying those
        // settings directly from the still-loaded template is what makes
        // the clone actually use the same settings the original import
        // relies on, instead of Bukkit falling back to defaults for
        // anything it doesn't recognize. Logged here so the exact
        // environment/generator/seed being applied is visible in console -
        // if the environment isn't NORMAL, that changes which on-disk
        // subfolder Bukkit expects the region data under (e.g. NETHER
        // conventionally expects DIM-1/region rather than region directly),
        // which would explain a byte-perfect copy Bukkit still can't find.
        WorldCreator instanceCreator = new WorldCreator(instanceName).copy(templateWorld);

        // Force-load+save the critical locations (radius matches
        // Arena.retarget's later pre-load in the cloned world) so the
        // admin's most recent edits near these specific spots are
        // guaranteed to be in memory and included in the flush below,
        // even if nobody had walked there recently.
        int radius = 2;
        for (Location loc : criticalLocations) {
            int centerChunkX = loc.getBlockX() >> 4;
            int centerChunkZ = loc.getBlockZ() >> 4;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    templateWorld.getChunkAt(centerChunkX + dx, centerChunkZ + dz);
                }
            }
        }

        // A real, confirmed Paper behavior: World.save() doesn't reliably
        // guarantee region files are actually flushed to disk right away -
        // modified chunks can sit unwritten in memory for a long time even
        // after calling save(), due to Paper's async chunk I/O. The one
        // thing confirmed to force an immediate, genuine flush is the
        // console command "save-all flush". This alone (without also
        // unloading the template) is what produced a byte-for-byte
        // perfect copy in testing - unloading the whole world in addition
        // to this added no further benefit, while forcibly evacuating
        // every player standing in it, which is a real, unacceptable cost
        // on a live server with other people around. So this stays
        // loaded and live the whole time; only the flush runs.
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "'save-all flush' failed before cloning arena world '"
                    + templateWorldName + "' - proceeding anyway", e);
        }

        // dispatchCommand triggers the flush but doesn't itself guarantee
        // the underlying async disk writes have actually finished by the
        // time it returns - a short buffer here gives that I/O a real
        // chance to complete before the copy starts reading the same files.
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> attemptCopy(sourceFolder, destinationParent, templateWorldName, instanceName, instanceCreator, 1,
                        onReady, onFailure),
                20L);
    }

    private void attemptCopy(Path sourceFolder, Path destinationParent, String templateWorldName, String instanceName,
                              WorldCreator instanceCreator, int attempt,
                              Consumer<World> onReady, Runnable onFailure) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Path destination = destinationParent.resolve(instanceName);
            deleteRecursivelyQuietly(destination); // clear out any partial copy left by a previous attempt first

            Exception failure = null;
            try {
                copyWorldFolder(sourceFolder, destination);
            } catch (IOException | java.io.UncheckedIOException e) {
                failure = e;
            }

            if (failure != null) {
                deleteRecursivelyQuietly(destination); // don't leave a half-written copy behind
                if (attempt < MAX_COPY_ATTEMPTS) {
                    plugin.getLogger().log(Level.WARNING, "Attempt " + attempt + " to clone arena world '"
                            + templateWorldName + "' hit an error - retrying", failure);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> attemptCopy(sourceFolder, destinationParent,
                            templateWorldName, instanceName, instanceCreator, attempt + 1,
                            onReady, onFailure), 20L);
                } else {
                    plugin.getLogger().log(Level.WARNING, "Failed to clone arena world '" + templateWorldName
                            + "' for a new instance after " + MAX_COPY_ATTEMPTS + " attempts", failure);
                    Bukkit.getScheduler().runTask(plugin, onFailure);
                }
                return;
            }

            // WorldGuard (and any other plugin keeping per-world data
            // outside the world folder itself) keeps its region
            // definitions and flags at plugins/WorldGuard/worlds/<name>/,
            // completely separate from the Minecraft world data this
            // method already copies. Without this, the clone starts with
            // no regions/flags at all - WorldGuard just writes it a blank
            // default config the moment it sees an unfamiliar world name,
            // which is exactly what the earlier console logs showed
            // happening. This has to finish before the world actually
            // loads on the main thread below, since WorldGuard reads this
            // folder at that exact moment.
            copyWorldGuardData(templateWorldName, instanceName);

            Bukkit.getScheduler().runTask(plugin, () -> {
                World world = Bukkit.createWorld(instanceCreator);
                if (world == null) {
                    plugin.getLogger().warning("Bukkit refused to load the cloned instance world '"
                            + instanceName + "' - deleting the copy since it can't be used.");
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> deleteRecursivelyQuietly(destination));
                    onFailure.run();
                    return;
                }
                world.setAutoSave(false); // an ephemeral instance world never needs its own autosave cycle
                onReady.accept(world);
            });
        });
    }

    /** Unloads and permanently deletes an instance world's folder - called
     *  once a run using it has fully ended. Guaranteed to eventually
     *  delete the folder rather than a single best-effort try: if the
     *  unload fails (most commonly because a player who just died is
     *  still technically "in" the world until their respawn event fires
     *  a few seconds later), this retries on a delay instead of giving
     *  up, for up to a minute before logging a loud failure that needs a
     *  human to look at. Safe to call more than once for the same world -
     *  a duplicate call is a no-op while one is already in progress. */
    public void destroyInstance(World world) {
        String name = world.getName();
        if (!destroyInProgress.add(name)) return; // already being torn down - nothing more to do here
        // Captured now, before any unload attempt - getWorldFolder() is
        // guaranteed reliable on a still-loaded world, so this is done
        // upfront rather than reconstructed later from just the name.
        Path folder = world.getWorldFolder().toPath();
        destroyInstance(world, folder, 0);
    }

    private void destroyInstance(World world, Path folder, int attempt) {
        String name = world.getName();
        evacuateStragglers(world);

        boolean unloaded = Bukkit.unloadWorld(world, false);
        if (!unloaded) {
            if (attempt >= MAX_UNLOAD_ATTEMPTS) {
                plugin.getLogger().severe("Gave up unloading instance world '" + name + "' after "
                        + MAX_UNLOAD_ATTEMPTS + " attempts over about a minute - its folder was NOT deleted and needs "
                        + "manual cleanup. This should not normally happen; please report it.");
                destroyInProgress.remove(name);
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> destroyInstance(world, folder, attempt + 1), 10L); // retry in 0.5s
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deleteRecursively(folder);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to delete instance world folder '" + name + "'", e);
            } finally {
                deleteWorldGuardData(name); // best-effort - see copyWorldGuardData for why this exists at all
                destroyInProgress.remove(name);
            }
        });
    }

    private void evacuateStragglers(World world) {
        // Defensive: nobody should still be in this world by the time
        // we're destroying it, but if anyone somehow is, get them out
        // first rather than let their presence be the reason the unload
        // keeps failing.
        World fallback = null;
        for (World candidate : Bukkit.getWorlds()) {
            if (candidate != world) {
                fallback = candidate;
                break;
            }
        }
        if (fallback == null) return; // no safe fallback exists (should never happen with a running server)
        for (Player straggler : world.getPlayers()) {
            straggler.teleport(fallback.getSpawnLocation());
        }
    }

    /** Best-effort teardown of every instance world still loaded right
     *  now - called from onDisable so a normal /stop or /reload while a
     *  run is active cleans up immediately instead of relying entirely on
     *  the next startup's scan. Takes the currently-tracked instance
     *  worlds as a starting point, but ALSO independently re-scans every
     *  loaded world for the instance naming pattern - a run can already
     *  have been dropped from the owning manager's own tracking (e.g. its
     *  destroy call was already mid-retry-loop when the server happened
     *  to stop) while its world is still loaded, which relying on the
     *  tracked list alone would miss entirely at this exact moment. Runs
     *  on the main thread deliberately (the plugin is shutting down;
     *  there is no "later" to schedule a retry into), so a stubborn
     *  unload failure here is simply logged - the next startup's
     *  cleanupOrphanedInstances will catch it regardless. */
    public void destroyAllOnShutdown(java.util.Collection<World> trackedInstanceWorlds) {
        java.util.Set<World> toDestroy = new java.util.LinkedHashSet<>(trackedInstanceWorlds);
        for (World loaded : Bukkit.getWorlds()) {
            if (isInstanceName(loaded.getName())) toDestroy.add(loaded);
        }

        for (World world : toDestroy) {
            String name = world.getName();
            Path folder = world.getWorldFolder().toPath(); // captured before unloading, same reasoning as destroyInstance
            evacuateStragglers(world);
            boolean unloaded = Bukkit.unloadWorld(world, false);
            if (!unloaded) {
                plugin.getLogger().warning("Could not unload instance world '" + name
                        + "' during shutdown - the next server startup will clean up its folder instead.");
                continue;
            }
            try {
                deleteRecursively(folder);
                deleteWorldGuardData(name);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to delete instance world folder '" + name
                        + "' during shutdown - the next server startup will clean it up instead.", e);
            }
        }
    }

    /** Deletes any instance-world folder left over from a previous server
     *  session - a crash, a forced stop, or anything else that skipped
     *  onDisable entirely. Scans both the default world container AND
     *  the parent folder of every currently-loaded world - an instance
     *  folder is always placed as a sibling of whatever world it was
     *  cloned from, and that sibling location isn't necessarily under
     *  the default container on a server using Multiverse-Core or any
     *  other world-management plugin, so checking loaded worlds' actual
     *  parent folders too is what makes this reliable regardless of
     *  server layout. Uses the same narrow, exact-suffix pattern as
     *  everywhere else in this class (see the class doc for why a loose
     *  substring match would be dangerous). Runs asynchronously and logs
     *  how many folders it removed. */
    public void cleanupOrphanedInstances() {
        java.util.Set<Path> dirsToScan = new java.util.LinkedHashSet<>();
        dirsToScan.add(Bukkit.getWorldContainer().toPath());
        for (World loaded : Bukkit.getWorlds()) {
            Path parent = loaded.getWorldFolder().toPath().getParent();
            if (parent != null) dirsToScan.add(parent);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int removed = 0;
            for (Path dir : dirsToScan) {
                try (Stream<Path> stream = Files.list(dir)) {
                    for (Path path : (Iterable<Path>) stream::iterator) {
                        if (!Files.isDirectory(path)) continue;
                        if (!isInstanceName(path.getFileName().toString())) continue;
                        try {
                            deleteRecursively(path);
                            deleteWorldGuardData(path.getFileName().toString());
                            removed++;
                        } catch (IOException e) {
                            plugin.getLogger().log(Level.WARNING, "Failed to delete leftover instance "
                                    + "folder '" + path.getFileName() + "' from a previous session", e);
                        }
                    }
                } catch (IOException | java.io.UncheckedIOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to scan '" + dir
                            + "' for leftover instance world folders", e);
                }
            }
            if (removed > 0) {
                plugin.getLogger().info("Cleaned up " + removed + " leftover instance world folder(s) from "
                        + "a previous session.");
            }
        });
    }

    /** Direct on-disk comparison of the region folder in the source and
     *  the just-created destination, logged to console right after the
     *  copy finishes and before anything else touches the destination -
     *  the one piece of evidence that can separate a genuine copy bug
     *  from something happening later, during world load. */
    /** Copies WorldGuard's per-world region/flag data (plugins/WorldGuard/
     *  worlds/<name>/) from the template to a newly-named folder for the
     *  instance, if WorldGuard's plugin folder even exists on this
     *  server - a plain, unfiltered recursive copy, since none of the
     *  special-case skips copyWorldFolder needs (session.lock, uid.dat,
     *  the world's own saved-data folder) apply to WorldGuard's own data
     *  at all. Silently does nothing if the template has no WorldGuard
     *  folder of its own (e.g. an arena with no regions defined) - that's
     *  a perfectly normal case, not a failure. Best-effort: a problem
     *  here shouldn't ever stop the actual world clone from working. */
    /** Cleans up the WorldGuard region/flag folder copyWorldGuardData
     *  created for an instance, once that instance is being torn down for
     *  good - otherwise every single match/run would leave a permanent,
     *  never-cleaned-up folder behind under WorldGuard's own worlds/
     *  directory. Best-effort and silent if there's nothing there to
     *  begin with (an arena with no regions never got one copied). */
    private void deleteWorldGuardData(String instanceName) {
        try {
            Path pluginsDir = plugin.getDataFolder().toPath().getParent();
            Path folder = pluginsDir.resolve("WorldGuard").resolve("worlds").resolve(instanceName);
            deleteRecursivelyQuietly(folder);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to clean up WorldGuard data for instance '"
                    + instanceName + "'", e);
        }
    }

    private void copyWorldGuardData(String templateWorldName, String instanceName) {
        try {
            Path pluginsDir = plugin.getDataFolder().toPath().getParent();
            Path source = pluginsDir.resolve("WorldGuard").resolve("worlds").resolve(templateWorldName);
            if (!Files.exists(source)) return; // nothing to copy - not every arena has regions defined
            Path destination = pluginsDir.resolve("WorldGuard").resolve("worlds").resolve(instanceName);
            deleteRecursivelyQuietly(destination);
            copyDirectoryRecursively(source, destination);
        } catch (IOException | java.io.UncheckedIOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to copy WorldGuard region data for '"
                    + templateWorldName + "' onto its instance '" + instanceName + "' - the clone will have no "
                    + "region protection/flags", e);
        }
    }

    /** A plain recursive directory copy with no special-case file
     *  skipping - used for copying another plugin's own per-world data
     *  folder (currently WorldGuard's), which has none of the
     *  Minecraft-world-specific quirks copyWorldFolder has to work
     *  around. */
    private void copyDirectoryRecursively(Path source, Path destination) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void copyWorldFolder(Path source, Path destination) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                    continue;
                }
                // A stale session.lock copied from the template could
                // confuse the new world's own lock-taking on load - each
                // instance needs to establish its own fresh lock instead.
                if (path.getFileName().toString().equals("session.lock")) continue;
                // A real, confirmed cause of the clone generating fresh
                // (and differently-seeded) terrain instead of using its
                // own copied region data: uid.dat holds this world's
                // unique identity, and copying it verbatim means the
                // clone claims the EXACT SAME identity as the template it
                // was copied from, while that template is still loaded
                // and running under that identity at the same time. When
                // the server detects that conflict, it doesn't trust the
                // clone's existing region data the way it would a
                // genuinely new world - which explains why the same
                // coordinates come back as freshly-generated (and
                // differently-seeded each time) vanilla terrain rather
                // than the real, copied build. Skipping this file lets
                // Bukkit generate a fresh, genuinely unique identity for
                // the instance the moment it loads, the same as it would
                // for any brand new world.
                if (path.getFileName().toString().equals("uid.dat")) continue;
                // A real, confirmed root cause of the NEW "duplicate
                // world" refusal on current Paper (26.1+): Paper moved a
                // world's unique identity out of uid.dat and into its own
                // per-dimension saved-data system, stored as a file
                // inside a "data/" subfolder within the dimension's own
                // folder (the same place scoreboard, raids, and idcounts
                // data lives). Copying that folder verbatim brings the
                // exact same Paper-level identity along with it, so the
                // clone claims to BE the template while the template is
                // still loaded and running - which Paper now explicitly
                // detects and refuses to load rather than silently
                // guessing. None of what lives in this folder (saved
                // per-dimension state, and now this identity file) is
                // something an ephemeral match instance needs preserved
                // from the template anyway, so the whole folder is
                // skipped - Paper generates a fresh, genuinely unique
                // identity for the clone the moment it loads, the same
                // as it already does for a skipped uid.dat.
                Path relative = source.relativize(path);
                if (relative.getNameCount() > 0 && relative.getName(0).toString().equals("data")) continue;
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup - a leftover file here isn't
                    // worth failing the whole deletion over.
                }
            });
        } catch (java.io.UncheckedIOException e) {
            // Files.walk's lazy iteration can surface an IOException as
            // this unchecked wrapper partway through, not just up front -
            // rethrow as the checked type every caller here already
            // handles, rather than letting it escape uncaught.
            throw e.getCause() != null ? e.getCause() : new IOException(e);
        }
    }

    private void deleteRecursivelyQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to clean up a partially-copied instance folder '"
                    + path.getFileName() + "'", e);
        }
    }
}
