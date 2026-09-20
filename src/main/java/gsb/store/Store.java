package gsb.store;

import gsb.model.AppState;
import gsb.model.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Store {
    private final Path directory;
    private final Path logFile;
    private final Path snapshotDirectory;
    private final Object lock = new Object();
    private final Replayer replayer = new Replayer();

    public Store(Path directory) {
        this.directory = directory;
        this.logFile = directory.resolve("events.log");
        this.snapshotDirectory = directory.resolve("snapshots");
        try {
            Files.createDirectories(snapshotDirectory);
            if (Files.notExists(logFile)) Files.createFile(logFile);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public AppState load() {
        synchronized (lock) {
            AppState state = new AppState();
            try {
                List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
                for (int lineNumber = 1; lineNumber <= lines.size(); lineNumber++) {
                    String line = lines.get(lineNumber - 1).trim();
                    if (line.isEmpty()) continue;
                    Map<String, Object> transaction;
                    try {
                        transaction = Json.object(Json.parse(line));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalStateException("Corrupt transaction at line " + lineNumber + "; no partial commit is accepted", exception);
                    }
                    replayer.applyTransaction(state, transaction);
                }
                restoreSnapshotFiles(state);
                return state;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    public void append(Map<String, Object> transaction) {
        synchronized (lock) {
            try {
                String current = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
                String line = Json.canonical(transaction);
                String next = current.isEmpty() ? line + "\n" : current + line + "\n";
                Path temporary = directory.resolve("events.log.tmp");
                Files.writeString(temporary, next, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, logFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, logFile, StandardCopyOption.REPLACE_EXISTING);
                }
                Map<String, Object> snapshot = Json.object(transaction.get("snapshot"));
                if (!snapshot.isEmpty()) {
                    String snapshotId = String.valueOf(snapshot.get("id"));
                    Path target = snapshotDirectory.resolve(snapshotId + ".json");
                    if (Files.notExists(target)) {
                        Path temporarySnapshot = snapshotDirectory.resolve(snapshotId + ".json.tmp");
                        Files.writeString(temporarySnapshot, Json.writePretty(snapshot), StandardCharsets.UTF_8);
                        try {
                            Files.move(temporarySnapshot, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } catch (AtomicMoveNotSupportedException ignored) {
                            Files.move(temporarySnapshot, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    public Path logFile() {
        return logFile;
    }

    public Path snapshotDirectory() {
        return snapshotDirectory;
    }

    private void restoreSnapshotFiles(AppState state) {
        state.snapshots.values().forEach(snapshot -> {
            Path target = snapshotDirectory.resolve(snapshot.id() + ".json");
            if (Files.notExists(target)) {
                try {
                    Path temporary = snapshotDirectory.resolve(snapshot.id() + ".json.tmp");
                    Files.writeString(temporary, Json.writePretty(snapshot.toJson()), StandardCharsets.UTF_8);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        });
    }

    public List<Map<String, Object>> readTransactions() {
        synchronized (lock) {
            try {
                List<Map<String, Object>> transactions = new ArrayList<>();
                for (String line : Files.readAllLines(logFile, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) transactions.add(Json.object(Json.parse(line)));
                }
                return transactions;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
