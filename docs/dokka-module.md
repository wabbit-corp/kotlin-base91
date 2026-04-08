# Module kotlin-base91

`kotlin-base91` is a small Kotlin Multiplatform library for turning binary values into compact Base91 text and back again.

It is useful when you want denser ASCII output than Base64 and do not need an alphabet tailored for human transcription.

## What It Supports

- arbitrary `ByteArray` values
- `kotlinx-io` `Source` and `Sink` wrappers in common code
- JVM `InputStream` and `OutputStream` adapters

This library implements plain Base91 encoding and decoding. It does **not** implement checksums, framing, or line wrapping.

## Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("one.wabbit:kotlin-base91:0.1.0")
}
```

## Quick Start

```kotlin
import one.wabbit.base91.Base91

val payload = "Hello".encodeToByteArray()
val encoded = Base91.encode(payload)
val decoded = Base91.decode(encoded).decodeToString()

check(encoded == ">OwJh>A")
check(decoded == "Hello")
```

## `kotlinx-io` Wrappers

```kotlin
import kotlinx.io.Buffer
import one.wabbit.base91.base91Decoding
import one.wabbit.base91.base91Encoding

val plain = Buffer().apply { write("Hello".encodeToByteArray()) }
val encoded = Buffer()
encoded.base91Encoding().use { sink ->
    sink.write(plain, plain.size)
}

val encodedBytes = ByteArray(encoded.size.toInt())
encoded.readAtMostTo(encodedBytes)

val decoded = Buffer()
Buffer().apply { write(encodedBytes) }.base91Decoding().use { source ->
    decoded.transferFrom(source)
}

val decodedBytes = ByteArray(decoded.size.toInt())
decoded.readAtMostTo(decodedBytes)

check(decodedBytes.decodeToString() == "Hello")
```

## JVM Stream Adapters

The JVM module includes `Base91EncoderStream` and `Base91DecoderStream` for `java.io` interop.

These types are annotated with `@PlatformSpecificBase91Api` because they depend on JVM-only stream APIs. In shared code, prefer the `kotlinx-io` wrappers.

## Error Handling

`Base91.decode*` functions throw `Base91Exception` when:

- the input contains characters outside the Base91 alphabet
- a string input contains non-ASCII characters

## API Notes

- Empty input encodes to an empty string.
- Decoding an empty string returns an empty `ByteArray`.
- Encoder stream `flush()` forwards emitted output downstream without finalizing the encoding state.
