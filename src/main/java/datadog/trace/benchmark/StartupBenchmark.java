package datadog.trace.benchmark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class StartupBenchmark {
    /**
     * Example - how long to load all classes in spring-petclinic
     * for each tracer version from 40 to 63 inclusive:
     *
     * java -jar spring-petclinic-2.2.0.BUILD-SNAPSHOT 40 63
     */
    public static void main(String... args) throws Exception {
        if (args.length >= 1) {
            String jarFile = args[0];
            int minVersion = 30;
            int maxVersion = -1;
            if (args.length >= 2) {
                minVersion = Integer.parseInt(args[1]);
                if (args.length >= 3) {
                    maxVersion = Integer.parseInt(args[2]);
                }
            }
            getJars(minVersion, maxVersion);
            Path tracersDir = Paths.get(System.getProperty("user.dir")).resolve("tracers");
            List<Result> results = new ArrayList<>();
            try (Stream<Path> tracerJars = Files.list(tracersDir)) {
                tracerJars.forEach(tracerJarFile -> {
                    ProcessBuilder processBuilder = new ProcessBuilder("java",
                            "-javaagent:" + tracerJarFile,
                            "-Ddd.jmxfetch.enabled=false",
                            "-Ddd.profiling.enabled=false",
                            "-cp",
                            jarFile + ":" + System.getProperty("java.class.path"),
                            LoadClasses.class.getName());
                    processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
                    long[] durations = new long[10];
                    boolean[] failures = new boolean[10];
                    for (int i = 0; i < durations.length; ++i) {
                        long start = System.nanoTime();
                        try {
                            Process process = processBuilder.start();
                            int exitCode = process.waitFor();
                            failures[i] = exitCode != 0;
                        } catch (Exception e) {
                            failures[i] = true;
                        } finally {
                            durations[i] = System.nanoTime() - start;
                        }
                    }
                    results.add(new Result(durations, failures, tracerJarFile.toString(), jarFile, extractVersion(tracerJarFile)));
                });
            }
            results.sort(Comparator.comparingInt(r -> versionToNumber(r.version)));
            System.out.println("version,jar,failures,mean(ms),stddev,min(ms),max(ms)");
            String jarFileName = jarFile.substring(jarFile.lastIndexOf('/') + 1);
            for (Result result : results) {
                System.out.println(result.version
                        + "," + jarFileName
                        + "," + result.failureCount
                        + "," + result.mean(MILLISECONDS)
                        + "," + result.stdDev(MILLISECONDS)
                        + "," + result.min(MILLISECONDS)
                        + "," + result.max(MILLISECONDS));
            }
        } else {
            System.err.println("usage: <jar to load class from> <min tracer version>? <max tracer version>?");
        }
    }

    private static void getJars(int minVersion, int maxVersion) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        if (maxVersion == -1) {
            maxVersion = Integer.MAX_VALUE;
        }
        Path tracersDir = Paths.get(System.getProperty("user.dir")).resolve("tracers");
        if (!Files.exists(tracersDir)) {
            Files.createDirectory(tracersDir);
        }
        for (int version = minVersion; version <= maxVersion; ++version) {
            Path tracerLoc = tracersDir.resolve("dd-java-agent-0." + version + ".0.jar");
            if (!Files.exists(tracerLoc)) {
                System.out.println("Downloading tracer 0." + version + ".0... to " + tracerLoc);
                try {
                    var response = client.send(HttpRequest.newBuilder().GET().uri(
                            new URI("https://repo1.maven.org/maven2/com/datadoghq/dd-java-agent/0." + version + ".0/dd-java-agent-0." + version + ".0.jar")
                    ).build(), HttpResponse.BodyHandlers.ofFile(tracerLoc));
                    if (response.statusCode() == 404) {
                        Files.delete(tracerLoc);
                        System.out.println("No version 0." + version + ".0 found, will stop looking as this was probably the latest version");
                        return;
                    }
                } catch (Exception versionNotFound) {
                    System.out.println("No version 0." + version + ".0 could be downloaded, will stop looking as this was probably the latest version");
                    return;
                }
            } else {
                System.out.println("Already downloaded tracer 0." + version + ".0");
            }
        }
    }

    private static class Result {
        private final long[] durations;
        private final boolean[] failed;
        private final String tracerJarFile;
        private final String jarFile;
        private final String version;
        private final int failureCount;

        private Result(long[] durations, boolean[] passed, String tracerJarFile, String jarFile, String version) {
            this.durations = durations;
            this.failed = passed;
            this.tracerJarFile = tracerJarFile;
            this.jarFile = jarFile;
            this.version = version;
            this.failureCount = failureCount();
        }

        double mean(TimeUnit tu) {
            long sum = 0;
            for (int i = 0; i < durations.length; ++i) {
                if (!failed[i]) {
                    sum += tu.convert(durations[i], NANOSECONDS);
                }
            }
            return (double) sum / (durations.length - failureCount);
        }

        double stdDev(TimeUnit tu) {
            double mean = mean(tu);
            double squaredError = 0;
            for (int i = 0; i < durations.length; ++i) {
                if (!failed[i]) {
                    squaredError += Math.pow((tu.convert(durations[i], NANOSECONDS) - mean), 2);
                }
            }
            return Math.sqrt(squaredError / (durations.length - failureCount));
        }

        long min(TimeUnit tu) {
            long min = Long.MAX_VALUE;
            for (int i = 0; i < durations.length; ++i) {
                if (!failed[i]) {
                    min = Math.min(min, tu.convert(durations[i], NANOSECONDS));
                }
            }
            return min;
        }

        long max(TimeUnit tu) {
            long max = Long.MIN_VALUE;
            for (int i = 0; i < durations.length; ++i) {
                if (!failed[i]) {
                    max = Math.max(max, tu.convert(durations[i], NANOSECONDS));
                }
            }
            return max;
        }

        int failureCount() {
            int failureCount = 0;
            for (boolean failure : failed) {
                failureCount += failure ? 1 : 0;
            }
            return failureCount;
        }


    }

    private static String extractVersion(Path tracerJar) {
        String fileName = tracerJar.toString();
        int versionStart = fileName.indexOf("dd-java-agent-") + "dd-java-agent-".length();
        int versionEnd = fileName.indexOf(".jar");
        return fileName.substring(versionStart, versionEnd);
    }

    private static int versionToNumber(String version) {
        int start = version.indexOf('.') + 1;
        int end = version.indexOf('.', start);
        return Integer.parseInt(version, start, end, 10);
    }
}
