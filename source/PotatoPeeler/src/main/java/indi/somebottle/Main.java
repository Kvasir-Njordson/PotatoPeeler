package indi.somebottle;

import indi.somebottle.entities.PeelResult;
import indi.somebottle.exceptions.PeelerArgIncompleteException;
import indi.somebottle.exceptions.RegionFileNotFoundException;
import indi.somebottle.exceptions.RegionTaskInterruptedException;
import indi.somebottle.logger.GlobalLogger;
import indi.somebottle.utils.*;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.List;
import indi.somebottle.entities.PeelingTaskConfig;
import java.io.FileInputStream;
import java.io.File;

public class Main {
    public static void main(String[] args) {
        // 设置编码为 UTF-8
        System.setProperty("file.encoding", "UTF-8");
        // 如果一个参数都没有
        if (args.length == 0) {
            // 尝试从工作目录下的 potatopeeler.args 文件中读取参数
            try {
                args = ArgsUtils.readArgsFromFile("potatopeeler.args");
            } catch (IOException e) {
                // 没有这个文件，或者文件中没有指定有效参数
                GlobalLogger.info("No args provided. Use 'java -jar PotatoPeeler.jar --help' to get help on usage.");
                System.exit(0);
            }
        }
        // 获得 JVM 参数
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        // 初始化 PotatoPeeler 参数
        HashMap<String, String> peelerArgs = new HashMap<>();
        // 除掉 PotatoPeeler 相关的参数，剩下的参数
        List<String> remainingArgs = null;
        // 提取出 PotatoPeeler 相关的参数
        try {
            remainingArgs = ArgsUtils.stripPeelerArgs(args, peelerArgs);
        } catch (PeelerArgIncompleteException e) {
            // 说明命令行参数不完整，打印错误信息
            GlobalLogger.severe(e.getMessage());
            GlobalLogger.warning("Use 'java -jar PotatoPeeler.jar --help' to get help on usage.");
            System.exit(1);
        }

        // Load configuration from potatopeeler.yml
        Configuration config = new Configuration();
        String configFilePath = peelerArgs.getOrDefault("--config-file", "potatopeeler.yml"); // Default to potatopeeler.yml in current dir
        try (InputStream inputStream = new FileInputStream(new File(configFilePath))) {
            Yaml yaml = new Yaml();
            config = yaml.loadAs(inputStream, Configuration.class);
            GlobalLogger.info("Configuration loaded from " + configFilePath + ".");
        } catch (IOException e) {
            GlobalLogger.warning("Could not load configuration from " + configFilePath + ", using default configuration. Error: " + e.getMessage());
            // If config file is not found or cannot be read, initialize with empty task list and default cooldown
            config.setPeelingTasks(List.of()); // No tasks by default
            config.setCoolDown(0); // No cooldown by default
        }


        // 可能只需要打印帮助信息
        boolean helpNeeded = peelerArgs.containsKey("--help");
        if (helpNeeded) {
            printHelp();
            System.exit(0);
        }
        // 因为要设置日志记录级别，这里要先拿到 --verbose 选项
        boolean verboseOutput = peelerArgs.containsKey("--verbose");
        GlobalLogger.setVerbose(verboseOutput);
        GlobalLogger.info("Potato Peeler starting...");
        // 先输出命令行参数
        GlobalLogger.fine("====== JVM ARGS ======");
        for (String arg : jvmArgs) {
            GlobalLogger.fine(arg + " ");
        }
        // 再输出 PotatoPeeler 相关的参数
        GlobalLogger.fine("====== POTATO-PEELER ARGS ======");
        for (String arg : peelerArgs.keySet()) {
            GlobalLogger.fine(arg + " " + peelerArgs.get(arg));
        }
        // 最后是其他参数
        GlobalLogger.fine("====== OTHER ARGS ======");
        for (String arg : remainingArgs) {
            GlobalLogger.fine(arg + " ");
        }
        // ========== 开始进行参数检查 ==========
        // 给没有指定的参数标上默认值
        ArgsUtils.setDefaultPeelerArgs(peelerArgs);
        if (!ArgsUtils.checkPeelerArgs(peelerArgs)) {
            // 有参数不合法则退出
            System.exit(1);
        }
        // 解析参数值
        // Global parameters from command line (if provided)
        int maxLogSize = Integer.parseInt(peelerArgs.getOrDefault("--max-log-size", "2097152")); // Default 2MB
        int retainLogFiles = Integer.parseInt(peelerArgs.getOrDefault("--retain-log-files", "10")); // Default 10 files
        boolean skipPeeler = peelerArgs.containsKey("--skip-peeler");

        // Configure logger
        GlobalLogger.resetLogFileHandler(maxLogSize, retainLogFiles);

        // List global parameters
        GlobalLogger.info("====== POTATO-PEELER GLOBAL PARAMS ======");
        GlobalLogger.info("Max log size: " + maxLogSize);
        GlobalLogger.info("Retain log files: " + retainLogFiles);
        GlobalLogger.info("Verbose output: " + verboseOutput);
        GlobalLogger.info("Skip peeler: " + skipPeeler);
        GlobalLogger.info("=========================================");

        // If no tasks are defined in config, use command-line args for a single task
        if (config.getPeelingTasks() == null || config.getPeelingTasks().isEmpty()) {
            GlobalLogger.info("No peeling tasks defined in potatopeeler.yml. Attempting to use command-line arguments for a single task.");
            // Re-parse command-line args for single task compatibility
            List<String> worldDirPaths = ArgsUtils.parseWorldDirs(peelerArgs.get("--world-dirs"));
            List<String> outputDirPaths = ArgsUtils.parseWorldDirs(peelerArgs.get("--output-dirs"));
            long minInhabited = Long.parseLong(peelerArgs.getOrDefault("--min-inhabited", "0"));
            long coolDownArg = Long.parseLong(peelerArgs.getOrDefault("--cool-down", "0"));
            int threadsNum = Integer.parseInt(peelerArgs.getOrDefault("--threads-num", "10"));
            boolean dryRunArg = peelerArgs.containsKey("--dry-run");
            int maxCreatedTimeArg = -1; // Default to -1 for command-line single task (no time filter)

            // Create a single PeelingTaskConfig from command-line args
            PeelingTaskConfig singleTask = new PeelingTaskConfig(
                    "Default Command-Line Task",
                    "chunk_level_deletion", // Default mode for command-line
                    minInhabited,
                    maxCreatedTimeArg,
                    dryRunArg,
                    peelerArgs.get("--world-dirs"),
                    peelerArgs.get("--output-dirs"),
                    threadsNum,
                    verboseOutput
            );
            config.setPeelingTasks(List.of(singleTask));
            config.setCoolDown(coolDownArg);
        }

        // Check if output paths count matches world paths count for each task
        for (PeelingTaskConfig task : config.getPeelingTasks()) {
            List<String> taskWorldDirPaths = ArgsUtils.parseWorldDirs(task.getWorldDirs());
            List<String> taskOutputDirPaths = ArgsUtils.parseWorldDirs(task.getOutputDirs());
            if (!taskOutputDirPaths.isEmpty() && taskOutputDirPaths.size() != taskWorldDirPaths.size()) {
                GlobalLogger.severe("The number of output paths (current: " + taskOutputDirPaths.size() + ") must be equal to the number of world paths (" + taskWorldDirPaths.size() + ") for task '" + task.getName() + "'.");
                System.exit(1);
            }
        }

        // In minInhabited > 200 warning (now per task)
        for (PeelingTaskConfig task : config.getPeelingTasks()) {
            if (task.getMinInhabited() > 200) {
                GlobalLogger.warning("****** WARNING (Task: " + task.getName() + ") ******");
                GlobalLogger.warning("You are setting 'minInhabited' to a value greater than 200 ticks (10 seconds).");
                GlobalLogger.warning("This may cause some chunks to be removed even if they are currently in use.");
                GlobalLogger.warning("Please make sure you know what you are doing.");
                GlobalLogger.warning("*********************");
                // 20 秒冷静期
                GlobalLogger.warning("The program will continue in 20 seconds.");
                try {
                    Thread.sleep(20000);
                } catch (InterruptedException e) {
                    System.exit(0);
                }
                break; // Only warn once for the first task that triggers it
            }
        }

        // Calculate time since last run
        long timeSinceLastRun = TimeUtils.timeNow() - TimeUtils.getLastRunTime();
        if (config.getPeelingTasks().isEmpty()) {
            GlobalLogger.info("====== POTATO-PEELER SKIPPED ======");
            GlobalLogger.info("No peeling tasks defined or no world to process.");
        } else if (skipPeeler) {
            GlobalLogger.info("====== POTATO-PEELER SKIPPED ======");
            GlobalLogger.info("Skipped by --skip-peeler argument.");
        } else if (timeSinceLastRun <= config.getCoolDown() * 60) {
            GlobalLogger.info("====== POTATO-PEELER SKIPPED ======");
            GlobalLogger.info("Currently in cool down period (" + config.getCoolDown() + " min), skipped.");
        } else {
            // Start processing tasks
            GlobalLogger.info("====== POTATO-PEELER RUNNING ======");
            GlobalLogger.info("********* DO NOT INTERRUPT ********");
            if (!verboseOutput) {
                GlobalLogger.info("You could use '--verbose' option for more detailed information.");
            }
            boolean peeled = false;
            for (PeelingTaskConfig task : config.getPeelingTasks()) {
                GlobalLogger.info(">>> Starting task: '" + task.getName() + "' (Mode: " + task.getMode() + ") ...");
                List<String> worldDirPaths = ArgsUtils.parseWorldDirs(task.getWorldDirs());
                List<String> outputDirPaths = ArgsUtils.parseWorldDirs(task.getOutputDirs());

                if (worldDirPaths.isEmpty()) {
                    GlobalLogger.warning("Task '" + task.getName() + "' has no world directories specified, skipping.");
                    continue;
                }

                for (int i = 0; i < worldDirPaths.size(); i++) {
                    String worldDirPath = worldDirPaths.get(i);
                    String outputDirPath = "";
                    if (!outputDirPaths.isEmpty()) {
                        outputDirPath = outputDirPaths.get(i);
                    }
                    try {
                        GlobalLogger.info(">>> Processing world '" + worldDirPath + "' for task '" + task.getName() + "' ...");
                        boolean isRegionLevelDeletionMode = "region_level_deletion".equals(task.getMode());
                        PeelResult peelResult = Potato.peel(
                                worldDirPath,
                                outputDirPath,
                                task.getThreadsNum(),
                                task.getMinInhabited(),
                                task.isDryRun(),
                                task.getMaxCreatedTime(),
                                isRegionLevelDeletionMode
                        );
                        GlobalLogger.info("=========== TASK RESULT ============");
                        GlobalLogger.info("Task: " + task.getName());
                        GlobalLogger.info("World: " + worldDirPath);
                        GlobalLogger.info("Time elapsed: " + (double) peelResult.getTimeElapsed() / 1000D + "s");
                        GlobalLogger.info("Total region files checked: " + peelResult.getTotalRegionFilesChecked());
                        if ("region_level_deletion".equals(task.getMode())) {
                            GlobalLogger.info("Region files deleted: " + peelResult.getRegionsAffected());
                        } else { // chunk_level_deletion mode
                            GlobalLogger.info("Regions affected: " + peelResult.getRegionsAffected());
                            GlobalLogger.info("Chunks removed: " + peelResult.getChunksRemoved());
                        }
                        GlobalLogger.info("Size reduced: " + NumUtils.bytesToHumanReadable(peelResult.getSizeReduced()));
                        GlobalLogger.info("====================================");
                        peeled = true;
                    } catch (RegionFileNotFoundException e) {
                        GlobalLogger.warning("Regions of world: '" + worldDirPath + "' not found for task '" + task.getName() + "', skipped.");
                        // Create an empty PeelResult to show 0 files adjusted
                        PeelResult emptyPeelResult = new PeelResult();
                        emptyPeelResult.setTotalRegionFilesChecked(0); // Set to 0 for empty result
                        GlobalLogger.info("=========== TASK RESULT ============");
                        GlobalLogger.info("Task: " + task.getName());
                        GlobalLogger.info("World: " + worldDirPath);
                        GlobalLogger.info("Time elapsed: " + (double) emptyPeelResult.getTimeElapsed() / 1000D + "s");
                        GlobalLogger.info("Total region files checked: " + emptyPeelResult.getTotalRegionFilesChecked());
                        if ("region_level_deletion".equals(task.getMode())) {
                            GlobalLogger.info("Region files deleted: " + emptyPeelResult.getRegionsAffected());
                        } else { // chunk_level_deletion mode
                            GlobalLogger.info("Regions affected: " + emptyPeelResult.getRegionsAffected());
                            GlobalLogger.info("Chunks removed: " + emptyPeelResult.getChunksRemoved());
                        }
                        GlobalLogger.info("Size reduced: " + NumUtils.bytesToHumanReadable(emptyPeelResult.getSizeReduced()));
                        GlobalLogger.info("====================================");
                    } catch (IOException e) {
                        GlobalLogger.warning("I/O Exception occurred while processing world: '" + worldDirPath + "' for task '" + task.getName() + "', skipped the world.", e);
                    } catch (RegionTaskInterruptedException e) {
                        GlobalLogger.severe("Failed to process regions of world: '" + worldDirPath + "' for task '" + task.getName() + "', interrupted.", e);
                        System.exit(1);
                    } catch (Exception e) {
                        GlobalLogger.severe("Unexpected exception during task '" + task.getName() + "' for world '" + worldDirPath + "'!", e);
                        System.exit(1);
                    }
                }
            }
        } // Closes the 'else' block for peeling tasks
        // 处理完区块后若没有指定 server-jar 则退出
        String serverJarPath = config.getServerJar();
        if (serverJarPath == null || serverJarPath.isEmpty()) {
            GlobalLogger.info("No serverJar specified in configuration, exiting normally.");
            System.exit(0);
        }
        // 启动服务器前先回收没有用的资源
        System.gc();
        // 如果指定了 --server-jar，就尝试启动 Minecraft 服务器
        // 开启启动 Minecraft 服务器
        GlobalLogger.info("====== LAUNCHING MINECRAFT SERVER ======");
        try {
            // 在当前 JVM 中载入服务端 jar 并执行主类程序
            // 同时也传入了剩余参数 remainingArgs
            JarUtils.runJarInCurrentJVM(serverJarPath, remainingArgs.toArray(new String[0]));
        } catch (Exception e) {
            GlobalLogger.severe("Exception occurred when launching Minecraft server: " + serverJarPath, e);
            System.exit(1);
        }
    }

