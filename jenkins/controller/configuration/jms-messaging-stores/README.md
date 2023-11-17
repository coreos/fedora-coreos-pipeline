These files contain the Fedora Messaging credentials for the `/public_pubsub`
endpoint. These credentials are well-known and part of the `fedora-messaging`
package in Fedora. But the JMS Messaging plugin needs them in Java KeyStore
format. This is a pain to do, so we just keep the resulting files in-tree.

This also allows us to work around the lack of support for using Jenkins
credentials in the JMS Messaging plugin:

https://github.com/jenkinsci/jms-messaging-plugin/issues/263

To generate these files from the source ones, the following guide was used:
https://docs.oracle.com/cd/E35976_01/server.740/es_admin/src/tadm_ssl_convert_pem_to_jks.html

These steps are abbreviated below:

### keystore.jks

```
# convert fedora user and cert to PKCS12
openssl pkcs12 -export -in /etc/fedora-messaging/fedora-cert.pem -inkey /etc/fedora-messaging/fedora-key.pem -out fedora.p12
<use password 'fedora'>

# create a keystore jks
keytool -genkey -keyalg RSA -alias foo -keystore keystore.jks
# <use password 'fedora'>
# <name values don't matter since we delete it right after>

# delete the key we just generated to empty it out
keytool -delete -alias foo -keystore keystore.jks
# <insert 'fedora' password>

# add PKCS12 creds to keystore
keytool -v -importkeystore -srckeystore fedora.p12 -srcstoretype PKCS12 \
  -destkeystore keystore.jks -deststoretype JKS
```

### truststore.jks

```
# create a new keystore jks
keytool -genkey -keyalg RSA -alias foo -keystore truststore.jks
# <use password 'fedora'>
# <name values don't matter since we delete it right after>

# delete the key we just generated to empty it out
keytool -delete -alias foo -keystore truststore.jks
# <insert 'fedora' password>

# import the CA cert
keytool -import -v -trustcacerts -alias cacerta -file /etc/fedora-messaging/cacert.pem -keystore truststore.jks
# <say yes to trust question>
```
