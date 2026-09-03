# Runtime binary

The final distribution contains exactly one executable JAR:

`schema-forge-v4-4.0.0.jar`

The authoritative frozen SHA-256 is stored in `checksums/SHA256SUMS.txt` and is written only after two clean builds produce the same byte-level JAR through `scripts\reproducible-ga-build-windows.cmd`.

Do not manually replace the binary during distribution assembly.
