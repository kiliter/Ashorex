.PHONY: format server-test ios-test operations-test verify

# 优先使用项目锁定的 FVM SDK；未安装本地 SDK 时再回退到系统 fvm。
IOS_FLUTTER := $(if $(wildcard apps/ios/.fvm/flutter_sdk/bin/flutter),.fvm/flutter_sdk/bin/flutter,fvm flutter)
IOS_DART := $(if $(wildcard apps/ios/.fvm/flutter_sdk/bin/dart),.fvm/flutter_sdk/bin/dart,fvm dart)

# 格式化服务端 Java 代码和 iOS Dart 代码。
format:
	cd apps/server && ./mvnw spotless:apply
	cd apps/ios && $(IOS_DART) format lib test integration_test

# 执行服务端编译、测试和验证。
server-test:
	cd apps/server && ./mvnw verify

# 获取 Flutter 依赖，并执行格式检查、静态分析和自动化测试。
ios-test:
	cd apps/ios && $(IOS_FLUTTER) pub get
	cd apps/ios && $(IOS_DART) format --output=none --set-exit-if-changed lib test integration_test
	cd apps/ios && $(IOS_FLUTTER) analyze
	cd apps/ios && $(IOS_FLUTTER) test

# 验证 SQLite 在线备份、完整性检查和独立恢复。
operations-test:
	infra/scripts/backup_restore_smoke_test.sh

# 执行仓库级完整验证。
verify: server-test ios-test operations-test
