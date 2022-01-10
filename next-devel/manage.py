#!/usr/bin/python3

import json
import os
import sys

def set_enabled(enabled):
    dir = os.path.realpath(os.path.dirname(sys.argv[0]))
    def write_json(filename, contents):
        with open(os.path.join(dir, filename), 'w') as fh:
            json.dump(contents, fh, indent=4)
            fh.write("\n")

    # fields for FCOS infrastructure
    write_json('status.json', {
        'enabled': enabled,
    })
    # fields for shields.io endpoint
    write_json('badge.json', {
        'schemaVersion': 1,
        'style': 'for-the-badge',
        'label': os.path.basename(dir),
        'message': 'open' if enabled else 'closed',
        'color': 'green' if enabled else 'lightgrey',
    })
    # Modify next_devel declaration in streams.groovy
    found = False
    with open(os.path.join(dir, '../streams.groovy'), 'r+') as fh:
        cur = fh.read()
        fh.seek(0)
        fh.truncate()
        for line in cur.strip().split('\n'):
            if line.startswith('next_devel = ['):
                stream_name = "'next-devel'" if enabled else ""
                print(f'next_devel = [{stream_name}]', file=fh)
                found = True
            else:
                print(line, file=fh)
    if not found:
        raise Exception("Couldn't find next_devel declaration in streams.groovy")


if __name__ == '__main__':
    try:
        if sys.argv[1] == 'enable':
            set_enabled(True)
        elif sys.argv[1] == 'disable':
            set_enabled(False)
        else:
            raise IndexError
    except IndexError:
        print(f'Usage: {sys.argv[0]} {{enable|disable}}')
