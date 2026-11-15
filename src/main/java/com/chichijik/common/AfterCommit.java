package com.chichijik.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * DB에 확정된 사실만 밖으로 알리기 위해, 트랜잭션이 커밋된 뒤에 실행합니다.
 * (WebSocket 알림, 파일 삭제 등 되돌릴 수 없는 부수 효과용) 트랜잭션 밖이면 즉시 실행합니다.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
