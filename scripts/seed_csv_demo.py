"""Load the versioned CSV demonstration bundle into a schema initialized by Flyway."""
import argparse
import csv
import hashlib
import json
import os
import re
import secrets
from collections import Counter
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'data/csv-demo'
SOURCE_FILES = {f'{area}_candidates_{source}.csv': (area.upper(), source.upper())
                for area in ('bojeong', 'dongbaek') for source in ('sbiz', 'ypay')}


def load_bundle(directory=DATA):
    directory = Path(directory).resolve()
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    expected = {'catalog.json'} | {'raw/' + name for name in SOURCE_FILES}
    if manifest.get('format_version') != 1 or set(manifest['files']) != expected:
        raise ValueError('Unexpected bundle files or version')
    for name, checksum in manifest['files'].items():
        file = (directory / name).resolve()
        if not file.is_relative_to(directory) or hashlib.sha256(file.read_bytes()).hexdigest() != checksum:
            raise ValueError('Bundle checksum mismatch: ' + name)
    catalog = json.loads((directory / 'catalog.json').read_text(encoding='utf-8'))
    stores = catalog['stores']
    keys = {s['store_key'] for s in stores}
    if len(keys) != len(stores) or len(stores) != manifest['stores']:
        raise ValueError('Invalid store identities/count')
    products = []
    for store in stores:
        if not re.fullmatch(r'[0-9a-f]{64}', store['store_key']) or not store['name'].startswith('[시연]'):
            raise ValueError('Only synthetic store identities are supported')
        if len({p['name'] for p in store['products']}) != len(store['products']):
            raise ValueError('Duplicate product name within a store')
        for p in store['products']:
            if (not p['name'].startswith('[시연]') or '시연용' not in p['description']
                    or type(p['price_amount']) is not int or p['price_amount'] < 0
                    or p['sale_status'] not in ('ON_SALE', 'SOLD_OUT', 'PAUSED')):
                raise ValueError('Invalid synthetic product')
            products.append(p)
    if len(products) != manifest['products'] or dict(Counter(p['sale_status'] for p in products)) != manifest['statuses']:
        raise ValueError('Product counts do not match manifest')
    raw_rows = {}
    for filename in SOURCE_FILES:
        with (directory / 'raw' / filename).open(encoding='utf-8-sig', newline='') as file:
            for number, row in enumerate(csv.DictReader(file), 2):
                raw_rows[(filename, number)] = row
    links = {(r['file'], r['line']): r['store_key'] for r in catalog['source_links']}
    if (len(links) != len(catalog['source_links']) or links.keys() != raw_rows.keys()
            or len(raw_rows) != manifest['original_csv_rows']
            or any(key is not None and key not in keys for key in links.values())):
        raise ValueError('Invalid source row links')
    return manifest, catalog, raw_rows, links


def connection_settings():
    database = os.environ.get('CSV_DEMO_DB_NAME', 'together_market_csv_demo')
    if not re.fullmatch(r'together_market_csv_(?:demo|test)(?:_[a-z0-9_]+)?', database):
        raise ValueError('Use a dedicated together_market_csv_demo or together_market_csv_test database')
    if any(p.strip().lower() in ('prod', 'production') for p in os.environ.get('SPRING_PROFILES_ACTIVE', '').split(',')):
        raise ValueError('Do not run a demonstration seed in a production profile')
    password = os.environ.get('CSV_DEMO_DB_PASSWORD') or os.environ.get('DB_PASSWORD')
    if not password:
        raise ValueError('Set CSV_DEMO_DB_PASSWORD or DB_PASSWORD; values are never printed')
    return dict(host=os.environ.get('CSV_DEMO_DB_HOST', '127.0.0.1'),
                port=int(os.environ.get('CSV_DEMO_DB_PORT', '3306')),
                user=os.environ.get('CSV_DEMO_DB_USERNAME', os.environ.get('DB_USERNAME', 'root')),
                password=password, database=database, charset='utf8mb4', autocommit=False)


