"""Real temporary install trees; host commands are confined to a simulated host.
No command or filesystem mutation touches the executor's system installation.
"""
import base64
import importlib.util
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock
import contextlib
import io

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import server_installation as installation
spec = importlib.util.spec_from_file_location('uninstaller', ROOT / 'scripts/uninstall-server.py')
uninstall = importlib.util.module_from_spec(spec)
spec.loader.exec_module(uninstall)
PRIVATE = base64.b64encode(bytes([7]) * 32).decode()
PUBLIC = base64.b64encode(bytes([9]) * 32).decode()


class Fixture(installation.Host):
    def __init__(self, root, custom=False):
        super().__init__(root=root, runner=self.run, owner=os.getuid())
        self.calls = []
        self.interface = True
        self.table = True
        self.fail = None
        self.extra_members = []
        self.extra_mounts = []
        self.user_present = True
        self.group_present = True
        self.running = set(installation.STOP_ORDER)
        self.record = installation.inventory('/srv/notes/program' if custom else '/opt/field-notes',
                                             '/srv/notes/data' if custom else '/var/lib/field-notes',
                                             '/srv/notes/settings' if custom else '/etc/field-notes')
        self.record.update(uid=4242, gid=4242, phase='installed', wireguard_public_key=PUBLIC)
        for directory in (self.record['prefix'], self.record['data'], self.record['config'],
                          installation.HELPER_STATE, installation.RUNTIME, '/etc/wireguard',
                          '/etc/systemd/system', '/var/lib/systemd/timers'):
            self.path(directory).mkdir(mode=0o700, parents=True, exist_ok=True)
        self.path(self.record['data'] + '/household.sqlite3').write_bytes(b'ENCRYPTED HOUSEHOLD FIXTURE')
        self.path(self.record['config'] + '/master.key').write_bytes(b'PRIVATE RECOVERY FIXTURE')
        self.path(installation.HELPER_STATE + '/state.json').write_text('{}')
        self.path(installation.RUNTIME + '/status.json').write_text('{}')
        for program in installation.PROGRAMS:
            self.path(self.record['prefix'] + '/' + program).write_text('installed program\n')
        main = (ROOT / 'deploy/field-notes-native.service').read_text()
        main = main.replace('/opt/field-notes', self.record['prefix']).replace('/var/lib/field-notes', self.record['data']).replace('/etc/field-notes', self.record['config'])
        for unit in installation.UNITS:
            text = main if unit == 'field-notes.service' else '[Unit]\nDescription=Field Notes fixture\n'
            self.path('/etc/systemd/system/' + unit).write_text(text)
            self.record['unit_hashes'][unit] = installation.digest(text.encode())
        for timer in installation.TIMERS:
            self.path('/var/lib/systemd/timers/stamp-' + timer).write_text('')
        self.path(installation.WG_CONFIG).write_text('[Interface]\nAddress = 10.82.0.1/24\nListenPort = 49272\nPrivateKey = ' + PRIVATE + '\n[Peer]\nPublicKey = ' + PUBLIC + '\nAllowedIPs = 10.82.0.2/32\n')
        self.path(installation.WG_CONFIG).chmod(0o600)
        self.bungee = self.path('/etc/wireguard/bungee.conf')
        self.bungee.write_text('BUNGEE MUST SURVIVE\n')
        self.path('/etc/systemd/system/bungee.service').write_text('BUNGEE SERVICE\n')
        self.write_record(self.record)

    def account(self):
        return types.SimpleNamespace(pw_uid=4242, pw_gid=4242, pw_dir=self.record['data'], pw_shell='/usr/sbin/nologin') if self.user_present else None

    def group(self):
        return types.SimpleNamespace(gr_gid=4242, gr_mem=self.extra_members) if self.group_present else None

    def other_group_users(self, gid):
        return []

    def mounts(self):
        return self.extra_mounts

    def run(self, args, **kwargs):
        self.calls.append((args, kwargs))
        if self.fail and self.fail(args):
            raise subprocess.CalledProcessError(1, args)
        if args[:2] == ['/usr/bin/systemctl', 'show']:
            unit = args[2]
            exists = self.path('/etc/systemd/system/' + unit).exists() if unit in installation.UNITS else True
            text = 'LoadState=' + ('loaded' if exists else 'not-found') + '\nActiveState=' + ('active' if unit in self.running else 'inactive')
            text += '\nFragmentPath=' + ('/etc/systemd/system/' + unit if exists and unit in installation.UNITS else '')
        elif args[:2] == ['/usr/bin/systemctl', 'disable']:
            unit = args[-1]
            self.running.discard(unit)
            if unit == 'wg-quick@fieldnotes.service':
                self.interface = False
            text = ''
        elif args[:2] == ['/usr/bin/systemctl', 'daemon-reload'] or args[:2] == ['/usr/bin/systemctl', 'reset-failed']:
            text = ''
        elif args[:4] == ['/usr/sbin/ip', '-j', '-d', 'link']:
            rows = [{'ifname': 'bungee', 'linkinfo': {'info_kind': 'wireguard'}}]
            if self.interface:
                rows.append({'ifname': 'fieldnotes', 'linkinfo': {'info_kind': 'wireguard'}})
            text = json.dumps(rows)
        elif args == ['/usr/sbin/ip', 'link', 'delete', 'dev', 'fieldnotes']:
            self.interface = False
            text = ''
        elif args == ['/usr/sbin/nft', '-j', 'list', 'tables']:
            rows = [{'table': {'family': 'inet', 'name': 'bungee'}}]
            if self.table:
                rows.append({'table': {'family': 'inet', 'name': 'fieldnotes'}})
            text = json.dumps({'nftables': rows})
        elif args == ['/usr/sbin/nft', '-j', 'list', 'table', 'inet', 'fieldnotes']:
            text = json.dumps({'nftables': [{'table': {'family': 'inet', 'name': 'fieldnotes', 'comment': 'Field Notes ' + self.record['id']}}]})
        elif args == ['/usr/sbin/nft', 'delete', 'table', 'inet', 'fieldnotes']:
            if self.interface:
                raise AssertionError('Firewall removed while tunnel was still present')
            self.table = False
            text = ''
        elif args[:2] == ['/usr/bin/wg', 'pubkey']:
            if kwargs.get('input', '').strip() != PRIVATE:
                raise AssertionError('Unexpected private key')
            text = PUBLIC + '\n'
        elif args[:4] == ['/usr/bin/wg', 'show', 'fieldnotes', 'public-key']:
            text = PUBLIC + '\n'
        elif args == ['/usr/sbin/userdel', 'fieldnotes']:
            self.user_present = False
            text = ''
        elif args == ['/usr/sbin/groupdel', 'fieldnotes']:
            self.group_present = False
            text = ''
        else:
            raise AssertionError('Unexpected host command: ' + repr(args))
        if PRIVATE in ' '.join(args):
            raise AssertionError('Private key leaked in command arguments')
        return types.SimpleNamespace(stdout=text, returncode=0)


