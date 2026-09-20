package local.gsb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class EventStore {
    private final Path directory;

    EventStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot create data directory", ex);
        }
    }

    void appendBatch(Map<String, Object> batch) {
        long seq = ((Number) batch.get("seq")).longValue();
        String file = String.format("%012d-%s.json", seq, batch.get("batchId"));
        Path target = directory.resolve(file);
        if (Files.exists(target)) {
            Object stored = Json.parse(readString(target));
            if (!Json.write(stored).equals(Json.write(batch))) {
                throw new IllegalStateException("Batch sequence " + seq + " was already published with different content");
            }
            return;
        }
        Path temporary = directory.resolve(file + ".tmp-" + ProcessHandle.current().pid());
        try {
            Files.writeString(temporary, Json.write(batch) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot persist batch " + seq, ex);
        }
    }

    List<Path> batchFiles() {
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot list event batches", ex);
        }
    }

    Map<String, Object> readBatch(Path file) {
        return Json.object(Json.parse(readString(file)));
    }

    private String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read " + file, ex);
        }
    }
}
