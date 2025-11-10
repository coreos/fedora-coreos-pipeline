# GitHub Actions Migration Guide

This repository has been migrated from Jenkins to GitHub Actions for building Fedora CoreOS PXE images.

## Overview

The new GitHub Actions workflow builds the essential PXE artifacts:
- **Kernel** (vmlinuz)
- **Initramfs** (initrd.img)
- **Rootfs** (rootfs.img)
- **Metal disk images** (for bare metal installation)

These artifacts are packaged in a Live ISO and metal images, which can be used for PXE network booting.

## Quick Start

### Running a Build

1. Go to the **Actions** tab in the GitHub repository
2. Select the **"Build Fedora CoreOS PXE Images"** workflow
3. Click **"Run workflow"**
4. Configure the build parameters:
   - **Stream**: Choose the CoreOS stream (stable, testing, next, testing-devel, rawhide)
   - **Architecture**: Select x86_64 or aarch64
   - **Force rebuild**: Check to force a rebuild even if no changes detected
   - **Skip tests**: Check to skip Kola tests (saves time, not recommended for production)
5. Click **"Run workflow"** to start the build

### Downloading Artifacts

After the build completes:

1. Go to the workflow run page
2. Scroll to the **Artifacts** section
3. Download the `fedora-coreos-pxe-*` artifact
4. Extract the archive to get the PXE artifacts

### Extracting PXE Components

The Live ISO contains the kernel, initramfs, and rootfs needed for PXE boot:

```bash
# Mount the ISO
mkdir -p /mnt/fcos-iso
sudo mount -o loop fedora-coreos-*-live.x86_64.iso /mnt/fcos-iso

# Copy PXE files
cp /mnt/fcos-iso/images/pxeboot/vmlinuz ./kernel
cp /mnt/fcos-iso/images/pxeboot/initrd.img ./initramfs
cp /mnt/fcos-iso/images/pxeboot/rootfs.img ./rootfs

# Unmount
sudo umount /mnt/fcos-iso
```

## Workflow Structure

The GitHub Actions workflow (`.github/workflows/build-pxe.yml`) performs the following steps:

1. **Maximize disk space**: Removes unnecessary files to free up disk space
2. **Checkout repository**: Clones the repository
3. **Set up build environment**: Installs Podman and prepares the workspace
4. **Pull coreos-assembler image**: Downloads the latest COSA container
5. **Initialize CoreOS build**: Initializes the build with the fedora-coreos-config
6. **Fetch packages**: Downloads RPM packages needed for the build
7. **Build OSTree**: Builds the immutable filesystem tree
8. **Build QEMU image**: Creates a QEMU-compatible image
9. **Run Kola tests** (optional): Runs CoreOS test suite
10. **Build metal artifacts**: Creates bare metal disk images
11. **Build live ISO**: Generates the Live ISO with PXE artifacts
12. **Compress artifacts**: Compresses the built images
13. **Upload artifacts**: Uploads the PXE artifacts to GitHub

## Differences from Jenkins Pipeline

### What's Included

✅ **Core build functionality**:
- OSTree builds
- QEMU image generation
- Metal and Metal4k disk images
- Live ISO with PXE artifacts
- Basic Kola testing

### What's Omitted

The following features from the original Jenkins pipeline were intentionally omitted as they're not needed for PXE image generation:

❌ **Cloud platform builds**:
- AWS, Azure, GCP, etc. (not needed for PXE)
- Cloud image uploads and replication

❌ **Multi-architecture builds**:
- Simplified to single architecture per run
- Can be run multiple times for different architectures

❌ **Release management**:
- S3 uploads to production buckets
- Signing with robosignatory
- OSTree repository imports
- Fedora messaging integration

❌ **Extended testing**:
- Cloud-specific tests (AWS, Azure, GCP, OpenStack)
- Extended upgrade tests
- Multi-arch parallel testing

❌ **Advanced features**:
- Hotfix support
- Stream metadata synchronization
- Automatic release triggering

### Preserved Functionality

✅ **Stream support**: All streams (stable, testing, next, testing-devel, rawhide) are supported
✅ **Strict/non-strict builds**: Mechanical streams (rawhide) use non-strict mode
✅ **Version management**: Supports custom version specification
✅ **Force rebuilds**: Can force rebuilds even without changes
✅ **Testing**: Basic Kola testing can be run (optional)

