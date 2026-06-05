package com.knowflow.backend.auth.avatar;

import org.springframework.web.multipart.MultipartFile;

/**
 * Stores and reads user avatar objects.
 *
 * <p>The database keeps only object keys. Implementations are responsible for upload,
 * best-effort delete and reading the stored bytes without exposing storage secrets to the frontend.</p>
 */
public interface AvatarStorageService {
    record AvatarObject(byte[] bytes, String contentType) {
    }

    String upload(Long userId, MultipartFile file);

    void delete(String objectKey);

    AvatarObject read(String objectKey);

    boolean isConfigured();
}
