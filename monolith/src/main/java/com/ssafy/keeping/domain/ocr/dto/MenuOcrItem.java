package com.ssafy.keeping.domain.ocr.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MenuOcrItem {
    private String nameKr;
    private Integer price;
    private String description;
}
