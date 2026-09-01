import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate, FlutterImplicitEngineDelegate,
  UIImagePickerControllerDelegate, UINavigationControllerDelegate
{
  private var examPhotoResult: FlutterResult?

  /// 完成 iOS 应用启动，并将后续生命周期交给 Flutter。
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }

  /// Flutter 隐式引擎就绪后注册项目依赖的原生插件。
  func didInitializeImplicitFlutterEngine(_ engineBridge: FlutterImplicitEngineBridge) {
    GeneratedPluginRegistrant.register(with: engineBridge.pluginRegistry)
    guard let registrar = engineBridge.pluginRegistry.registrar(forPlugin: "ExamPhotoPicker") else {
      return
    }
    let channel = FlutterMethodChannel(
      name: "com.shangan/exam-photo-picker",
      binaryMessenger: registrar.messenger()
    )
    channel.setMethodCallHandler { [weak self] call, result in
      guard call.method == "pickImage" else {
        result(FlutterMethodNotImplemented)
        return
      }
      let arguments = call.arguments as? [String: Any]
      self?.presentExamPhotoPicker(source: arguments?["source"] as? String, result: result)
    }
  }

  /// 使用 iOS 原生图片选择器，避免为单一 iOS 功能引入跨平台依赖。
  private func presentExamPhotoPicker(source: String?, result: @escaping FlutterResult) {
    guard examPhotoResult == nil else {
      result(FlutterError(code: "PICKER_BUSY", message: "图片选择器正在使用中", details: nil))
      return
    }
    let sourceType: UIImagePickerController.SourceType = source == "camera" ? .camera : .photoLibrary
    guard UIImagePickerController.isSourceTypeAvailable(sourceType) else {
      result(FlutterError(code: "SOURCE_UNAVAILABLE", message: "当前设备不支持该图片来源", details: nil))
      return
    }
    guard let presenter = activeViewController() else {
      result(FlutterError(code: "PRESENTER_UNAVAILABLE", message: "暂时无法打开图片选择器", details: nil))
      return
    }
    examPhotoResult = result
    let picker = UIImagePickerController()
    picker.delegate = self
    picker.sourceType = sourceType
    picker.mediaTypes = ["public.image"]
    picker.allowsEditing = false
    presenter.present(picker, animated: true)
  }

  /// 找到当前 Scene 中最上层控制器，确保选择器从可见页面弹出。
  private func activeViewController() -> UIViewController? {
    let root = UIApplication.shared.connectedScenes
      .compactMap { $0 as? UIWindowScene }
      .flatMap { $0.windows }
      .first { $0.isKeyWindow }?
      .rootViewController
    var current = root
    while let presented = current?.presentedViewController {
      current = presented
    }
    return current
  }

  func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
    let result = examPhotoResult
    examPhotoResult = nil
    picker.dismiss(animated: true) {
      result?(nil)
    }
  }

  /// 原生端统一转成 JPEG，服务端再校验文件签名、大小和归属关系。
  func imagePickerController(
    _ picker: UIImagePickerController,
    didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
  ) {
    let result = examPhotoResult
    examPhotoResult = nil
    guard let image = info[.originalImage] as? UIImage,
      let bytes = image.jpegData(compressionQuality: 0.9)
    else {
      picker.dismiss(animated: true) {
        result?(FlutterError(code: "IMAGE_INVALID", message: "无法读取所选图片", details: nil))
      }
      return
    }
    let filename = "试卷-\(Int(Date().timeIntervalSince1970)).jpg"
    picker.dismiss(animated: true) {
      result?([
        "filename": filename,
        "bytes": FlutterStandardTypedData(bytes: bytes),
      ])
    }
  }
}
