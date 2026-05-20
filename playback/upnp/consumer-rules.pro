# jUPnP is an OSGi bundle; these annotations are compile-time only and not
# present on Android. Tell R8 to ignore the missing references.
-dontwarn org.osgi.**
-dontwarn javax.enterprise.**
-dontwarn javax.inject.**

# Preserve jUPnP service and support classes used via reflection
-keep class org.jupnp.** { *; }
-keep interface org.jupnp.** { *; }
