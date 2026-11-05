# Portable content file v1

The website and Android use the same `.fieldnotes` format. A content archive is
independent of an installation's encryption key and contains no login, network
or signing capabilities. This is passphrase encryption of an exported file;
the live service remains server-readable encryption.

All integers below are unsigned big-endian. Passphrases use their exact UTF-8
bytes without trimming or Unicode normalization, from 12 through 256 bytes.
PBKDF2-HMAC-SHA256 uses 600,000 iterations and the random 16-byte salt to derive
an AES-256 key.

| Component | Bytes / meaning |
| --- | --- |
| Header | UTF-8 `FNARCHIVE1\n` (11 bytes), salt (16), random nonce prefix (8) |
| Frame length | uint32, ciphertext length including its 16-byte GCM tag |
| Frame payload | AES-256-GCM ciphertext of at most 65,536 plaintext bytes |
| Nonce | Eight-byte header prefix followed by uint32 frame counter, starting at zero |
| AAD | Complete header, counter, and the encoded ciphertext length |
| Terminator | Mandatory final frame encrypting empty plaintext, length 16, then EOF |

Counters prevent frame reordering; the authenticated terminator prevents accepting
a valid prefix of a truncated export. Data frames have lengths 17–65,552.
Android authenticates one frame at a time, avoiding providers that buffer an
entire multi-image file. Readers reject trailing encrypted bytes, bad passwords,
changed headers/tags, truncated frames and expanded size violations.

The plaintext is a ZIP containing exactly `manifest.json` plus the media declared
there at `media/<uuid>.bin`. No entry is extracted to a filesystem path. Duplicate
names, unknown paths, unreferenced files, missing images and bad SHA-256/size
descriptors are rejected. The manifest identifies `org.fieldnotes.archive`,
version 1, source workspace UUID and generation, creation time, content records,
people labels, media descriptors and retained changes. JSON fields follow the
server's record schema. Future unknown fields fail visibly instead of being dropped.

The server stores import previews and image chunks with authenticated storage
encryption, scoped to an authenticated user/session and workspace epoch/generation.
Chunk AAD binds its plan, image and index. A preview also binds the content sequence.
Commit repeats validation and all inserts within one immediate SQLite transaction.
Image creation records a verified original upload digest inside encrypted metadata,
so normalizing a PNG does not create a false conflict when its file is imported again.
Source-to-target mappings, exact semantic content matches and encrypted commit
receipts provide duplicate suppression and retry safety. Incoming author/time
history is provenance, not an authorization identity or an ownership claim.

Relevant implementation: `apps/admin/archive.js`, JVM-shared
`PortableCipher.kt`/`PortableArchive.kt`, Android `PortableFiles.kt`, and
`apps/server/src/store/transfer.rs` with `transfer/recovery.rs`. The upstream
MIT-licensed fflate ZIP reader runs locally in the browser; Android uses Java ZIP.
