#!/usr/bin/env python3
"""Exercise manager capabilities and effective mounts without touching a device."""
from pathlib import Path
import hashlib
import importlib.util
import os
import shutil
import subprocess
import tempfile
import unittest
import sys

ROOT = Path(__file__).resolve().parents[1]


class RootRuntime(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='opairshare-root-')
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.adb = self.base / 'adb'
        self.system = self.base / 'system-bin'
        self.system.mkdir()
        self.calls = self.base / 'calls'
        self.library = self.base / 'root-runtime.sh'
        self.library.write_text((ROOT / 'module/bin/root-runtime.sh').read_text()
                                .replace('/data/adb', str(self.adb))
                                .replace('/system/bin', str(self.system)))
        self.env = {k: v for k, v in os.environ.items()
                    if k not in ('KSU', 'APATCH', 'MAGISK_VER', 'MAGISK_VER_CODE', 'OPAIRSHARE_GLOBAL_ENTERED')}
        self.env.update(CALLS=str(self.calls), PATH=str(self.system) + ':' + os.environ['PATH'])

    def command(self, path, code):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#!/bin/sh\n' + code + '\n')
        path.chmod(0o755)
        return path

    def busybox(self, manager, applets=('mount', 'umount', 'nsenter')):
        return self.command(self.adb / manager / 'busybox',
                            'if [ "$1" = --list ]; then printf "%s\\n" ' +
                            ' '.join(applets) + '; exit; fi\n' +
                            f'printf "{manager} %s\\n" "$*" >> "$CALLS"\n' +
                            'exit "${TOOL_RC:-0}"')

    def run_shell(self, code, *args):
        return subprocess.run(['sh', '-eu', '-c', '. "$1"; shift; ' + code, 'root-test',
                               str(self.library), *map(str, args)], env=self.env,
                              capture_output=True, text=True, timeout=5)

    def test_active_manager_tools_win_over_stale_installations(self):
        for path in ('ksu/bin', 'ap/bin', 'magisk'):
            self.busybox(path)
        for hints, expected in [({'KSU': 'true'}, 'ksu/bin'),
                                ({'APATCH': 'true'}, 'ap/bin'),
                                ({'MAGISK_VER': '30.0'}, 'magisk')]:
            with self.subTest(manager=expected):
                self.env.update(KSU='false', APATCH='false', MAGISK_VER='')
                self.env.update(hints)
                result = self.run_shell('root_tool mount -o remount,bind,ro "file with spaces" target')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.calls.read_text().splitlines()[-1],
                                 expected + ' mount -o remount,bind,ro file with spaces target')

    def test_applet_capability_and_native_fallback(self):
        self.env['KSU'] = 'true'
        self.busybox('ksu/bin', ('echo',))
        self.busybox('ap/bin')
        self.assertEqual(self.run_shell('root_tool mount -o bind source target').returncode, 0)
        self.assertIn('ap/bin mount', self.calls.read_text())
        shutil.rmtree(self.adb)
        self.command(self.system / 'mount', 'printf "native %s\\n" "$*" >> "$CALLS"; exit 19')
        result = self.run_shell('root_tool mount -o bind source target')
        self.assertEqual(result.returncode, 19)
        self.assertIn('native -o bind source target', self.calls.read_text())
        result = self.run_shell('root_tool opairshare_missing_tool')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('required tool unavailable', result.stderr)

    def test_tool_failures_propagate_without_retrying_a_mutation(self):
        self.busybox('ksu/bin')
        self.busybox('ap/bin')
        self.env['TOOL_RC'] = '23'
        self.assertEqual(self.run_shell('root_tool mount -o bind source target').returncode, 23)
        self.assertEqual(len(self.calls.read_text().splitlines()), 1)

    def test_global_namespace_reexec_preserves_arguments_and_rejects_failed_entry(self):
        self.command(self.system / 'readlink', '''
case "$1" in
 /proc/1/ns/mnt) echo global;;
 *) if [ -n "${OPAIRSHARE_GLOBAL_ENTERED:-}" ] && [ "${FAILED_ENTRY:-0}" = 0 ]; then echo global; else echo private; fi;;
esac''')
        (self.system / 'sh').symlink_to('/bin/sh')
        self.command(self.system / 'nsenter', '''
printf '%s\\n' "$*" >> "$CALLS"
[ "$1 $2 $3" = '-t 1 -m' ] || exit 99
shift 3
exec "$@"''')
        runner = self.base / 'runner.sh'
        runner.write_text(f'. "{self.library}"\nroot_enter_global "$0" "$@" || exit 2\nprintf "arg=%s\\n" "$1"\n')
        result = subprocess.run(['sh', str(runner), 'space argument'], env=self.env,
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, 'arg=space argument\n')
        self.env['FAILED_ENTRY'] = '1'
        result = subprocess.run(['sh', str(runner), 'arg'], env=self.env,
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('cannot enter', result.stderr)
        self.assertEqual(len(self.calls.read_text().splitlines()), 2)

    def test_policy_uses_actual_root_domain_and_safe_manager_fallback(self):
        module = self.base / 'module'
        (module / 'bin').mkdir(parents=True)
        template = ROOT / 'module/bin/root-sepolicy.rule'
        shutil.copy2(template, module / 'bin')
        self.command(self.system / 'id', 'echo "${CONTEXT:-u:r:recovery:s0}"')
        for context, hints, expected in [
            ('u:r:ksu:s0', {}, 'ksu'), ('u:r:magisk:s0', {}, 'magisk'),
            ('u:r:su:s0', {}, 'su'), ('u:r:recovery:s0', {'KSU': 'true'}, 'ksu'),
            ('u:r:recovery:s0', {'APATCH': 'true'}, 'magisk'),
            ('u:r:recovery:s0', {'MAGISK_VER': '30'}, 'magisk'),
        ]:
            self.env.update(CONTEXT=context, KSU='false', APATCH='false', MAGISK_VER='')
            self.env.update(hints)
            result = self.run_shell('root_write_policy "$1"', module)
            self.assertEqual(result.returncode, 0, result.stderr)
            policy = (module / 'sepolicy.rule').read_text()
            self.assertIn(f'allow {expected} mosey_service', policy)
            self.assertNotIn('ROOT_DOMAIN', policy)
            self.assertEqual((module / 'bin/root-sepolicy.rule').read_bytes(), template.read_bytes())
        self.env.update(CONTEXT='u:r:untrusted_app:s0', KSU='false', APATCH='false', MAGISK_VER='')
        result = self.run_shell('root_write_policy "$1"', module)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('unsupported SELinux context', result.stderr)

    def test_full_install_generates_policy_without_breaking_payload_verification(self):
        module = self.base / 'installed-module'
        shutil.copytree(ROOT / 'module', module)
        installer = module / 'customize.sh'
        installer.write_text(installer.read_text().replace('/data/adb', str(self.adb)))
        for name in ('setup-gms.sh', 'install-oshare.sh'):
            (module / 'bin' / name).write_text('echo fixture; exit 0\n')
        self.command(self.system / 'id', '''
case "$1" in -u) echo 0;; -Z) echo "${CONTEXT}";; *) exit 1;; esac''')
        self.command(self.system / 'getprop', '''
case "$1" in ro.board.platform) echo sun;; ro.product.*) echo OnePlus;; esac''')
        (module / 'SHA256SUMS').write_text(''.join(
            f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(module)}\n'
            for path in sorted(module.rglob('*')) if path.is_file()))
        for context, hint in [('ksu', 'KSU=true'), ('magisk', 'MAGISK_VER=30'),
                              ('magisk', 'APATCH=true'), ('su', '')]:
            self.env.update(CONTEXT=f'u:r:{context}:s0', KSU='false', APATCH='false', MAGISK_VER='')
            prefix = (hint + '; ') if hint else ''
            result = self.run_shell(prefix + '''
MODPATH=$1
ui_print() { echo "$*"; }
abort() { echo "$*" >&2; exit 2; }
set_perm() { :; }
set_perm_recursive() { :; }
. "$MODPATH/customize.sh"
sh "$MODPATH/bin/verify.sh" "$MODPATH" installed
''', module)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn(f'allow {context} mosey_service', (module / 'sepolicy.rule').read_text())
            self.assertFalse((self.adb / 'metamodule').exists())

    def test_release_overlay_replaces_captured_policy_with_install_template(self):
        sys.path.insert(0, str(ROOT / 'tools'))
        self.addCleanup(sys.path.remove, str(ROOT / 'tools'))
        spec = importlib.util.spec_from_file_location('package_coex', ROOT / 'tools/package-coex.py')
        packager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(packager)
        source = self.base / 'source'
        (source / 'module').mkdir(parents=True)
        shutil.copytree(ROOT / 'module/bin', source / 'module/bin')
        (source / 'webui/dist').mkdir(parents=True)
        (source / 'webui/dist/index.html').write_text('fixture')
        packager.ROOT = source
        module = self.base / 'release'
        (module / 'bin').mkdir(parents=True)
        (module / 'webroot').mkdir()
        (module / 'sepolicy.rule').write_text('old device policy')
        packager.overlay_sources(module)
        self.assertFalse((module / 'sepolicy.rule').exists())
        self.assertIn('ROOT_DOMAIN', (module / 'bin/root-sepolicy.rule').read_text())
        self.assertTrue((module / 'bin/root-runtime.sh').is_file())
        self.assertTrue((module / 'bin/mount-check.sh').is_file())

    def test_callback_ownership_uses_backing_inode_with_any_mount_root(self):
        mountinfo = self.base / 'mountinfo'
        target = self.base / 'target.apk'
        source = self.base / 'callback.apk'
        source.write_text('patched')
        target.hardlink_to(source)
        code = (ROOT / 'module/bin/callback.sh').read_text().split('owned() {', 1)[1].split('restore_owned()', 1)[0]
        function = 'owned() {' + code.replace('/proc/self/mountinfo', str(mountinfo))
        for mount_root in ('/adb/callback.apk', '/data/adb/callback.apk', '/arbitrary/root'):
            mountinfo.write_text(f'1 2 0:1 {mount_root} {target} ro - ext4 /dev/test ro\n')
            result = self.run_shell(function + '\nowned "$1" "$2"', target, source)
            self.assertEqual(result.returncode, 0, result.stderr)
        # Same bytes from someone else's mount do not establish ownership.
        target.unlink()
        target.write_bytes(source.read_bytes())
        self.assertNotEqual(self.run_shell(function + '\nowned "$1" "$2"', target, source).returncode, 0)
        target.unlink()
        target.hardlink_to(source)
        mountinfo.write_text('')
        self.assertNotEqual(self.run_shell(function + '\nowned "$1" "$2"', target, source).returncode, 0)


