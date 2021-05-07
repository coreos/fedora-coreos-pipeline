@Library('github.com/coreos/coreos-ci-lib@master') _

config_repo = "coreos/fedora-coreos-config"
releng_script_repo = "coreos/fedora-coreos-releng-automation"
branches = [
    "testing-devel"
    "next-devel"
]
botCreds = "github-coreosbot-token"

cosaPod {
    // set up fedora-coreos-releng-automation repository
    shwrap("""
    mkdir fcos-releng
    git clone --branch master https://github.com/${releng_script_repo} fcos-releng"
    git config --global user.name "CoreOS Bot"
    git config --global user.email "coreosbot@fedoraproject.org"
    """)

    parallel branches.collectEntries { branch -> [branch, {
        // build 'release-notes.yaml' using 'release-notes.d/' under testing-devel and next-devel
        stage("Build 'release-notes.yaml'") {
            shwrap("mkdir ${branch}")

            dir(branch) {
                shwrap("cosa init --branch ${branch} https://github.com/${config_repo}")
            }

            // generate 'release-notes.yaml' from yaml snippets under 'config/release-notes.d/'
            dir("fcos-releng") {
                shwrap("python3 coreos-release-note-generator/release-note-generator.py build \
                    --config-dir ~/${branch}/src/config \
                    --output-dir ~/${branch}/src/config")
            }
        }

        // delete all 'release-notes.d/*.yaml' files for next release version
        stage("Clean up 'release-notes.d/'") {
            shwrap("""
            cd ${branch}/src/config
            git checkout ${branch}
            rm -rf release-notes.d/*.yaml
            git commit -am "release-notes.d: clean up release-notes.d for next release"
            """)
            withCredentials([usernamePassword(credentialsId: botCreds,
                                                usernameVariable: 'GHUSER',
                                                passwordVariable: 'GHTOKEN')]) {
                // should gracefully handle race conditions here
                sh("git push https://\${GHUSER}:\${GHTOKEN}@github.com/${config_repo} ${branch}")
            }
        }

        // push 'release-notes.yaml' from testing-devel and next-devel to testing and next branch correspondingly
        stage("Push 'release-notes.yaml'") {
            target_branch = branch.replace("-devel", "")
            shwrap("""
            cd ${branch}/src/config
            git checkout ${target_branch}
            git add release-notes.yaml
            git commit -am "release-notes.yaml: generate latest release notes"
            """)
            withCredentials([usernamePassword(credentialsId: botCreds,
                                                usernameVariable: 'GHUSER',
                                                passwordVariable: 'GHTOKEN')]) {
                // should gracefully handle race conditions here
                sh("git push https://\${GHUSER}:\${GHTOKEN}@github.com/${config_repo} ${target_branch}")
            }
        }
    }] }
}
