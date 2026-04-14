package com.ssafy.keeping.global.util;

// 트랜잭션 관련 util
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
public final class TxUtils {
    private TxUtils() {}

    public static void afterCommit(Runnable r){
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            r.run(); return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public int getOrder() {
                        // 실행 순서, 낮을수록 먼저 실행됨
                        return Integer.MAX_VALUE; // 제일 마지막에 실행되도록
                    }

                    @Override
                    public void afterCommit() {
                        try {
                            r.run();
                        } catch (Throwable t) {
                            log.error("afterCommit fail", t);
                        }
                    }
                }
        );
    }

}
