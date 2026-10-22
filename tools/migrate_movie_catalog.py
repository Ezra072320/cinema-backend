"""Copy movie metadata into empty catalog/snapshot tables, preserving current accounts and orders.

Default is a read-only inventory. Back up both databases and stop catalog/trade writers
before --execute. Credentials: SOURCE_MYSQL_PASSWORD and TARGET_MYSQL_PASSWORD.
Requires tools/requirements-migration.txt. Source is never modified.
"""
import argparse
import datetime
import decimal
import hashlib
import json
import os
import re

import pymysql

TABLES = ('type', 'movie', 'person', 'movie_to_type', 'movie_crew', 'movie_image',
          'movie_video', 'movie_tag', 'movie_alt_title')


def connect(host, port, user, password):
    connection = pymysql.connect(host=host, port=port, user=user, password=password,
                                 charset='utf8mb4', autocommit=False, connect_timeout=5,
                                 read_timeout=90, write_timeout=90)
    with connection.cursor() as cursor:
        cursor.execute("SET time_zone = '+08:00'")
    return connection


def canonical(value):
    if isinstance(value, (datetime.datetime, datetime.date, datetime.time, decimal.Decimal)):
        return str(value)
    if isinstance(value, bytes):
        return {'binary_hex': value.hex()}
    raise TypeError(type(value).__name__)


def fingerprint(cursor, query):
    cursor.execute(query)
    digest = hashlib.sha256()
    count = 0
    while rows := cursor.fetchmany(500):
        for row in rows:
            digest.update(json.dumps(row, ensure_ascii=False, separators=(',', ':'), default=canonical).encode())
            digest.update(b'\n')
            count += 1
    return {'rows': count, 'sha256': digest.hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-host', default='127.0.0.1')
    parser.add_argument('--source-port', type=int, default=3306)
    parser.add_argument('--source-user', required=True)
    parser.add_argument('--source-database', default='cinema')
    parser.add_argument('--target-host', default='127.0.0.1')
    parser.add_argument('--target-port', type=int, default=3306)
    parser.add_argument('--target-user', required=True)
    parser.add_argument('--target-catalog', default='cinema_catalog')
    parser.add_argument('--target-trade', default='cinema_trade')
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    if any(not re.fullmatch(r'[A-Za-z0-9_]+', name) for name in
           [args.source_database, args.target_catalog, args.target_trade]):
        parser.error('Use alphanumeric database names with optional underscores.')
    if (args.source_host, args.source_port) == (args.target_host, args.target_port) and args.source_database in [args.target_catalog, args.target_trade]:
        parser.error('Source and target must be different databases.')
    source = connect(args.source_host, args.source_port, args.source_user, os.environ['SOURCE_MYSQL_PASSWORD'])
    target = connect(args.target_host, args.target_port, args.target_user, os.environ['TARGET_MYSQL_PASSWORD'])
    report = {'executed': args.execute, 'tables': {}}
    try:
        with source.cursor(pymysql.cursors.SSCursor) as read, target.cursor() as write:
            read.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
            read.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
            for database, minimum in [(args.target_catalog, 2), (args.target_trade, 3)]:
                write.execute(f'SELECT COUNT(*) FROM `{database}`.flyway_schema_history WHERE success=1')
                if write.fetchone()[0] < minimum:
                    raise RuntimeError(f'{database}: initialize the service schema before importing.')
            target_counts = {}
            for database, tables in [(args.target_catalog, TABLES), (args.target_trade, ['movie_snapshot'])]:
                for table in tables:
                    write.execute(f'SELECT COUNT(*) FROM `{database}`.`{table}`')
                    target_counts[f'{database}.{table}'] = write.fetchone()[0]
            if args.execute and any(target_counts.values()):
                raise RuntimeError('Movie metadata targets are not empty; existing data will not be overwritten.')
            report['target_counts_before'] = target_counts
            for table in TABLES:
                read.execute('SELECT column_name FROM information_schema.columns WHERE table_schema=%s '
                             'AND table_name=%s AND generation_expression=%s ORDER BY ordinal_position',
                             (args.source_database, table, ''))
                columns = [row[0] for row in read.fetchall()]
                if not columns:
                    raise RuntimeError(f'Source table is missing: {table}')
                write.execute('SELECT column_name FROM information_schema.columns WHERE table_schema=%s AND table_name=%s',
                              (args.target_catalog, table))
                available = {row[0] for row in write.fetchall()}
                if set(columns) - available:
                    raise RuntimeError(f'Incompatible columns in {table}: {sorted(set(columns) - available)}')
                read.execute('SELECT column_name FROM information_schema.key_column_usage WHERE table_schema=%s '
                             'AND table_name=%s AND constraint_name=%s ORDER BY ordinal_position',
                             (args.source_database, table, 'PRIMARY'))
                primary = [row[0] for row in read.fetchall()]
                if not primary:
                    raise RuntimeError(f'Missing primary key in {table}; cannot verify deterministic row checksums.')
                projection = ','.join(f'`{name}`' for name in columns)
                order = ','.join(f'`{name}`' for name in primary)
                query = f'SELECT {projection} FROM `{args.source_database}`.`{table}` ORDER BY {order}'
                expected = fingerprint(read, query)
                report['tables'][table] = {'source': expected}
                if args.execute:
                    read.execute(query)
                    statement = (f'INSERT INTO `{args.target_catalog}`.`{table}` ({projection}) VALUES ('
                                 + ','.join(['%s'] * len(columns)) + ')')
                    while rows := read.fetchmany(500):
                        write.executemany(statement, rows)
                    actual = fingerprint(write, f'SELECT {projection} FROM `{args.target_catalog}`.`{table}` ORDER BY {order}')
                    if actual != expected:
                        raise RuntimeError(f'Row count or checksum mismatch: {table}')
                    report['tables'][table]['target'] = actual
            if args.execute:
                projection = 'id,title,poster_url,runtime_min,total_box'
                query = f'SELECT {projection} FROM `{args.source_database}`.movie ORDER BY id'
                expected = fingerprint(read, query)
                read.execute(query)
                while rows := read.fetchmany(500):
                    write.executemany(f'INSERT INTO `{args.target_trade}`.movie_snapshot({projection}) VALUES(%s,%s,%s,%s,%s)', rows)
                actual = fingerprint(write, f'SELECT {projection} FROM `{args.target_trade}`.movie_snapshot ORDER BY id')
                if expected != actual:
                    raise RuntimeError('Movie snapshot count or checksum mismatch.')
                report['movie_snapshots'] = actual
                target.commit()
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except Exception:
        target.rollback()
        raise
    finally:
        source.rollback()
        source.close()
        target.close()


if __name__ == '__main__':
    main()
