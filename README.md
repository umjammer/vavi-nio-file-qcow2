[![Release](https://jitpack.io/v/umjammer/vavi-nio-file-qcow2.svg)](https://jitpack.io/#umjammer/vavi-nio-file-qcow2)
[![Java CI](https://github.com/umjammer/vavi-nio-file-qcow2/actions/workflows/maven.yml/badge.svg)](https://github.com/umjammer/vavi-nio-file-qcow2/actions/workflows/maven.yml)
[![CodeQL](https://github.com/umjammer/vavi-nio-file-qcow2/actions/workflows/codeql.yml/badge.svg)](https://github.com/umjammer/vavi-nio-file-qcow2/actions/workflows/codeql.yml)
![Java](https://img.shields.io/badge/Java-17-b07219)

# vavi-nio-file-qcow2

## Install

* [maven](https://jitpack.io/#umjammer/vavi-nio-file-qcow2)

## Usage

## References

* https://jitpack.io/#umjammer/vavi-nio-file-qcow
* https://jules.google.com/task/2709532535608792412 🔐

## TODO

* spi is wrong direction -> one of a physical? format

---

# [Original](https://github.com/dpeckett/qcow2)

A Go library for reading and writing QCOW2 disk images. QCOW2 is a format used by QEMU and KVM to store virtual machine disk images.

Written based on the official QCOW2 specification: [qcow2.txt](https://gitlab.com/qemu-project/qemu/-/blob/master/docs/interop/qcow2.txt).

## Caveats

The library is not yet complete. It can read and write most QCOW2 images, but some features are not supported:

- Compression (expect for reading DEFLATE)
- Encryption
- Backing files
- External data

You shouldn't use this library in any application that requires data integrity. It has not been tested thoroughly and definitely will result in data loss.