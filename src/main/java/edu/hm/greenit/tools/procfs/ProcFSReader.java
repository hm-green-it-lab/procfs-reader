package edu.hm.greenit.tools.procfs;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@QuarkusMain
public class ProcFSReader implements QuarkusApplication {

    private static final String OVERALL_PROC_STAT_PATH = "/proc/stat";
    private static final String OVERALL_PROC_MEMINFO_PATH = "/proc/meminfo";
    private static final String OVERALL_PROC_NETDEV_PATH = "/proc/net/dev";
    private static final String PROC_PATH_PROCESS_STAT = "/proc/{process_id}/stat";
    private static final String PROC_PATH_PROCESS_STATUS = "/proc/{process_id}/status";

    private static final List<String> MEMINFO_FIELDS = List.of("MemTotal", "MemFree", "Cached");
    private static final List<String> STATUS_FIELDS = List.of("VmSize", "VmRSS");

    private static final String[] HEADER = {
            "SourceFile", "Timestamp",                              // DEFAULT
            "userTime (Ticks)", "systemTime (Ticks)",               // CPU
            "MemTotal", "MemFree", "Cached", "VmSize", "VmRSS",     // MEMORY
            "Interface", "Receive (Bytes)", "Transmit (Bytes)",     // NETWORK
            "rchar", "wchar", "read_bytes", "write_bytes"           // IO
    };

    private final List<String> processesToMonitor = new ArrayList<>();
    private final Set<String> enabledMetrics = new HashSet<>();

    public static void main(String[] args) {
        Quarkus.run(ProcFSReader.class, args);
    }

    @Override
    public int run(String... args) throws IOException, InterruptedException {
        parseArguments(args);
        printHeader();
        Quarkus.waitForExit();
        return 0;
    }

    private void parseArguments(String[] args) {
        if (args == null) args = new String[0];

        List<String> pids = new ArrayList<>();

        for (String arg : args) {
            if (arg.startsWith("--")) {
                String metric = arg.substring(2).toLowerCase();
                if (!Set.of("cpu", "memory", "network", "io").contains(metric)) {
                    throw new IllegalArgumentException("Unknown metric type: " + metric);
                }
                enabledMetrics.add(metric);
            } else if (arg.matches("\\d+")) {
                pids.add(arg);
            } else {
                throw new IllegalArgumentException("Invalid argument: " + arg);
            }
        }

        if (enabledMetrics.isEmpty()) {
            enabledMetrics.add("cpu");
        }

        // Apply PIDs only to metrics that allow them
        if (enabledMetrics.contains("network") && !pids.isEmpty()) {
            System.err.println("[WARN] PIDs are ignored for 'network' metric.");
        }

        if (enabledMetrics.contains("io") && pids.isEmpty()) {
            throw new IllegalArgumentException("[ERROR] 'io' metric requires at least one PID to monitor.");
        }

        for (String pid : pids) {
            processesToMonitor.add(PROC_PATH_PROCESS_STAT.replace("{process_id}", pid));
        }
    }

    private void printHeader() {
        System.out.println(String.join(",", HEADER));
    }

    @RunOnVirtualThread
    @Scheduled(cron = "${procfs.cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void readAndDisplayProcFSData() throws IOException {
        long timestamp = System.currentTimeMillis();

        if (enabledMetrics.contains("cpu")) {
            readAndDisplayProcStat(timestamp);
            for (String process : processesToMonitor) {
                readAndDisplayProcStatForProcess(process, timestamp);
            }
        }

        if (enabledMetrics.contains("memory")) {
            readAndDisplayMemInfo(timestamp);
            for (String process : processesToMonitor) {
                String statusPath = process.replace("/stat", "/status");
                readAndDisplayProcStatusForProcess(statusPath, timestamp);
            }
        }

        if (enabledMetrics.contains("network")) {
            readAndDisplayNetDev(timestamp);
        }

        if (enabledMetrics.contains("io")) {
            for (String process : processesToMonitor) {
                String ioPath = process.replace("/stat", "/io");
                readAndDisplayProcIOForProcess(ioPath, timestamp);
            }
        }
    }

    public static void readAndDisplayProcStat(long timestamp) throws IOException {
        String userTime = "-1";
        String systemTime = "-1";

        try (BufferedReader reader = new BufferedReader(new FileReader(OVERALL_PROC_STAT_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("cpu ")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 4) {
                        userTime = parts[1];
                        systemTime = parts[3];
                    }
                    break;
                }
            }
        }

