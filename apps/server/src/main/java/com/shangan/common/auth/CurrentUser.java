package com.shangan.common.auth;

/** 当前 Bearer Token 对应的用户身份，只包含服务端已验证的声明。 */
public record CurrentUser(String userId, String username, String role, String timezone) {}
