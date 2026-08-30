import Flutter
import UIKit
import XCTest

class RunnerTests: XCTestCase {

  /// 原生测试目标必须挂载到上岸 Runner，而不是其他宿主应用。
  func testRunnerBundleIdentifier() {
    XCTAssertEqual(Bundle.main.bundleIdentifier, "com.shangan.ios")
  }

}