class EffectiveMounts(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='opairshare-mounts-')
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.module = self.base / 'module with spaces'
        shutil.copytree(ROOT / 'module', self.module)
        self.device = self.base / 'device'
        # Minimal real file tree; no manager or metamodule directory exists.
        self.binary = 'system_ext/bin/mosey_server'
        for rel in [self.binary, 'system_ext/lib64/libmosey_daemon_ffi.so']:
            source = self.module / 'system' / rel
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_text('fixture')
            source.chmod(0o755)
        for line in (self.module / 'APP-SHA256SUMS').read_text().splitlines():
            apk = self.module / 'system/system_ext/priv-app/MoseyApp' / line.split()[1]
            apk.parent.mkdir(parents=True, exist_ok=True)
            apk.write_text('original APK')
        shutil.copytree(self.module / 'system', self.device)

    def check(self):
        return subprocess.run(['sh', str(self.module / 'bin/mount-check.sh'),
                               str(self.module), str(self.device)], capture_output=True, text=True)

    def test_builtin_mounts_without_metamodule_pass_and_relocated_layout_passes(self):
        self.assertEqual(self.check().returncode, 0)
        for partition in ('product', 'system_ext'):
            (self.module / 'system' / partition).rename(self.module / partition)
        result = self.check()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_mounts_report_mountify_configuration(self):
        (self.device / self.binary).unlink()
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(self.binary, result.stderr)
        self.assertIn('Mountify', result.stderr)
        self.assertIn('skip_mount', result.stderr)

    def test_conflicting_xml_or_missing_split_is_rejected(self):
        path = self.device / 'product/etc/permissions/mosey-hiddenapi-opairshare.xml'
        path.write_text('<permissions/>')
        self.assertIn('configuration differs', self.check().stderr)
        shutil.copy2(self.module / 'system' / path.relative_to(self.device), path)
        (self.device / 'system_ext/priv-app/MoseyApp/split_config.en.apk').unlink()
        self.assertIn('missing system APK', self.check().stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
