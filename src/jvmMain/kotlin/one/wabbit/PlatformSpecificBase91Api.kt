package one.wabbit

@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message =
        "This API depends on java.io stream types and is not KMP-friendly. Prefer the kotlinx-io based Base91 source/sink wrappers in commonMain.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
annotation class PlatformSpecificBase91Api
