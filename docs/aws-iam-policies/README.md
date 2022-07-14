
This directory contains files that correspond to IAM policies in the
community Fedora AWS account. The policies for the prod Fedora AWS
account are stored in the [infra ansible repo](https://pagure.io/fedora-infra/ansible).

#### Prod Account:

- `fcos-builds-bot` user has the following policies attached
    - [fcos-upload-amis.json](https://pagure.io/fedora-infra/ansible/blob/main/f/files/aws/iam/policies/fcos-upload-amis.json)
        - Allows uploading/creating AMIs
    - [fcos-poc-artifacts.json](https://pagure.io/fedora-infra/ansible/blob/main/f/files/aws/iam/policies/fcos-poc-artifacts.json)
        - Allows writing to `fcos-builds` bucket

#### Community Account

- The `prod-account-match-fcos-builds-bot` group has the following policies attached:
    - [prod-account-match-fcos-upload-amis](community-account/prod-account-match-fcos-upload-amis.json)
        - Allows uploading/creating AMIs
        - Compare to prod with
            - `vimdiff https://pagure.io/fedora-infra/ansible/raw/main/f/files/aws/iam/policies/fcos-upload-amis.json community-account/prod-account-match-fcos-upload-amis.json`
    - [prod-account-match-fcos-poc-artifacts](community-account/prod-account-match-fcos-poc-artifacts.json)
        - Allows writing to `prod-account-match-fcos-builds` bucket
        - Compare to prod with
            - `vimdiff https://pagure.io/fedora-infra/ansible/raw/main/f/files/aws/iam/policies/fcos-poc-artifacts.json community-account/prod-account-match-fcos-poc-artifacts.json`
