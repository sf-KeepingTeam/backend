package com.ssafy.keeping.global.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;

@Component
@Slf4j
public class FirebaseConfig {

    @Value("${fcm.service-account-file:}")
    private Resource serviceAccountResource;
    
    @Value("${fcm.project-id:}")
    private String projectId;

    @PostConstruct
    public void initialize() {
        try {
            if (FirebaseApp.getApps().isEmpty()) {
                log.info("Firebase 초기화 시작");
                
                if (serviceAccountResource == null || !serviceAccountResource.exists()) {
                    log.warn("FCM 서비스 계정 파일을 찾을 수 없습니다. FCM 기능이 비활성화됩니다.");
                    return;
                }

                if (projectId == null || projectId.trim().isEmpty()) {
                    log.warn("FCM 프로젝트 ID가 설정되지 않았습니다. FCM 기능이 비활성화됩니다.");
                    return;
                }

                try (InputStream in = serviceAccountResource.getInputStream()) {
                    FirebaseOptions options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(in))
                            .setProjectId(projectId)
                            .build();

                    FirebaseApp.initializeApp(options);
                    log.info("Firebase 초기화 완료 - 프로젝트: {}", projectId);
                }
            } else {
                log.info("Firebase 이미 초기화됨");
            }
        } catch (IOException e) {
            log.error("Firebase 초기화 실패", e);
            log.warn("FCM 없이 애플리케이션 시작");
        } catch (Exception e) {
            log.error("Firebase 초기화 중 예상치 못한 오류", e);
            log.warn("FCM 없이 애플리케이션 시작");
        }
    }
}