"""Initialize an empty identity database; never changes an existing administrator.

Install tools/requirements-migration.txt. Database credentials come from
DB_PASSWORD; the initial password comes from INITIAL_ADMIN_PASSWORD or a hidden prompt.
"""
import argparse
import getpass
import os

import bcrypt
import pymysql


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=3306)
    parser.add_argument('--database', default='cinema_identity')
    parser.add_argument('--db-user', default='cinema_identity')
    parser.add_argument('--name', default='admin')
    args = parser.parse_args()
    password = os.environ.get('INITIAL_ADMIN_PASSWORD') or getpass.getpass('Initial administrator password: ')
    if len(password) < 12 or len(password.encode()) > 72:
        parser.error('Use a password with at least 12 characters and at most 72 UTF-8 bytes.')
    connection = pymysql.connect(host=args.host, port=args.port, user=args.db_user,
                                 password=os.environ['DB_PASSWORD'], database=args.database,
                                 charset='utf8mb4', connect_timeout=5)
    try:
        with connection.cursor() as cursor:
            cursor.execute('SELECT COUNT(*) FROM admin')
            if cursor.fetchone()[0]:
                raise RuntimeError('An administrator already exists; initialization will not change it.')
            encoded = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12)).decode()
            cursor.execute('INSERT INTO admin(name,password,status,create_time) VALUES(%s,%s,1,NOW())',
                           (args.name, encoded))
        connection.commit()
        print('Administrator created. Sign in through the gateway.')
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == '__main__':
    main()
