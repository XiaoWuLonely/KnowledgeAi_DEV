package com.knowflow.backend.auth.dto.response;

import lombok.Data;

@Data
public class UserResponse {
    public enum AvatarSource {
        UPLOAD,
        PRESET,
        NONE
    }

    private Long id;
    private String username;
    private String role;
    private String email;
    private String phone;
    /**
     * @Desc 用户头像URL
     */
    private String avatarUrl;
    private boolean avatarConfigured;
    /**
     * @Desc 用户头像存储是否已配置
     */
    private boolean avatarStorageConfigured;
    private AvatarSource avatarSource;
    private String avatarPresetId;

    /**
     * @Desc 构造函数
     * @param id
     * @param username
     * @param role
     * @param email
     */
    public UserResponse(Long id, String username, String role, String email) {
        this(id, username, role, email, null, null, false, false, AvatarSource.NONE, null);
    }

    /**
     * @Desc 构造函数
     * @param id
     * @param username
     * @param role
     * @param email
     * @param avatarUrl
     * @param avatarConfigured
     */
    public UserResponse(Long id, String username, String role, String email, String avatarUrl, boolean avatarConfigured) {
        this(id, username, role, email, null, avatarUrl, avatarConfigured, false,
                avatarConfigured ? AvatarSource.UPLOAD : AvatarSource.NONE,
                null);
    }

    /**
     * @Desc 构造函数
     * @param id
     * @param username
     * @param role
     * @param email
     * @param phone
     * @param avatarUrl
     * @param avatarConfigured
     * @param avatarStorageConfigured
     * @param avatarSource
     * @param avatarPresetId
     */
    public UserResponse(
            Long id,
            String username,
            String role,
            String email,
            String phone,
            String avatarUrl,
            boolean avatarConfigured,
            boolean avatarStorageConfigured,
            AvatarSource avatarSource,
            String avatarPresetId
    ) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.email = email;
        this.phone = phone;
        this.avatarUrl = avatarUrl;
        this.avatarConfigured = avatarConfigured;
        this.avatarStorageConfigured = avatarStorageConfigured;
        this.avatarSource = avatarSource;
        this.avatarPresetId = avatarPresetId;
    }
}
