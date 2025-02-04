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

    We would like to get AWS public access enabled for this region. Please use the following command:
        "aws ec2 disable-image-block-public-access --region `<missing-region>`"

    We would also like the "Public AMIs" quota level to be set to a minimum value. The quota codes can be viewed using the below command:
	"aws service-quotas list-service-quotas --service-code=ec2 --quota-code=L-0E3CBAB9 --region=`<missing-region>`"

    Let's make sure to adjust the quota to match the other regions if necessary. If the current quota is less than 1000, let's bump it to at least 1000 with the following request:
        "aws service-quotas request-service-quota-increase --service-code=ec2 --quota-code=L-0E3CBAB9 --desired-value 1000.00 --region=`<missing-region>`"

    When do you need this to be done by? (YYYY/MM/DD)
    Preferably before our next FCOS release.
    ```

### 3. Submit the Ticket:

In case of FCOS:
- Ensure all relevant information is included and submit the ticket to the Fedora Infrastructure team.
- AWS Account ID: 125523088429

In case of RHCOS:
- Follow the link https://devservices.dpp.openshift.com/support/, select "general AWS Question/request ticket" and submit ticket to the DPP team.
  If more direct interactions would be needed with the folks handling the ticket, please check in the slack channel #forum-pge-cloud-ops
- Account: 531415883065
  ARN: arn:aws:iam::531415883065:user/art-rhcos-ci-s3-bot
  Refer 'RH-DEV AWS Account' in Bitwarden for User ID
- Note:
    - Ensure that the new AWS regions are added to the Tenant Egress configuration in the RHCOS ITUP PROD cluster to prevent any TCP timeout issues.
    - Update the Tenant Egress directory in the internal Git repository with details of the new AWS regions.
      Include the corresponding DPP ticket reference in the comments as shown below:
      Example:
      ```
      // DPP ticket - https://issues.redhat.com/browse/DPP-16314
      to:
        dnsName: ec2.ap-southeast-3.amazonaws.com
      type: Allow
      ```

### 4. Follow Up:

- Monitor the ticket status and follow up as necessary to ensure the region is enabled and public access is configured.

## Reference link for the ticket

- FCOS ticket - Request AWS region [ticket](https://pagure.io/fedora-infrastructure/issue/11707)
- RHCOS ticket - Request AWS region [ticket](https://issues.redhat.com/browse/DPP-16314)
