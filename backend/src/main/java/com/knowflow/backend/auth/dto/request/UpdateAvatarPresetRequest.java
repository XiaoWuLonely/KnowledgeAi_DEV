package com.knowflow.backend.auth.dto.request;

import lombok.Data;

/**
 * @Desc 更新用户头像预设请求体
 */
@Data
public class UpdateAvatarPresetRequest {
    private String avatarPresetId;
}
