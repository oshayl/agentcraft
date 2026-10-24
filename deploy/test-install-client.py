import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import io
import tarfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('client_setup', Path(__file__).with_name('install-client.py'))
client_setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client_setup)


class InstallClientChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.release = self.root / 'release'
        (self.release / 'mods').mkdir(parents=True)
        self.game = self.root / 'AgentCraft'
        self.launcher = self.root / 'launcher'
        self.launcher.mkdir()
        mod = self.release / 'mods/agentcraft-0.2.0.jar'
        mod.write_bytes(b'new release')
        self.manifest = {'version': '0.2.0', 'minecraft': '26.3', 'fabricLoader': '0.19.5', 'clientMods': [{'file': mod.name, 'sha256': hashlib.sha256(mod.read_bytes()).hexdigest()}]}
        self.write_manifest()

    def tearDown(self):
        self.temp.cleanup()

    def write_manifest(self):
        (self.release / 'release.json').write_text(json.dumps(self.manifest))

    def install(self):
        return client_setup.install(self.release, self.game, self.launcher, 'private-hq:25565', '/test/java', configure=False)

    def test_installs_separately_and_retains_server_entry_on_update(self):
        other = self.launcher / 'unrelated-profile'
        other.mkdir(); (other / 'world.txt').write_text('preserve me')
        self.install()
        saved = (self.game / 'servers.dat').read_bytes()
        (self.game / 'mods/unrelated.jar').write_bytes(b'other mod')
        self.install()
        self.assertEqual((other / 'world.txt').read_text(), 'preserve me')
        self.assertEqual((self.game / 'mods/unrelated.jar').read_bytes(), b'other mod')
        self.assertEqual((self.game / 'servers.dat').read_bytes(), saved)
        self.assertEqual(len(list((self.game / 'mod-backups').rglob('agentcraft-*.jar'))), 1)

    def test_checksum_failure_does_not_modify_existing_profile(self):
        self.install()
        before = (self.game / 'mods/agentcraft-0.2.0.jar').read_bytes()
        (self.release / 'mods/agentcraft-0.2.0.jar').write_bytes(b'corrupted')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            self.install()
        self.assertEqual((self.game / 'mods/agentcraft-0.2.0.jar').read_bytes(), before)

    def test_refuses_unmanaged_world_directory(self):
        self.game.mkdir(); (self.game / 'world.txt').write_text('existing world')
        with self.assertRaisesRegex(ValueError, 'not an AgentCraft profile'):
            self.install()
        self.assertFalse((self.game / 'mods').exists())

    def test_refuses_manifest_path_traversal(self):
        self.manifest['clientMods'][0]['file'] = '../elsewhere.jar'
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'filename'):
            self.install()
        self.assertFalse(self.game.exists())

    def test_launcher_profile_collision_is_checked_before_any_mutation(self):
        p = self.launcher / 'launcher_profiles.json'
        p.write_text(json.dumps({'profiles': {'AgentCraft': {'gameDir': '/different/profile'}}}))
        before = p.read_bytes()
        with patch.object(client_setup.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'another directory'):
                client_setup.install(self.release, self.game, self.launcher, 'private-hq:25565', '/test/java')
            run.assert_not_called()
        self.assertEqual(p.read_bytes(), before)
        self.assertFalse(self.game.exists())


    def test_bootstraps_verified_native_java_into_private_user_storage(self):
        archive = self.root / 'java.tar.gz'
        with tarfile.open(archive, 'w:gz') as tar:
            content = b'test native Java executable'
            member = tarfile.TarInfo('jdk-test/Contents/Home/bin/java')
            member.size = len(content); member.mode = 0o755
            tar.addfile(member, io.BytesIO(content))
        self.manifest['macJava'] = {'aarch64': {'url': archive.as_uri(), 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'version': 'jdk-test'}}
        self.write_manifest()
        probes = [SimpleNamespace(returncode=1, stdout=''), SimpleNamespace(returncode=0, stdout='1')]
        with patch.object(client_setup.Path, 'home', return_value=self.root), patch.object(client_setup.subprocess, 'run', side_effect=probes):
            java = Path(client_setup.resolve_java(self.release))
        self.assertEqual(java.read_bytes(), b'test native Java executable')
        self.assertTrue(str(java).startswith(str(self.root / 'Library/Application Support/AgentCraft/runtime')))

    def test_rejects_runtime_archive_escape_before_extracting(self):
        archive = self.root / 'bad-java.tar.gz'
        with tarfile.open(archive, 'w:gz') as tar:
            member = tarfile.TarInfo('../../escaped-file'); member.size = 4
            tar.addfile(member, io.BytesIO(b'bad!'))
        self.manifest['macJava'] = {'x64': {'url': archive.as_uri(), 'sha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'version': 'jdk-test'}}
        self.write_manifest()
        probes = [SimpleNamespace(returncode=1, stdout=''), SimpleNamespace(returncode=0, stdout='0')]
        with patch.object(client_setup.Path, 'home', return_value=self.root), patch.object(client_setup.subprocess, 'run', side_effect=probes):
            with self.assertRaisesRegex(ValueError, 'unsafe'):
                client_setup.resolve_java(self.release)
        self.assertFalse((self.root / 'escaped-file').exists())


if __name__ == '__main__':
    unittest.main()
