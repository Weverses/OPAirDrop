#!/usr/bin/env python3
"""Exercise persistent entry selection and real supervisor isolation, without a phone."""
import json
from settings_base import Settings
from test_receive_lifecycle import ReceiveLifecycle
import unittest


class ShareModeSettings(Settings):
    def test_default_and_invalid_preference_preserve_coexistence(self):
        for value in ['', 'invalid', 'quickshare;touch bad']:
            (self.base / 'share-mode-store').write_text(value)
            data = json.loads(self.run_api('status').stdout)
            self.assertEqual((data['share_mode'], data['share_mode_active']), ('both', 'both'))

    def test_save_during_transfer_queues_mode_without_interrupting(self):
        (self.state / 'ui-session').touch()
        self.env['TEST_TRANSFER_BUSY'] = '1'
        for mode in ['quickshare', 'oshare', 'both']:
            data = json.loads(self.run_api('share-mode', mode).stdout)
            self.assertEqual((data['share_mode'], data['share_mode_active']), (mode, 'both'))
            self.assertTrue((self.state / 'ui-session').exists())
            self.assertEqual(self.params_now(), self.original)

    def test_offline_mode_applies_without_reboot(self):
        for mode in ['quickshare','oshare','both']:
            data=json.loads(self.run_api('share-mode',mode).stdout)
            self.assertEqual((data['share_mode'],data['share_mode_active']),(mode,mode))

    def test_invalid_values_or_failed_save_do_not_change_preference(self):
        self.run_api('share-mode', 'oshare')
        for args in [('share-mode', 'invalid'), ('share-mode', 'quickshare;touch bad'),
                     ('share-mode',), ('share-mode', 'both', 'extra')]:
            self.run_api(*args, ok=False)
        self.env['TEST_NAME_WRITE_FAIL'] = '1'
        self.run_api('share-mode', 'quickshare', ok=False)
        self.assertEqual(json.loads(self.run_api('status').stdout)['share_mode'], 'oshare')

    def test_cannot_save_without_confirming_current_mode(self):
        self.env['TEST_MODE_PROPERTY_FAIL'] = '1'
        self.run_api('share-mode', 'quickshare', ok=False)
        self.assertFalse((self.base / 'share-mode-store').exists())


class ShareModeLifecycle(ReceiveLifecycle):
    def set_mode(self, mode):
        (self.base / 'prop-debug.opairshare.share_mode').write_text(mode)
        (self.base / 'saved-mode').write_text(mode)

    def test_quickshare_mode_does_not_start_oshare_background_or_panel(self):
        self.set_mode('quickshare')
        log = self.run_lifecycle([{'foreground': 'open'}] * 4)
        self.assertNotIn('oshare:start', log)
        self.assertNotIn('oshare:delegate:start', log)
        self.assertNotIn('provider:ensure', log)
        self.assertNotIn('ready:1', log)

    def test_quickshare_mode_activates_native_panel_and_cleans_up(self):
        self.set_mode('quickshare')
        log = self.run_lifecycle([{'foreground': 'quickshare'}] * 2 + [{}] * 4)
        self.assertIn('provider:ensure', log)
        self.assertIn('provider:release', log)
        self.assertNotIn('oshare:start', log)
        self.assertNotIn('ready:1', log)

    def test_oshare_mode_retains_background_reception(self):
        self.set_mode('oshare')
        log = self.run_lifecycle([{}] * 3)
        self.assertIn('provider:ensure', log)
        self.assertIn('oshare:start', log)
        self.assertIn('ready:1', log)

    def test_idle_switch_releases_old_provider_and_applies_without_reboot(self):
        self.set_mode('both')
        log=self.run_lifecycle([{}, {'mode':'quickshare'}, {'mode':'quickshare'}, {'mode':'quickshare'}])
        self.assertIn('provider:release',log)
        self.assertIn('property:debug.opairshare.share_mode:quickshare',log)
        self.assertEqual((self.base/'prop-debug.opairshare.share_mode').read_text(),'quickshare')
        self.assertNotIn('wifi:',log)

    def test_switch_waits_for_transfer_and_bridge_publication_before_releasing(self):
        self.set_mode('both')
        log=self.run_lifecycle([{}, {'mode':'quickshare','busy':1},
                                {'mode':'quickshare','bridge_busy':True},
                                {'mode':'quickshare'}, {'mode':'quickshare'}])
        lines=log.splitlines()
        self.assertGreater(lines.index('provider:release'),lines.index('tick:2 ready:0 provider:True'))
        self.assertEqual(log.count('provider:release'),1)
        self.assertNotIn('wifi:',log)

    def test_quickshare_to_oshare_starts_receiver_without_reboot(self):
        self.set_mode('quickshare')
        log=self.run_lifecycle([{}, {'mode':'oshare'}, {'mode':'oshare'}])
        self.assertIn('property:debug.opairshare.share_mode:oshare',log)
        self.assertIn('oshare:start',log)
        self.assertIn('ready:1',log)

    def test_legacy_off_markers_are_ignored_and_every_band_receives(self):
        (self.state/'coex-off').touch();(self.state/'coex-same149-only').touch()
        log=self.run_lifecycle([{'frequency':2447}]*2)
        self.assertIn('provider:ensure',log)
        self.assertIn('ready:1',log)
        self.assertNotIn('wifi:',log)


def load_tests(loader, tests, pattern):
    # The imported fixtures contain existing regressions; run the new cases once.
    return unittest.TestSuite(cls(name) for cls in [ShareModeSettings, ShareModeLifecycle]
                              for name in cls.__dict__ if name.startswith('test_'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