def seed(connection, manifest, catalog, raw_rows, links):
    import bcrypt
    counts = Counter()
    store_ids = {}
    with connection.cursor() as c:
        c.execute("SELECT GET_LOCK(CONCAT(DATABASE(), ':csv_demo_seed_v1'),10)")
        if c.fetchone()[0] != 1:
            raise RuntimeError('Another CSV import is running')
        try:
            c.execute('SELECT version FROM flyway_schema_history WHERE success=1')
            if not {'1', '2', '3'} <= {r[0] for r in c.fetchall()}:
                raise ValueError('Initialize the application schema with Flyway first')
            c.execute('SELECT id FROM purpose_categories')
            categories = {r[0] for r in c.fetchall()}
            if any(tag not in categories for s in catalog['stores'] for p in s['products'] for tag in p['purpose_category_ids']):
                raise ValueError('Required purpose categories are missing')
            # No shared password or password hash is included in the bundle.
            locked_password = bcrypt.hashpw(secrets.token_urlsafe(48).encode(), bcrypt.gensalt()).decode()
            for s in catalog['stores']:
                email = 'csv-demo-' + s['store_key'] + '@demo.together.invalid'
                c.execute('SELECT id,role FROM users WHERE email=%s', (email,))
                owner = c.fetchone()
                if owner is None:
                    c.execute("INSERT INTO users(email,password_hash,name,role) VALUES(%s,%s,'CSV 시연 점주','OWNER')", (email, locked_password))
                    owner = (c.lastrowid, 'OWNER')
                    counts['new_owners'] += 1
                if owner[1] != 'OWNER':
                    raise ValueError('Existing account role conflict')
                c.execute('SELECT id,name,address FROM stores WHERE owner_id=%s', (owner[0],))
                existing = c.fetchall()
                if len(existing) > 1:
                    raise ValueError('Multiple stores for a demonstration owner')
                if existing:
                    sid, name, address = existing[0]
                    if name != s['name'] or address != s['address']:
                        raise ValueError('Existing store identity changed; review before importing')
                else:
                    c.execute('INSERT INTO stores(owner_id,name,business_type,address,latitude,longitude,phone) VALUES(%s,%s,%s,%s,%s,%s,%s)',
                              (owner[0], s['name'], s['business_type'], s['address'], s['latitude'], s['longitude'], ''))
                    sid = c.lastrowid
                    counts['new_stores'] += 1
                store_ids[s['store_key']] = sid
                c.execute('SELECT id,name FROM products WHERE store_id=%s', (sid,))
                existing_products = c.fetchall()
                products = {name: pid for pid, name in existing_products}
                if len(products) != len(existing_products):
                    raise ValueError('Ambiguous duplicate product names')
                for p in s['products']:
                    pid = products.get(p['name'])
                    if pid is None:
                        c.execute('INSERT INTO products(store_id,name,description,price_amount,preparation_minutes,sale_status) VALUES(%s,%s,%s,%s,%s,%s)',
                                  (sid, p['name'], p['description'], p['price_amount'], p['preparation_minutes'], p['sale_status']))
                        pid = c.lastrowid
                        counts['new_products'] += 1
                    # Existing prices, descriptions and sale states are deliberately preserved.
                    c.execute('SELECT purpose_category_id FROM product_purpose_tags WHERE product_id=%s', (pid,))
                    present = {r[0] for r in c.fetchall()}
                    for tag in p['purpose_category_ids']:
                        if tag not in present:
                            c.execute('INSERT INTO product_purpose_tags(product_id,purpose_category_id) VALUES(%s,%s)', (pid, tag))
                            counts['new_tags'] += 1
                c.execute('SELECT weekday,hour_type FROM store_hours WHERE store_id=%s AND weekday IS NOT NULL', (sid,))
                present_hours = set(c.fetchall())
                for h in s['hours']:
                    if (h['weekday'], h['hour_type']) not in present_hours:
                        c.execute('INSERT INTO store_hours(store_id,weekday,hour_type,is_closed,opens_at,closes_at,end_day_offset) VALUES(%s,%s,%s,%s,%s,%s,%s)',
                                  (sid, h['weekday'], h['hour_type'], h['is_closed'], h['opens_at'], h['closes_at'], h['end_day_offset']))
                        counts['new_hours'] += 1
            for (filename, line), row in raw_rows.items():
                area, provider = SOURCE_FILES[filename]
                source = f'CSV_DEMO_V1_{area}_{provider}'
                # Store the original source identifier, never the exported public_record_id.
                external_id = row.get('external_id') or row['source_record_key']
                source_row = row.get('source_row_number') or str(line - 1)
                key = links[(filename, line)]
                matched = store_ids[key] if key is not None else None
                c.execute('SELECT id,matched_store_id FROM public_store_records WHERE source=%s AND external_id=%s AND source_row_number=%s',
                          (source, external_id, source_row))
                existing = c.fetchall()
                if len(existing) > 1 or (existing and existing[0][1] != matched):
                    raise ValueError('Existing public source mapping conflict')
                if not existing:
                    branch = row.get('branch_name') or ''
                    if branch == 'NULL':
                        branch = ''
                    c.execute('INSERT INTO public_store_records(source,external_id,source_row_number,name,branch_name,business_type,address,latitude,longitude,last_seen_at,matched_store_id) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',
                              (source, external_id, source_row, row['name'], branch, row['business_type'], row['address'],
                               row.get('latitude') or None, row.get('longitude') or None,
                               datetime.fromisoformat(manifest['source_observed_at']).replace(tzinfo=None), matched))
                    counts['new_public_records'] += 1
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            c.execute("SELECT RELEASE_LOCK(CONCAT(DATABASE(), ':csv_demo_seed_v1'))")
    return dict(counts), store_ids


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Insert into the explicitly configured demonstration database')
    parser.add_argument('--data-dir', type=Path, default=DATA)
    args = parser.parse_args()
    manifest, catalog, rows, links = load_bundle(args.data_dir)
    summary = {k: manifest[k] for k in ('original_csv_rows', 'stores', 'product_stores', 'products', 'statuses')}
    summary['applied'] = args.apply
    if args.apply:
        import pymysql
        with pymysql.connect(**connection_settings()) as connection:
            changes, ids = seed(connection, manifest, catalog, rows, links)
        runtime = args.data_dir / 'runtime'
        runtime.mkdir(parents=True, exist_ok=True)
        pending = runtime / 'candidate-store-ids.tmp'
        pending.write_text(''.join(f'{sid}\n' for sid in sorted(ids.values())), encoding='utf-8')
        pending.replace(runtime / 'candidate-store-ids.txt')
        summary['changes'] = changes
        (runtime / 'last-import.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    main()
