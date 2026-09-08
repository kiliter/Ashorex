import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// 仅包含协议白名单标识与当前任务身份，不携带路由参数或用户输入。
@immutable
class AppActivity {
  const AppActivity(this.page, this.state, [this.todoId]);
  final String page;
  final String state;
  final String? todoId;
  @override
  bool operator ==(Object other) =>
      other is AppActivity &&
      page == other.page &&
      state == other.state &&
      todoId == other.todoId;
  @override
  int get hashCode => Object.hash(page, state, todoId);
}

/// 当前可见页面快照；遮挡页面的播放器回调不能覆盖最上层催办。
class AppActivityTracker extends ChangeNotifier {
  final Map<Object, AppActivity> _visible = {};
  AppActivity get current => _visible.isEmpty
      ? const AppActivity('OTHER', 'BROWSING')
      : _visible.values.last;

  /// 同一页面重复播放回调不触发心跳；只保存当前挂载页面的最新值。
  void update(Object owner, AppActivity activity) {
    final previous = current;
    _visible[owner] = activity;
    if (current != previous) notifyListeners();
  }

  void hide(Object owner) {
    final previous = current;
    _visible.remove(owner);
    if (current != previous) notifyListeners();
  }
}

final appActivityProvider = Provider<AppActivityTracker>((ref) {
  final tracker = AppActivityTracker();
  ref.onDispose(tracker.dispose);
  return tracker;
});

/// 跟踪页面遮挡与返回；对普通弹窗显示其他页面，对催办使用独立标识。
final activityRouteObserver = RouteObserver<ModalRoute<dynamic>>();

class AppActivityScope extends ConsumerStatefulWidget {
  const AppActivityScope({
    required this.activity,
    required this.child,
    super.key,
  });
  final AppActivity activity;
  final Widget child;
  @override
  ConsumerState<AppActivityScope> createState() => _AppActivityScopeState();
}

class _AppActivityScopeState extends ConsumerState<AppActivityScope>
    with RouteAware {
  late final AppActivityTracker _tracker = ref.read(appActivityProvider);
  ModalRoute<dynamic>? _route;
  bool _visible = false;
  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    if (_route != route) {
      activityRouteObserver.unsubscribe(this);
      _route = route;
      if (route != null) activityRouteObserver.subscribe(this, route);
    }
    _visible = route?.isCurrent ?? true;
    _sync();
  }

  @override
  void didUpdateWidget(AppActivityScope oldWidget) {
    super.didUpdateWidget(oldWidget);
    _sync();
  }

  void _sync() {
    if (_visible) {
      _tracker.update(this, widget.activity);
    } else {
      _tracker.hide(this);
    }
  }

  @override
  void didPush() {
    _visible = true;
    _sync();
  }

  @override
  void didPopNext() {
    _visible = true;
    _sync();
  }

  @override
  void didPushNext() {
    _visible = false;
    _sync();
  }

  @override
  void didPop() {
    _visible = false;
    _sync();
  }

  @override
  void dispose() {
    activityRouteObserver.unsubscribe(this);
    _tracker.hide(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
