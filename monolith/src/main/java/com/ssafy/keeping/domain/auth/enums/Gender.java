package com.ssafy.keeping.domain.auth.enums;

public enum Gender {
    MALE, FEMALE;

    public static Gender genderFromDigit(String genderDigit) {
        if(genderDigit == null || genderDigit.length() != 1) {
            throw new IllegalStateException("1자리만 입력 가능합니다.");
        }

        return switch (genderDigit) {
            case "1", "3" -> MALE;
            case "2", "4" -> FEMALE;
            default -> throw new IllegalStateException("성별을 알 수 없습니다. genderDigit : " + genderDigit);
        };
    }
}
