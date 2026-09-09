#!/usr/bin/env python3
"""CI 真实运行验收：真实 ApplicationContext、Docker 镜像切换和恢复，不断言业务 SQL。"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import uuid
import updater as u


def docker(*args):
    """CI 只报告安全状态，docker 输出留作异常诊断，不打印部署环境。"""
    return subprocess.check_output(['docker', *args], text=True).strip()


def main():
    base = os.environ['SMOKE_IMAGE']
    prefix = 'shangan-smoke-' + uuid.uuid4().hex[:8]
    registry = prefix + '-registry'
    volumes = [prefix + suffix for suffix in ('-data', '-backup', '-state')]
    engine = u.Docker()
    # 仅 Smoke 使用本机 registry；发布版升级器的可信仓库常量没有配置入口。
    u.IMAGE = '127.0.0.1:15000/ashorex-server'
    revision = 'b' * 40
    try:
        docker('run', '-d', '--name', registry, '-p', '127.0.0.1:15000:5000', 'registry:2')
        manifests = []
        for number, bad in ((0, False), (1, False), (2, True)):
            ver = '9.0.' + str(number)
            tag = u.IMAGE + ':' + ver
            with tempfile.TemporaryDirectory() as folder:
                Path(folder, 'Dockerfile').write_text(
                    f'FROM {base}\nENV SHANGAN_VERSION={ver}\nLABEL org.opencontainers.image.version={ver} org.opencontainers.image.revision={revision}\n'
                    + ('ENTRYPOINT ["/bin/false"]\n' if bad else ''))
                docker('build', '-q', '-t', tag, folder)
            docker('push', tag)
            digest = json.loads(docker('image', 'inspect', tag))[0]['RepoDigests'][0]
            manifests.append({'version': ver, 'image': digest, 'protocol': 1, 'automatic': True, 'revision': revision, 'notes': 'CI Smoke'})
        for volume in volumes: docker('volume', 'create', volume)
        docker('run', '-d', '--name', prefix, '--network', 'host', '--restart', 'unless-stopped',
               '--label', 'com.docker.compose.project=shangan', '--label', 'com.docker.compose.service=server',
               '-v', volumes[0]+':/data', '-v', volumes[1]+':/backup', '-v', volumes[2]+':/updates',
               '-e', 'UPDATES_DIR=/updates', '-e', 'JWT_SECRET='+uuid.uuid4().hex+uuid.uuid4().hex,
               '-e', 'ADMIN_BOOTSTRAP_USERNAME=admin', '-e', 'ADMIN_BOOTSTRAP_PASSWORD='+uuid.uuid4().hex,
               manifests[0]['image'])
        # 主机获得与容器同一状态卷，以真实文件协议执行状态机。
        mount = json.loads(docker('volume', 'inspect', volumes[2]))[0]['Mountpoint']
        worker = u.Updater(mount, engine)
        # 初始化时同样只在维护状态下进行验收。
        (worker.root/'maintenance').touch()
        old = engine.current()
        engine.verify(old)
        (worker.root/'maintenance').unlink()
        worker.apply(manifests[1])
        assert worker.read('status')['phase'] == 'SUCCEEDED', worker.read('status')
        assert not (worker.root/'maintenance').exists()
        worker.apply(manifests[2])
        assert worker.read('status')['phase'] == 'ROLLED_BACK', worker.read('status')
        assert not (worker.root/'maintenance').exists()
        assert engine.current()['Config']['Labels']['org.opencontainers.image.version'] == '9.0.1'
        print('真实容器验收通过：健康启动、版本升级、坏版本恢复及维护解除。')
    finally:
        # CI 自建容器与卷按随机前缀清理，不接触其他部署。
        for line in docker('ps', '-a', '--format', '{{.ID}} {{.Names}}').splitlines():
            cid, name = line.split(' ', 1)
            if name.startswith(prefix) or name.startswith('shangan-upgrade-helper-'):
                docker('rm', '-f', cid)
        for volume in volumes:
            subprocess.run(['docker', 'volume', 'rm', volume], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == '__main__': main()
