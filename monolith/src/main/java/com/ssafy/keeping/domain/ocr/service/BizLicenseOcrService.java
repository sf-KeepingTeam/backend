package com.ssafy.keeping.domain.ocr.service;

import com.ssafy.keeping.domain.ocr.dto.BizLicenseOcrResponse;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BizLicenseOcrService {

    @Value("${clova.ocr.url}")
    private String clovaUrl;

    @Value("${clova.ocr.secret}")
    private String clovaSecret;

    @Value("${clova.ocr.template-ids}")
    private String templateIdsProp;

    private final RestTemplate restTemplate = new RestTemplate();

    public BizLicenseOcrResponse recognize(MultipartFile file) {
        try {
            // 요청 헤더
            // 1) 멀티파트 구성: message(JSON) + file(바이너리)
            HttpHeaders rootHeaders = new HttpHeaders();
            rootHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
            rootHeaders.set("X-OCR-SECRET", clovaSecret);

            // 요청 바디
            MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();

            // message 파트(JSON) — 템플릿 OCR: templateIds 포함!!!
            HttpHeaders msgHeaders = new HttpHeaders();
            msgHeaders.setContentType(MediaType.APPLICATION_JSON);
            String messageJson = buildMessageJson(file.getOriginalFilename(), file.getContentType());
            HttpEntity<String> messagePart = new HttpEntity<>(messageJson, msgHeaders);
            multipart.add("message", messagePart);

            // file 파트
            ByteArrayResource fileResource = new ByteArrayResource(file.getBytes()) {
                @Override public String getFilename() {
                    return guessFilename(file.getOriginalFilename(), file.getContentType());
                }
            };
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            fileHeaders.setContentDisposition(
                    ContentDisposition.builder("form-data")
                            .name("file")
                            .filename(guessFilename(file.getOriginalFilename(), file.getContentType()))
                            .build()
            );
            HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(fileResource, fileHeaders);
            multipart.add("file", filePart);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(multipart, rootHeaders);

            // 2) 외부 호출
            ResponseEntity<Map> resp = restTemplate.exchange(clovaUrl, HttpMethod.POST, requestEntity, Map.class);
            if (resp == null || resp.getBody() == null || !resp.getStatusCode().is2xxSuccessful()) {
                if (resp != null && resp.getStatusCode().is4xxClientError()) {
                    throw new CustomException(ErrorCode.OCR_UPSTREAM_BAD_REQUEST);
                }
                throw new CustomException(ErrorCode.OCR_UPSTREAM_ERROR);
            }

            // 3) 응답 파싱/정규화
            return mapClovaResponse(resp.getBody());

        } catch (HttpStatusCodeException e) {
            log.error("CLOVA Template OCR 실패 status={} body={}", e.getStatusCode(), safe(e.getResponseBodyAsString()));
            if (e.getStatusCode().is4xxClientError()) {
                throw new CustomException(ErrorCode.OCR_UPSTREAM_BAD_REQUEST);
            }
            // 그 외는 외부 API 장애로 간주
            throw new CustomException(ErrorCode.OCR_UPSTREAM_ERROR);
        } catch (Exception e) {
            log.error("OCR 처리 중 오류", e);
            throw new CustomException(ErrorCode.OCR_UPSTREAM_ERROR);
        }
    }

    /** Template OCR: message JSON(버전, 타임스탬프, images, templateIds 포함) */
    private String buildMessageJson(String originalName, String contentType) {
        String name = (originalName == null || originalName.isBlank()) ? "upload" : originalName;
        String format = guessFormat(contentType, name);

        Map<String, Object> imageObj = new HashMap<>();
        imageObj.put("name", name);
        imageObj.put("format", format);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", "V2");
        root.put("requestId", UUID.randomUUID().toString());
        root.put("timestamp", Instant.now().toEpochMilli());
        root.put("images", List.of(imageObj));

        // ⭐ Template OCR 핵심: templateIds (도메인/템플릿 빌더에서 발급된 ID들)
        List<Integer> templateIds = parseTemplateIdsAsInt(templateIdsProp);
        if (templateIds.isEmpty()) throw new IllegalStateException("templateIds 필요");
        root.put("templateIds", templateIds); // [39076] ← 숫자!

        // 필요 시 언어/옵션 추가 가능 (문서 옵션 범위 확인 후)
        return toJson(root);
    }

    private List<Integer> parseTemplateIdsAsInt(String prop) {
        if (prop == null || prop.isBlank()) return List.of();
        String[] arr = prop.split(",");
        List<Integer> out = new ArrayList<>();
        for (String s : arr) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(Integer.parseInt(t)); // ← int 변환
        }
        return out;
    }

    private String guessFilename(String originalName, String contentType) {
        if (originalName != null && !originalName.isBlank()) return originalName;
        return "upload." + guessFormat(contentType, "upload");
    }

    private String guessFormat(String contentType, String filename) {
        if (contentType != null) {
            if (contentType.equals(MimeTypeUtils.IMAGE_JPEG_VALUE)) return "jpg";
            if (contentType.equals(MimeTypeUtils.IMAGE_PNG_VALUE))  return "png";
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpeg") || lower.endsWith(".jpg")) return "jpg";
        if (lower.endsWith(".png")) return "png";
        return "jpg";
    }

    @SuppressWarnings("unchecked")
    private BizLicenseOcrResponse mapClovaResponse(Map<String, Object> body) {
        List<Map<String, Object>> images = (List<Map<String, Object>>) body.getOrDefault("images", List.of());
        if (images.isEmpty()) throw new IllegalStateException("OCR 결과에 images가 없습니다.");
        Map<String, Object> first = images.get(0);
        List<Map<String, Object>> fields = (List<Map<String, Object>>) first.getOrDefault("fields", List.of());

        Map<String, String> byName = new HashMap<>();
        List<Double> confidences = new ArrayList<>();
        for (Map<String, Object> f : fields) {
            String name = String.valueOf(f.getOrDefault("name", "")).trim();
            String text = String.valueOf(f.getOrDefault("inferText", "")).trim();
            if (!name.isEmpty() && !text.isEmpty()) byName.put(name, text);

            Object c = f.containsKey("inferConfidence") ? f.get("inferConfidence") : f.get("confidence");
            if (c instanceof Number) confidences.add(((Number) c).doubleValue());
        }

        // 템플릿 라벨 정확 매칭(한글) + 보조 키(영문/대체)
        String rawBizNo   = firstNonNullExact(byName, "등록번호", "사업자등록번호", "bizNumber", "businessNumber");
        String rawFull    = firstNonNullExact(byName, "성명", "대표자", "fullName", "name");
        String rawOpen    = firstNonNullExact(byName, "개업연월일", "openDate", "startDate");

        String bizNo    = normalizeBizNo(rawBizNo);
        String fullName = nullOrTrim(rawFull);
        String openDate = normalizeDate(rawOpen);

        Double avgConf  = confidences.isEmpty() ? null
                : Math.round(confidences.stream().mapToDouble(Double::doubleValue).average().orElse(0.0) * 100.0) / 100.0;

        return BizLicenseOcrResponse.builder()
                .bizNumber(bizNo)
                .fullName(fullName)
                .openDate(openDate)
                .confidence(avgConf)
                .build();
    }

    private static String firstNonNullExact(Map<String, String> byName, String... keys) {
        for (String k : keys) {
            if (byName.containsKey(k)) {
                String v = byName.get(k);
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }

    private static String nullOrTrim(String s) { return s == null ? null : s.trim(); }

    /** 10자리면 3-2-5 하이픈 포맷으로 정규화 */
    private static String normalizeBizNo(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("\\D", "");
        if (digits.length() == 10) {
            return digits.replaceFirst("^(\\d{3})(\\d{2})(\\d{5})$", "$1-$2-$3");
        }
        return s.trim();
    }

    /** 다양한 표기를 YYYY-MM-DD로 정규화 */
    private static String normalizeDate(String s) {
        if (s == null) return null;
        String t = s.trim()
                .replace(".", "-")
                .replace("/", "-")
                .replace("년", "-")
                .replace("월", "-")
                .replace("일", "");
        String digits = t.replaceAll("[^0-9-]", "");
        if (digits.matches("^\\d{8}$")) {
            return digits.replaceFirst("^(\\d{4})(\\d{2})(\\d{2})$", "$1-$2-$3");
        }
        if (digits.matches("^\\d{4}-\\d{1,2}-\\d{1,2}$")) {
            String[] p = digits.split("-");
            return String.format("%s-%02d-%02d", p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        return digits;
    }

    // 최소 JSON 직렬화(외부 라이브러리 없이)
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':');
            sb.append(toJsonValue(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }
    @SuppressWarnings("unchecked")
    private static String toJsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof String) return "\"" + escape((String) v) + "\"";
        if (v instanceof Map) return toJson((Map<String, Object>) v);
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                sb.append(toJsonValue(o));
            }
            sb.append(']');
            return sb.toString();
        }
        return "\"" + escape(v.toString()) + "\"";
    }
    private static String escape(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"")
                .replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");
    }
    private static String safe(String s) { return s == null ? "" : s; }
}