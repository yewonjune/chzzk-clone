package com.chichijik;

import static com.chichijik.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 5단계 운영: Kubernetes 가 부르는 liveness·readiness probe 주소가 로그인 없이 응답합니다.
 * (기본 프로필: Kubernetes 밖에서 bootRun 으로 띄워도 같은 주소로 확인 가능)
 */
class HealthProbeTest {

    @TempDir
    Path dataDir;

    @Test
    void 로그인_없이_liveness와_readiness가_UP() throws Exception {
        try (var app = ApiClient.startServer(dataDir)) {
            ApiClient guest = ApiClient.anonymous(ApiClient.baseUrl(app));
            for (String probe : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
                HttpResponse<String> res = guest.get(probe);
                assertThat(res.statusCode()).as(probe + " " + res.body()).isEqualTo(200);
                assertThat(json(res).get("status").asString()).isEqualTo("UP");
            }
        }
    }
}
