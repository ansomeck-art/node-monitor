#!/usr/bin/env python3
"""Sign a development APK with a transient key held only in memory."""
import os
import subprocess
import sys


def command(arguments, data=None):
    result = subprocess.run(arguments, input=data, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if result.returncode:
        raise RuntimeError("development signing command failed")
    return result.stdout


def signing_pipe(data):
    read_fd, write_fd = os.pipe()
    try:
        os.write(write_fd, data)
    finally:
        os.close(write_fd)
    return read_fd


def main():
    if len(sys.argv) != 4:
        raise RuntimeError("expected apksigner, input APK, and output APK")
    signer, unsigned, output = sys.argv[1:]
    key_pem = command(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"])
    key_der = command(["openssl", "pkcs8", "-topk8", "-nocrypt", "-outform", "DER"], key_pem)
    certificate = command(["openssl", "req", "-new", "-x509", "-key", "/dev/stdin", "-sha256", "-days", "3650",
                           "-subj", "/CN=Node Monitor Development"], key_pem)
    key_fd = signing_pipe(key_der)
    cert_fd = signing_pipe(certificate)
    try:
        result = subprocess.run([signer, "sign", "--key", "/dev/fd/" + str(key_fd), "--cert", "/dev/fd/" + str(cert_fd),
                                 "--out", output, unsigned], pass_fds=(key_fd, cert_fd),
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode:
            raise RuntimeError("APK signing failed")
    finally:
        os.close(key_fd)
        os.close(cert_fd)


try:
    main()
except Exception as error:
    print("Android development signing error:", str(error), file=sys.stderr)
    sys.exit(1)
