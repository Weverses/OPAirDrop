#!/usr/bin/env python3
"""Run the installer's exact generated SQL against both LSPosed database layouts."""
from pathlib import Path
import sqlite3
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
PKG = 'com.tlsu.oshareairdrop'
MODERN = '''
CREATE TABLE modules(module_pkg_name TEXT PRIMARY KEY NOT NULL,apk_path TEXT);
CREATE TABLE modules_state(module_pkg_name TEXT NOT NULL,user_id INTEGER NOT NULL,
 enabled BOOLEAN DEFAULT 0,scope_request_blocked BOOLEAN DEFAULT 0,
 PRIMARY KEY(module_pkg_name,user_id),FOREIGN KEY(module_pkg_name) REFERENCES modules ON DELETE CASCADE);
CREATE TABLE scope(module_pkg_name TEXT NOT NULL,app_pkg_name TEXT NOT NULL,user_id INTEGER NOT NULL,
 PRIMARY KEY(module_pkg_name,app_pkg_name,user_id),FOREIGN KEY(module_pkg_name) REFERENCES modules ON DELETE CASCADE);
'''
LEGACY = '''
CREATE TABLE modules(mid INTEGER PRIMARY KEY AUTOINCREMENT,module_pkg_name TEXT UNIQUE NOT NULL,
 apk_path TEXT NOT NULL,enabled BOOLEAN NOT NULL DEFAULT 0);
CREATE TABLE scope(mid INTEGER NOT NULL,app_pkg_name TEXT NOT NULL,user_id INTEGER NOT NULL,
 PRIMARY KEY(mid,app_pkg_name,user_id),FOREIGN KEY(mid) REFERENCES modules ON DELETE CASCADE);
'''


def plan(layout, apk='/data/app/test/base.apk'):
    return subprocess.check_output(['java', '-cp', str(ROOT / 'build/lsposed/classes'),
                                    'OPAirShareLSPosed', '--sql', layout, apk], text=True, stderr=subprocess.PIPE)


class ActivationTests(unittest.TestCase):
    def database(self, layout):
        db = sqlite3.connect(':memory:')
        db.execute('PRAGMA foreign_keys=ON')
        db.executescript(MODERN if layout == 'modern' else LEGACY)
        db.execute("INSERT INTO modules(module_pkg_name,apk_path) VALUES ('other.module','/data/app/other/base.apk')")
        db.execute('INSERT INTO modules(module_pkg_name,apk_path) VALUES (?,?)', (PKG, '/data/app/old/base.apk'))
        if layout == 'modern':
            db.execute("INSERT INTO modules_state VALUES ('other.module',0,1,1)")
            db.execute('INSERT INTO modules_state VALUES (?,0,0,1)', (PKG,))
            db.execute('INSERT INTO modules_state VALUES (?,10,0,1)', (PKG,))
            owner = PKG
        else:
            owner = db.execute('SELECT mid FROM modules WHERE module_pkg_name=?', (PKG,)).fetchone()[0]
        db.execute('INSERT INTO scope VALUES (?,?,?)', (owner, 'existing.scope', 0))
        db.commit()
        return db, owner

    def test_enable_scope_preserve_and_repeat(self):
        for layout in ('modern', 'legacy'):
            with self.subTest(layout=layout):
                db, owner = self.database(layout)
                other = db.execute("SELECT * FROM modules WHERE module_pkg_name='other.module'").fetchall()
                for _ in range(2):
                    db.executescript('BEGIN;\n' + plan(layout) + '\nCOMMIT;')
                key = 'module_pkg_name' if layout == 'modern' else 'mid'
                scopes = db.execute(f'SELECT app_pkg_name,user_id FROM scope WHERE {key}=?', (owner,)).fetchall()
                self.assertEqual(set(scopes), {('existing.scope', 0), ('com.coloros.oshare', 0), ('com.google.android.mosey', 0)})
                self.assertEqual(db.execute('SELECT apk_path FROM modules WHERE module_pkg_name=?', (PKG,)).fetchone()[0], '/data/app/test/base.apk')
                self.assertEqual(db.execute("SELECT * FROM modules WHERE module_pkg_name='other.module'").fetchall(), other)
                if layout == 'modern':
                    self.assertEqual(db.execute('SELECT user_id,enabled,scope_request_blocked FROM modules_state WHERE module_pkg_name=? ORDER BY user_id', (PKG,)).fetchall(), [(0, 1, 1), (10, 0, 1)])
                    self.assertEqual(db.execute("SELECT * FROM modules_state WHERE module_pkg_name='other.module'").fetchall(), [('other.module', 0, 1, 1)])
                else:
                    self.assertEqual(db.execute('SELECT mid,enabled FROM modules WHERE module_pkg_name=?', (PKG,)).fetchone(), (owner, 1))
                self.assertEqual(db.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_first_install(self):
        for layout in ('modern', 'legacy'):
            with self.subTest(layout=layout):
                db = sqlite3.connect(':memory:')
                db.executescript(MODERN if layout == 'modern' else LEGACY)
                db.executescript(plan(layout))
                self.assertEqual(db.execute('SELECT COUNT(*) FROM modules').fetchone()[0], 1)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM scope').fetchone()[0], 2)

    def test_quote_path(self):
        db, _ = self.database('modern')
        apk = "/data/app/it's-a-package/base.apk"
        db.executescript(plan('modern', apk))
        self.assertEqual(db.execute('SELECT apk_path FROM modules WHERE module_pkg_name=?', (PKG,)).fetchone()[0], apk)

    def test_bad_inputs_fail_before_any_sql(self):
        for layout, apk in [('unknown', '/data/app/test/base.apk'), ('modern', '/tmp/base.apk'),
                            ('legacy', '/data/app/test\n/base.apk')]:
            with self.subTest(layout=layout, apk=apk):
                with self.assertRaises(subprocess.CalledProcessError) as error:
                    plan(layout, apk)
                self.assertEqual(error.exception.output, '')


if __name__ == '__main__':
    unittest.main()