    /**
     * 打印帮助信息，当命令行选项有 --help 时执行
     */
    public static void printHelp() {
        System.out.println();
        System.out.println("Potato Peeler - A simple tool to remove unused chunks from Minecraft worlds.");
        System.out.println();
        System.out.println("Author: github.com/SomeBottle");
        System.out.println();
        System.out.println("Usage: ");
        System.out.println("\tjava [jvm-options] -jar PotatoPeeler.jar [options] [--world-dirs <worldPath1>,<worldPath2>,...]");
        System.out.println("\t\t[--output-dirs <outputWorldPath1>,<outputWorldPath2>,...]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("\t--help                           Show this help message and exit.");
        System.out.println("\t--config-file <path>             Path to the potatopeeler.yml configuration file. (default: potatopeeler.yml in current directory)");
        System.out.println("\t--max-log-size <size>            Maximum size of a single log file in bytes. (default: 2097152)");
        System.out.println("\t--retain-log-files <number>      Maximum number of log files to retain. (default: 10)");
        System.out.println("\t--verbose                        Enable verbose output.");
        System.out.println("\t--skip-peeler                    Skip the Potato Peeler process.");
        System.out.println("\t--server-jar <server.jar>        Path to the Minecraft server JAR file to launch after processing regions.");
        System.out.println();
        System.out.println("Configuration via potatopeeler.yml:");
        System.out.println("\t- The tool now primarily uses 'potatopeeler.yml' for defining peeling tasks and global cooldown.");
        System.out.println("\t- You can define multiple tasks, each with its own mode (chunk_level_deletion or region_level_deletion), minInhabited, minCreationHours, dryRun, worldDirs, outputDirs, threadsNum, and verbose settings.");
        System.out.println("\t- A global 'coolDown' (in minutes) can be set in the YAML for the entire sequence of tasks.");
        System.out.println();
        System.out.println("List of protected chunks:");
        System.out.println("\t- In order to protect certain chunks from being removed, you can create a file named 'chunks.protected' in the world(dimension) directory, as a sibling of the directory 'region'.");
        System.out.println("\t- The file should contain the coordinates (ranges are supported) of the chunks you want to protect, one per line, in the format 'x,z' or 'x1~x2,z1~z2'.");
        System.out.println("\t- In addition, wildcard asterisk is supported. For instance:");
        System.out.println("\t   > By adding a line '*,*', all of the chunks will be protected.");
        System.out.println("\t   > '0~10,*' will protect chunks from x=0 to x=10 in all z positions.");
        System.out.println("\t   > '1~*,2~9' will protect chunks from x=1 to the maximum coordinate and z positions from z=2 to z=9.");
        System.out.println("\t   > '114,514' will only protect the chunk at x=114, z=514.");
        System.out.println("\t- Please note that comments starting with the '#' are supported, including both single-line and inline comments.");
        System.out.println();
        System.out.println("Note:");
        System.out.println("\t- Command-line arguments for '--world-dirs', '--output-dirs', '--min-inhabited', '--cool-down', '--threads-num', '--dry-run' are now primarily handled within 'potatopeeler.yml' for each task.");
        System.out.println("\t- If 'potatopeeler.yml' does not define any tasks, the tool will attempt to run a single 'chunk_level_deletion' task using command-line arguments for compatibility.");
        System.out.println("\t- After all peeling tasks complete, the server JAR file (if specified in YAML or via --server-jar) will be launched in the current JVM. Any remaining command-line arguments will be passed to the server jar.");
        System.out.println();
        System.out.println("Example (Using potatopeeler.yml for tasks):");
        System.out.println("\tjava -Xmx4G -jar PotatoPeeler.jar --server-jar server.jar");
        System.out.println();
        System.out.println("Example (Single task via command-line for compatibility):");
        System.out.println("\tjava -Xmx4G -jar PotatoPeeler.jar --min-inhabited 50 --cool-down 60 --threads-num 5 --world-dirs 'world' --dry-run");
    }
}