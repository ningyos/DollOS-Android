# Build Environment Setup

This guide covers setting up a build environment for compiling DollOS, which is based on GrapheneOS (AOSP 16) for Pixel devices.

## System Requirements

| Component | Minimum |
|-----------|---------|
| OS | Ubuntu 22.04 LTS or 24.04 LTS (64-bit) |
| RAM | 32 GB |
| Free disk space | 500 GB SSD (NVMe recommended) |
| Shell | bash or zsh |
| CPU | Modern multi-core (more cores = faster builds) |

Note: A full build takes approximately 1 to 3 hours on modern hardware. Incremental builds are significantly faster.

## Install Dependencies

Update the package list and install all required build tools in one step:

```bash
sudo apt update && sudo apt install -y \
    git \
    curl \
    python3 \
    python3-pip \
    openjdk-21-jdk \
    zip \
    unzip \
    rsync \
    bison \
    flex \
    gcc \
    g++ \
    make \
    libssl-dev \
    libncurses-dev \
    gperf \
    xmlstarlet \
    fonttools \
    ninja-build \
    yarnpkg
```

Install the `repo` tool from Google:

```bash
mkdir -p ~/.local/bin
curl https://storage.googleapis.com/git-repo-downloads/repo -o ~/.local/bin/repo
chmod a+x ~/.local/bin/repo
```

Ensure `~/.local/bin` is on your PATH. Add this to `~/.bashrc` or `~/.zshrc` if it is not already present:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

Reload your shell:

```bash
source ~/.bashrc   # or source ~/.zshrc
```

Verify the installation:

```bash
repo --version
java -version      # must report OpenJDK 21
python3 --version
```

## Configure Git Identity

AOSP build scripts require a Git identity. Set your name and email globally:

```bash
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
```

## Directory Structure

Use the following layout to keep build artifacts and signing keys organized:

```
~/Desktop/DollOS-build/    - AOSP source tree and all build output
~/dollos-keys/     - Signing keys (see docs/key-management.md)
```

Create the directories:

```bash
mkdir -p ~/dollos-build
mkdir -p ~/dollos-keys
```

## Initialize the Source Tree

DollOS is based on GrapheneOS. The manifest repository is a fork of GrapheneOS's manifest with DollOS-specific repo entries added.

```bash
cd ~/dollos-build

# Use the DollOS fork of GrapheneOS manifest (branch 16, based on AOSP 16)
repo init -u https://github.com/dollos/platform_manifest.git -b 16 --depth=1
repo sync -j$(nproc) --no-tags --no-clone-bundle
```

This syncs the full GrapheneOS/AOSP source tree (~90GB+). Depending on network speed, this may take several hours.

## Extract Vendor Binaries

GrapheneOS uses `adevtool` to extract proprietary vendor binaries for Pixel devices:

```bash
cd ~/dollos-build
source build/envsetup.sh

# Install adevtool dependencies
yarn --cwd vendor/adevtool/ install

# Extract vendor binaries for Pixel 6a (bluejay)
# Option A: from a connected device running GrapheneOS
node vendor/adevtool/bin/adevtool generate-all -d bluejay -s

# Option B: from a downloaded factory image
# node vendor/adevtool/bin/adevtool generate-all -d bluejay -b ~/dollos-build
```

Consult the GrapheneOS build documentation if the `adevtool` CLI has changed since this guide was written.

## Build

```bash
cd ~/dollos-build
source build/envsetup.sh
lunch dollos_bluejay-cur-userdebug
m -j$(nproc)
```

A full clean build takes 1 to 3 hours on modern hardware depending on core count and disk speed. Ensure the machine does not suspend during the build. Output images will be in `out/target/product/bluejay/`.

## Flash to Pixel 6a

Prerequisite: unlock the bootloader (one-time, Developer Options > OEM Unlocking, then reboot to fastboot and run `fastboot flashing unlock`).

```bash
cd ~/Desktop/DollOS-build/out/target/product/bluejay/
fastboot flashall -w
```
