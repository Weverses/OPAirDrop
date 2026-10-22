"""Run the production foreground classifier against Android activity-record fixtures."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ChooserForeground(unittest.TestCase):
    def classify(self, record, mode='both', failure=False):
        source = (ROOT / 'module/bin/ui-lifecycle.sh').read_text()
        function = source.split('foreground_state() {', 1)[1].split('\nclose_session()', 1)[0]
        function = 'foreground_state() {' + function
        function = function.replace('/system/bin/timeout 4 /system/bin/dumpsys', 'fake_dumpsys')
        with tempfile.TemporaryDirectory() as work:
            script = '''active_share_mode() { printf '%s\\n' "$MODE"; }
fake_dumpsys() { [ "$FAIL" = 0 ] || return 1; printf '%s\\n' "$RECORD"; }
STATE=$WORK
''' + function + '\nforeground_state\n'
            result = subprocess.run(['sh', '-c', script], capture_output=True, text=True, check=True,
                                    env=dict(os.environ, MODE=mode, RECORD=record, WORK=work, FAIL=str(int(failure))))
            return result.stdout.strip()

    def test_chooser_and_launcher_alias_in_supported_modes(self):
        for mode in ['both', 'oshare']:
            for activity in ['.ChooserActivity', 'com.android.intentresolver.ChooserActivity',
                             '.ChooserActivityLauncher', 'com.android.intentresolver.ChooserActivityLauncher']:
                with self.subTest(mode=mode, activity=activity):
                    self.assertEqual(self.classify('topResumedActivity= ActivityRecord{ab com.android.intentresolver/'
                                                   + activity + ' t12}', mode), 'open')

    def test_other_resolvers_and_prefix_lookalikes_are_closed(self):
        for activity in ['.ResolverActivity', '.ChooserActivityOther', '.ChooserActivityLauncherOther']:
            self.assertEqual(self.classify('topResumedActivity= com.android.intentresolver/' + activity + ' t12}'), 'closed')
        self.assertEqual(self.classify('topResumedActivity= unrelated.package/.ChooserActivity t12}'), 'closed')
        self.assertEqual(self.classify('topResumedActivity= com.android.intentresolver/.ChooserActivity t12}', 'quickshare'), 'closed')

    def test_existing_entrances_and_unknown_state(self):
        self.assertEqual(self.classify('topResumedActivity= com.coloros.oshare/.ui.ShareActivity t12}'), 'open')
        self.assertEqual(self.classify('topResumedActivity= com.google.android.gms/.nearby.sharing.ShareActivity t12}'), 'open')
        self.assertEqual(self.classify('topResumedActivity= com.google.android.gms/.nearby.sharing.ShareActivity t12}', 'oshare'), 'closed')
        self.assertEqual(self.classify(''), 'unknown')
        self.assertEqual(self.classify('', failure=True), 'unknown')


if __name__ == '__main__':
    unittest.main()
