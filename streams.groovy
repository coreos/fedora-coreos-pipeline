// Canonical definition of all our streams and their type.

// Contains 'next-devel' when that stream is enabled.
// Automatically edited by next-devel/manage.py.
next_devel = ['next-devel']

production = ['testing', 'stable', 'next']
development = ['testing-devel'] + next_devel
mechanical = ['rawhide', 'branched' /* 'bodhi-updates', 'bodhi-updates-testing' */]

// list of secondary architectures we support
additional_arches = ['aarch64', 'ppc64le', 's390x']

all_streams = production + development + mechanical

return this
