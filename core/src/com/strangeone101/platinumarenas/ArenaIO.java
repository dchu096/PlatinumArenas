package com.strangeone101.platinumarenas;

import com.google.common.collect.Maps;
import com.strangeone101.platinumarenas.blockentity.Wrapper;
import com.strangeone101.platinumarenas.blockentity.WrapperRegistry;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.DeflaterOutputStream;

public class ArenaIO {

    private static final byte SECTION_SPLIT = '\u0002';
    private static final byte KEY_SPLIT = '\u0003';

    public static final int FILE_VERSION = 6;
    public static final int MIN_SUPPORTED_MC_VERSION = 12100;

    /**
     * Saves an arena to disk.
     * @param file The File to save to
     * @param arena The arena to save
     * @param callback Any callback functions you want to run after this
     *                 completes (this doesn't run async so why did I do this?)
     * @return True if the arena saved.
     */
    public static boolean saveArena(File file, final Arena arena, Runnable... callback) {
        Location corner1 = arena.getCorner1();
        Location corner2 = arena.getCorner2();

        if (corner1.getWorld() != corner2.getWorld()) return false;

        try {
            FileOutputStream fileStream = new FileOutputStream(file);
            DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(
                    file.getName().toLowerCase(Locale.ROOT).endsWith(".datc")
                            ? new DeflaterOutputStream(fileStream)
                            : fileStream));
            Location l = arena.getCorner1();
            Location l2 = arena.getCorner2();

            //HEADER SECTION
            String header = FILE_VERSION + "," + arena.getName() + "," + l.getWorld().getName() + "," + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + "," +
                    l2.getBlockX() + "," + l2.getBlockY() + "," + l2.getBlockZ() + "," + arena.getCreator().toString() + "," + PlatinumArenas.INSTANCE.getMCVersion()
                    + "," + arena.getCreationTime();
            stream.write(header.getBytes(StandardCharsets.US_ASCII));
            stream.writeByte(SECTION_SPLIT);

            //BLOCKDATA KEY SECTION
            boolean firstKey = true;
            for (BlockData data : arena.getKeys()) {
                if (!firstKey) {
                    stream.writeByte(KEY_SPLIT); //Add splitting character
                }
                String s = data.getAsString(true); //Convert BlockData into string
                stream.write(s.getBytes(StandardCharsets.US_ASCII)); //Then to bytes in ascii
                firstKey = false;
            }
            stream.writeByte(SECTION_SPLIT);

            //BLOCK SECTION
            short sections = (short) arena.getSections().size(); //Amount of sections
            stream.writeShort(sections); //Means the max number of sections is 32k

            for (int s = 0; s < arena.getSections().size(); s++) {
                Section section = arena.getSections().get(s);

                byte[] nbtBytes = section.getNBTData();

                stream.writeInt(section.getStart().getBlockX());
                stream.writeInt(section.getStart().getBlockY());
                stream.writeInt(section.getStart().getBlockZ());
                stream.writeInt(section.getEnd().getBlockX());
                stream.writeInt(section.getEnd().getBlockY());
                stream.writeInt(section.getEnd().getBlockZ());

                stream.writeInt(section.getBlockTypes().length * 2); //Amount of block data to write

                stream.write(nbtBytes); //Write the NBT

                for (int i = 0; i < section.getBlockAmounts().length; i++) {
                    stream.writeShort(section.getBlockAmounts()[i]);
                    stream.writeShort(section.getBlockTypes()[i]);
                }
            }
            stream.close();

            for (Runnable r : callback) r.run();

        } catch (IOException e) {
            e.printStackTrace();
        }


