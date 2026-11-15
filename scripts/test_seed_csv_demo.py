import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from seed_csv_demo import DATA, connection_settings, load_bundle


class BundleTest(unittest.TestCase):
    def test_bundle_is_complete_and_contains_no_account_or_request_fields(self):
        manifest, catalog, rows, links = load_bundle()
        self.assertEqual(len(rows), 3265)
        self.assertEqual(len(catalog['stores']), 2224)
        self.assertEqual(manifest['products'], 1980)
        self.assertEqual(manifest['statuses']['ON_SALE'], 1907)
        self.assertEqual(rows.keys(), links.keys())
        for store in catalog['stores']:
            self.assertEqual(set(store), {'store_key', 'name', 'business_type', 'address',
                'latitude', 'longitude', 'area', 'products', 'hours'})

    def test_missing_or_tampered_bundle_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            manifest = json.loads((DATA / 'manifest.json').read_text(encoding='utf-8'))
            (target / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            (target / 'catalog.json').write_text('{}', encoding='utf-8')
            with self.assertRaises((ValueError, FileNotFoundError)):
                load_bundle(target)

    def test_unexpected_manifest_path_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            manifest = json.loads((DATA / 'manifest.json').read_text(encoding='utf-8'))
            manifest['files']['../secret'] = '0' * 64
            (target / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
            with self.assertRaises(ValueError):
                load_bundle(target)

    def test_only_dedicated_databases_are_allowed(self):
        for name in ('together_market', 'production', 'mysql', 'demo; DROP DATABASE x'):
            with patch.dict(os.environ, {'CSV_DEMO_DB_NAME': name}, clear=True):
                with self.assertRaises(ValueError):
                    connection_settings()
        with patch.dict(os.environ, {'CSV_DEMO_DB_PASSWORD': 'test-only'}, clear=True):
            self.assertEqual(connection_settings()['database'], 'together_market_csv_demo')

    def test_production_profile_is_rejected(self):
        with patch.dict(os.environ, {'SPRING_PROFILES_ACTIVE': 'demo,production'}, clear=True):
            with self.assertRaises(ValueError):
                connection_settings()


if __name__ == '__main__':
    unittest.main()
