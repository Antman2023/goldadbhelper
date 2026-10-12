# Signed field arithmetic and Android 4.4 Dalvik

The local field-multiplication correctness repair is retained. It uses the
signed radix-2^25.5 EdDSA-Java/ref10 coefficients, rather than the fork's unsigned
routine. The signed-byte point-decoding repair is also unchanged. See
`UPSTREAM_MANIFEST.json` for the upstream revisions and licensing.

## Why the additional refactor was necessary

A real Android 4.4.2 x86 runtime aborted in its JIT compiler during the synthetic
SPAKE2 exchange with `exceeds size of memVRTable`. The previous multiplication
method kept 100 long products plus carries in separate local variables. The two
squaring methods each kept 55 long products. Interpreter-only (`-Xint`) execution
does not establish that an ordinary app process can run this code.

The refactor directly accumulates each of the same ten output-limb expressions
into a ten-element long array. Squaring retains its symmetric 55-product formula;
twice-the-square doubles the full-width product before reduction. A small shared
helper applies the original twelve signed carries in the original order:
0, 4, 1, 5, 2, 6, 3, 7, 4, 8, wrap 9 to 0 with factor 19, then 0.
The temporary product array is erased after copying out the reduced limbs.

There are no value-dependent loops, branches, array indices or allocations in
these routines. Promotions to long occur before multiplication. The documented
input bounds, signed-limb output bounds and canonical encodings are preserved.
No BigInteger, native cryptography replacement or interpreter override is used
in production. This is a data-independent implementation property, not a claim
that all JVM execution timing has been formally verified.

## Register audit

Official Android build-tools 35.0.0 D8, min API 19, followed by dexdump:

| Method | Before | After |
| --- | ---: | ---: |
| multiply | 216 | 32 |
| square | 116 | 2 |
| squareAndDouble | 116 | 7 |
| squareProduct (new helper) | n/a | 35 |
| reduceProduct (new helper) | n/a | 13 |
| carryProduct (new helper) | n/a | 8 |

Future changes must retain a small virtual-register working set and be exercised
on an ordinary JIT-enabled API 19 process. A successful JVM test or reduced
register count alone does not replace that runtime check.

The frozen candidate 04 APK (SHA-256
`d505eaf9a5e6225e58b6645ea6580891d686c8021b79dce9d16eaa9f65759fe4`)
was then exercised on the Android 4.4.2 x86 emulator with the default JIT. Its
production SPAKE2 classes completed message generation and both peers'
initialization without the former JIT abort. TLS 1.3 and the matching exporter
also passed. This candidate's complete pairing probe still failed afterward:
the system BouncyCastle AES-GCM provider rejected `GCMParameterSpec` in
`PairingCrypto.encrypt`. That separate provider compatibility issue must be
resolved before claiming end-to-end API 19 pairing success.

## Regression coverage

`core/src/test/java/io/github/muntashirakon/crypto/ed25519/FieldArithmeticTests.java`
checks the modular integer value, canonical encoding, reduced-limb bounds and
non-mutation of inputs. Its 21,057 checks cover:

- Seven fixed synthetic byte-output vectors, including negative inputs,
  maximum-magnitude limbs and the odd-limb wrap coefficient 38
- All 100 pairs of limb positions
- All 1,024 sign patterns at the documented input bounds
- 4,096 reproducible random signed pairs
- 64 inversion and exponentiation chains against an independent integer oracle

The BigInteger oracle is confined to test source. Both the pre-refactor and
refactored field implementations pass it. The 14 existing PairingTests also
pass, including four independently generated affine-curve SPAKE2 vectors, 24
random bidirectional exchanges, authenticated AES-GCM failure checks, TLS 1.3
exporter-bound loopback pairing and encrypted ADB STLS. The existing Python
SPAKE2 vector generator reproduces its checked-in resource byte for byte.

Run the focused checks from the repository root:

```sh
CONSCRYPT_TEST_JAR=vendor/conscrypt-openjdk-uber-2.5.2.jar bash tools/test-pairing.sh
java -ea -cp build/pairing-classes io.github.muntashirakon.crypto.ed25519.FieldArithmeticTests
```

Only synthetic material and local loopback fixtures are used. No user device,
real credentials, pairing code or external service is involved. Actual API 19
runtime results and the exact candidate APK hash are recorded separately in
the project's compatibility/QA reports.
