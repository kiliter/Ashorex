-- 课程来源搜索范围由管理员显式绑定；JSON 只保存媒体库安全元数据，不含主机、密钥或路径。
ALTER TABLE runtime_integration_settings
ADD COLUMN emby_libraries_json TEXT NOT NULL DEFAULT '[]'
CHECK (json_valid(emby_libraries_json));
