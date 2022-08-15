// Canonical definition of all our streams and their type.

// Contains 'next-devel' when that stream is enabled.
// Automatically edited by next-devel/manage.py.
next_devel = []

production = ['testing', 'stable', 'next']
development = ['testing-devel'] + next_devel
mechanical = ['rawhide' /* 'branched', 'bodhi-updates', 'bodhi-updates-testing' */]

// list of secondary architectures we support
additional_arches = ['aarch64', 'ppc64le', 's390x']

all_streams = production + development + mechanical

// Maps a list of streams to a list of GitSCM branches.
def as_branches(streams) {
    return streams.collect{ [name: "origin/${it}"] }
}

// Retrieves the stream name from a branch name. Returns "" if branch doesn't
// correspond to a stream.
def from_branch(branch) {
    assert branch.startsWith('origin/')
    stream = branch['origin/'.length()..-1]
    if (stream in all_streams) {
        return stream
    }
    return ""
}

// Returns the default trigger for push notifications. This will trigger builds
// when SCMs change (either the pipeline code itself, or fedora-coreos-config).
def get_push_trigger() {
    return [
        // this corresponds to the "GitHub hook trigger for GITScm polling"
        // checkbox; i.e. trigger a poll when a webhook event comes in at
        // /github-webhook/ for the repo we care about
        githubPush(),
        // but still also force poll SCM every 30m as fallback in case hooks
        // are down/we miss one
        pollSCM('H/30 * * * *')
    ]
}


// Starts a stream build.
def build_stream(stream) {
    build job: 'build', wait: false, parameters: [
      string(name: 'STREAM', value: stream)
    ]
}

return this
