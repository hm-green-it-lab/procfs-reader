# ProcFS-Reader

A Quarkus-based CLI application for reading Linux `/proc` file system metrics in specified intervals. It currently supports monitoring:

* Global CPU usage from `/proc/stat`
* Per-process CPU usage from `/proc/[pid]/stat`
* Global memory stats and per-process memory usage
* Network interface stats (system-wide only)
* Per-process disk I/O statistics

## 📦 Features

* ⏱ Scheduled metric collection every X seconds via Quarkus Cron
* 🥵 Runs on virtual threads for lightweight concurrency
* 📈 Outputs metrics in a consistent CSV-like format (always 16 columns)
* 📁 Reads both system-wide and per-process stats where appropriate
* 🧪 Ideal for experiments and lightweight telemetry

## ⚠️ Breaking Change

**Default interval changed**

This version introduces a **new default Cron schedule of every second** for metric collection.
Previous versions used a static `10000ms` (10s). To change it:

```bash
java -Dprocfs.cron="*/1 * * * * ?" -jar procfs-reader-1.0-runner.jar ...
```

## 📄 Output Format

All outputs follow a **fixed 16-column format**:

```csv
SourceFile,Timestamp,userTime (Ticks),systemTime (Ticks),MemTotal,MemFree,Cached,VmSize,VmRSS,Interface,Receive (Bytes),Transmit (Bytes),rchar,wchar,read_bytes,write_bytes
```

Unavailable fields are filled with `-1`. This ensures clean CSV parsing even across heterogeneous metrics.

### 📊 CPU Example

```txt
/proc/stat,1718123456789,1234567,987654,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1
/proc/1234/stat,1718123456789,4321,1234,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1
```

### 📊 Memory Example

```txt
/proc/meminfo,1718123456789,-1,-1,395865868,363247528,17697684,-1,-1,-1,-1,-1,-1,-1,-1,-1
/proc/1234/status,1718123456789,-1,-1,-1,-1,-1,22652,12800,-1,-1,-1,-1,-1,-1,-1
```

### 📊 Network Example

```txt
/proc/net/dev,1718123456789,-1,-1,-1,-1,-1,-1,-1,eth0,123456789,987654321,-1,-1,-1,-1
```

> [!warning]
> Per-process network statistics are not supported. This is a limitation of the `/proc` filesystem: per-process network counters are either unavailable or inconsistent across environments.

### 📊 I/O Example

```txt
/proc/1234/io,1718123456789,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,204934795625,973290428472,42865933824,1075767472128
```

> \[!warning]
> Disk I/O metrics (`--io`) require at least one PID. Running with `--io` and no PID will raise an error. Some fields may also require `sudo` depending on permissions.

## 🚀 Usage

### 🔧 Prerequisites

* Java 17+
* Maven or Quarkus CLI (for building)
* Linux system with readable `/proc`

### 🚪 Running the Application

```bash
# Runs with default interval (1000ms)
java -jar procfs-reader-1.0-runner.jar --cpu 1 2
java -jar procfs-reader-1.0-runner.jar --memory 1234
java -jar procfs-reader-1.0-runner.jar --network
sudo java -jar procfs-reader-1.0-runner.jar --io 1234 5678

# Custom Cron schedule (Every 5 seconds)
java -Dprocfs.cron="*/5 * * * * ?" -jar procfs-reader-1.0-runner.jar --cpu --io 1 2
```

### ⚙️ Behavior Matrix

| Metric      | Requires PIDs? | Allows PIDs? | Runtime Behavior                             |
| ----------- | -------------- | ------------ | -------------------------------------------- |
| `--cpu`     | ❌ No           | ✅ Yes        | Collects system + optional per-process stats |
| `--memory`  | ❌ No           | ✅ Yes        | Collects system + optional per-process stats |
| `--network` | ❌ No           | ❌ No         | Ignores any passed PIDs with a warning       |
| `--io`      | ✅ Yes          | ✅ Yes        | Fails if no PID given                        |

> [!warning]
> When combining metrics like `--network --io 123`, the parser:
>   * Ignores the PID for `--network` and prints a warning
>   * Enforces required PIDs for `--io`

### ⚠️ Caveats

* CPU values are in **clock ticks**; convert manually using system `CLK_TCK`
* Missing permissions (e.g., `/proc/[pid]/io`) will raise an ERROR
* Use `sudo` where necessary for full access
* Interval/Schedule is set via ` -Dprocfs.cron="*/5 * * * * ?"` (not CLI args)
