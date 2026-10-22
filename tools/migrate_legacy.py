"""Offline, additive migration from the upgraded monolith into initialized empty service databases.

Dependencies: python -m pip install -r tools/requirements-migration.txt
Default: read-only inventory. Pass --execute only after stopping all writers.
Credentials are read from SOURCE_MYSQL_PASSWORD and TARGET_MYSQL_PASSWORD.
"""
import argparse
import os
import json
import pymysql

OWNERS = {
    'identity': ['user', 'admin'],
    'catalog': ['type', 'movie', 'movie_to_type', 'user_want_movie', 'person', 'movie_crew',
                'movie_image', 'movie_video', 'movie_tag', 'movie_alt_title'],
    'trade': ['hall', 'seat', 'schedule', 'ticket', 'order', 'score', 'hall_seat_version',
              'seat_template', 'seat_template_unit', 'seat_couple_group', 'order_action', 'admin_operation_log'],
    'ai': ['ai_session', 'ai_message', 'ai_action'],
}


def connect(host, port, user, password):
    return pymysql.connect(host=host, port=port, user=user, password=password,
                           charset='utf8mb4', autocommit=False, connect_timeout=5,
                           read_timeout=60, write_timeout=60)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-host', default='127.0.0.1')
    parser.add_argument('--source-port', type=int, default=3306)
    parser.add_argument('--source-user', required=True)
    parser.add_argument('--source-database', default='cinema')
    parser.add_argument('--target-host', default='127.0.0.1')
    parser.add_argument('--target-port', type=int, default=3306)
    parser.add_argument('--target-user', required=True)
    parser.add_argument('--target-prefix', default='cinema_')
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    names = [args.source_database, *[args.target_prefix + owner for owner in OWNERS]]
    if any(not name.replace('_', '').isalnum() for name in names):
        parser.error('Database names must contain only letters, numbers and underscores.')
    if args.source_host == args.target_host and args.source_port == args.target_port and args.source_database in names[1:]:
        parser.error('Source and target must be different databases.')
    source = connect(args.source_host, args.source_port, args.source_user, os.environ['SOURCE_MYSQL_PASSWORD'])
    target = connect(args.target_host, args.target_port, args.target_user, os.environ['TARGET_MYSQL_PASSWORD'])
    counts = {}
    try:
        with source.cursor(pymysql.cursors.SSCursor) as read, target.cursor() as write:
            read.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
            read.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
            # Validate all targets before writing any rows. Initial Flyway migrations must already exist.
            for owner, tables in OWNERS.items():
                database = args.target_prefix + owner
                write.execute(f'SELECT COUNT(*) FROM `{database}`.flyway_schema_history WHERE success=1')
                if write.fetchone()[0] < (3 if owner == 'trade' else 2):
                    raise RuntimeError(f'{database}: initialize the schema with the new service first')
                for table in [*tables, 'service_operation', *(['movie_snapshot'] if owner == 'trade' else [])]:
                    write.execute(f'SELECT COUNT(*) FROM `{database}`.`{table}`')
                    if write.fetchone()[0]:
                        raise RuntimeError(f'{database}.{table} is not empty; migration never overwrites existing data')
            for owner, tables in OWNERS.items():
                database = args.target_prefix + owner
                for table in tables:
                    read.execute(f'SELECT COUNT(*) FROM `{args.source_database}`.`{table}`')
                    count = read.fetchall()[0][0]
                    counts[f'{database}.{table}'] = count
                    if not args.execute:
                        continue
                    # MySQL rejects explicit inserts into generated columns (e.g. V24 seat_a/seat_b).
                    read.execute('SELECT column_name FROM information_schema.columns '
                                 'WHERE table_schema=%s AND table_name=%s AND generation_expression=%s '
                                 'ORDER BY ordinal_position', (args.source_database, table, ''))
                    columns = [row[0] for row in read.fetchall()]
                    projection = ','.join(f'`{name}`' for name in columns)
                    read.execute(f'SELECT {projection} FROM `{args.source_database}`.`{table}`')
                    sql = (f'INSERT INTO `{database}`.`{table}` (' + ','.join(f'`{name}`' for name in columns)
                           + ') VALUES (' + ','.join(['%s'] * len(columns)) + ')')
                    while rows := read.fetchmany(500):
                        write.executemany(sql, rows)
                    write.execute(f'SELECT COUNT(*) FROM `{database}`.`{table}`')
                    if write.fetchone()[0] != count:
                        raise RuntimeError(f'Row count mismatch: {database}.{table}')
            if args.execute:
                trade = args.target_prefix + 'trade'
                read.execute(f'SELECT id,title,poster_url,runtime_min,total_box FROM `{args.source_database}`.movie')
                while rows := read.fetchmany(500):
                    write.executemany(f'INSERT INTO `{trade}`.movie_snapshot(id,title,poster_url,runtime_min,total_box) VALUES(%s,%s,%s,%s,%s)', rows)
                read.execute(f'SELECT id,name FROM `{args.source_database}`.`user`')
                while rows := read.fetchmany(500):
                    write.executemany(f'UPDATE `{trade}`.score SET author_name=%s WHERE user_id=%s', [(name, uid) for uid, name in rows])
                # All four target schemas share this migration connection, so a failure rolls back all copied rows.
                target.commit()
        print(json.dumps({'executed': args.execute, 'row_counts': counts}, ensure_ascii=False, indent=2))
    except Exception:
        target.rollback()
        raise
    finally:
        source.rollback()
        source.close()
        target.close()


if __name__ == '__main__':
    main()
