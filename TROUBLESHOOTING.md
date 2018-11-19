### issue for plugins not being up to date

If you are getting stacktraces from jenkins you may have a version
mismatch between different pieces (plugins) that are talking to each
other.

- In the web UI: `Manage Jenkins` -> `Manage Plugins`
- Select All
- Click `Download now and install after restart`


After upgrading I no longer had issues. See 
[this random issue](https://issues.jenkins-ci.org/browse/JENKINS-47059)
for some background.

In the future it would be nice to have this automated. See
[this stackoverflow issue](https://stackoverflow.com/questions/7709993/how-can-i-update-jenkins-plugins-from-the-terminal)
for some ideas.

### issue for jenkins dns names

If you blow away your jenkins instance in kubernetes/openshift and it
gets recreated but you use the same backing store you may end up with
different IP addresses for the services than what you had in the past.

It's best if you make sure to set a few variables in the kubernetes
plugin configuration to use the dns names of the services themselves
rather than the IP addresses of the services. 

- In the web UI: `Manage Jenkins` -> `Configure System`
- In the `Kubernetes` section (under the `Cloud` section)
- Set Jenkins URL = `http://jenkins:80`
- Set Jenkins tunnel = `jenkins-jnlp:50000`
