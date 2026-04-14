package com.ssafy.keeping.domain.ocr.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.ocr.dto.MenuOcrItem;
import com.ssafy.keeping.domain.ocr.dto.MenuOcrResponse;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class MenuOcrService {

    private final ObjectMapper om;

    @Value("${openai.api.key}")
    private String openAiApiKey;

    @Value("${openai.api.url:https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions}")
    private String openAiUrl;

    public MenuOcrResponse recognize(MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String b64 = Base64.getEncoder().encodeToString(bytes);
            String dataUrl = "data:" + Optional.ofNullable(file.getContentType()).orElse("image/jpeg")
                    + ";base64," + b64;

            // 프롬프트(LLM에게 JSON만 내도록 강제)
            String system = """
                You extract Korean menu items from an image (OCR + light layout reasoning).
                Return ONLY valid JSON. No prose.
                Rules:
                - Output price as KRW integer (strip commas/₩/원).
                - If price is missing/ambiguous, drop the item.
                - description is optional; brief (≤40 chars) if present.
                
                JSON schema:
                { "items":[ { "nameKr":"string", "price":0, "description":"string|null" } ] }
                """;
            String user = """
                메뉴판 이미지가 첨부됩니다.
                1) 메뉴명(nameKr)과 가격(price), 설명(description|optional)만 추출하세요.
                2) 중복 항목은 하나로 합치고 가장 신뢰되는 가격만 남기세요.
                3) 범위/모호 가격은 제외하세요.
                """;

            // OpenAI 호출 바디
            Map<String, Object> body = Map.of(
                    "model", "gpt-4o",
                    "temperature", 0,
                    "response_format", Map.of("type", "json_object"),
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", List.of(
                                    Map.of("type", "text", "text", user),
                                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                            ))
                    )
            );

            // HTTP 호출
            WebClient client = WebClient.builder()
                    .baseUrl(openAiUrl)
                    .defaultHeader("Authorization", "Bearer " + openAiApiKey)
                    .build();

            String raw = client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info(raw);

            // 응답 파싱 (choices[0].message.content 가 JSON 문자열)
            JsonNode root = om.readTree(raw);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new CustomException(ErrorCode.OCR_EXTERNAL_API_FAILED);
            }

            // JSON → DTO
            JsonNode json = om.readTree(content);

            List<MenuOcrItem> items = new ArrayList<>();
            if (json.has("items") && json.get("items").isArray()) {
                for (JsonNode n : json.get("items")) {
                    String name = optText(n, "nameKr");
                    Integer price = optInt(n, "price");
                    String desc = n.hasNonNull("description") ? n.get("description").asText() : null;

                    if (name != null && !name.isBlank() && price != null && price >= 0 && price <= 2_000_000) {
                        items.add(MenuOcrItem.builder()
                                .nameKr(name.trim())
                                .price(price)
                                .description((desc == null || desc.isBlank()) ? null : desc.trim())
                                .build());
                    }
                }
            }
            return MenuOcrResponse.builder()
                    .items(dedupeByNameLowestPrice(items))
                    .build();

        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            log.error("Menu OCR failed", e);
            throw new CustomException(ErrorCode.OCR_EXTERNAL_API_FAILED);
        }
    }

    private static String optText(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.get(f).asText() : null;
    }

    private static Integer optInt(JsonNode n, String f) {
        if (!n.hasNonNull(f)) return null;
        try { return Integer.parseInt(n.get(f).asText().replaceAll("[^0-9]", "")); }
        catch (Exception e) { return null; }
    }

    private static List<MenuOcrItem> dedupeByNameLowestPrice(List<MenuOcrItem> src) {
        Map<String, MenuOcrItem> map = new LinkedHashMap<>();
        for (MenuOcrItem it : src) {
            String key = it.getNameKr().replaceAll("\\s+", "");
            MenuOcrItem prev = map.get(key);
            if (prev == null || it.getPrice() < prev.getPrice()) {
                map.put(key, it);
            }
        }
        return new ArrayList<>(map.values());
    }

}
