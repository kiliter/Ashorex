-- 管理员可使用固定短间隔快速验收服务端驱动的验活流程；普通用户继续使用监督等级随机区间。
ALTER TABLE users ADD COLUMN alive_check_interval_seconds INTEGER
    CHECK (alive_check_interval_seconds IS NULL
        OR alive_check_interval_seconds BETWEEN 1 AND 3600);
