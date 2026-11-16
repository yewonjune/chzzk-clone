package com.chichijik;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * cluster 프로필 테스트 하네스: PostgreSQL·Redis 컨테이너를 하나씩 띄우고,
 * 같은 공유 업로드 폴더를 쓰는 서버 A·B 를 기동합니다. (Docker 필요, {@code @Tag("cluster")} 테스트 전용)
 *
 * 접속 정보는 운영과 같은 환경변수 이름(DB_URL, REDIS_HOST …)으로 넘깁니다.
 * 명령행 인자도 ${DB_URL} 같은 자리표시자를 채우므로, application-cluster.properties 가 읽는 이름을 그대로 검증합니다.
 */
final class Cluster implements AutoCloseable {

    final PostgreSQLContainer postgres;
    final GenericContainer<?> redis;
    final Path sharedUploads;
    final List<ConfigurableApplicationContext> servers = new ArrayList<>();
    final String a;
    final String b;

    private Cluster(Path tempDir, String... extra) throws Exception {
        postgres = new PostgreSQLContainer("postgres:16-alpine");
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        postgres.start();
        redis.start();
        sharedUploads = Files.createDirectories(tempDir.resolve("shared-uploads"));
        // 같은 빈 DB 에 두 서버가 동시에 테이블을 만들지 않도록 차례로 기동
        a = ApiClient.baseUrl(startServer(tempDir.resolve("a"), extra));
        b = ApiClient.baseUrl(startServer(tempDir.resolve("b"), extra));
    }

    static Cluster start(Path tempDir, String... extra) throws Exception {
        return new Cluster(tempDir, extra);
    }

    private ConfigurableApplicationContext startServer(Path dataDir, String... extra) throws Exception {
        Files.createDirectories(dataDir);
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=cluster",
                "--DB_URL=" + postgres.getJdbcUrl(),
                "--DB_USERNAME=" + postgres.getUsername(),
                "--DB_PASSWORD=" + postgres.getPassword(),
                "--REDIS_HOST=" + redis.getHost(),
                "--REDIS_PORT=" + redis.getMappedPort(6379),
                "--UPLOAD_DIR=" + sharedUploads.toString().replace('\\', '/')));
        args.addAll(List.of(extra));
        ConfigurableApplicationContext app = ApiClient.startServer(dataDir, args.toArray(String[]::new));
        servers.add(app);
        return app;
    }

    @Override
    public void close() {
        for (ConfigurableApplicationContext s : servers) s.close();
        redis.stop();
        postgres.stop();
    }
}
