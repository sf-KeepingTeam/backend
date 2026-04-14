package com.ssafy.keeping.domain.ocr.dto;

import lombok.*;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MenuOcrResponse {
    private List<MenuOcrItem> items;
}