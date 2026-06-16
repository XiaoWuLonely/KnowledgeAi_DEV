package com.knowflow.backend.auth.dto.request;

/**
 * @Desc 更新用户个人信息请求体
 */
public class UpdateCurrentUserRequest {
    private String username;
    private String email;
    private String phone;
    private boolean usernamePresent;
    private boolean emailPresent;
    private boolean phonePresent;

    public String getUsername() {
        return username;
    }

    /**
     * @Desc 设置用户用户名
     */
    public void setUsername(String username) {
        this.username = username;
        this.usernamePresent = true;
    }

    /**
     * @return
     * @Desc 获取用户邮箱
     */
    public String getEmail() {
        return email;
    }

    /**
     * @param email
     * @Desc 设置用户邮箱
     */
    public void setEmail(String email) {
        this.email = email;
        this.emailPresent = true;
    }

    /**
     * @Desc 检查用户用户名是否已设置
     * @return
     */
    public boolean isUsernamePresent() {
        return usernamePresent;
    }

    public boolean isEmailPresent() {
        return emailPresent;
    }

    public String getPhone() {
        return phone;
    }

    /**
     * @Desc 设置用户手机号
     * @param phone
     */
    public void setPhone(String phone) {
        this.phone = phone;
        this.phonePresent = true;
    }

    public boolean isPhonePresent() {
        return phonePresent;
    }
}
