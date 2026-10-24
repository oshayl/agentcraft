#!/usr/bin/env python3
"""Regression checks for preserving private state and requiring explicit EULA consent."""
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch


class ConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / 'state'
        self.release = self.root / 'release'
        (self.release / 'mods').mkdir(parents=True)
        (self.release / 'mods/agentcraft-0.2.0.jar').write_bytes(b'mod fixture')
        (self.release / 'release.json').write_text(json.dumps({'clientMods': [{'file': 'agentcraft-0.2.0.jar'}]}))

    def configure(self, *extra):
        argv = ['container-config.py', '--state-dir', str(self.state), '--release', str(self.release), '--owner-uuid', '00000000-0000-4000-8000-000000000001', '--owner-name', 'TestOwner', *extra]
        old_umask = os.umask(0o077)
        try:
            with patch('sys.argv', argv), patch('os.chown'), redirect_stdout(io.StringIO()):
                runpy.run_path(str(Path(__file__).with_name('container-config.py')), run_name='__main__')
        finally:
            os.umask(old_umask)

    def test_eula_is_absent_until_explicit_acceptance(self):
        self.configure()
        self.assertFalse((self.state / 'minecraft/eula.txt').exists())
        self.configure('--accept-eula')
        self.assertEqual((self.state / 'minecraft/eula.txt').read_text(), 'eula=true\n')

    def test_repeat_preserves_token_and_existing_foreman_configuration(self):
        self.configure()
        token = (self.state / 'config/bridge-token').read_bytes()
        config = self.state / 'foreman/config.json'
        data = json.loads(config.read_text())
        data['repos'] = ['/var/lib/agentcraft/workspaces/pilot']
        config.write_text(json.dumps(data))
        before = config.read_bytes()
        self.configure()
        self.assertEqual((self.state / 'config/bridge-token').read_bytes(), token)
        self.assertEqual(config.read_bytes(), before)

    def test_changed_owner_is_rejected_before_touching_state(self):
        self.configure()
        before = {str(p): p.read_bytes() for p in self.state.rglob('*') if p.is_file()}
        with self.assertRaisesRegex(SystemExit, 'owner differs'):
            self.configure('--owner-uuid', '00000000-0000-4000-8000-000000000002')
        self.assertEqual(before, {str(p): p.read_bytes() for p in self.state.rglob('*') if p.is_file()})

    def test_old_mod_is_rejected_before_reconfiguration(self):
        self.configure()
        (self.state / 'minecraft/mods/agentcraft-0.1.0.jar').write_bytes(b'old fixture')
        with self.assertRaisesRegex(SystemExit, 'older mod'):
            self.configure('--accept-eula')
        self.assertFalse((self.state / 'minecraft/eula.txt').exists())


if __name__ == '__main__':
    unittest.main()