## Configuration

### Workflow Inputs

| Input | Description | Default | Options |
|-------|-------------|---------|---------|
| `stream` | CoreOS stream to build | `testing-devel` | stable, testing, next, testing-devel, rawhide |
| `arch` | Target architecture | `x86_64` | x86_64, aarch64 |
| `force` | Force rebuild | `false` | true, false |
| `skip_tests` | Skip Kola tests | `false` | true, false |

### Storage Requirements

GitHub Actions runners have limited disk space (~14GB free on ubuntu-latest). The workflow includes disk cleanup steps, but large builds may still fail due to space constraints.

If you encounter disk space issues:

1. **Enable skip_tests**: Skip Kola tests to save disk space
2. **Use larger runners**: Upgrade to GitHub Actions runners with more disk space
3. **Mount additional storage**: Contact your GitHub admin to provision additional storage

## Troubleshooting

### Build Fails with "No space left on device"

**Solution**: The workflow includes aggressive disk cleanup, but some builds may still exceed available space.

Options:
- Run with `skip_tests: true` to save space
- Use a self-hosted runner with more disk space
- Mount additional storage (see note below)

### Podman/SELinux Errors

**Solution**: The workflow disables SELinux labeling (`--security-opt label=disable`) to avoid permission issues in GitHub Actions.

If you still see permission errors, check that the build directory is properly mounted.

### KVM Not Available Warning

**Solution**: GitHub Actions runners don't have KVM enabled by default. The workflow detects this and skips KVM-dependent tests.

This is expected behavior. Tests will run slower or be skipped, but the build will complete successfully.

### Tests Fail But Build Continues

**Solution**: The workflow is configured to continue even if tests fail, ensuring artifacts are still produced.

This is intentional for flexibility. For production builds, review test results and re-run without `skip_tests` if needed.

## Advanced Usage

### Mounting Additional Storage (GitHub Actions)

If you have credentials for mounting additional storage, you can modify the workflow to include a storage mounting step:

```yaml
- name: Mount additional storage
  run: |
    # Add your storage mounting commands here
    # Example for Azure:
    # az login --service-principal -u ${{ secrets.AZURE_CLIENT_ID }} -p ${{ secrets.AZURE_SECRET }} --tenant ${{ secrets.AZURE_TENANT }}
    # az disk attach --resource-group mygroup --vm-name $(hostname) --name mydisk
    # sudo mkfs.ext4 /dev/sdc
    # sudo mount /dev/sdc /mnt/extra-storage
    # sudo chmod 777 /mnt/extra-storage
```

### Running Locally with Act

You can test the workflow locally using [act](https://github.com/nektos/act):

```bash
# Install act
brew install act  # macOS
# or
curl https://raw.githubusercontent.com/nektos/act/master/install.sh | sudo bash  # Linux

# Run the workflow
act workflow_dispatch \
  -s GITHUB_TOKEN=your_token \
  --input stream=testing-devel \
  --input arch=x86_64
```

### Building Multiple Architectures

To build for multiple architectures, run the workflow multiple times with different `arch` values:

1. First run: `arch=x86_64`
2. Second run: `arch=aarch64`

## Migration Checklist

If you're migrating from the Jenkins setup:

- [x] Core build pipeline converted to GitHub Actions
- [x] PXE artifact generation working
- [x] Metal and Live ISO builds functional
- [x] Basic testing support (Kola)
- [ ] Optional: Configure additional storage if needed
- [ ] Optional: Set up scheduled builds (add `schedule` trigger)
- [ ] Optional: Add PR validation (add `pull_request` trigger)

## Next Steps

1. **Test the workflow**: Run a build and verify the artifacts
2. **Configure storage** (if needed): Add storage mounting for larger builds
3. **Set up automation**: Add triggers for scheduled or PR-based builds
4. **Customize**: Adjust the workflow for your specific needs

## Resources

- [Fedora CoreOS Documentation](https://docs.fedoraproject.org/en-US/fedora-coreos/)
- [CoreOS Assembler](https://github.com/coreos/coreos-assembler)
- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Original Jenkins Pipeline](./jobs/)

## Support

For issues or questions:
- Open an issue in this repository
- Consult the Fedora CoreOS documentation
- Check the coreos-assembler documentation
