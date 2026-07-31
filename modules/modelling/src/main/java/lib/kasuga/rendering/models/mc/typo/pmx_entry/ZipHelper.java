package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import lombok.Getter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class ZipHelper implements AutoCloseable {

    @Getter
    private final Map<ZipEntry, ByteBuffer> entries;

    @Getter
    private final Map<String, ZipEntry> entryNameMap;

    private final Map<ZipEntry, List<ZipEntry>> entryTree;

    @Getter
    private final Object path;

    public ZipHelper(ZipFile file) {
        List<ZipEntry> entriesList = (List<ZipEntry>) file.stream().toList();
        entryNameMap = new HashMap<>();
        this.entries = new HashMap<>();
        entryTree = new HashMap<>();
        for (ZipEntry entry : entriesList) {
            try {
                if (!entry.isDirectory()) {
                    byte[] entryData = file.getInputStream(entry).readAllBytes();
                    ByteBuffer buffer = ByteBuffer.allocate(entryData.length);
                    buffer.order(ByteOrder.nativeOrder());
                    buffer.put(entryData);
                    buffer.flip();
                    entries.put(entry, buffer);
                    entryNameMap.put(normalizeEntryName(entry.getName()), entry);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        path = file.getName();
    }

    public ZipHelper(ResourceLocation rl, ZipInputStream stream) {
        this.entries = new HashMap<>();
        entryNameMap = new HashMap<>();
        entryTree = new HashMap<>();
        path = rl;
        ZipEntry entry;
        try {
            while ((entry = stream.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] entryData = stream.readAllBytes();
                    ByteBuffer buffer = ByteBuffer.allocate(entryData.length);
                    buffer.order(ByteOrder.nativeOrder());
                    buffer.put(entryData);
                    buffer.flip();
                    entries.put(entry, buffer);
                    entryNameMap.put(normalizeEntryName(entry.getName()), entry);
                }
                stream.closeEntry();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasEntry(String entryName) {
        return entryNameMap.containsKey(normalizeEntryName(entryName));
    }

    public boolean hasEntry(ZipEntry entry) {
        return entries.containsKey(entry);
    }

    public int entryCount() {
        return entries.size();
    }

    public @Nullable ByteBuffer getBuffer(String entryName) {
        ZipEntry entry = entryNameMap.get(normalizeEntryName(entryName));
        if (entry == null) return null;
        return entries.get(entry);
    }

    public @Nullable ByteBuffer getBuffer(ZipEntry entry) {
        return entries.getOrDefault(entry, null);
    }

    public ByteBuffer getBufferOrDefault(String entryName, @Nullable ByteBuffer defaultValue) {
        ZipEntry entry = entryNameMap.get(normalizeEntryName(entryName));
        if (entry == null) return defaultValue;
        return entries.getOrDefault(entry, defaultValue);
    }

    public List<ByteBuffer> searchForName(Predicate<String> namePredicate) {
        return entryNameMap.entrySet()
                .stream()
                .filter(e -> namePredicate.test(e.getKey()))
                .map(e -> entries.get(e.getValue()))
                .toList();
    }

    public List<ByteBuffer> searchFor(Predicate<ZipEntry> entryPredicate) {
        return entries.keySet().stream().filter(entryPredicate).map(entries::get).toList();
    }

    public @Nullable ZipResource getResource(String entryName) {
        String normalized = normalizeEntryName(entryName);
        ZipEntry entry = entryNameMap.get(normalized);
        if (entry == null) return null;
        ByteBuffer buffer = entries.get(entry);
        if (buffer == null) return null;
        return new ZipResource(this, normalized, entry, buffer);
    }

    public @Nullable ZipResource getResource(ZipEntry entry) {
        ByteBuffer buffer = entries.get(entry);
        if (buffer == null) return null;
        return new ZipResource(this, entry.getName(), entry, buffer);
    }

    public ZipResource getResourceOrDefault(String entryName, @Nullable ZipResource defaultValue) {
        String normalized = normalizeEntryName(entryName);
        ZipEntry entry = entryNameMap.get(normalized);
        if (entry == null) return defaultValue;
        ByteBuffer buffer = entries.get(entry);
        if (buffer == null) return defaultValue;
        return new ZipResource(this, normalized, entry, buffer);
    }

    public static String normalizeEntryName(String entryName) {
        if (entryName == null || entryName.isBlank()) return "";
        Deque<String> parts = new ArrayDeque<>();
        for (String part : entryName.replace('\\', '/').split("/+")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts).toLowerCase(Locale.ROOT);
    }

    public List<ZipResource> searchNameForResource(Predicate<String> namePredicate) {
        return entryNameMap.entrySet()
                .stream()
                .filter(e -> namePredicate.test(e.getKey()))
                .map(e -> {
                    ZipEntry entry = e.getValue();
                    ByteBuffer buffer = entries.get(entry);
                    if (buffer == null) return null;
                    return new ZipResource(this, e.getKey(), entry, buffer);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public List<ZipResource> searchForResource(Predicate<ZipEntry> entryPredicate) {
        return entries.entrySet().stream()
                .filter(e -> entryPredicate.test(e.getKey()))
                .map(e -> new ZipResource(this, e.getKey().getName(), e.getKey(), e.getValue()))
                .toList();
    }

    public static ZipHelper fromFile(String filePath) throws Exception {
        return new ZipHelper(new ZipFile(filePath));
    }

    public static @Nullable ZipHelper fromResource(ResourceManager manager, ResourceLocation rl) throws Exception {
        Optional<Resource> resource = manager.getResource(rl);
        if (resource.isEmpty()) return null;
        Resource res = resource.get();
        try (ZipInputStream zin = new ZipInputStream(res.open())) {
            return new ZipHelper(rl, zin);
        } catch (Exception e) {
            return null;
        }
    }

    public static @Nullable ZipHelper fromResource(ResourceLocation rl, Resource resource, @Nullable Charset charset) {
        try (ZipInputStream zin = new ZipInputStream(resource.open(), charset == null ? StandardCharsets.UTF_8 : charset)) {
            return new ZipHelper(rl, zin);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() throws Exception {
        entries.forEach((k, v) -> {
            if (v.isDirect()) {
                MemoryUtil.memFree(v);
            }
        });
        entries.clear();
        entryNameMap.clear();
    }

    @Override
    public String toString() {
        return "ZipHelper{" +
                "path=" + path.toString() +
                ", entryCount=" + entries.size() +
                '}';
    }
}
