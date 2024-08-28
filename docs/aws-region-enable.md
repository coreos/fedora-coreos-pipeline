# AWS Region Enablement for FCOS

## Overview

This document provides the information on how to address and resolve missing AWS region notifications identified by the [check-aws-regions](https://github.com/coreos/fedora-coreos-pipeline/blob/main/jobs/check-aws-regions.Jenkinsfile) Jenkins job. 

## Steps to Follow

### 1. Receive Notification

- **Jenkins Job Alert**: When the `check-aws-regions` Jenkins job runs and detects a missing AWS region, it will generate a notification.

### 2. Open a New Ticket:

- **Title:** Opt-in for AWS region to `<missing-region>` acct:`<account-id>`
- **Description:** Include the content below in the ticket description.

    ```
    Describe what you would like us to do:
    Include the `<missing-region>` region for cloud and coreos image deployments in Amazon EC2.
    https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-regions-availability-zones.html

    To Enable a region:
    Sign in to the AWS Management Console.
    In the upper right corner of the console, choose your account name or number and then choose My Account.
    In the AWS Regions section, next to the name of the Region that you want to enable, choose Enable.
    In the dialog box, review the informational text and choose Enable Region.
    Wait until the Region is ready to use.

    We would also like to get AWS public access enabled for this region. Please use the following command:
        "aws ec2 disable-image-block-public-access --region `<missing-region>`"

    Let's also adjust the quota to match the other regions :
        "aws service-quotas request-service-quota-increase --region=`<missing-region>` --service-code=ec2 --quota-code=L-0E3CBAB9 --desired-value 1000.00"

    When do you need this to be done by? (YYYY/MM/DD)
    Preferably before our next FCOS release.
    ```

### 3. Submit the Ticket:

- Ensure all relevant information is included and submit the ticket to the Fedora Infrastructure team.

### 4. Follow Up:

- Monitor the ticket status and follow up as necessary to ensure the region is enabled and public access is configured.

## Reference link for the ticket

- Request AWS region [ticket](https://pagure.io/fedora-infrastructure/issue/11707)