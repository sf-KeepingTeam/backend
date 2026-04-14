package com.ssafy.keeping.global.response;


public class ExternalApiResponse<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;
    private ExternalApiErrorResponse error;
}
