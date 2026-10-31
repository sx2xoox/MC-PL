package io.github.sx2xoox.mcpl.core.world;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * Where plugin-made worlds live on disk and how they are deleted. Paper 26 keeps every world as a dimension of the
 * main save ({@code <save>/dimensions/minecraft/<name>}, next to the overworld's folder); older servers kept it in
 * {@code <world container>/<name>} (Paper migrates that one when the world is loaded). Only folders named exactly
 * like the world are ever deleted, never the save around them.
 */
public final class WorldFiles {

    private WorldFiles() {
    }

    /** Every folder the world {@code name} may live in (existing or not); empty where the server has no folders. */
    public static List<Path> worldPaths(String name) {
        List<Path> paths = new ArrayList<>(2);
        try {
            World loaded = Bukkit.getWorld(name);
            if (loaded != null) {
                paths.add(loaded.getWorldPath());
            } else {
                Path dimensions = dimensionsFolder();
                if (dimensions != null) {
                    paths.add(dimensions.resolve(name));
                }
            }
        } catch (RuntimeException noWorldStorage) {
            // test servers have no world folders
        }
        Path container = containerFolder();
        if (container != null) {
            paths.add(container.resolve(name));
        }
        return paths;
    }

    /** The folder of the world {@code name} next to the main world's folder {@code overworld}. */
    public static Path dimensionPath(Path overworld, String name) {
        return overworld.resolveSibling(name);
    }

    /** True if the world {@code name} exists on disk (false where the server has no world folders, as in tests). */
    public static boolean exists(String name) {
        return worldPaths(name).stream().anyMatch(Files::isDirectory);
    }

    /**
     * Names of the world folders on disk (dimension folders and the old world container) accepted by {@code filter},
     * without duplicates; empty where the server has no world folders.
     */
    public static List<String> folderNames(Predicate<String> filter) {
        Set<String> names = new LinkedHashSet<>();
        Path dimensions = null;
        try {
            dimensions = dimensionsFolder();
        } catch (RuntimeException noWorldStorage) {
            // test servers have no world folders
        }
        for (Path parent : new Path[] {dimensions, containerFolder()}) {
            if (parent != null) {
                names.addAll(childFolders(parent, filter));
            }
        }
        return List.copyOf(names);
    }

    /** Names of the folders directly in {@code parent} accepted by {@code filter} (empty if it cannot be listed). */
    public static List<String> childFolders(Path parent, Predicate<String> filter) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(parent)) {
            return out;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(parent)) {
            for (Path child : children) {
                String name = String.valueOf(child.getFileName());
                if (Files.isDirectory(child) && filter.test(name)) {
                    out.add(name);
                }
            }
        } catch (IOException | RuntimeException e) {
            return out;
        }
        return out;
    }

    /**
     * Deletes the folders of the (unloaded) world {@code name}: only folders whose own name is exactly {@code name}.
     *
     * @throws IOException if a folder could not be deleted completely
     */
    public static void deleteWorldFolders(String name, List<Path> folders) throws IOException {
        for (Path folder : folders) {
            if (!Files.isDirectory(folder) || !name.equals(String.valueOf(folder.getFileName()))) {
                continue;
            }
            deleteRecursively(folder);
        }
    }

    /** Deletes a folder and everything in it. */
    public static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** {@code <save>/dimensions/minecraft}: the parent of the main world's folder, or null without world storage. */
    private static @Nullable Path dimensionsFolder() {
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        Path overworld = Bukkit.getWorlds().getFirst().getWorldPath();
        return overworld.getParent();
    }

    private static @Nullable Path containerFolder() {
        try {
            File container = Bukkit.getWorldContainer();
            return container == null ? null : container.toPath();
        } catch (RuntimeException noWorldContainer) {
            return null;
        }
    }
}
