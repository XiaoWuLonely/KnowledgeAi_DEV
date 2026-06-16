package com.knowflow.backend.auth.avatar;

import org.springframework.web.multipart.MultipartFile;

/**
 * @Desc 头像存储服务接口
 */
public interface AvatarStorageService {

    /**
     * @param bytes
     * @param contentType
     * @Desc 头像对象记录 阿里云oss接口
     */
    record AvatarObject(byte[] bytes, String contentType) {
    }

    //上传
    String upload(Long userId, MultipartFile file);

    //删除
    void delete(String objectKey);

    //读取
    AvatarObject read(String objectKey);

    //是否配置
    boolean isConfigured();
}