        // See HEADER for full structure
        System.out.printf("%s,%d,%s,%s,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1%n",
                OVERALL_PROC_STAT_PATH, timestamp, userTime, systemTime);
    }

    public static void readAndDisplayProcStatForProcess(String statPath, long timestamp) throws IOException {
        String userTime = "-1";
        String systemTime = "-1";

        try (BufferedReader reader = new BufferedReader(new FileReader(statPath))) {
            String[] fields = reader.readLine().split("\\s+");
            if (fields.length >= 15) {
                userTime = fields[13];
                systemTime = fields[14];
            }
        } catch (IOException e) {
            // Do nothing, we will use the default -1 values
        }

        // See HEADER for full structure
        System.out.printf("%s,%d,%s,%s,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1%n",
                statPath, timestamp, userTime, systemTime);
    }

    public static void readAndDisplayMemInfo(long timestamp) throws IOException {
        Map<String, String> memValues = new HashMap<>();
        for (String field : MEMINFO_FIELDS) memValues.put(field, "-1");

        try (BufferedReader reader = new BufferedReader(new FileReader(OVERALL_PROC_MEMINFO_PATH))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String field : MEMINFO_FIELDS) {
                    if (line.startsWith(field + ":")) {
                        String[] parts = line.split(":");
                        if (parts.length == 2) {
                            memValues.put(field, parts[1].replaceAll("[^0-9]", "").trim());
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Do nothing, we will use the default -1 values
        }

        // See HEADER for full structure
        System.out.printf("%s,%d,-1,-1,%s,%s,%s,-1,-1,-1,-1,-1,-1,-1,-1,-1%n",
                OVERALL_PROC_MEMINFO_PATH, timestamp,
                memValues.get("MemTotal"), memValues.get("MemFree"), memValues.get("Cached"));
    }

    public static void readAndDisplayProcStatusForProcess(String statusPath, long timestamp) throws IOException {
        Map<String, String> statusValues = new HashMap<>();
        for (String field : STATUS_FIELDS) statusValues.put(field, "-1");

        try (BufferedReader reader = new BufferedReader(new FileReader(statusPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String field : STATUS_FIELDS) {
                    if (line.startsWith(field + ":")) {
                        String[] parts = line.split(":");
                        if (parts.length == 2) {
                            statusValues.put(field, parts[1].replaceAll("[^0-9]", "").trim());
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Do nothing, we will use the default -1 values
        }

        // See HEADER for full structure
        System.out.printf("%s,%d,-1,-1,-1,-1,-1,%s,%s,-1,-1,-1,-1,-1,-1,-1%n",
                statusPath, timestamp,
                statusValues.get("VmSize"), statusValues.get("VmRSS"));
    }

    public static void readAndDisplayNetDev(long timestamp) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(OVERALL_PROC_NETDEV_PATH))) {
            String line;
            int skipped = 0;

            while ((line = reader.readLine()) != null) {
                if (skipped++ < 2) continue;

                String[] parts = line.split(":");
                if (parts.length != 2) continue;

                String iface = parts[0].trim();
                String[] values = parts[1].trim().split("\\s+");
                if (values.length < 9) continue;

                String received = values[0];
                String transmitted = values[8];

                // See HEADER for full structure
                System.out.printf("%s,%d,-1,-1,-1,-1,-1,-1,-1,%s,%s,%s,-1,-1,-1,-1%n",
                        OVERALL_PROC_NETDEV_PATH, timestamp, iface, received, transmitted);
            }
        } catch (IOException e) {
            // Do nothing, we will use the default -1 values
        }
    }

    public static void readAndDisplayProcIOForProcess(String ioPath, long timestamp) throws IOException {
        String rchar = "-1";
        String wchar = "-1";
        String readBytes = "-1";
        String writeBytes = "-1";

        try (BufferedReader reader = new BufferedReader(new FileReader(ioPath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("rchar:")) rchar = line.split(":\\s*")[1];
                if (line.startsWith("wchar:")) wchar = line.split(":\\s*")[1];
                if (line.startsWith("read_bytes:")) readBytes = line.split(":\\s*")[1];
                if (line.startsWith("write_bytes:")) writeBytes = line.split(":\\s*")[1];
            }
        } catch (IOException e) {
            // Do nothing, we will use the default -1 values
        }

        // See HEADER for full structure
        System.out.printf("%s,%d,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,%s,%s,%s,%s%n",
                ioPath, timestamp, rchar, wchar, readBytes, writeBytes);
    }
}