class Removal(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='field-notes-uninstall-')
        self.host = Fixture(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def unchanged_bungee(self):
        self.assertEqual(self.host.bungee.read_text(), 'BUNGEE MUST SURVIVE\n')
        self.assertEqual(self.host.path('/etc/systemd/system/bungee.service').read_text(), 'BUNGEE SERVICE\n')
        for args, _ in self.host.calls:
            self.assertNotIn('bungee', args)
            self.assertNotIn('flush', args)
            self.assertNotIn('apt-get', args)

    def test_preview_writes_nothing_and_prints_no_secrets(self):
        def snapshot():
            return {str(p): (p.read_bytes(), p.stat().st_mode) for p in pathlib.Path(self.tmp.name).rglob('*') if p.is_file()}
        before = snapshot()
        record = installation.load(self.host)
        uninstall.preflight(self.host, record, False)
        preview = json.dumps(uninstall.plan(record, False))
        self.assertEqual(snapshot(), before)
        self.assertNotIn(PRIVATE, preview)
        self.assertNotIn('PRIVATE RECOVERY FIXTURE', preview)
        self.assertEqual(json.loads(preview)['retain_data'], [record['data'], record['config']])
        self.unchanged_bungee()

    def test_normal_remove_preserves_data_all_keys_and_later_purge_works(self):
        host = self.host
        uninstall.apply(host, installation.load(host))
        self.assertFalse(host.interface or host.table or host.user_present or host.group_present)
        self.assertFalse(host.path(host.record['prefix']).exists())
        self.assertFalse(host.path(installation.WG_CONFIG).exists())
        self.assertEqual(host.path(host.record['data'] + '/household.sqlite3').read_bytes(), b'ENCRYPTED HOUSEHOLD FIXTURE')
        self.assertEqual(host.path(host.record['config'] + '/master.key').read_bytes(), b'PRIVATE RECOVERY FIXTURE')
        copies = list(host.path(host.record['config'] + '/recovery').glob('*.conf'))
        self.assertEqual(len(copies), 1)
        self.assertIn(PRIVATE, copies[0].read_text())
        for path in (host.record['data'], host.record['config']):
            self.assertEqual(host.path(path).stat().st_mode & 0o777, 0o700)
        record = installation.load(host)
        self.assertEqual(record['phase'], 'removed')
        uninstall.apply(host, record)  # repeatable normal removal
        uninstall.apply(host, installation.load(host), purge=True)
        self.assertFalse(host.path(record['data']).exists() or host.path(record['config']).exists())
        self.unchanged_bungee()

    def test_bundled_android_downloads_are_removed_with_programs(self):
        from android_artifact import FILES
        downloads = self.host.path(self.host.record['prefix'] + '/downloads')
        downloads.mkdir(mode=0o755)
        for name in FILES:
            (downloads / name).write_bytes(b'public software fixture')
        uninstall.apply(self.host, installation.load(self.host))
        self.assertFalse(downloads.exists())
        self.assertTrue(self.host.path(self.host.record['data'] + '/household.sqlite3').exists())
        self.unchanged_bungee()

    def test_unknown_or_symlink_android_downloads_refuse_before_stopping(self):
        downloads = self.host.path(self.host.record['prefix'] + '/downloads')
        downloads.mkdir(mode=0o755)
        extra = downloads / 'unrelated-backup'
        extra.write_text('keep this')
        with self.assertRaises(ValueError):
            uninstall.apply(self.host, installation.load(self.host))
        self.assertTrue(self.host.interface)
        extra.unlink()
        (downloads / 'field-notes.apk').symlink_to(self.host.bungee)
        with self.assertRaises(ValueError):
            uninstall.apply(self.host, installation.load(self.host))
        self.assertTrue(self.host.interface)
        self.unchanged_bungee()

    def test_purge_removes_managed_resources_and_leaves_external_symlink_target(self):
        external = self.host.path('/external-backup')
        external.write_text('DO NOT DELETE')
        self.host.path(self.host.record['data'] + '/external-link').symlink_to(external)
        uninstall.apply(self.host, installation.load(self.host), purge=True)
        for path in (self.host.record['prefix'], self.host.record['data'], self.host.record['config'], installation.HELPER_STATE, installation.RUNTIME, installation.WG_CONFIG):
            self.assertFalse(self.host.path(path).exists())
        self.assertEqual(external.read_text(), 'DO NOT DELETE')
        for unit in installation.UNITS:
            self.assertFalse(self.host.path('/etc/systemd/system/' + unit).exists())
        for timer in installation.TIMERS:
            self.assertFalse(self.host.path('/var/lib/systemd/timers/stamp-' + timer).exists())
        self.unchanged_bungee()

    def test_stop_failure_retains_programs_keys_and_firewall_for_retry(self):
        self.host.fail = lambda args: args[:2] == ['/usr/bin/systemctl', 'disable'] and args[-1] == 'field-notes-peers.timer'
        with self.assertRaises(subprocess.CalledProcessError):
            uninstall.apply(self.host, installation.load(self.host), purge=True)
        self.assertTrue(self.host.interface and self.host.table)
        self.assertTrue(self.host.path(self.host.record['prefix']).exists())
        self.assertTrue(self.host.path(self.host.record['config'] + '/master.key').exists())
        self.host.fail = None
        uninstall.apply(self.host, installation.load(self.host), purge=True)
        self.unchanged_bungee()

    def test_firewall_failure_does_not_delete_inventory_or_claim_success(self):
        self.host.fail = lambda args: args[:3] == ['/usr/sbin/nft', 'delete', 'table']
        with self.assertRaises(subprocess.CalledProcessError):
            uninstall.apply(self.host, installation.load(self.host), purge=True)
        self.assertFalse(self.host.interface)
        self.assertTrue(self.host.path(self.host.record['config'] + '/' + installation.RECORD).exists())
        self.assertTrue(self.host.path(self.host.record['prefix']).exists())
        self.host.fail = None
        uninstall.apply(self.host, installation.load(self.host), purge=True)

    def test_modified_service_or_unknown_program_refuses_before_stopping(self):
        unit = self.host.path('/etc/systemd/system/field-notes.service')
        original = unit.read_bytes()
        unit.write_bytes(original + b'\nExecStart=/bin/other\n')
        with self.assertRaises(ValueError):
            uninstall.apply(self.host, self.host.record, True)
        self.assertTrue(self.host.interface)
        unit.write_bytes(original)
        self.host.path(self.host.record['prefix'] + '/unrelated-backup').write_text('KEEP')
        with self.assertRaises(ValueError):
            uninstall.apply(self.host, self.host.record, True)
        self.assertTrue(self.host.interface)

    def test_mounts_symlinked_roots_and_shared_account_group_are_refused(self):
        self.host.extra_mounts = [self.host.path(self.host.record['data'] + '/mounted-volume')]
        with self.assertRaises(ValueError):
            uninstall.preflight(self.host, self.host.record, True)
        self.host.extra_mounts = []
        self.host.extra_members = ['someone-else']
        with self.assertRaises(ValueError):
            uninstall.preflight(self.host, self.host.record, True)
        self.host.extra_members = []
        target = self.host.path(self.host.record['data'])
        target.rename(target.with_name('held-data'))
        target.symlink_to(target.with_name('held-data'))
        with self.assertRaises(ValueError):
            uninstall.preflight(self.host, self.host.record, True)
        self.unchanged_bungee()

    def test_hooks_routes_and_different_live_keys_cannot_be_executed(self):
        path = self.host.path(installation.WG_CONFIG)
        raw = path.read_text()
        for line in ('PostDown = touch /tmp/should-not-run\n', 'AllowedIPs = 0.0.0.0/0\n'):
            path.write_text(raw + line)
            with self.assertRaises(ValueError):
                uninstall.preflight(self.host, self.host.record, True)
        path.write_text(raw)
        changed = dict(self.host.record, wireguard_public_key=base64.b64encode(bytes([8]) * 32).decode())
        with self.assertRaises(ValueError):
            uninstall.preflight(self.host, changed, True)
        self.assertFalse(any(args[:2] == ['/usr/bin/systemctl', 'disable'] for args, _ in self.host.calls))

    def test_custom_paths_and_legacy_install_are_discovered_without_guessing(self):
        with tempfile.TemporaryDirectory() as tmp:
            host = Fixture(tmp, custom=True)
            record_path = host.path(host.record['config'] + '/' + installation.RECORD)
            record_path.unlink()
            discovered = installation.load(host)
            self.assertTrue(discovered['legacy'])
            self.assertEqual(discovered['prefix'], '/srv/notes/program')
            host.write_record(discovered)
            uninstall.apply(host, discovered, True)
            self.assertFalse(host.path('/srv/notes/data').exists())
            self.assertEqual(host.bungee.read_text(), 'BUNGEE MUST SURVIVE\n')

    def test_partial_install_and_already_absent_paths_are_safe(self):
        for unit in installation.UNITS:
            self.host.path('/etc/systemd/system/' + unit).unlink()
        self.host.running.clear()
        self.host.interface = self.host.table = False
        self.host.path(installation.WG_CONFIG).unlink()
        record = self.host.record.copy()
        record.update(phase='installing', unit_hashes={}, wireguard_public_key=None)
        self.host.write_record(record)
        uninstall.apply(self.host, record, True)
        self.assertIsNone(installation.load(self.host))
        self.unchanged_bungee()

    def test_cli_purge_requires_confirmation_before_any_mutation(self):
        args=['uninstall-server.py','--apply','--purge']
        with mock.patch.object(uninstall,'Host',return_value=self.host), mock.patch.object(sys,'argv',args), mock.patch.object(os,'geteuid',return_value=0), mock.patch.object(sys.stdin,'isatty',return_value=False), contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(ValueError,'confirm-purge'):
                uninstall.main()
        self.assertTrue(self.host.interface and self.host.table)
        self.assertEqual(installation.load(self.host)['phase'],'installed')
        self.assertFalse(self.host.path(self.host.record['config']+'/lifecycle.lock').exists())

    def test_cli_adopts_legacy_inventory_then_removes_from_custom_paths(self):
        self.host.path(self.host.record['config']+'/'+installation.RECORD).unlink()
        args=['uninstall-server.py','--apply','--purge','--confirm-purge','DELETE-FIELD-NOTES']
        with mock.patch.object(uninstall,'Host',return_value=self.host), mock.patch.object(sys,'argv',args), mock.patch.object(os,'geteuid',return_value=0), contextlib.redirect_stdout(io.StringIO()):
            uninstall.main()
        self.assertFalse(self.host.interface or self.host.table)
        self.assertFalse(self.host.path(self.host.record['config']).exists())
        self.unchanged_bungee()

    def test_customized_wireguard_instance_and_dropins_refuse(self):
        directory=self.host.path('/etc/systemd/system/wg-quick@fieldnotes.service.d')
        directory.mkdir()
        (directory/'custom.conf').write_text('[Service]\nExecStop=some-other-program\n')
        with self.assertRaisesRegex(ValueError,'customized WireGuard'):
            uninstall.preflight(self.host,self.host.record,True)
        self.assertTrue(self.host.interface and self.host.table)

    def test_wrong_firewall_identity_refuses_without_mutation(self):
        original=self.host.runner
        def altered(args,**kwargs):
            if args==['/usr/sbin/nft','-j','list','table','inet','fieldnotes']:
                return types.SimpleNamespace(stdout=json.dumps({'nftables':[{'table':{'name':'fieldnotes','comment':'Someone else'}}]}))
            return original(args,**kwargs)
        self.host.runner=altered
        with self.assertRaisesRegex(ValueError,'installation identity'):
            uninstall.apply(self.host,self.host.record,True)
        self.assertTrue(self.host.interface and self.host.table)

    def test_concurrent_cli_removal_is_locked_before_any_stop(self):
        import fcntl
        lock=self.host.path(self.host.record['config']+'/lifecycle.lock').open('w')
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        try:
            args=['uninstall-server.py','--apply']
            with mock.patch.object(uninstall,'Host',return_value=self.host), mock.patch.object(sys,'argv',args), mock.patch.object(os,'geteuid',return_value=0), contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(BlockingIOError):uninstall.main()
            self.assertTrue(self.host.interface and self.host.table)
        finally:lock.close()

    def test_installed_entrypoint_forwards_custom_config_and_flags(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            wrapper=root/'uninstall-server.sh'
            wrapper.write_text(installation.uninstall_wrapper('/srv/notes/settings'))
            (root/'uninstall-server.py').write_text('import sys,json;print(json.dumps(sys.argv[1:]))')
            result=subprocess.run(['bash',str(wrapper),'--plan','--purge'],check=True,capture_output=True,text=True)
            self.assertEqual(json.loads(result.stdout),['--config','/srv/notes/settings','--plan','--purge'])
            self.assertFalse((root/'__pycache__').exists())

    def test_cli_help_does_not_create_bytecode_or_require_host_tools(self):
        with tempfile.TemporaryDirectory() as tmp:
            import shutil
            root=pathlib.Path(tmp)
            for name in ('uninstall-server.py','server_installation.py'):
                shutil.copy2(ROOT/'scripts'/name,root/name)
            result=subprocess.run([sys.executable,str(root/'uninstall-server.py'),'--help'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('--purge',result.stdout)
            self.assertFalse((root/'__pycache__').exists())

    def test_inventory_cannot_target_shared_roots_or_other_units(self):
        for field, value in [('data', '/var/lib'), ('prefix', '/opt'), ('config', '/etc/wireguard')]:
            with self.assertRaises(ValueError):
                installation.validate(dict(self.host.record, **{field: value}))
        record = self.host.record.copy()
        record['unit_hashes'] = {'bungee.service': 'a' * 64}
        with self.assertRaises(ValueError):
            installation.validate(record)


class ContainerUninstall(unittest.TestCase):
    def fixture(self, root):
        from test_containers import DockerFixture,settings
        host=Fixture(root)
        config=settings();config.update(installation=host.record['id'])
        host.record.update(runtime='containers',docker={'settings':config,'tags':{'app':'field-notes-app:0.3.0','gateway':'field-notes-gateway:0.3.0'}})
        contents=(ROOT/'deploy/field-notes.service').read_bytes()
        host.path('/etc/systemd/system/field-notes.service').write_bytes(contents)
        host.record['unit_hashes']['field-notes.service']=installation.digest(contents)
        host.path('/etc/systemd/system/field-notes-firewall.service').unlink()
        del host.record['unit_hashes']['field-notes-firewall.service']
        host.write_record(host.record)
        return host,DockerFixture(config)

    def test_container_remove_and_later_purge_preserve_host_network_and_bungee(self):
        with tempfile.TemporaryDirectory() as root:
            host,client=self.fixture(root)
            with mock.patch.object(uninstall,'engine',return_value=client):
                uninstall.apply(host,installation.load(host))
                self.assertFalse(client.present)
                self.assertFalse(any(client.tags.values()))
                self.assertTrue(host.interface and host.table)
                self.assertTrue(host.path(installation.WG_CONFIG).exists())
                self.assertTrue(host.path(host.record['config']+'/master.key').exists())
                self.assertEqual(host.bungee.read_text(),'BUNGEE MUST SURVIVE\n')
                self.assertFalse(any(args[0].endswith(('/wg','/nft','/ip')) for args,_ in host.calls))
                uninstall.apply(host,installation.load(host),purge=True)
                self.assertFalse(host.path(host.record['config']).exists())
                self.assertFalse(host.path(host.record['data']).exists())

    def test_foreign_namespace_member_stops_before_host_mutation(self):
        with tempfile.TemporaryDirectory() as root:
            host,client=self.fixture(root);client.extra_namespace=True
            before=host.path(host.record['config']+'/installation.json').read_bytes()
            with mock.patch.object(uninstall,'engine',return_value=client),self.assertRaises(ValueError):
                uninstall.apply(host,installation.load(host),purge=True)
            self.assertEqual(host.path(host.record['config']+'/installation.json').read_bytes(),before)
            self.assertTrue(host.user_present)
            self.assertFalse(any(args[:2]==['/usr/bin/systemctl','disable'] for args,_ in host.calls))

    def test_only_known_root_owned_bytecode_is_removable(self):
        with tempfile.TemporaryDirectory() as root:
            host,client=self.fixture(root)
            cache=host.path(host.record['prefix']+'/__pycache__');cache.mkdir()
            (cache/'container_deployment.cpython-311.pyc').write_bytes(b'cache')
            with mock.patch.object(uninstall,'engine',return_value=client):
                uninstall.preflight(host,host.record,False)
                (cache/'unrelated.cpython-311.pyc').write_bytes(b'unrelated')
                with self.assertRaises(ValueError):uninstall.preflight(host,host.record,False)


if __name__ == '__main__':
    unittest.main()
