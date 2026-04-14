package com.ssafy.keeping.domain.notification.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
@Slf4j
public class EmitterRepository {

    // 모든 Emitter를 저장하는 ConcurrentHashMap
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    
    // Last-Event-ID를 통해 유실된 이벤트를 찾기 위한 이벤트 캐시
    private final Map<String, Object> eventCache = new ConcurrentHashMap<>();

    /**
     * Emitter 저장
     * @param emitterId 고유한 Emitter ID (예: "customer-1_1234567890")
     * @param sseEmitter SSE Emitter 객체
     */
    public SseEmitter save(String emitterId, SseEmitter sseEmitter) {
        emitters.put(emitterId, sseEmitter);
        log.info("Emitter 저장 완료 - ID: {}, 전체 연결 수: {}", emitterId, emitters.size());
        return sseEmitter;
    }

    /**
     * 이벤트를 캐시에 저장 (재연결 시 유실된 이벤트 전송용)
     * @param eventCacheId 이벤트 캐시 ID
     * @param event 이벤트 데이터
     */
    public void saveEventCache(String eventCacheId, Object event) {
        eventCache.put(eventCacheId, event);
    }

    /**
     * 특정 사용자의 모든 Emitter 조회
     * @param receiverType "customer" 또는 "owner"
     * @param receiverId 사용자 ID
     * @return 해당 사용자의 모든 Emitter
     */
    public Map<String, SseEmitter> findAllEmitterStartWithByReceiver(String receiverType, Long receiverId) {
        String prefix = receiverType + "-" + receiverId;
        return emitters.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }

    /**
     * 특정 사용자의 모든 이벤트 캐시 조회
     * @param receiverType "customer" 또는 "owner"
     * @param receiverId 사용자 ID
     * @return 해당 사용자의 모든 캐시된 이벤트
     */
    public Map<String, Object> findAllEventCacheStartWithByReceiver(String receiverType, Long receiverId) {
        String prefix = receiverType + "-" + receiverId;
        return eventCache.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue
                ));
    }

    /**
     * Emitter를 제거
     * @param id 제거할 Emitter ID
     */
    public void deleteById(String id) {
        emitters.remove(id);
        log.info("Emitter 제거 완료 - ID: {}, 남은 연결 수: {}", id, emitters.size());
    }

    /**
     * 특정 사용자의 활성 SSE 연결이 있는지 확인
     * @param receiverType "customer" 또는 "owner"
     * @param receiverId 사용자 ID
     * @return 활성 연결 여부
     */
    public boolean hasActiveConnection(String receiverType, Long receiverId) {

        String prefix = receiverType + "-" + receiverId;
        return emitters.entrySet().stream()
                .anyMatch(entry -> entry.getKey().startsWith(prefix));
    }
}