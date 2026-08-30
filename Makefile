.PHONY: format server-test ios-test operations-test verify

# 格式化服务端 Java 代码和 iOS Dart 代码。
format:
	cd apps/server && ./mvnw spotless:apply
	cd apps/ios && fvm dart format lib test integration_test

# 执行服务端编译、测试和验证。
server-test:
	cd apps/server && ./mvnw verify

# 获取 Flutter 依赖，并执行格式检查、静态分析和自动化测试。
ios-test:
	cd apps/ios && fvm flutter pub get
	cd apps/ios && fvm dart format --output=none --set-exit-if-changed lib test integration_test
	cd apps/ios && fvm flutter analyze
	cd apps/ios && fvm flutter test

# 验证 SQLite 在线备份、完整性检查和独立恢复。
operations-test:
	infra/scripts/backup_restore_smoke_test.sh

# 执行仓库级完整验证。
verify: server-test ios-test operations-test
