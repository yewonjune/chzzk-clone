package com.chzzk;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * cluster 프로필 테스트 하네스: PostgreSQL·Redis 컨테이너를 하나씩 띄우고,
 * 같은 공유 업로드 폴더를 쓰는 서버 A·B 를 기동합니다. (Docker 필요, {@code @Tag("cluster")} 테스트 전용)
 *
 * 접속 정보는 운영과 같은 환경변수 이름(DB_URL, REDIS_HOST …)으로 넘깁니다.
 * 명령행 인자도 ${DB_URL} 같은 자리표시자를 채우므로, application-cluster.properties 가 읽는 이름을 그대로 검증합니다.
 *
 * 서버가 내려가는 상황을 위해 서버를 더 띄울 수 있습니다.
 *   {@link #startServer()}  같은 JVM 의 서버. {@link Server#stop()} 은 정상 종료(스케일 인: 연결을 4001 로 닫음)
 *   {@link #startProcess()} 별도 JVM 프로세스의 서버. {@link Process#destroyForcibly()} 로 강제 종료(정리 작업 없이 죽음)
 */
final class Cluster implements AutoCloseable {

    final PostgreSQLContainer postgres;
    final GenericContainer<?> redis;
    final Path tempDir;
    final Path sharedUploads;
    final List<String> extra;
    final List<ConfigurableApplicationContext> servers = new ArrayList<>();
    final List<Process> processes = new ArrayList<>();
    final String a;
    final String b;
    private final AtomicInteger seq = new AtomicInteger();

    private Cluster(Path tempDir, String... extra) throws Exception {
        this.tempDir = tempDir;
        this.extra = List.of(extra);
        postgres = new PostgreSQLContainer("postgres:16-alpine");
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        postgres.start();
        redis.start();
        sharedUploads = Files.createDirectories(tempDir.resolve("shared-uploads"));
        // 같은 빈 DB 에 두 서버가 동시에 테이블을 만들지 않도록 차례로 기동
        a = startServer().base();
        b = startServer().base();
    }

    static Cluster start(Path tempDir, String... extra) throws Exception {
        return new Cluster(tempDir, extra);
    }

    /** 같은 JVM 에서 도는 서버 하나 */
    record Server(ConfigurableApplicationContext app, String base) {
        /** 정상 종료 (Kubernetes 가 Pod 를 내릴 때처럼 종료 신호를 받아 정리하고 내려감) */
        void stop() {
            app.close();
        }
    }

    /** 별도 JVM 프로세스로 도는 서버 하나 */
    record ProcessServer(Process process, String base) {
        /** 강제 종료: 종료 처리 없이 프로세스를 죽임 (연결은 끊기고 Redis 기록은 남음) */
        void kill() throws InterruptedException {
            process.destroyForcibly();
            process.waitFor(30, TimeUnit.SECONDS);
        }
    }

    private List<String> clusterArgs() {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=cluster",
                "--DB_URL=" + postgres.getJdbcUrl(),
                "--DB_USERNAME=" + postgres.getUsername(),
                "--DB_PASSWORD=" + postgres.getPassword(),
                "--REDIS_HOST=" + redis.getHost(),
                "--REDIS_PORT=" + redis.getMappedPort(6379),
                "--UPLOAD_DIR=" + sharedUploads.toString().replace('\\', '/')));
        args.addAll(extra);
        return args;
    }

    private Path newDataDir() throws Exception {
        return Files.createDirectories(tempDir.resolve("server-" + seq.incrementAndGet()));
    }

    /** 서버를 하나 더 기동 (같은 DB·Redis·공유 폴더) */
    Server startServer() throws Exception {
        ConfigurableApplicationContext app = ApiClient.startServer(newDataDir(), clusterArgs().toArray(String[]::new));
        servers.add(app);
        return new Server(app, ApiClient.baseUrl(app));
    }

    /** 서버를 별도 JVM 프로세스로 기동하고 요청을 받을 수 있을 때까지 기다림 */
    ProcessServer startProcess() throws Exception {
        Path dataDir = newDataDir();
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                ChzzkApplication.class.getName(),
                "--server.port=" + port,
                "--app.data-dir=" + dataDir.toString().replace('\\', '/')));
        command.addAll(clusterArgs());
        File log = dataDir.resolve("server.log").toFile();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();
        processes.add(process);
        String base = "http://localhost:" + port;
        HttpClient http = HttpClient.newHttpClient();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new AssertionError("서버 프로세스가 기동하지 못함: " + Files.readString(log.toPath()));
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health/readiness")).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) return new ProcessServer(process, base);
            } catch (java.io.IOException notYet) {
                // 아직 포트를 열지 않음
            }
            Thread.sleep(300);
        }
        process.destroyForcibly();
        throw new AssertionError("서버 프로세스가 제때 기동하지 못함: " + Files.readString(log.toPath()));
    }

    @Override
    public void close() {
        for (Process p : processes) p.destroyForcibly();
        for (ConfigurableApplicationContext s : servers) s.close();
        redis.stop();
        postgres.stop();
    }
}
