
The directory contains files that correspond to IAM policies in the
prod and community Fedora AWS accounts.

#### Prod Account:

- `fcos-builds-bot` user has the following policies attached
    - [fcos-upload-amis](prod-account/fcos-upload-amis)
    - [fcos-poc-artifacts](prod-account/fcos-poc-artifacts)
        - Allows writing to `fcos-builds` bucket

#### Community Account

- The `prod-account-match-fcos-builds-bot` group has the following policies attached:
    - [prod-account-match-fcos-upload-amis](community-account/prod-account-match-fcos-upload-amis)
    - [prod-account-match-fcos-poc-artifacts](community-account/prod-account-match-fcos-poc-artifacts)
        - Allows writing to `prod-account-match-fcos-builds` bucket
