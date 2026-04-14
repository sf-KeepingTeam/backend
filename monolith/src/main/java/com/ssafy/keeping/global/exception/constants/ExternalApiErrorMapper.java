package com.ssafy.keeping.global.exception.constants;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class ExternalApiErrorMapper {

    private static final Map<String, ErrorCode> ERROR_CODE_MAP;

    static {
        Map<String, ErrorCode> map = new HashMap<>();
        map.put("H1000", ErrorCode.INVALID_HEADER);
        map.put("H1001", ErrorCode.INVALID_API_ITEM);
        map.put("H1002", ErrorCode.INVALID_TRANSMISSION_DATE);
        map.put("H1003", ErrorCode.INVALID_TRANSMISSION_TIME);
        map.put("H1004", ErrorCode.INVALID_INSTITUTION_CODE);
        map.put("H1005", ErrorCode.INVALID_FINTECHAPP_NUMBER);
        map.put("H1006", ErrorCode.INVALID_SERVICE_CODE);
        map.put("H1007", ErrorCode.INVALID_INSTITUTION_TRANSACTION_NUMBER);
        map.put("H1008", ErrorCode.INVALID_API_KEY);
        map.put("H1009", ErrorCode.INVALID_USER_KEY);
        map.put("H1010", ErrorCode.INVALID_INSTITUTION_TRANSACTION_NUMBER_DUPLICATE);
        map.put("A1003", ErrorCode.INVALID_ACCOUNT_NUMBER);
        map.put("E4002", ErrorCode.USER_KEY_ALREADY_EXISTS);

        ERROR_CODE_MAP = Collections.unmodifiableMap(map);
    }

    public ErrorCode mapToInternalErrorCode(String externalCode) {
        return ERROR_CODE_MAP.getOrDefault(externalCode, ErrorCode.EXTERNAL_API_ERROR);
    }
}
