#!/usr/bin/env python3
"""独立升级器：固定 Docker 服务、原子状态文件、验收前恢复；不监听网络端口。"""
import copy
import datetime as dt
import fcntl
import http.client
import json
import os
from pathlib import Path
import re
import socket
import time
import urllib.parse
import urllib.request
import uuid
from zoneinfo import ZoneInfo

IMAGE = 'ghcr.io/kiliter/ashorex-server'
RELEASES = 'https://api.github.com/repos/kiliter/Ashorex/releases'
DEFAULT_CONFIG = {'automatic': False, 'time': '03:00', 'timezone': 'Asia/Shanghai'}


class UpgradeError(Exception):
    """仅携带允许显示的中文错误，不携带 Docker/HTTP 原始输出。"""


def version(value):
    """只接受三个数字的正式版本，拒绝预发布、路径和命令文本。"""
    if not isinstance(value, str) or not re.fullmatch(r'\d+\.\d+\.\d+', value):
        raise UpgradeError('版本号必须是正式版本')
    return tuple(map(int, value.split('.')))


def validate_release(value):
    """清单只能指向固定仓库 digest，协议不兼容时在停止服务前拒绝。"""
    version(value.get('version'))
    if (value.get('protocol') != 1 or type(value.get('automatic')) is not bool
            or not re.fullmatch(re.escape(IMAGE) + r'@sha256:[0-9a-f]{64}', value.get('image', ''))
            or not re.fullmatch(r'[0-9a-f]{40}', value.get('revision', ''))):
        raise UpgradeError('发布清单不可信或升级协议不兼容')
    if not isinstance(value.get('notes', ''), str):
        raise UpgradeError('更新说明格式错误')
    return {key: value[key] for key in ('version', 'image', 'protocol', 'automatic', 'revision')} | {'notes': value.get('notes', '')[:12000]}


def validate_config(value):
    """后台只允许开关、时间和 IANA 时区，代理与镜像来源由部署者固定。"""
    result = DEFAULT_CONFIG | value
    if type(result['automatic']) is not bool or not re.fullmatch(r'([01]\d|2[0-3]):[0-5]\d', result['time']):
        raise UpgradeError('自动升级时间或开关无效')
    try:
        ZoneInfo(result['timezone'])
    except (KeyError, ValueError, TypeError):
        raise UpgradeError('升级时区无效') from None
    return {key: result[key] for key in DEFAULT_CONFIG}


