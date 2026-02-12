# Multi-arch Pipeline debug-pod:
This document contains the background infomation and steps to access the debug-pod for multi-arch builders.


## Introduction to debug-pod:

A debug-pod inside the Fedora CoreOS Pipeline and RedHat CoreOS Pipeline sets up a session on a remote multi-arch builder and then creates a tmux session that can be re-attached to by the user.


## Why use a debug-pod?

Team members would need access to multi arch builders for debugging failed tests and developing new features. A debug-pod would be helpful to operate at such instances. 


## Steps to request a debug-pod:

- User requests a debug-pod in Jenkins: 
    - In the Jenkins UI start a 'debug-pod' job and set the parameters - 'stream', 'arch', 'cosa_img' and 'timeout' value. The timeout is set to be 8 hours by default.

- After the build is triggered, this job spins up and starts a remote session on the builder of the user's chosen architecture and sets up the COSA environment.

- The user would be able to access the pod that was created using 'oc' CLI or Open shift web interface. The pod created could be identified using the prefix "debug-pod" followed by the "username".
    - for example: `oc rsh pod/debug-pod-username-ppc64le-cf5af868-bvx3s-d5df4`

- After accessing the terminal of the running pod entering "tmux attach" will open a session with two panes. The top pane will be a ssh shell on the remote builder and the bottom pane will be a COSA shell in a remote session. The arch requested would be displayed in the COSA shell.


## Reference links to access debug-pod

- FCOS Pipeline:
    - [Jenkins](https://jenkins-fedora-coreos-pipeline.apps.ocp.fedoraproject.org/job/debug-pod/)
    - [Openshift Console (pods)](https://console-openshift-console.apps.ocp.fedoraproject.org/k8s/ns/fedora-coreos-pipeline/core~v1~Pod)

- RHCOS Pipeline:
    - [Jenkins](https://jenkins-rhcos--prod-pipeline.apps.int.prod-stable-spoke1-dc-iad2.itup.redhat.com/job/debug-pod/)
    - [Openshift Console (pods)](https://console-openshift-console.apps.prod-stable-spoke1-dc-iad2.itup.redhat.com/k8s/ns/rhcos--prod-pipeline/core~v1~Pod)
