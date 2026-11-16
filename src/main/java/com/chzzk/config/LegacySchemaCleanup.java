package com.chzzk.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 1·2단계 DB를 그대로 쓰는 경우를 위한 정리.
 * 3단계에서 엔티티에서 빠진 컬럼(자유 입력 스트리머 이름, 송출 키 해시)은 Hibernate 의 ddl-auto=update 가
 * 지우지 않으므로, NOT NULL 제약만 풀어 새 행을 넣을 수 있게 합니다. (데이터는 그대로 둡니다.)
 */
@Component
public class LegacySchemaCleanup {

    private static final Logger log = LoggerFactory.getLogger(LegacySchemaCleanup.class);

    private static final List<String[]> RETIRED_COLUMNS = List.of(
            new String[] {"REPLAYS", "STREAMER"},
            new String[] {"LIVE_BROADCASTS", "STREAMER"},
            new String[] {"LIVE_BROADCASTS", "STREAM_KEY_HASH"});

    private final JdbcTemplate jdbc;

    public LegacySchemaCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void relaxRetiredColumns() {
        for (String[] tc : RETIRED_COLUMNS) {
            Integer notNull = jdbc.queryForObject(
                    "select count(*) from information_schema.columns "
                            + "where table_schema = 'PUBLIC' and table_name = ? and column_name = ? and is_nullable = 'NO'",
                    Integer.class, tc[0], tc[1]);
            if (notNull != null && notNull > 0) {
                jdbc.execute("alter table " + tc[0] + " alter column " + tc[1] + " set null");
                log.info("이전 단계 컬럼 {}.{} 의 NOT NULL 제약을 해제했습니다.", tc[0], tc[1]);
            }
        }
    }
}
