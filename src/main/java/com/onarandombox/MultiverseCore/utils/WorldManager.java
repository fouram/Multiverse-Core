/******************************************************************************
 * Multiverse 2 Copyright (c) the Multiverse Team 2011.                       *
 * Multiverse 2 is licensed under the BSD License.                            *
 * For more information please check the README.md file included              *
 * with this project.                                                         *
 ******************************************************************************/

package com.onarandombox.MultiverseCore.utils;

import com.onarandombox.MultiverseCore.MVWorld;
import com.onarandombox.MultiverseCore.MultiverseCore;
import com.onarandombox.MultiverseCore.api.MVWorldManager;
import com.onarandombox.MultiverseCore.api.MultiverseWorld;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Public facing API to add/remove Multiverse worlds.
 *
 * @author fernferret, Rigby90
 */
public class WorldManager implements MVWorldManager {
    private MultiverseCore plugin;
    private PurgeWorlds worldPurger;
    private Map<String, MultiverseWorld> worlds;
    private List<String> unloadedWorlds;
    private FileConfiguration configWorlds = null;
    private Map<String, String> defaultGens;

    public WorldManager(MultiverseCore core) {
        this.plugin = core;
        this.worlds = new HashMap<String, MultiverseWorld>();
        this.unloadedWorlds = new ArrayList<String>();
        this.worldPurger = new PurgeWorlds(this.plugin);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void getDefaultWorldGenerators() {
        this.defaultGens = new HashMap<String, String>();
        File[] files = this.plugin.getServerFolder().listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File file, String s) {
                return s.equalsIgnoreCase("bukkit.yml");
            }
        });
        if (files.length == 1) {
            FileConfiguration bukkitConfig = YamlConfiguration.loadConfiguration(files[0]);
            if (bukkitConfig.isConfigurationSection("worlds")) {
                Set<String> keys = bukkitConfig.getConfigurationSection("worlds").getKeys(false);
                for (String key : keys) {
                    defaultGens.put(key, bukkitConfig.getString("worlds." + key + ".generator", ""));
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addWorld(String name, Environment env, String seedString, String generator) {
        plugin.log(Level.FINE, "Adding world with: " + name + ", " + env.toString() + ", " + seedString + ", " + generator);
        Long seed = null;
        WorldCreator c = new WorldCreator(name);
        if (seedString != null && seedString.length() > 0) {
            try {
                seed = Long.parseLong(seedString);
            } catch (NumberFormatException numberformatexception) {
                seed = (long) seedString.hashCode();
            }
            c.seed(seed);
        }


        // TODO: Use the fancy kind with the commandSender
        if (generator != null && generator.length() != 0) {
            c.generator(generator);
        }
        c.environment(env);

        World world = null;
        if (seed != null) {
            if (generator != null) {
                this.plugin.log(Level.INFO, "Loading World & Settings - '" + name + "' - " + env + " with seed: " + seed + " & Custom Generator: " + generator);
            } else {
                this.plugin.log(Level.INFO, "Loading World & Settings - '" + name + "' - " + env + " with seed: " + seed);
            }
        } else {
            if (generator != null) {
                this.plugin.log(Level.INFO, "Loading World & Settings - '" + name + "' - " + env + " & Custom Generator: " + generator);
            } else {
                this.plugin.log(Level.INFO, "Loading World & Settings - '" + name + "' - " + env);
            }
        }
        try {
            world = c.createWorld();
        } catch (Exception e) {
            this.plugin.log(Level.SEVERE, "The world '" + name + "' could NOT be loaded because it contains errors!");
            this.plugin.log(Level.SEVERE, "Try using Chukster to repair your world! '" + name + "'");
            this.plugin.log(Level.SEVERE, "http://forums.bukkit.org/threads/admin-chunkster.8186/");
            return false;
        }

        if (world == null) {
            this.plugin.log(Level.SEVERE, "Failed to Create/Load the world '" + name + "'");
            return false;
        }

        MultiverseWorld mvworld = new MVWorld(world, this.configWorlds, this.plugin, this.plugin.getServer().getWorld(name).getSeed(), generator);
        this.worldPurger.purgeWorld(null, mvworld);
        this.worlds.put(name, mvworld);
        if (this.unloadedWorlds.contains(name)) {
            this.unloadedWorlds.remove(name);
        }
        return true;
    }

    /**
     * Verifies that a given Plugin generator string exists.
     *
     * @param generator The name of the generator plugin. This should be something like CleanRoomGenerator.
     * @return True if the plugin exists and is enabled, false if not.
     */
    private boolean pluginExists(String generator) {
        Plugin plugin = this.plugin.getServer().getPluginManager().getPlugin(generator);
        return plugin != null && plugin.isEnabled();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChunkGenerator getChunkGenerator(String generator, String generatorID, String worldName) {
        if (generator == null) {
            return null;
        }

        Plugin plugin = this.plugin.getServer().getPluginManager().getPlugin(generator);
        if (plugin == null) {
            return null;
        } else {
            return plugin.getDefaultWorldGenerator(worldName, generatorID);

        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeWorldFromConfig(String name) {
        if (!unloadWorld(name)) {
            return false;
        }
        if (this.configWorlds.get("worlds." + name) != null) {
            this.plugin.log(Level.INFO, "World '" + name + "' was removed from config.yml");
            this.configWorlds.set("worlds." + name, null);

            this.saveWorldsConfig();
            // Remove it from the list of unloaded worlds.
            if (this.unloadedWorlds.contains(name)) {
                this.unloadedWorlds.remove(name);
            }
            return true;
        } else {
            this.plugin.log(Level.INFO, "World '" + name + "' was already removed from config.yml");
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean unloadWorld(String name) {
        if (this.worlds.containsKey(name)) {
            if (this.unloadWorldFromBukkit(name, true)) {
                this.worlds.remove(name);
                this.plugin.log(Level.INFO, "World '" + name + "' was unloaded from memory.");
                this.unloadedWorlds.add(name);
                return true;
            } else {
                this.plugin.log(Level.WARNING, "World '" + name + "' could not be unloaded. Is it a default world?");
            }
        } else if (this.plugin.getServer().getWorld(name) != null) {
            this.plugin.log(Level.WARNING, "Hmm Multiverse does not know about this world but it's loaded in memory.");
            this.plugin.log(Level.WARNING, "To unload it using multiverse, use:");
            this.plugin.log(Level.WARNING, "/mv import " + name + " " + this.plugin.getServer().getWorld(name).getEnvironment().toString());
        } else {
            this.plugin.log(Level.INFO, "Multiverse does not know about " + name + " and it's not loaded by Bukkit.");
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean loadWorld(String name) {
        // Check if the World is already loaded
        if (this.worlds.containsKey(name)) {
            // Ensure it's not unloaded, since it IS loaded.
            if (this.unloadedWorlds.contains(name)) {
                this.unloadedWorlds.remove(name);
            }
            return true;
        }

        // Grab all the Worlds from the Config.
        Set<String> worldKeys = this.configWorlds.getConfigurationSection("worlds").getKeys(false);

        // Check that the list is not null and that the config contains the world
        if ((worldKeys != null) && (worldKeys.contains(name))) {
            // Grab the initial values from the config file.
            String environment = this.configWorlds.getString("worlds." + name + ".environment", "NORMAL"); // Grab the Environment as a String.
            String seedString = this.configWorlds.getString("worlds." + name + ".seed", "");
            String generatorString = this.configWorlds.getString("worlds." + name + ".generator");

            addWorld(name, this.plugin.getEnvFromString(environment), seedString, generatorString);
            if (this.unloadedWorlds.contains(name)) {
                this.unloadedWorlds.remove(name);
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Boolean deleteWorld(String name, boolean removeFromConfig) {
        World world = this.plugin.getServer().getWorld(name);
        if (world == null) {
            // We can only delete loaded worlds
            return false;
        }
        if (removeFromConfig) {
            if (!removeWorldFromConfig(name)) {
                return false;
            }
        } else {
            if (!this.unloadWorld(name)) {
                return false;
            }
        }

        try {
            File worldFile = world.getWorldFolder();
            plugin.log(Level.FINER, "deleteWorld(): worldFile: " + worldFile.getAbsolutePath());
            boolean deletedWorld = FileUtils.deleteFolder(worldFile);
            if (deletedWorld) {
                this.plugin.log(Level.INFO, "World " + name + " was DELETED.");
            } else {
                this.plugin.log(Level.SEVERE, "World " + name + " was NOT deleted.");
                this.plugin.log(Level.SEVERE, "Are you sure the folder " + name + " exists?");
                this.plugin.log(Level.SEVERE, "Please check your file permissions on " + name);
            }
            return deletedWorld;
        } catch (Throwable e) {
            this.plugin.log(Level.SEVERE, "Hrm, something didn't go as planned. Here's an exception for ya.");
            this.plugin.log(Level.SEVERE, "You can go politely explain your situation in #multiverse on esper.net");
            this.plugin.log(Level.SEVERE, "But from here, it looks like your folder is oddly named.");
            this.plugin.log(Level.SEVERE, "This world has been removed from Multiverse-Core so your best bet is to go delete the folder by hand. Sorry.");
            this.plugin.log(Level.SEVERE, e.getMessage());
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Boolean deleteWorld(String name) {
        return this.deleteWorld(name, true);
    }

    /**
     * Unload a world from Bukkit
     *
     * @param name   Name of the world to unload
     * @param safely Perform this safely. Set to True to save world files before unloading.
     * @return True if the world was unloaded, false if not.
     */
    private boolean unloadWorldFromBukkit(String name, boolean safely) {
        this.removePlayersFromWorld(name);
        return this.plugin.getServer().unloadWorld(name, safely);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removePlayersFromWorld(String name) {
        World w = this.plugin.getServer().getWorld(name);
        if (w != null) {
            World safeWorld = this.plugin.getServer().getWorlds().get(0);
            List<Player> ps = w.getPlayers();
            SafeTTeleporter teleporter = this.plugin.getTeleporter();
            for (Player p : ps) {
                // We're removing players forcefully from a world, they'd BETTER spawn safely.
                teleporter.safelyTeleport(null, p, safeWorld.getSpawnLocation(), true);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<MultiverseWorld> getMVWorlds() {
        return this.worlds.values();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MultiverseWorld getMVWorld(String name) {
        if (this.worlds.containsKey(name)) {
            return this.worlds.get(name);
        }
        return this.getMVWorldByAlias(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MultiverseWorld getMVWorld(World world) {
        if (world != null) {
            return this.getMVWorld(world.getName());
        }
        return null;
    }

    /**
     * Returns a {@link MVWorld} if it exists, and null if it does not. This will search ONLY alias.
     *
     * @param alias The alias of the world to get.
     * @return A {@link MVWorld} or null.
     */
    private MultiverseWorld getMVWorldByAlias(String alias) {
        for (MultiverseWorld w : this.worlds.values()) {
            if (w.getAlias().equalsIgnoreCase(alias)) {
                return w;
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMVWorld(String name) {
        return (this.worlds.containsKey(name) || isMVWorldAlias(name));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isMVWorld(World world) {
        return world != null && this.isMVWorld(world.getName());
    }

    /**
     * This method ONLY checks the alias of each world.
     *
     * @param alias The alias of the world to check.
     * @return True if the world exists, false if not.
     */
    private boolean isMVWorldAlias(String alias) {
        for (MultiverseWorld w : this.worlds.values()) {
            if (w.getAlias().equalsIgnoreCase(alias)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void loadDefaultWorlds() {
        this.ensureConfigIsPrepared();
        List<World> worlds = this.plugin.getServer().getWorlds();
        Set<String> worldStrings = this.configWorlds.getConfigurationSection("worlds").getKeys(false);
        for (World w : worlds) {
            String name = w.getName();
            if (!worldStrings.contains(name)) {
                if (this.defaultGens.containsKey(name)) {
                    this.addWorld(name, w.getEnvironment(), w.getSeed() + "", this.defaultGens.get(name));
                } else {
                    this.addWorld(name, w.getEnvironment(), w.getSeed() + "", null);
                }

            }
        }
    }

    private void ensureConfigIsPrepared() {
        if (this.configWorlds.getConfigurationSection("worlds") == null) {
            this.configWorlds.createSection("worlds");
            try {
                this.configWorlds.save(new File(this.plugin.getDataFolder(), "worlds.yml"));
            } catch (IOException e) {
                this.plugin.log(Level.SEVERE, "Failed to save worlds.yml. Please check your file permissions.");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void loadWorlds(boolean forceLoad) {
        // Basic Counter to count how many Worlds we are loading.
        int count = 0;
        this.ensureConfigIsPrepared();
        // Grab all the Worlds from the Config.
        Set<String> worldKeys = this.configWorlds.getConfigurationSection("worlds").getKeys(false);

        // Force the worlds to be loaded, ie don't just load new worlds.
        if (forceLoad) {
            // Remove all world permissions.

            Permission allAccess = this.plugin.getServer().getPluginManager().getPermission("multiverse.access.*");
            Permission allExempt = this.plugin.getServer().getPluginManager().getPermission("multiverse.exempt.*");
            for (MultiverseWorld w : this.worlds.values()) {
                // Remove this world from the master list
                if (allAccess != null) {
                    allAccess.getChildren().remove(w.getAccessPermission().getName());
                }
                if (allExempt != null) {
                    allExempt.getChildren().remove(w.getAccessPermission().getName());
                }
                this.plugin.getServer().getPluginManager().removePermission(w.getAccessPermission().getName());
                this.plugin.getServer().getPluginManager().removePermission(w.getExemptPermission().getName());
            }
            // Recalc the all permission
            this.plugin.getServer().getPluginManager().recalculatePermissionDefaults(allAccess);
            this.plugin.getServer().getPluginManager().recalculatePermissionDefaults(allExempt);
            this.worlds.clear();
        }

        // Check that the list is not null.
        if (worldKeys != null) {
            for (String worldKey : worldKeys) {
                // Check if the World is already loaded within the Plugin.
                if (this.worlds.containsKey(worldKey)) {
                    continue;
                }

                // If autoload was set to false, don't load this one.
                if (!this.configWorlds.getBoolean("worlds." + worldKey + ".autoload", true)) {
                    if (!this.unloadedWorlds.contains(worldKey)) {
                        this.unloadedWorlds.add(worldKey);
                    }
                    continue;
                }
                // Grab the initial values from the config file.
                String environment = this.configWorlds.getString("worlds." + worldKey + ".environment", "NORMAL"); // Grab the Environment as a String.
                String seedString = this.configWorlds.getString("worlds." + worldKey + ".seed", null);
                if(seedString == null) {
                    seedString = this.configWorlds.getLong("worlds." + worldKey + ".seed") + "";
                }

                String generatorString = this.configWorlds.getString("worlds." + worldKey + ".generator");
                if (environment.equalsIgnoreCase("skylands")) {
                    this.plugin.log(Level.WARNING, "Found SKYLANDS world. Not importing automatically, as it won't work atm :(");
                    continue;
                }
                addWorld(worldKey, this.plugin.getEnvFromString(environment), seedString, generatorString);

                // Increment the world count
                count++;
            }
        }

        // Simple Output to the Console to show how many Worlds were loaded.
        this.plugin.log(Level.INFO, count + " - World(s) loaded.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PurgeWorlds getWorldPurger() {
        return this.worldPurger;
    }

    /**
     * Load the config from a file.
     *
     * @param file The file to load.
     * @return A loaded configuration.
     */
    @Override
    public FileConfiguration loadWorldConfig(File file) {
        this.configWorlds = YamlConfiguration.loadConfiguration(file);
        return this.configWorlds;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean saveWorldsConfig() {
        try {
            this.configWorlds.save(new File(this.plugin.getDataFolder(), "worlds.yml"));
            return true;
        } catch (IOException e) {
            this.plugin.log(Level.SEVERE, "Could not save worlds.yml. Please check your settings.");
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MultiverseWorld getSpawnWorld() {
        return this.getMVWorld(this.plugin.getServer().getWorlds().get(0));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getUnloadedWorlds() {
        return this.unloadedWorlds;
    }

    public FileConfiguration getConfigWorlds() {
        return this.configWorlds;
    }
}
