This directory exists just so we don't create an admin user.
The `users.xml` makes sure the user list is empty, and to be
doubly-sure, the `admin/config.xml` makes sure the Jenkins
S2I builder doesn't inject a password hash. We should add a
flag there to disable that entirely. See:

https://github.com/openshift/jenkins/blob/24f155acb997064af5bde009cbfbc072ecf6872b/2/contrib/s2i/assemble#L41
https://github.com/openshift/jenkins/blob/24f155acb997064af5bde009cbfbc072ecf6872b/2/contrib/s2i/run#L34
