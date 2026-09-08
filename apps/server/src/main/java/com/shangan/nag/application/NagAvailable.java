package com.shangan.nag.application;

/** 事务内发布的轻量通知；提交成功后才发往当前用户的连接。 */
public record NagAvailable(String userId, String nagId) {}
