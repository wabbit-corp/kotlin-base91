# kotlin-base91

`kotlin-base91` is a Kotlin Multiplatform library for encoding binary data as compact Base91 text and decoding it back to bytes.

It is designed for cases where you want denser text output than Base64 and do not need an alphabet optimized for humans to read aloud or type manually.

## Why Base91

Base91 packs data more tightly than Base64 by using a larger alphabet and a variable 13/14-bit encoding scheme.

That makes it useful for:

- compact textual payloads
- protocol fields that need to stay ASCII
- streaming transformations over binary data

This library implements plain Base91 encoding. It does not add checksums, framing, or line wrapping.

## Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("one.wabbit:kotlin-base91:0.1.0")
}
```

## Byte Array Example

```kotlin
import one.wabbit.Base91

val payload = "Hello".encodeToByteArray()
val encoded = Base91.encode(payload)
val decoded = Base91.decode(encoded).decodeToString()

check(encoded == ">OwJh>A")
check(decoded == "Hello")
```

## `kotlinx-io` Wrappers

The common API also includes stream-style wrappers for `kotlinx-io`:

```kotlin
import kotlinx.io.Buffer
import one.wabbit.base91Decoding
import one.wabbit.base91Encoding

val source = Buffer().apply { write("Hello".encodeToByteArray()) }
val encoded = Buffer()
encoded.base91Encoding().use { sink ->
    sink.write(source, source.size)
}

val encodedBytes = ByteArray(encoded.size.toInt())
encoded.readAtMostTo(encodedBytes)

val decoded = Buffer()
Buffer().apply { write(encodedBytes) }.base91Decoding().use { input ->
    decoded.transferFrom(input)
}

val decodedBytes = ByteArray(decoded.size.toInt())
decoded.readAtMostTo(decodedBytes)

check(decodedBytes.decodeToString() == "Hello")
```

## JVM Stream Adapters

On the JVM, the library also provides `java.io` adapters:

- `Base91EncoderStream`
- `Base91DecoderStream`

These APIs are annotated with `@PlatformSpecificBase91Api` because they depend on `java.io` and are not portable across Kotlin targets. Prefer the `kotlinx-io` wrappers in shared code.

## Error Handling

`Base91.decode` throws `Base91Exception` when:

- the input contains characters outside the Base91 alphabet
- the input string contains non-ASCII characters

## API Notes

- Empty input encodes to an empty string.
- Decoding an empty string returns an empty `ByteArray`.
- `flush()` on the encoder stream does not finalize the Base91 stream; finalization happens on `close()`.

## Licensing

This project is licensed under the GNU Affero General Public License v3.0 (AGPL-3.0) for open source use.

For commercial use, contact Wabbit Consulting Corporation at `wabbit@wabbit.one`.

## Contributing

Before contributions can be merged, contributors need to agree to the repository CLA.
