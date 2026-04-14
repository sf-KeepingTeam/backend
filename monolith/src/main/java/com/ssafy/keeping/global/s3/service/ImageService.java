package com.ssafy.keeping.global.s3.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private final AmazonS3 amazonS3;
    private final CustomerRepository customerRepository;
    private final OwnerRepository ownerRepository;
    private final MenuRepository menuRepository;
    private final StoreRepository storeRepository;

    @Value("${cloud.aws.s3.bucket}")
    private String bucketName;

    // 이미지 저장/업데이트
    public String updateProfileImage(String oldImgUrl, MultipartFile newImage) {

        deleteFileFromS3(oldImgUrl);

        String fileName = UUID.randomUUID() + "_" + newImage.getOriginalFilename();

        // 메타데이터 설정
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(newImage.getContentType());
        metadata.setContentLength(newImage.getSize());

        PutObjectRequest putObjectRequest = null;
        try {
            putObjectRequest = new PutObjectRequest(bucketName, fileName, newImage.getInputStream(), metadata);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.IMAGE_UPLOAD_ERROR);
        }
        amazonS3.putObject(putObjectRequest);

        return getPublicUrl(fileName);
    }

    public String uploadImage(MultipartFile image, String kindOfImage) {
        // 파일이 없을때 기본 basic 이미지 url로 반환
        if (image == null || image.isEmpty()) {

            String basicImageFileName =
                    "store".equals(kindOfImage.toLowerCase()) ?
                            "storeBasicImage.jpg"
                            : "menuBasicImage.jpg";

            return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, amazonS3.getRegionName(), basicImageFileName);
        }

        String fileName = UUID.randomUUID() + "_" + image.getOriginalFilename();

        // 메타데이터 설정
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(image.getContentType());
        metadata.setContentLength(image.getSize());

        PutObjectRequest putObjectRequest = null;
        try {
            putObjectRequest = new PutObjectRequest(bucketName, fileName, image.getInputStream(), metadata);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.IMAGE_UPLOAD_ERROR);
        }
        amazonS3.putObject(putObjectRequest);

        return getPublicUrl(fileName);
    }


    // 기존에 있던 S3 파일 삭제
    public void deleteFileFromS3(String s3Url) {
        if(!isS3Url(s3Url)) {
            log.debug("S3 URL 이 아님 : {}", s3Url);
            return;
        }

        try {
            String s3Key = extractS3Key(s3Url);
            deleteFile(s3Key);
            log.debug("S3 파일 삭제 완료 : {}, Url : {}", s3Key, s3Url);
        } catch (Exception e) {
            log.debug("S3 파일 삭제 실패 : {}", s3Url, e);
        }
    }

    public boolean isS3Url(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        return url.contains("amazonaws.com") &&
                url.contains("s3") &&
                url.contains(bucketName);
    }

    // s3key 추출
    public String extractS3Key(String s3Url) {
        if (!isS3Url(s3Url)) {
            throw new IllegalArgumentException("유효하지 않은 S3 URL입니다: " + s3Url);
        }

        try {
            // URL 패턴: https://bucket.s3.region.amazonaws.com/key
            // 또는: https://s3.region.amazonaws.com/bucket/key

            String key;
            if (s3Url.contains(".s3.")) {
                // bucket.s3.region.amazonaws.com/key 패턴
                key = s3Url.substring(s3Url.lastIndexOf(".com/") + 5);
            } else if (s3Url.contains("s3.") && s3Url.contains("amazonaws.com")) {
                // s3.region.amazonaws.com/bucket/key 패턴
                String afterAmazonaws = s3Url.substring(s3Url.indexOf("amazonaws.com/") + 14);
                int firstSlash = afterAmazonaws.indexOf('/');
                if (firstSlash > 0) {
                    key = afterAmazonaws.substring(firstSlash + 1);
                } else {
                    throw new IllegalArgumentException("S3 키를 추출할 수 없습니다: " + s3Url);
                }
            } else {
                throw new IllegalArgumentException("지원하지 않는 S3 URL 형식입니다: " + s3Url);
            }

            // URL 디코딩 (한글 파일명 등을 위해)
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8.toString());
            } catch (UnsupportedEncodingException e) {
                log.warn("URL 디코딩 실패, 원본 키 사용: {}", key);
            }

            log.debug("S3 키 추출 성공 - URL: {}, Key: {}", s3Url, key);
            return key;

        } catch (Exception e) {
            log.error("S3 키 추출 실패 - URL: {}", s3Url, e);
            throw new IllegalArgumentException("S3 키 추출 중 오류 발생: " + s3Url, e);
        }
    }


    public void deleteFile(String s3Key) {
        try {
            // 파일이 존재하는지 확인
            if (amazonS3.doesObjectExist(bucketName, s3Key)) {
                amazonS3.deleteObject(bucketName, s3Key);
                log.info("S3 파일 삭제 성공: {}", s3Key);
            } else {
                log.warn("삭제하려는 S3 파일이 존재하지 않음: {}", s3Key);
            }
        } catch (Exception e) {
            log.error("S3 파일 삭제 실패: {}", s3Key, e);
            throw new RuntimeException("S3 파일 삭제 중 오류 발생", e);
        }
    }

    // content type 추정
    private String getContentTypeFromFileName(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();

        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            default:
                return "image/jpeg"; // 기본값
        }
    }


    // fileName 추출
    private String getFileNameFromUrl(String imgUrl) {
        String fileName = imgUrl.substring(imgUrl.lastIndexOf('/') + 1);

        // 쿼리 파라미터가 있다면 제거
        int queryIndex = fileName.indexOf('?');
        if (queryIndex != -1) {
            fileName = fileName.substring(0, queryIndex);
        }

        // 확장자가 없다면 기본값 설정
        if (!fileName.contains(".")) {
            fileName += ".jpg";
        }

        return fileName;
    }

    private String getPublicUrl(String fileName) {
        return String.format("https://%s.s3.%s.amazonaws.com/%s", bucketName, amazonS3.getRegionName(), fileName);
    }





}
