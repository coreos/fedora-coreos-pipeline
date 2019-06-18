// Canonical definition of all our streams and their type.

prod = ['testing' /* , 'stable', 'next' */]
devel = ['testing-devel' /* , 'next-devel' */]
mechanical = ['bodhi-updates' /* , 'bodhi-updates-testing', 'branched', 'rawhide' */]

// Maps a list of streams to a list of GitSCM branches.
def as_branches(streams) {
    return streams.collect{ [name: "origin/${it}"] }
}

// Retrieves the stream name from a branch name.
def from_branch(branch) {
    assert branch.startsWith('origin/')
    return branch['origin/'.length()..-1]
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

// Returns true if the build was triggered by a push notification.
def triggered_by_push() {
    return (currentBuild.getBuildCauses('com.cloudbees.jenkins.GitHubPushCause').size() > 0)
}

// Starts a stream build.
def build_stream(stream) {
    // use `oc start-build` instead of the build step
    // https://bugzilla.redhat.com/show_bug.cgi?id=1580468
    sh "oc start-build fedora-coreos-pipeline -e STREAM=${stream}"
}

return this
