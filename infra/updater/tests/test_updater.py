"""升级事务窄测试：使用内存 Docker 替身，不启动数据库。"""
import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('updater', pathlib.Path(__file__).parents[1] / 'updater.py')
u = importlib.util.module_from_spec(spec)
spec.loader.exec_module(u)


class Engine:
    """记录外部动作，精确注入启动、拉取和备份故障。"""
    def __init__(self, failure=None):
        self.events = []
        self.failure = failure

    def current(self):
        return {'Id': 'old', 'Name': '/shangan-server', 'Image': 'sha256:old',
                'Config': {'Labels': {'org.opencontainers.image.version': '2.2.0'}}}

    def pull(self, release):
        self.events.append('pull')
        if self.failure == 'pull': raise u.UpgradeError('拉取失败')

    def stop(self, container): self.events.append('stop')
    def backup(self, container, operation):
        self.events.append('backup')
        if self.failure == 'backup': raise u.UpgradeError('备份失败')
    def replace(self, container, release): self.events.append('replace')
    def verify(self, container, release=None):
        self.events.append('verify-new' if release else 'verify-old')
        if release and self.failure == 'verify': raise u.UpgradeError('启动失败')
    def restore(self, container, operation): self.events.append('restore')
    def restart_old(self, container): self.events.append('restart-old')
    def cancel_helper(self, operation): pass
    def finish(self, container): self.events.append('finish')


class UpgradeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.release = {'version': '2.3.0', 'image': u.IMAGE + '@sha256:' + 'a'*64,
                        'protocol': 1, 'automatic': True, 'notes': '修复', 'revision': 'b'*40}

    def run_upgrade(self, failure=None):
        engine = Engine(failure)
        worker = u.Updater(self.root, engine)
        worker.apply(self.release)
        return engine, worker

    def test_success_opens_only_after_verification(self):
        engine, worker = self.run_upgrade()
        self.assertEqual(engine.events, ['pull', 'stop', 'backup', 'replace', 'verify-new', 'finish'])
        self.assertFalse((self.root/'maintenance').exists())
        self.assertEqual(worker.read('status')['phase'], 'SUCCEEDED')

    def test_pull_failure_never_stops_server(self):
        engine, worker = self.run_upgrade('pull')
        self.assertEqual(engine.events, ['pull'])
        self.assertEqual(worker.read('status')['phase'], 'FAILED')
        self.assertFalse((self.root/'maintenance').exists())

    def test_backup_failure_restarts_without_restoring_partial_snapshot(self):
        engine, _ = self.run_upgrade('backup')
        self.assertEqual(engine.events, ['pull', 'stop', 'backup', 'restart-old', 'verify-old'])
        self.assertFalse((self.root/'maintenance').exists())

    def test_failed_new_version_restores_before_opening(self):
        engine, worker = self.run_upgrade('verify')
        self.assertEqual(engine.events[-3:], ['restore', 'restart-old', 'verify-old'])
        self.assertEqual(worker.read('status')['phase'], 'ROLLED_BACK')
        self.assertFalse((self.root/'maintenance').exists())

    def test_restart_after_commit_never_restores_data(self):
        engine = Engine()
        worker = u.Updater(self.root, engine)
        worker.write('operation', {'phase': 'COMMITTED', 'old': engine.current(), 'release': self.release})
        (self.root/'maintenance').touch()
        worker.recover()
        self.assertNotIn('restore', engine.events)
        self.assertFalse((self.root/'maintenance').exists())

    def test_interrupted_backup_recovers_old_without_snapshot(self):
        engine = Engine()
        worker = u.Updater(self.root, engine)
        worker.write('operation', {'phase': 'BACKING_UP', 'old': engine.current(), 'release': self.release})
        (self.root/'maintenance').touch()
        worker.recover()
        self.assertEqual(engine.events, ['restart-old', 'verify-old'])

    def test_manifest_rejects_arbitrary_image_and_prerelease(self):
        for fields in ({'image': 'evil/image:latest'}, {'version': '2.3.0-rc1'}, {'protocol': 2}):
            with self.assertRaises(u.UpgradeError): u.validate_release(self.release | fields)
        self.assertEqual(u.validate_release(self.release), self.release)

    def test_versions_are_numeric_and_configuration_is_strict(self):
        self.assertGreater(u.version('2.10.0'), u.version('2.9.0'))
        for config in ({'time': '25:00'}, {'timezone': 'invalid'}, {'automatic': 'yes'}):
            with self.assertRaises(u.UpgradeError): u.validate_config(config)
        self.assertEqual(u.validate_config({'time': '03:00', 'timezone': 'Asia/Shanghai'})['time'], '03:00')


    def test_recovered_commit_does_not_restore_again(self):
        engine = Engine()
        worker = u.Updater(self.root, engine)
        worker.write('operation', {'phase': 'RECOVERED', 'old': engine.current(), 'release': self.release})
        (self.root/'maintenance').touch()
        worker.recover()
        self.assertEqual(engine.events, [])
        self.assertFalse((self.root/'maintenance').exists())

    def test_interrupted_replacement_restores_snapshot(self):
        engine = Engine()
        worker = u.Updater(self.root, engine)
        worker.write('operation', {'id': 'test', 'phase': 'REPLACING', 'snapshot': True, 'old': engine.current(), 'release': self.release})
        worker.recover()
        self.assertEqual(engine.events, ['restore', 'restart-old', 'verify-old'])

    def test_restore_failure_keeps_maintenance_and_transaction(self):
        class FailingRestore(Engine):
            def restore(self, old, operation): raise u.UpgradeError('恢复失败')
        worker = u.Updater(self.root, FailingRestore('verify'))
        worker.apply(self.release)
        self.assertTrue((self.root/'maintenance').exists())
        self.assertTrue((self.root/'operation.json').exists())
        self.assertEqual(worker.read('status')['phase'], 'NEEDS_ATTENTION')

    def test_manual_check_does_not_pull_or_stop_and_claims_request(self):
        engine = Engine()
        worker = u.Updater(self.root, engine)
        worker.write('request', {'action': 'CHECK'})
        with patch.object(u, 'latest_release', return_value=self.release): worker.tick()
        self.assertEqual(engine.events, [])
        self.assertEqual(worker.read('status')['latestVersion'], '2.3.0')
        self.assertFalse((self.root/'request.json').exists())
        self.assertTrue((self.root/'executing').exists())

    def test_automatic_skips_failed_version_and_only_checks_once_per_day(self):
        worker = u.Updater(self.root, Engine())
        worker.write('config', {'automatic': True, 'time': '00:00', 'timezone': 'Asia/Shanghai'})
        worker.write('failure', {'version': '2.3.0'})
        with patch.object(u, 'latest_release', return_value=self.release) as fetch:
            worker.tick()
            worker.tick()
            self.assertEqual(fetch.call_count, 1)
        self.assertEqual(worker.engine.events, [])


    def test_version_probe_uses_detached_exec_and_waits_for_exit(self):
        calls = []
        class ProbeDocker(u.Docker):
            def call(self, method, path, body=None, missing=False):
                calls.append((method, path, body))
                if path.endswith('/exec'): return {'Id': 'probe'}
                if path.endswith('/start'): return {}
                if path.startswith('/exec/'): return {'Running': False, 'ExitCode': 0}
                return {'Id': 'old', 'State': {'Status': 'running', 'Health': {'Status': 'healthy'}}}
        ProbeDocker().verify(Engine().current())
        start = next(body for method, path, body in calls if path == '/exec/probe/start')
        self.assertTrue(start['Detach'])
        self.assertEqual(calls[-1][1], '/exec/probe/json')


if __name__ == '__main__': unittest.main()
