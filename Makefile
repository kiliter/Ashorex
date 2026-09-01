.PHONY: format server-test ios-test verify

# 优先使用项目锁定的 FVM SDK；CI 已固定 Flutter 版本时可直接使用系统命令。
IOS_FLUTTER := $(if $(wildcard apps/ios/.fvm/flutter_sdk/bin/flutter),.fvm/flutter_sdk/bin/flutter,$(if $(shell command -v fvm 2>/dev/null),fvm flutter,flutter))
IOS_DART := $(if $(wildcard apps/ios/.fvm/flutter_sdk/bin/dart),.fvm/flutter_sdk/bin/dart,$(if $(shell command -v fvm 2>/dev/null),fvm dart,dart))

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

# 执行仓库级完整验证。
verify: server-test ios-test