        return true;
    }

    /**
     * Loads an Arena object from the provided file
     * @param file The arena file to load
     * @return New Arena object
     */
    public static Arena loadArena(File file) {
        try {
            byte[] readBytes = Files.readAllBytes(file.toPath());

            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".datc")) { //.datc is the compressed arena format
                readBytes = Util.decompress(readBytes);
            }

            //HEADER SECTION
            int firstSectionSplit = ArrayUtils.indexOf(readBytes, SECTION_SPLIT);
            byte[] header = Arrays.copyOfRange(readBytes, 0, firstSectionSplit);
            String headerString = new String(header, StandardCharsets.US_ASCII);
            String[] headerParts = headerString.split(",");

            String name = "**BrokenArena**";
            Location corner1 = null;
            Location corner2 = null;
            UUID owner = PlatinumArenas.DEFAULT_OWNER;
            long created = 0;
            int arenaMCVersion = 0;
            int currentMCVersion = PlatinumArenas.getIntVersion(PlatinumArenas.getMCVersion());

            int version = Integer.parseInt(headerParts[0]);

            if (version >= 4) {
                created = Long.parseLong(headerParts[11]);
            }
            if (version >= 3) {
                arenaMCVersion = PlatinumArenas.getIntVersion(headerParts[10]);

                if (arenaMCVersion > currentMCVersion) {
                    PlatinumArenas.INSTANCE.getLogger().warning("Arena \"" + name + "\" was made in a newer version of minecraft!");
                    PlatinumArenas.INSTANCE.getLogger().warning("PlatinumArenas will attempt to load it but may fail if it runs into unknown blockstates!");
                }
            }
            if (version >= 2) {
                owner = UUID.fromString(headerParts[9]);
            }
            if (version >= 1) {
                name = headerParts[1];
                String world = headerParts[2];
                int x1 = Integer.parseInt(headerParts[3]);
                int y1 = Integer.parseInt(headerParts[4]);
                int z1 = Integer.parseInt(headerParts[5]);
                int x2 = Integer.parseInt(headerParts[6]);
                int y2 = Integer.parseInt(headerParts[7]);
                int z2 = Integer.parseInt(headerParts[8]);

                World realWorld = Bukkit.getWorld(world);

                if (realWorld == null) {
                    PlatinumArenas.INSTANCE.getLogger().warning("Could not locate world \"" + world + "\" for arena \"" + name + "\"! Using default world");
                    realWorld = Bukkit.getWorlds().get(0);
                }

                corner1 = new Location(realWorld, x1, y1, z1);
                corner2 = new Location(realWorld, x2, y2, z2);
            }

            if (arenaMCVersion < MIN_SUPPORTED_MC_VERSION) {
                PlatinumArenas.INSTANCE.getLogger().warning("Arena \"" + name + "\" was made before Minecraft 1.21 and will not be loaded by this build.");
                return null;
            }

            if (Arena.arenas.containsKey(name)) {
                PlatinumArenas.INSTANCE.getLogger().warning("Tried to load arena \"" + name + "\" twice! Did you duplicate the file?");
                return null;
            }

            //KEY SECTION
            int keySectionSplit = ArrayUtils.indexOf(readBytes, SECTION_SPLIT, firstSectionSplit + 1);
            byte[] keyBytes = Arrays.copyOfRange(readBytes, firstSectionSplit + 1, keySectionSplit);
            //PlatinumArenas.INSTANCE.getLogger().info("Keybyte size = " + keyBytes.length);
            List<BlockData> blockDataSet = new ArrayList<>();

            for (byte[] key : Util.split(new byte[] {KEY_SPLIT}, keyBytes)) {
                String blockData = new String(key, StandardCharsets.US_ASCII);
                //PlatinumArenas.INSTANCE.getLogger().info("Loaded block key: " + blockData);

                try {
                    BlockData bukkitData = Bukkit.createBlockData(blockData);
                    blockDataSet.add(bukkitData);
                } catch (IllegalArgumentException e) {
                    PlatinumArenas.INSTANCE.getLogger().severe("Failed to parse block data '" + blockData + "' for arena '" + name + "'!");
                    try {
                        blockDataSet.add(Bukkit.createBlockData(blockData.split("\\[")[0])); //One without blockstates
                    } catch (IllegalArgumentException e2) {
                        if (ConfigManager.IGNORE_OUTDATED_MATERIALS) {
                            PlatinumArenas.INSTANCE.getLogger().severe("This block will be skipped. Arena will continue to load.");
                        } else {
                            PlatinumArenas.INSTANCE.getLogger().severe("Failed to even use the base material! Are you trying to load an arena from another minecraft version?");
                            PlatinumArenas.INSTANCE.getLogger().severe("Arena \"" + name + "\" will not be loaded.");
                            e.printStackTrace();
                        }

                        return null;
                    }
                }
            }

            Arena arena = new Arena(name, corner1, corner2);
            arena.setCreator(owner);
            arena.setCreationTime(created);
            if (version >= 3) {
                arena.setMcVersion(headerParts[10]);
            } else {
                arena.setMcVersion("Unknown");
            }
            arena.setFileVersion(version);

            arena.setKeys(blockDataSet);

            //BLOCK SECTION
            //int blockSectionSplit = ArrayUtils.indexOf(readBytes, SECTION_SPLIT, keySectionSplit + 1);
            byte[] blockBytes = Arrays.copyOfRange(readBytes, keySectionSplit + 1, readBytes.length);

            ByteBuffer bb = ByteBuffer.allocate(2);
            bb.put(blockBytes[0]);
            bb.put(blockBytes[1]);
            short sectionCount = bb.getShort(0);
            short currentSection = 0;


            blockBytes = Arrays.copyOfRange(blockBytes, 2, blockBytes.length); //Cut the 2 bytes off at the front
            //PlatinumArenas.INSTANCE.getLogger().info(blockBytes.length + " bytes in blockBytes");

            ByteBuffer buffer = ByteBuffer.wrap(blockBytes);

            while (currentSection < sectionCount) {
                int x1 = buffer.getInt();
                int y1 = buffer.getInt();
                int z1 = buffer.getInt();
                int x2 = buffer.getInt();
                int y2 = buffer.getInt();
                int z2 = buffer.getInt();
                Location start = new Location(corner1.getWorld(), x1, y1, z1);
                Location end = new Location(corner1.getWorld(), x2, y2, z2);
                int left = buffer.getInt();

                Map<Integer, Pair<Wrapper, Object>> NBT = Maps.newHashMapWithExpectedSize(0);

                if (version >= 4) { //Version 4 adds the basic NBT support
                    NBT = new HashMap<>();

                    byte differentTypes = buffer.get(); //Get the amount of wrapper types in this section
                    for (int i = 0; i < differentTypes; i++) {
                        int id = buffer.get();
                        int amount = buffer.getShort(); //Get amount of blocks with NBT data for this ID in this section

                        Wrapper wrapper = WrapperRegistry.getFromId(id); //Get wrapper from ID

                        for (int j = 0; j < amount; j++) {

                            byte amountOfIndexes = buffer.get(); //The amount of indexes
                            int[] indexes = new int[amountOfIndexes];

                            for (int k = 0; k < amountOfIndexes; k++) { //Get the list of indexes
                                indexes[k] = buffer.getInt();
                            }

                            int dataLength = buffer.getInt(); //Get the length of the data to cache
                            byte[] dataBytes = new byte[dataLength];
                            for (int k = 0; k < dataLength; k++) dataBytes[k] = buffer.get(); //Read it

                            Object cache = wrapper.read(dataBytes); //Convert bytes to object to store in memory

                            for (int index : indexes) {
                                NBT.put(index, new ImmutablePair<>(wrapper, cache));
                            }
                        }
                    }
                }

                int numLeft = left / 2;

                short[] amounts = new short[numLeft];
                short[] types = new short[numLeft];

                for (int i = 0; i < numLeft; i++) {
                    amounts[i] = buffer.getShort();
                    types[i] = buffer.getShort();

                    if (version <= 4 && amounts[i] < 0) { //Fix overflow bug on old formats
                        short next = (short) (amounts[i] - Short.MAX_VALUE); //Reverse overflow
                        amounts[i] = Short.MAX_VALUE; //Set this index to max
                        amounts = ArrayUtils.add(amounts, i + 1, next); //Insert the remainder into the amount in the next slot
                        types = ArrayUtils.add(types, i + 1, types[i]); //Insert the same type into the next type index too
                        numLeft++; //Since the array size increased
                        i++;       //Skip the part we already did ourselves
                    }
                }

                //Create the current section
                Section section = new Section(arena, currentSection, start, end, types, amounts, NBT);
                arena.getSections().add(section); //Add it to the arena automatically
                currentSection++;

                //The arena section split is no longer added to arenas on version 4 or later
                if (version < 4 && currentSection != sectionCount) {
                    buffer.getInt(); //Skip the arena section split
                }
            }

            return arena;

        } catch (Exception e) {
            PlatinumArenas.INSTANCE.getLogger().warning("Failed to load arena file \"" + file.getName() + "\"!");
            e.printStackTrace();
        }

        return null;  //unfinished
    }



    /**
     * Unloads the current arenas and loads them all from file again.
     * @return The list of arenas
     */
    public static Collection<Arena> loadAllArenas() {
        File folder = new File(PlatinumArenas.INSTANCE.getDataFolder(), "Arenas");
        if (!folder.exists()) {
            folder.mkdirs();
        }

        Arena.arenas.clear();
        long time = System.currentTimeMillis();

        File[] arenaFiles = folder.listFiles((f) -> f.getName().toLowerCase().endsWith(".datc") ||
                f.getName().toLowerCase().endsWith(".dat"));
        if (arenaFiles == null) arenaFiles = new File[0];

        for (File file : arenaFiles) {
            try {
                Arena arena = ArenaIO.loadArena(file);
                if (arena == null) continue;
                PlatinumArenas.INSTANCE.getLogger().info("Loaded arena \"" + arena.getName() + "\" from file " + file.getName());
                Arena.arenas.put(arena.getName(), arena);
            } catch (Exception e) {
                PlatinumArenas.INSTANCE.getLogger().warning("Failed to load arena file \"" + file.getName() + "\"!");
                e.printStackTrace();
            }
        }

        long took = System.currentTimeMillis() - time;

        PlatinumArenas.INSTANCE.getLogger().info("Loaded " + Arena.arenas.size() + " arenas in " + took + "ms!");
        PlatinumArenas.INSTANCE.ready = true;

        return Arena.arenas.values();
    }
}