def fetch_json(url):
    """官方 HTTPS 源读取有超时和体积限制，urllib 沿用 HTTP(S)_PROXY。"""
    request = urllib.request.Request(url, headers={'Accept': 'application/json', 'User-Agent': 'shangan-updater'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            data = response.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise ValueError()
        return json.loads(data)
    except Exception:
        raise UpgradeError('版本源访问失败，请检查网络或升级器代理') from None


def latest_release():
    """只选择有完整服务端清单的正式 Release，避免移动端先创建空 Release。"""
    for release in fetch_json(RELEASES + '?per_page=20'):
        if release.get('draft') or release.get('prerelease'):
            continue
        tag = release.get('tag_name', '')
        if not re.fullmatch(r'v\d+\.\d+\.\d+', tag):
            continue
        if any(asset.get('name') == 'server-update.json' for asset in release.get('assets', [])):
            manifest = validate_release(fetch_json('https://github.com/kiliter/Ashorex/releases/download/' + tag + '/server-update.json'))
            if tag != 'v' + manifest['version']:
                raise UpgradeError('版本清单与 Release 标签不一致')
            return manifest
    raise UpgradeError('尚无支持自动升级协议的正式服务端版本')


class DockerConnection(http.client.HTTPConnection):
    """仅通过本地 Unix socket 访问 Docker，不接受网络 daemon 地址。"""
    def __init__(self):
        super().__init__('localhost', timeout=900)

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect('/var/run/docker.sock')


class Docker:
    """限定 Compose server 服务的 Docker 适配，inspect 凭据从不进入日志。"""
    def call(self, method, path, body=None, missing=False):
        # 与 daemon 协商 API，避免新 Docker 移除旧 API 后升级器无法工作。
        if path != '/version' and not getattr(self, '_api_version', None):
            self._api_version = self.call('GET', '/version')['ApiVersion']
            if not re.fullmatch(r'\d+\.\d+', self._api_version):
                raise UpgradeError('Docker API 版本无效')
        prefix = '' if path == '/version' else '/v' + self._api_version
        connection = DockerConnection()
        try:
            connection.request(method, prefix + path, body=None if body is None else json.dumps(body), headers={'Content-Type': 'application/json'})
            response = connection.getresponse()
            if missing and response.status == 404:
                return None
            # 镜像拉取使用流式 JSON；逐行丢弃进度，避免累计大响应。
            if path.startswith('/images/create') and response.status < 300:
                while line := response.readline():
                    if line.strip() and 'error' in json.loads(line):
                        raise UpgradeError('镜像拉取失败，请检查 Docker daemon 代理和仓库权限')
                return {}
            data = response.read(16 * 1024 * 1024)
            if response.status >= 300 and response.status != 304:
                raise UpgradeError(f'Docker 操作失败（HTTP {response.status}），请检查服务容器与 socket 权限')
            return json.loads(data) if data else {}
        except UpgradeError:
            raise
        except Exception:
            raise UpgradeError('Docker 无法访问或操作超时，请检查 daemon 与 socket 权限') from None
        finally:
            connection.close()

    def current(self):
        filters = urllib.parse.quote(json.dumps({'label': ['com.docker.compose.project=shangan', 'com.docker.compose.service=server']}))
        containers = self.call('GET', '/containers/json?all=true&filters=' + filters)
        # 保留的旧容器也带 Compose 标签，只选固定正式名称。
        active = [c for c in containers if not any('-upgrade-old-' in name for name in c['Names'])]
        if len(active) != 1:
            raise UpgradeError('必须存在唯一的 shangan/server 容器')
        result = self.call('GET', '/containers/' + active[0]['Id'] + '/json')
        if result['HostConfig']['NetworkMode'] != 'host':
            raise UpgradeError('当前升级协议仅支持 Linux host 网络部署')
        mounts = {m['Destination']: m for m in result['Mounts']}
        if any(p not in mounts or mounts[p]['Type'] != 'volume' for p in ('/data', '/backup', '/updates')):
            raise UpgradeError('请先手动接入升级部署配置和三个命名数据卷')
        if result['Config'].get('Labels', {}).get('com.shangan.upgrade-protocol') != '1':
            raise UpgradeError('当前服务尚未接入维护协议，请先手动升级部署')
        return result

    def pull(self, release):
        self.call('POST', '/images/create?fromImage=' + urllib.parse.quote(release['image'], safe=''))
        image = self.call('GET', '/images/' + urllib.parse.quote(release['image'], safe='') + '/json')
        labels = image['Config'].get('Labels', {})
        if (labels.get('com.shangan.upgrade-protocol') != '1'
                or labels.get('org.opencontainers.image.version') != release['version']
                or labels.get('org.opencontainers.image.revision') != release['revision']):
            raise UpgradeError('镜像版本、提交或维护协议与清单不一致')

    def stop(self, container):
        self.call('POST', '/containers/' + container['Id'] + '/stop?t=30')

    def helper(self, old, operation, restore=False):
        """辅助容器无网络，复用旧镜像与命名卷；超时后必须确认退出才恢复业务。"""
        name = 'shangan-upgrade-helper-' + operation['id']
        existing = self.call('GET', '/containers/' + name + '/json', missing=True)
        if existing:
            self.call('DELETE', '/containers/' + existing['Id'] + '?force=true')
        mounts = [{'Type': 'volume', 'Source': m['Name'], 'Target': m['Destination']} for m in old['Mounts'] if m['Destination'] in ('/data', '/backup')]
        folder = '/backup/upgrades/' + operation['id']
        command = ['/app/infra/scripts/restore.sh', folder + '/study-snapshot.db'] if restore else ['/app/infra/scripts/backup.sh']
        helper = self.call('POST', '/containers/create?name=' + name, {
            'Image': old['Image'], 'User': '10001:10001', 'Entrypoint': ['/bin/bash'], 'Cmd': command,
            'Env': ['DATA_DIR=/data', 'BACKUP_DIR=' + folder, 'STAMP=' + (uuid.uuid4().hex if restore else 'snapshot'), 'SERVICE_STOPPED=1'],
            'HostConfig': {'NetworkMode': 'none', 'Mounts': mounts, 'SecurityOpt': ['no-new-privileges:true']}})
        try:
            self.call('POST', '/containers/' + helper['Id'] + '/start')
            result = self.call('POST', '/containers/' + helper['Id'] + '/wait')
            if result['StatusCode'] != 0:
                raise UpgradeError('升级快照恢复失败' if restore else '升级备份或完整性校验失败')
        finally:
            self.call('DELETE', '/containers/' + helper['Id'] + '?force=true')

    def backup(self, old, operation):
        self.helper(old, operation)

    def replace(self, old, release):
        """保留停止的旧容器，复制部署配置，仅替换镜像及镜像自带构建元数据。"""
        self.call('POST', '/containers/' + old['Id'] + '/rename?name=' + old['Name'].lstrip('/') + '-upgrade-old-' + old['Id'][:12])
        config = copy.deepcopy(old['Config'])
        new_image = self.call('GET', '/images/' + urllib.parse.quote(release['image'], safe='') + '/json')['Config']
        config['Image'] = release['image']
        # 启动程序和健康命令跟随镜像；环境、卷和宿主机网络保留部署配置。
        for key in ('Entrypoint', 'Cmd', 'Healthcheck', 'WorkingDir', 'User'):
            if key in new_image: config[key] = new_image[key]
        config['Hostname'] = ''
        config['Labels'] = (config.get('Labels') or {}) | new_image.get('Labels', {})
        # 构建版本只从镜像读取，不能继承旧容器环境里的旧值。
        config['Env'] = [v for v in config.get('Env', []) if not v.startswith(('SHANGAN_VERSION=', 'SHANGAN_REVISION='))]
        config['Env'] += [v for v in new_image.get('Env', []) if v.startswith(('SHANGAN_VERSION=', 'SHANGAN_REVISION='))]
        config['HostConfig'] = old['HostConfig']
        created = self.call('POST', '/containers/create?name=' + old['Name'].lstrip('/'), config)
        self.call('POST', '/containers/' + created['Id'] + '/start')

    def verify(self, old, release=None):
        """健康与构建信息均经 Docker exec 在容器内读取，避免代理/DNS 干扰。"""
        target = old['Name'].lstrip('/') if release else old['Id']
        expected = release['version'] if release else old['Config']['Labels'].get('org.opencontainers.image.version', 'dev')
        for _ in range(90):
            container = self.call('GET', '/containers/' + target + '/json')
            if container['State'].get('Health', {}).get('Status') == 'healthy':
                check = self.call('POST', '/containers/' + container['Id'] + '/exec', {
                    'AttachStdout': False, 'AttachStderr': False,
                    'Cmd': ['python3', '-c', 'import json,urllib.request,sys; d=json.load(urllib.request.urlopen("http://127.0.0.1:18080/internal/upgrade-readiness",timeout=5));sys.exit(0 if d["version"]==sys.argv[1] and d["maintenance"] else 1)', expected]})
                # 避免 Docker exec 的 HTTP hijack/raw-stream；后台执行后只读结构化状态。
                self.call('POST', '/exec/' + check['Id'] + '/start', {'Detach': True, 'Tty': False})
                for _ in range(8):
                    result = self.call('GET', '/exec/' + check['Id'] + '/json')
                    if not result['Running']:
                        if result['ExitCode'] == 0: return
                        break
                    time.sleep(1)
            if container['State']['Status'] in ('exited', 'dead') or container.get('RestartCount', 0) > 0:
                break
            time.sleep(2)
        raise UpgradeError('服务启动健康检查或实际版本校验未通过')

    def restore(self, old, operation):
        """恢复前移除新容器，保证数据库及附件没有其他写入者。"""
        current = self.call('GET', '/containers/' + old['Name'].lstrip('/') + '/json', missing=True)
        if current and current['Id'] != old['Id']:
            self.call('DELETE', '/containers/' + current['Id'] + '?force=true')
        self.stop(old)
        self.helper(old, operation, restore=True)

    def cancel_helper(self, operation):
        name = 'shangan-upgrade-helper-' + operation['id']
        helper = self.call('GET', '/containers/' + name + '/json', missing=True)
        if helper:
            self.call('DELETE', '/containers/' + helper['Id'] + '?force=true')

    def restart_old(self, old):
        current = self.call('GET', '/containers/' + old['Id'] + '/json')
        if current['Name'] != old['Name']:
            self.call('POST', '/containers/' + old['Id'] + '/rename?name=' + old['Name'].lstrip('/'))
        self.call('POST', '/containers/' + old['Id'] + '/start')

    def finish(self, old):
        self.call('DELETE', '/containers/' + old['Id'], missing=True)


class Updater:
    """写前日志状态机：只有 COMMITTED 后可开放业务，之后永不自动恢复快照。"""
    def __init__(self, root, engine):
        self.root, self.engine = Path(root), engine
        self.root.mkdir(parents=True, exist_ok=True)

    def read(self, name):
        path = self.root / (name + '.json')
        return json.loads(path.read_text()) if path.exists() else {}

    def write(self, name, value):
        """同文件系统替换并 fsync 文件及目录，掉电后不接受半份状态。"""
        target = self.root / (name + '.json')
        tmp = self.root / (name + '.' + uuid.uuid4().hex + '.tmp')
        with open(tmp, 'x') as stream:
            os.chmod(tmp, 0o600)
            json.dump(value, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, target)
        self.sync_directory()

    def sync_directory(self):
        fd = os.open(self.root, os.O_RDONLY)
        try: os.fsync(fd)
        finally: os.close(fd)

    def status(self, phase, message, **extra):
        self.write('status', self.read('status') | {'phase': phase, 'message': message, 'updatedAt': dt.datetime.now(dt.timezone.utc).isoformat()} | extra)
        print(json.dumps({'phase': phase, 'message': message}, ensure_ascii=False), flush=True)

    def phase(self, operation, phase):
        operation['phase'] = phase
        self.write('operation', operation)
        self.status(phase, {'PREPARING': '准备维护窗口', 'BACKING_UP': '正在备份并校验数据', 'REPLACING': '正在启动新版本', 'COMMITTED': '新版本已通过验收', 'RESTORING': '正在恢复升级前数据'}[phase])

    def apply(self, release):
        operation = None
        try:
            release = validate_release(release)
            old = self.engine.current()
            current_version = old['Config']['Labels'].get('org.opencontainers.image.version', 'dev')
            if current_version != 'dev' and version(release['version']) <= version(current_version):
                raise UpgradeError('目标版本没有高于当前版本')
            self.status('PULLING', '正在拉取并校验正式镜像', targetVersion=release['version'])
            self.engine.pull(release)
            operation = {'id': str(uuid.uuid4()), 'old': old, 'release': release, 'snapshot': False}
            self.phase(operation, 'PREPARING')
            (self.root/'maintenance').touch()
            self.sync_directory()
            self.engine.stop(old)
            self.phase(operation, 'BACKING_UP')
            self.engine.backup(old, operation)
            operation['snapshot'] = True
            self.phase(operation, 'REPLACING')
            self.engine.replace(old, release)
            self.engine.verify(old, release)
            self.phase(operation, 'COMMITTED')
            self.complete(operation)
        except Exception as error:
            message = str(error) if isinstance(error, UpgradeError) else '升级执行异常，请检查升级器状态'
            self.write('failure', {'version': release.get('version', '')})
            if operation:
                # 已提交后任何清理失败都不能触发数据库恢复。
                if operation.get('phase') == 'COMMITTED':
                    self.status('NEEDS_ATTENTION', '新版本已验收，清理未完成；请重启升级器接续')
                else:
                    self.recover(message)
            else:
                self.status('FAILED', message)

    def complete(self, operation):
        release = operation['release']
        self.write('current', {'version': release['version'], 'image': release['image']})
        (self.root/'maintenance').unlink(missing_ok=True)
        self.sync_directory()
        # 独立保留每次升级的恢复元数据；历史文件为 0600，绝不通过后台投影。
        self.write('history-' + operation['id'], operation)
        self.status('SUCCEEDED', '升级成功，业务已开放', currentVersion=release['version'], previousVersion=operation['old']['Config']['Labels'].get('org.opencontainers.image.version', 'dev'))
        self.engine.finish(operation['old'])
        (self.root/'operation.json').unlink(missing_ok=True)
        self.sync_directory()

    def recover(self, message='升级器重启，正在恢复中断的升级'):
        operation = self.read('operation')
        if not operation:
            return
        if operation['phase'] == 'RECOVERED':
            (self.root/'maintenance').unlink(missing_ok=True)
            (self.root/'operation.json').unlink()
            self.sync_directory()
            return
        if operation['phase'] == 'COMMITTED':
            self.complete(operation)
            return
        try:
            (self.root/'maintenance').touch()
            self.sync_directory()
            self.engine.cancel_helper(operation)
            if operation.get('snapshot'):
                self.phase(operation, 'RESTORING')
                self.engine.restore(operation['old'], operation)
            self.engine.restart_old(operation['old'])
            self.engine.verify(operation['old'])
            # 恢复确认后清除事务再开放；重启不会再次还原已恢复后的业务写入。
            self.write('failure', {'version': operation['release']['version']})
            self.write('current', {'version': operation['old']['Config']['Labels'].get('org.opencontainers.image.version', 'dev'), 'image': operation['old']['Image']})
            operation['phase'] = 'RECOVERED'
            self.write('operation', operation)
            self.write('history-' + operation['id'], operation)
            (self.root/'maintenance').unlink(missing_ok=True)
            self.status('ROLLED_BACK' if operation.get('snapshot') else 'FAILED', message + '；旧服务已恢复')
            (self.root/'operation.json').unlink(missing_ok=True)
            self.sync_directory()
        except Exception:
            self.status('NEEDS_ATTENTION', '自动恢复未完成，维护状态已保留；请按手册恢复')

    def check(self):
        release = latest_release()
        old = self.engine.current()
        self.write('release', release)
        self.status('READY', '版本检查完成', currentVersion=old['Config']['Labels'].get('org.opencontainers.image.version', 'dev'), latestVersion=release['version'], notes=release['notes'])
        return release

    def tick(self):
        """已持久化任务先执行；无人值守每日一次且不重复尝试失败版本。"""
        self.write('heartbeat', {'at': dt.datetime.now(dt.timezone.utc).isoformat()})
        if self.read('operation'):
            return
        request = self.read('request')
        if request:
            (self.root/'executing').touch()
            (self.root/'request.json').unlink()
            self.sync_directory()
            action = request.get('action')
            release = self.check()
            if action == 'APPLY': self.apply(release)
            elif action == 'DOWNLOAD':
                self.status('PULLING', '正在预下载正式镜像')
                self.engine.pull(release)
                self.status('READY', '镜像已下载并校验，可执行升级')
            elif action != 'CHECK': raise UpgradeError('不支持的升级操作')
            return
        config = validate_config(self.read('config'))
        now = dt.datetime.now(ZoneInfo(config['timezone']))
        day = now.date().isoformat()
        if config['automatic'] and now.strftime('%H:%M') >= config['time'] and self.read('schedule').get('day') != day:
            (self.root/'executing').touch()
            self.write('schedule', {'day': day})
            release = self.check()
            current = self.read('status').get('currentVersion', 'dev')
            if release['automatic'] and current != 'dev' and version(release['version']) > version(current) and self.read('failure').get('version') != release['version']:
                self.apply(release)


def main():
    """进程级锁与持久化恢复；仅中文脱敏结果输出到 Docker 日志。"""
    os.umask(0o077)
    worker = Updater(os.environ.get('UPDATES_DIR', '/updates'), Docker())
    with open(worker.root/'worker.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # RECOVERED 是恢复验收提交点，不能再次恢复旧快照。
        if worker.read('operation').get('phase') == 'RECOVERED':
            (worker.root/'maintenance').unlink(missing_ok=True)
            (worker.root/'operation.json').unlink()
        else:
            worker.recover()
        (worker.root/'executing').unlink(missing_ok=True)
        while True:
            try: worker.tick()
            except Exception as error:
                worker.status('FAILED', str(error) if isinstance(error, UpgradeError) else '升级任务异常，请检查升级配置')
            finally:
                (worker.root/'executing').unlink(missing_ok=True)
            time.sleep(5)


if __name__ == '__main__': main()
