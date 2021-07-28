// A function to wrap what's needed to run gangplank for our aarch64
// builder host. Accepts a params map with the usual parameters for
// a call do runSpec or runSingleCmd.
def gangplankArchWrapper(params = [:]) {
    arch = params['arch']
    withCredentials([
        string(credentialsId: "fcos-${arch}-builder-host-string",
               variable: 'REMOTEHOST'),
        string(credentialsId: "fcos-${arch}-builder-uid-string",
               variable: 'REMOTEUID'),
        sshUserPrivateKey(credentialsId: "fcos-${arch}-builder-sshkey-key",
                          usernameVariable: 'REMOTEUSER',
                          keyFileVariable: 'CONTAINER_SSHKEY')
    ]) {
        withEnv(["CONTAINER_HOST=ssh://${REMOTEUSER}@${REMOTEHOST}/run/user/${REMOTEUID}/podman/podman.sock"]) {
            shwrap("""
            # workaround bug: https://github.com/jenkinsci/configuration-as-code-plugin/issues/1646
            sed -i s/^----BEGIN/-----BEGIN/ \$CONTAINER_SSHKEY
            """)
            // Apply some defaults params for building our FCOS multi-arch builds via gangplank.
            // - use a locally built COSA because we don't currently build/push multi-arch COSA anywhere. 
            // - use pod mode with --podman for remote podman execution
            // - use the 'builds' "bucket" will fetch artifacts back into the expected 'builds' directory
            // - add --arch=$arch to target the specific architecture
            if (params['extraFlags'] == null) {
                params['extraFlags'] = '--podman --bucket=builds'
            }
            if (params['image'] == null) {
                params['image'] = 'localhost/coreos-assembler:latest'
            }
            // If the caller added any more flags to append to the defaults, do that now.
            if (params['appendFlags'] != null) {
                params['extraFlags'] += " " + params['appendFlags']
            }
            gangplank.runGangplank(params)
        }
    }
}

return this
