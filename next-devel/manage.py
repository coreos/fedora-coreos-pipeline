#!/usr/bin/python3

import json
import yaml
import os
import sys

MANAGED = '# do not touch; line managed by `next-devel/manage.py`'


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

    # Modify next_devel declaration in config.yaml. This does a string-level
    # modification instead of parsing the YAML because we want to keep
    # comments.
    found = False
    with open(os.path.join(dir, '../config.yaml'), 'r+') as fh:
        cur = fh.read()
        fh.seek(0)
        fh.truncate()
        for line in cur.strip().split('\n'):
            if MANAGED in line:
                stripped_line = line.lstrip()
                indent = line[0:len(line)-len(stripped_line)]
                if enabled and stripped_line.startswith('# '):
                    line = f'{indent}{stripped_line[2:]}'
                if not enabled and not stripped_line.startswith('# '):
                    line = f'{indent}# {stripped_line}'
                found = True
            print(line, file=fh)
    if not found:
        raise Exception("Couldn't find managed line in config.yaml")


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
