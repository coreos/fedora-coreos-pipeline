#!/bin/bash
set -euo pipefail

# Reference document for the cex configuration in zKVM
# https://www.ibm.com/docs/en/linux-on-systems?topic=management-configuring-crypto-express-adapters-kvm-guests

modprobe vfio_ap

# Hardcoded the UUID for the quick verification of mediated device.
# This UUID is passed to kola via the KOLA_CEX_UUID env var in the pipecfg.
UUID='68cd2d83-3eef-4e45-b22c-534f90b16cb9'
if  ls /sys/devices/vfio_ap/matrix | grep -q "${UUID}"; then
    echo "${UUID}"
    exit 1
fi

cards=$(ls /sys/bus/ap/devices | grep -E 'card[0-9]{2}')
if [ -z "${cards}" ]; then
    echo "cex device not found."
    exit 1
fi

# if more than one card picks the first available cca controller
for card in $cards; do
    if $(grep -q cca /sys/bus/ap/devices/${card}/uevent); then
        card=${card}
        card_domain=$(ls /sys/bus/ap/devices/$card/ | grep -E '[0-9a-f]{2}.[0-9a-f]{4}')
        break
    fi
done

# Validating the card and domain
if [ -z "${card}" ] || [ -z "${card_domain}" ]; then
    echo "couldn't find card with CCA controller"
    exit 1
fi

echo "Freeing adapter and domain."
echo 0x0 > /sys/bus/ap/apmask
echo 0x0 >  /sys/bus/ap/aqmask
echo ${UUID} > /sys/devices/vfio_ap/matrix/mdev_supported_types/vfio_ap-passthrough/create
if [ $? != 0 ]; then
    echo "failed creating mediated device."
    exit 1
fi

echo "Configuring the adapter and domain."
card_no=$(echo ${card_domain} | cut -f1 -d.)
domain_no=$(echo ${card_domain} | cut -f2 -d.)
echo 0x${card_no} > /sys/devices/vfio_ap/matrix/${UUID}/assign_adapter
echo 0x${domain_no} > /sys/devices/vfio_ap/matrix/${UUID}/assign_domain

echo "Validating the domains."
validate_dom=$(cat /sys/devices/vfio_ap/matrix/${UUID}/matrix)
if [[ "${card_no}.${domain_no}" != ${validate_dom} ]]; then
    echo -e "Mismatched card number. \n"
    exit 1
fi

echo "Setting the permission on vfio device node."
if [ ! -c '/dev/vfio/0' ]; then
    echo -e "Failed setting the permission on '/dev/vfio/0'. \n"
    exit 1
fi
chmod 0666 /dev/vfio/0
